package com.discordlite.server

import com.discordlite.server.messaging.PreKeyBundleDto
import com.discordlite.server.servers.ChannelMessageDto
import com.discordlite.server.servers.CreateChannelRequest
import com.discordlite.server.servers.CreateServerRequest
import com.discordlite.server.servers.MentionDto
import com.discordlite.server.servers.Permissions
import com.discordlite.server.servers.RoleRequest
import com.discordlite.server.servers.SendChannelMessageRequest
import com.discordlite.server.servers.ServerDto
import com.discordlite.server.servers.ServerMemberDto
import com.discordlite.server.servers.ServerSummary
import com.discordlite.server.servers.SetMemberRolesRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerTest {

    @Test
    fun `creating a server sets up channels, roles and the owner's powers`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val alice = http.newUser(app)
        val bob = http.newUser(app)
        val stranger = http.newUser(app)
        http.befriend(alice, bob)

        val server = http.createServer(alice, "Apex Bros", members = listOf(bob))
        assertEquals("Apex Bros", server.name)
        assertEquals(Permissions.ALL, server.myPermissions)
        assertEquals(listOf("Information", "Text channels"), server.categories.map { it.name })
        assertEquals(listOf("welcome", "general", "clips", "lfg"), server.channels.map { it.name })
        assertEquals(setOf("everyone", "Admin"), server.roles.map { it.name }.toSet())
        assertEquals(2, server.memberCount)

        // Bob sees it as a plain member; a stranger can't see it at all.
        val asBob = http.get("/v1/servers/${server.id}") { bearerAuth(bob.token) }.body<ServerDto>()
        assertEquals(Permissions.DEFAULT, asBob.myPermissions) // just "Create invites"
        assertEquals(listOf(server.id), http.get("/v1/servers") { bearerAuth(bob.token) }.body<List<ServerSummary>>().map { it.id })
        assertEquals(HttpStatusCode.NotFound, http.get("/v1/servers/${server.id}") { bearerAuth(stranger.token) }.status)

        val blank = http.createServer(alice, "Just us", template = "blank")
        assertEquals(listOf("general"), blank.channels.map { it.name })
        assertTrue(blank.categories.isEmpty())
    }

    @Test
    fun `only people with Add members can add, and only their friends`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val alice = http.newUser(app)
        val bob = http.newUser(app)
        val carol = http.newUser(app)
        val dave = http.newUser(app)
        http.befriend(alice, bob)
        http.befriend(bob, carol)
        val server = http.createServer(alice, "Squad", members = listOf(bob))

        // Carol isn't Alice's friend.
        val notFriend = http.postJson("/v1/servers/${server.id}/members", mapOf("userIds" to listOf(carol.id)), alice.token)
        assertEquals(HttpStatusCode.Forbidden, notFriend.status)
        assertTrue(notFriend.bodyAsText().contains("not_friends"))

        // Bob is Carol's friend but has no "Add members" power.
        assertEquals(HttpStatusCode.Forbidden, http.postJson("/v1/servers/${server.id}/members", mapOf("userIds" to listOf(carol.id)), bob.token).status)

        val inviter = http.createRole(alice, server, "Inviter", Permissions.ADD_MEMBERS).roles.first { it.name == "Inviter" }
        http.setRoles(alice, server, bob, listOf(inviter.id))
        val added = http.postJson("/v1/servers/${server.id}/members", mapOf("userIds" to listOf(carol.id)), bob.token)
        assertEquals(HttpStatusCode.OK, added.status, added.bodyAsText())
        assertEquals(3, added.body<ServerDto>().memberCount)
        assertEquals(HttpStatusCode.NotFound, http.get("/v1/servers/${server.id}") { bearerAuth(dave.token) }.status)
    }

    @Test
    fun `channel messages are stored, paged, sent live, and deleted by the author or a moderator`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val ws = createClient { install(WebSockets) }
        val alice = http.newUser(app)
        val bob = http.newUser(app)
        val stranger = http.newUser(app)
        http.befriend(alice, bob)
        val server = http.createServer(alice, "Squad", members = listOf(bob))
        val general = server.channels.first { it.name == "general" }

        ws.webSocket("/v1/gateway", request = { bearerAuth(bob.token) }) {
            while (nextFrame().t != "ready") Unit
            http.say(alice, general.id, "first")
            val live = nextFrame()
            assertEquals("channel_message", live.t)
            assertEquals("first", live.channelMessage!!.body)
            assertEquals(server.id, live.channelMessage!!.serverId)
        }
        (2..5).forEach { http.say(alice, general.id, "message $it") }

        val latest = http.history(bob, general.id, limit = 2)
        assertEquals(listOf("message 4", "message 5"), latest.map { it.body })
        val older = http.history(bob, general.id, before = latest.first().id, limit = 10)
        assertEquals(listOf("first", "message 2", "message 3"), older.map { it.body })
        assertEquals(HttpStatusCode.NotFound, http.get("/v1/channels/${general.id}/messages") { bearerAuth(stranger.token) }.status)

        // Retrying with the same client id doesn't post twice.
        val clientId = UUID.randomUUID().toString()
        val once = http.postJson("/v1/channels/${general.id}/messages", SendChannelMessageRequest("once", clientId), bob.token).body<ChannelMessageDto>()
        val twice = http.postJson("/v1/channels/${general.id}/messages", SendChannelMessageRequest("once", clientId), bob.token).body<ChannelMessageDto>()
        assertEquals(once.id, twice.id)

        // Bob can't delete Alice's message... until he's a moderator. He can always delete his own.
        val target = latest.last()
        assertEquals(HttpStatusCode.Forbidden, http.delete("/v1/channels/${general.id}/messages/${target.id}") { bearerAuth(bob.token) }.status)
        assertEquals(HttpStatusCode.NoContent, http.delete("/v1/channels/${general.id}/messages/${once.id}") { bearerAuth(bob.token) }.status)
        val mods = http.createRole(alice, server, "Mods", Permissions.MANAGE_MESSAGES).roles.first { it.name == "Mods" }
        http.setRoles(alice, server, bob, listOf(mods.id))
        assertEquals(HttpStatusCode.NoContent, http.delete("/v1/channels/${general.id}/messages/${target.id}") { bearerAuth(bob.token) }.status)
        assertFalse(http.history(alice, general.id).any { it.id == target.id || it.id == once.id })
    }

    @Test
    fun `roles can't hand out powers you don't have, and only the owner can act on people with powers`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val alice = http.newUser(app)
        val bob = http.newUser(app)
        val carol = http.newUser(app)
        val dave = http.newUser(app)
        listOf(bob, carol, dave).forEach { http.befriend(alice, it) }
        val server = http.createServer(alice, "Squad", members = listOf(bob, carol, dave))

        val withMods = http.createRole(alice, server, "Mods", Permissions.KICK_MEMBERS or Permissions.MANAGE_ROLES)
        // A new role goes below the existing ones (roles come highest first, "everyone" last).
        assertEquals(listOf("Admin", "Mods", "everyone"), withMods.roles.map { it.name })
        val mods = withMods.roles.first { it.name == "Mods" }
        http.setRoles(alice, server, bob, listOf(mods.id))
        http.setRoles(alice, server, carol, listOf(mods.id))

        // Bob can make a role with powers he has, but not one with powers he lacks.
        assertEquals(HttpStatusCode.OK, http.createRoleRaw(bob, server, "Kickers", Permissions.KICK_MEMBERS).status)
        val tooMuch = http.createRoleRaw(bob, server, "Bosses", Permissions.MANAGE_SERVER)
        assertEquals(HttpStatusCode.Forbidden, tooMuch.status)
        assertTrue(tooMuch.bodyAsText().contains("cannot_grant_permission"))
        val admin = server.roles.first { it.name == "Admin" }
        assertEquals(HttpStatusCode.Forbidden, http.setRolesRaw(bob, server, dave, listOf(admin.id)).status)

        // Bob can kick Dave (no powers), not Carol (a fellow mod) and never Alice (the owner).
        assertEquals(HttpStatusCode.Forbidden, http.delete("/v1/servers/${server.id}/members/${carol.id}") { bearerAuth(bob.token) }.status)
        assertEquals(HttpStatusCode.Forbidden, http.delete("/v1/servers/${server.id}/members/${alice.id}") { bearerAuth(bob.token) }.status)
        assertEquals(HttpStatusCode.NoContent, http.delete("/v1/servers/${server.id}/members/${dave.id}") { bearerAuth(bob.token) }.status)
        assertEquals(HttpStatusCode.NotFound, http.get("/v1/servers/${server.id}") { bearerAuth(dave.token) }.status)

        // The owner can.
        assertEquals(HttpStatusCode.NoContent, http.delete("/v1/servers/${server.id}/members/${carol.id}") { bearerAuth(alice.token) }.status)
        val members = http.get("/v1/servers/${server.id}/members") { bearerAuth(alice.token) }.body<List<ServerMemberDto>>()
        assertEquals(setOf(alice.id, bob.id), members.map { it.id }.toSet())
        assertEquals(listOf(mods.id), members.first { it.id == bob.id }.roleIds)
    }

    @Test
    fun `mentions notify, show as badges, and clear when read`() = testApplication {
        val pusher = CapturingPusher()
        val app = startApp(pusher)
        val http = jsonClient()
        val alice = http.newUser(app)
        val bob = http.newUser(app)
        http.befriend(alice, bob)
        val server = http.createServer(alice, "Squad", members = listOf(bob))
        val general = server.channels.first { it.name == "general" }

        http.say(bob, general.id, "anyone on?")
        val mention = http.say(bob, general.id, "@${alice.username} get on, also @nobody_here")
        assertEquals(listOf(alice.id), mention.mentionIds)
        delay(300)
        assertEquals(listOf(UUID.fromString(alice.id)), pusher.woken.toList()) // offline → woken

        val summary = http.get("/v1/servers") { bearerAuth(alice.token) }.body<List<ServerSummary>>().single()
        assertTrue(summary.unread)
        assertEquals(1, summary.mentions)
        assertEquals(1, http.get("/v1/servers/${server.id}") { bearerAuth(alice.token) }.body<ServerDto>().channels.first { it.id == general.id }.mentions)
        val pending = http.get("/v1/mentions") { bearerAuth(alice.token) }.body<List<MentionDto>>()
        assertEquals(listOf(mention.id), pending.map { it.message.id })
        assertEquals("general", pending.single().channelName)
        assertEquals("Squad", pending.single().serverName)

        // Bob's own messages never count as unread for him.
        assertFalse(http.get("/v1/servers") { bearerAuth(bob.token) }.body<List<ServerSummary>>().single().unread)

        http.postJson("/v1/channels/${general.id}/read", mapOf("lastReadId" to mention.id), alice.token)
        val after = http.get("/v1/servers") { bearerAuth(alice.token) }.body<List<ServerSummary>>().single()
        assertFalse(after.unread)
        assertEquals(0, after.mentions)
        assertTrue(http.get("/v1/mentions") { bearerAuth(alice.token) }.body<List<MentionDto>>().isEmpty())
    }

    @Test
    fun `channels and categories are managed with Manage channels, and the owner deletes rather than leaves`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val ws = createClient { install(WebSockets) }
        val alice = http.newUser(app)
        val bob = http.newUser(app)
        http.befriend(alice, bob)
        val server = http.createServer(alice, "Squad", members = listOf(bob))
        val text = server.categories.first { it.name == "Text channels" }

        val created = http.postJson("/v1/servers/${server.id}/channels", CreateChannelRequest("Looking For Group!", text.id), alice.token).body<ServerDto>()
        assertTrue(created.channels.any { it.name == "looking-for-group" && it.categoryId == text.id })
        assertEquals(HttpStatusCode.Forbidden, http.postJson("/v1/servers/${server.id}/channels", CreateChannelRequest("mine"), bob.token).status)

        // Deleting a category keeps its channels.
        val withoutCategory = http.delete("/v1/servers/${server.id}/categories/${text.id}") { bearerAuth(alice.token) }.body<ServerDto>()
        assertTrue(withoutCategory.channels.filter { it.name in setOf("general", "clips", "lfg") }.all { it.categoryId == null })

        val leave = http.delete("/v1/servers/${server.id}/members/${alice.id}") { bearerAuth(alice.token) }
        assertEquals(HttpStatusCode.BadRequest, leave.status)
        assertTrue(leave.bodyAsText().contains("owner_cannot_leave"))

        ws.webSocket("/v1/gateway", request = { bearerAuth(bob.token) }) {
            while (nextFrame().t != "ready") Unit
            assertEquals(HttpStatusCode.NoContent, http.delete("/v1/servers/${server.id}") { bearerAuth(alice.token) }.status)
            val removed = nextFrame()
            assertEquals("server_removed", removed.t)
            assertEquals(server.id, removed.serverId)
        }
        assertTrue(http.get("/v1/servers") { bearerAuth(bob.token) }.body<List<ServerSummary>>().isEmpty())
    }

    @Test
    fun `sharing a server counts for the default who-can-DM-me setting`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val (alice, _) = http.signalUser(app)
        val (bob, _) = http.signalUser(app)
        val (carol, _) = http.signalUser(app)
        http.befriend(alice, bob)
        http.befriend(alice, carol)

        // Bob and Carol aren't friends: no DM yet.
        assertEquals(HttpStatusCode.Forbidden, http.get("/v1/keys/${carol.id}") { bearerAuth(bob.token) }.status)
        http.createServer(alice, "Squad", members = listOf(bob, carol))
        assertEquals(carol.id, http.get("/v1/keys/${carol.id}") { bearerAuth(bob.token) }.body<PreKeyBundleDto>().userId)

        // Carol can still choose friends-only.
        http.setPolicy(carol, "FRIENDS")
        assertEquals(HttpStatusCode.Forbidden, http.get("/v1/keys/${carol.id}") { bearerAuth(bob.token) }.status)
    }

    // --- helpers ---

    private suspend fun HttpClient.createServer(owner: TestUser, name: String, members: List<TestUser> = emptyList(), template: String = "gaming"): ServerDto {
        val response = postJson("/v1/servers", CreateServerRequest(name, template, members.map { it.id }), owner.token)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.body()
    }

    private suspend fun HttpClient.createRoleRaw(user: TestUser, server: ServerDto, name: String, permissions: Long): HttpResponse =
        postJson("/v1/servers/${server.id}/roles", RoleRequest(name, 0, permissions), user.token)

    private suspend fun HttpClient.createRole(user: TestUser, server: ServerDto, name: String, permissions: Long): ServerDto {
        val response = createRoleRaw(user, server, name, permissions)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.body()
    }

    private suspend fun HttpClient.setRolesRaw(user: TestUser, server: ServerDto, target: TestUser, roleIds: List<String>): HttpResponse =
        put("/v1/servers/${server.id}/members/${target.id}/roles") {
            bearerAuth(user.token)
            contentType(ContentType.Application.Json)
            setBody(SetMemberRolesRequest(roleIds))
        }

    private suspend fun HttpClient.setRoles(user: TestUser, server: ServerDto, target: TestUser, roleIds: List<String>) =
        assertEquals(HttpStatusCode.NoContent, setRolesRaw(user, server, target, roleIds).status)

    private suspend fun HttpClient.say(user: TestUser, channelId: String, text: String): ChannelMessageDto {
        val response = postJson("/v1/channels/$channelId/messages", SendChannelMessageRequest(text, UUID.randomUUID().toString()), user.token)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.body()
    }

    private suspend fun HttpClient.history(user: TestUser, channelId: String, before: Long? = null, limit: Int = 50): List<ChannelMessageDto> =
        get("/v1/channels/$channelId/messages?limit=$limit" + (before?.let { "&before=$it" } ?: "")) { bearerAuth(user.token) }.body()
}
