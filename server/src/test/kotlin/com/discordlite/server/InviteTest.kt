package com.discordlite.server

import com.discordlite.server.db.exec
import com.discordlite.server.servers.BanDto
import com.discordlite.server.servers.CreateInviteRequest
import com.discordlite.server.servers.CreateServerRequest
import com.discordlite.server.servers.InviteDto
import com.discordlite.server.servers.InvitePreviewDto
import com.discordlite.server.servers.Permissions
import com.discordlite.server.servers.RoleRequest
import com.discordlite.server.servers.ServerDto
import com.discordlite.server.servers.SetMemberRolesRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InviteTest {

    @Test
    fun `anyone in a server can make a link, and anyone with it can preview and join`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val alice = http.newUser(app)
        val bob = http.newUser(app)
        val stranger = http.newUser(app)
        http.befriend(alice, bob)
        val server = http.createServer(alice, "Apex Bros", members = listOf(bob))

        // Bob is a plain member: "Create invites" is on for everyone by default.
        val invite = http.invite(bob, server)
        assertEquals(8, invite.code.length)
        assertNull(invite.maxUses)
        assertTrue(invite.expiresAt!! > System.currentTimeMillis())

        val preview = http.preview(stranger, invite.code).body<InvitePreviewDto>()
        assertEquals("Apex Bros", preview.serverName)
        assertEquals(2, preview.memberCount)
        assertFalse(preview.joined)

        val joined = http.post("/v1/invites/${invite.code}/join") { bearerAuth(stranger.token) }
        assertEquals(HttpStatusCode.OK, joined.status, joined.bodyAsText())
        assertEquals(3, joined.body<ServerDto>().memberCount)
        assertTrue(http.preview(stranger, invite.code).body<InvitePreviewDto>().joined)

        // Joining again changes nothing and doesn't use the link up.
        http.post("/v1/invites/${invite.code}/join") { bearerAuth(stranger.token) }
        assertEquals(1, http.invites(alice, server).single { it.code == invite.code }.uses)
    }

    @Test
    fun `used-up, expired and made-up links are all just invalid`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val alice = http.newUser(app)
        val bob = http.newUser(app)
        val carol = http.newUser(app)
        val server = http.createServer(alice, "Squad")

        val once = http.invite(alice, server, CreateInviteRequest(maxUses = 1))
        assertEquals(HttpStatusCode.OK, http.post("/v1/invites/${once.code}/join") { bearerAuth(bob.token) }.status)
        val usedUp = http.post("/v1/invites/${once.code}/join") { bearerAuth(carol.token) }
        assertEquals(HttpStatusCode.NotFound, usedUp.status)
        assertTrue(usedUp.bodyAsText().contains("invite_invalid"))

        val soon = http.invite(alice, server, CreateInviteRequest(maxAgeSeconds = 60))
        app.deps.db.tx { exec("UPDATE server_invites SET expires_at = now() - interval '1 second' WHERE code = ?", soon.code) }
        assertEquals(HttpStatusCode.NotFound, http.preview(carol, soon.code).status)
        assertEquals(HttpStatusCode.NotFound, http.preview(carol, "Zz9Zz9Zz").status)
        assertEquals(HttpStatusCode.NotFound, http.preview(carol, "../../etc").status)

        // Expired and used-up links drop off the list.
        assertTrue(http.invites(alice, server).none { it.code == once.code || it.code == soon.code })
        assertEquals(HttpStatusCode.BadRequest, http.postJson("/v1/servers/${server.id}/invites", CreateInviteRequest(maxUses = 0), alice.token).status)
        val never = http.invite(alice, server, CreateInviteRequest(maxAgeSeconds = null))
        assertNull(never.expiresAt)
        // The app's way of saying it (it leaves nulls out).
        assertNull(http.invite(alice, server, CreateInviteRequest(neverExpires = true)).expiresAt)
    }

    @Test
    fun `the owner can take invite-making away from everyone and give it to a role`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val alice = http.newUser(app)
        val bob = http.newUser(app)
        val carol = http.newUser(app)
        http.befriend(alice, bob)
        http.befriend(alice, carol)
        val server = http.createServer(alice, "Squad", members = listOf(bob, carol))
        val everyone = server.roles.single { it.isDefault }
        assertEquals(Permissions.CREATE_INVITES, everyone.permissions)

        http.patchJson("/v1/servers/${server.id}/roles/${everyone.id}", RoleRequest("everyone", 0, 0), alice.token)
        val refused = http.postJson("/v1/servers/${server.id}/invites", CreateInviteRequest(), bob.token)
        assertEquals(HttpStatusCode.Forbidden, refused.status)

        val inviters = http.postJson("/v1/servers/${server.id}/roles", RoleRequest("Inviters", 0, Permissions.CREATE_INVITES), alice.token)
            .body<ServerDto>().roles.single { it.name == "Inviters" }
        http.setRoles(alice, server, bob, listOf(inviters.id))
        val bobs = http.invite(bob, server)

        // Bob sees his own links; Alice (who manages the server) sees everyone's and can delete them.
        val carols = runCatching { http.invite(carol, server) }
        assertTrue(carols.isFailure)
        val alices = http.invite(alice, server)
        assertEquals(listOf(bobs.code), http.invites(bob, server).map { it.code })
        assertEquals(setOf(bobs.code, alices.code), http.invites(alice, server).map { it.code }.toSet())
        assertEquals(HttpStatusCode.Forbidden, http.delete("/v1/invites/${alices.code}") { bearerAuth(bob.token) }.status)
        assertEquals(HttpStatusCode.NoContent, http.delete("/v1/invites/${bobs.code}") { bearerAuth(alice.token) }.status)
        assertEquals(HttpStatusCode.NotFound, http.preview(carol, bobs.code).status)
    }

    @Test
    fun `banned people are removed and can't come back by link or be added, until unbanned`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val ws = createClient { install(WebSockets) }
        val alice = http.newUser(app)
        val bob = http.newUser(app)
        val troll = http.newUser(app)
        http.befriend(alice, bob)
        http.befriend(alice, troll)
        val server = http.createServer(alice, "Squad", members = listOf(bob, troll))
        val link = http.invite(alice, server)

        // Bob can't ban without the power.
        assertEquals(HttpStatusCode.Forbidden, http.put("/v1/servers/${server.id}/bans/${troll.id}") { bearerAuth(bob.token) }.status)

        ws.webSocket("/v1/gateway", request = { bearerAuth(troll.token) }) {
            while (nextFrame().t != "ready") Unit
            assertEquals(HttpStatusCode.NoContent, http.put("/v1/servers/${server.id}/bans/${troll.id}") { bearerAuth(alice.token) }.status)
            assertEquals("server_removed", nextFrame().t)
        }
        assertEquals(HttpStatusCode.NotFound, http.get("/v1/servers/${server.id}") { bearerAuth(troll.token) }.status)

        val rejoin = http.post("/v1/invites/${link.code}/join") { bearerAuth(troll.token) }
        assertEquals(HttpStatusCode.Forbidden, rejoin.status)
        assertTrue(rejoin.bodyAsText().contains("banned_from_server"))
        assertEquals(HttpStatusCode.Forbidden, http.postJson("/v1/servers/${server.id}/members", mapOf("userIds" to listOf(troll.id)), alice.token).status)
        assertEquals(listOf(troll.id), http.get("/v1/servers/${server.id}/bans") { bearerAuth(alice.token) }.body<List<BanDto>>().map { it.userId })

        // A moderator with "Ban members" still can't ban the owner.
        val mods = http.postJson("/v1/servers/${server.id}/roles", RoleRequest("Mods", 0, Permissions.BAN_MEMBERS), alice.token)
            .body<ServerDto>().roles.single { it.name == "Mods" }
        http.setRoles(alice, server, bob, listOf(mods.id))
        assertEquals(HttpStatusCode.Forbidden, http.put("/v1/servers/${server.id}/bans/${alice.id}") { bearerAuth(bob.token) }.status)

        assertEquals(HttpStatusCode.NoContent, http.delete("/v1/servers/${server.id}/bans/${troll.id}") { bearerAuth(bob.token) }.status)
        assertEquals(HttpStatusCode.OK, http.post("/v1/invites/${link.code}/join") { bearerAuth(troll.token) }.status)
    }

    @Test
    fun `guessing invite codes is rate limited`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val guesser = http.newUser(app)
        val statuses = (1..31).map { http.preview(guesser, "Guess${it.toString().padStart(3, 'x')}").status }
        assertTrue(statuses.take(30).all { it == HttpStatusCode.NotFound })
        assertEquals(HttpStatusCode.TooManyRequests, statuses.last())
    }

    // --- helpers ---

    private suspend fun HttpClient.createServer(owner: TestUser, name: String, members: List<TestUser> = emptyList()): ServerDto =
        postJson("/v1/servers", CreateServerRequest(name, "gaming", members.map { it.id }), owner.token).body()

    private suspend fun HttpClient.invite(user: TestUser, server: ServerDto, request: CreateInviteRequest = CreateInviteRequest()): InviteDto {
        val response = postJson("/v1/servers/${server.id}/invites", request, user.token)
        check(response.status == HttpStatusCode.Created) { "invite failed: ${response.status} ${response.bodyAsText()}" }
        return response.body()
    }

    private suspend fun HttpClient.invites(user: TestUser, server: ServerDto): List<InviteDto> =
        get("/v1/servers/${server.id}/invites") { bearerAuth(user.token) }.body()

    private suspend fun HttpClient.preview(user: TestUser, code: String): HttpResponse = get("/v1/invites/$code") { bearerAuth(user.token) }

    private suspend fun HttpClient.patchJson(path: String, body: Any, token: String): HttpResponse = patch(path) {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpClient.setRoles(user: TestUser, server: ServerDto, target: TestUser, roleIds: List<String>) {
        val response = put("/v1/servers/${server.id}/members/${target.id}/roles") {
            bearerAuth(user.token)
            contentType(ContentType.Application.Json)
            setBody(SetMemberRolesRequest(roleIds))
        }
        assertEquals(HttpStatusCode.NoContent, response.status, response.bodyAsText())
    }
}
