package com.discordlite.server

import com.discordlite.server.db.query
import com.discordlite.server.messaging.CreateGroupRequest
import com.discordlite.server.messaging.Envelope
import com.discordlite.server.messaging.GatewayFrame
import com.discordlite.server.messaging.GroupDto
import com.discordlite.server.messaging.PreKeyBundleDto
import com.discordlite.server.messaging.SendGroupMessageRequest
import com.discordlite.server.messaging.SendMessageRequest
import com.discordlite.server.messaging.b64
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.message.CiphertextMessage
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a group message is encrypted once, reaches every member, and the server can't read it`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val (alice, aliceSignal) = http.signalUser(app)
        val (bob, bobSignal) = http.signalUser(app)
        val (carol, carolSignal) = http.signalUser(app)
        http.befriend(alice, bob)
        http.befriend(alice, carol)

        val group = http.createGroup(alice, listOf(bob, carol), name = "Squad")
        assertEquals("Squad", group.name)
        assertEquals(alice.id, group.ownerId)
        assertEquals(1, group.epoch)
        assertEquals(setOf(alice.id, bob.id, carol.id), group.members.map { it.id }.toSet())

        // Alice makes her sender key and shares it with each member over their 1:1 sessions.
        val distributionId = UUID.randomUUID()
        http.shareSenderKey(alice, aliceSignal, distributionId, group, listOf(bob, carol))
        for ((user, signal) in listOf(bob to bobSignal, carol to carolSignal)) {
            val shared = http.inbox(user).single()
            assertEquals(group.id, shared.groupId)
            signal.acceptSenderKey(alice.id, signal.decryptBytes(shared))
            http.ack(user, shared)
        }

        // One ciphertext for everyone.
        val secret = "push B site after the reset ${UUID.randomUUID()}"
        val sent = http.sendGroup(alice, group, aliceSignal.encryptForGroup(distributionId, secret))
        assertEquals(HttpStatusCode.Accepted, sent.status, sent.bodyAsText())

        val stored = app.deps.db.tx {
            query("SELECT body FROM message_queue WHERE group_id = ?", UUID.fromString(group.id)) { it.getBytes("body") }
        }
        assertEquals(2, stored.size) // one copy for Bob, one for Carol, none for Alice
        stored.forEach { assertFalse(String(it, Charsets.ISO_8859_1).contains("push B site")) }

        for ((user, signal) in listOf(bob to bobSignal, carol to carolSignal)) {
            val message = http.inbox(user).single()
            assertEquals(CiphertextMessage.SENDERKEY_TYPE, message.type)
            assertEquals(group.id, message.groupId)
            assertEquals(alice.id, message.senderId)
            assertEquals(secret, signal.decryptGroup(message))
        }
        assertTrue(http.inbox(alice).isEmpty())
    }

    @Test
    fun `members who aren't friends can share keys inside the group, but still can't just DM`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val (alice, _) = http.signalUser(app)
        val (bob, bobSignal) = http.signalUser(app)
        val (carol, carolSignal) = http.signalUser(app)
        val (dave, _) = http.signalUser(app)
        http.befriend(alice, bob)
        http.befriend(alice, carol)
        val group = http.createGroup(alice, listOf(bob, carol))

        // Bob and Carol aren't friends: no keys without the group, keys with it.
        assertEquals(HttpStatusCode.Forbidden, http.get("/v1/keys/${carol.id}") { bearerAuth(bob.token) }.status)
        val bundle = http.get("/v1/keys/${carol.id}?groupId=${group.id}") { bearerAuth(bob.token) }
        assertEquals(HttpStatusCode.OK, bundle.status)
        bobSignal.startSession(bundle.body<PreKeyBundleDto>())

        val distributionId = UUID.randomUUID()
        val share = bobSignal.encryptBytes(carol.id, bobSignal.senderKeyFor(distributionId).serialize())
        assertEquals(HttpStatusCode.Accepted, http.sendPairwise(bob, carol, share, group.id).status)
        carolSignal.acceptSenderKey(bob.id, carolSignal.decryptBytes(http.inbox(carol).single()))

        // The same 1:1 session, without the group: refused, and no DM was started.
        val dm = http.sendPairwise(bob, carol, bobSignal.encrypt(carol.id, "hey, add me?"), groupId = null)
        assertEquals(HttpStatusCode.Forbidden, dm.status)
        assertTrue(dm.bodyAsText().contains("dm_not_allowed"))
        val threads = app.deps.db.tx {
            query(
                "SELECT 1 FROM dm_threads WHERE user_low = LEAST(?::uuid, ?::uuid) AND user_high = GREATEST(?::uuid, ?::uuid)",
                UUID.fromString(bob.id), UUID.fromString(carol.id), UUID.fromString(bob.id), UUID.fromString(carol.id),
            ) { true }
        }
        assertTrue(threads.isEmpty())

        // Someone outside the group can't use it as a way in.
        assertEquals(HttpStatusCode.NotFound, http.get("/v1/keys/${carol.id}?groupId=${group.id}") { bearerAuth(dave.token) }.status)
    }

    @Test
    fun `joining or leaving bumps the epoch, stale senders are told, removed members get nothing`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val ws = createClient { install(WebSockets) }
        val (alice, _) = http.signalUser(app)
        val (bob, _) = http.signalUser(app)
        val (carol, _) = http.signalUser(app)
        http.befriend(alice, bob)
        http.befriend(alice, carol)
        var group = http.createGroup(alice, listOf(bob))
        assertEquals(HttpStatusCode.Accepted, http.sendGroupRaw(alice, group, "before carol").status)

        group = http.postJson("/v1/groups/${group.id}/members", mapOf("userIds" to listOf(carol.id)), alice.token).body()
        assertEquals(2, group.epoch)
        // Carol sees nothing from before she joined.
        assertTrue(http.inbox(carol).isEmpty())

        // Alice's app still thinks it's epoch 1: told to refresh (and share her key with Carol) first.
        val stale = http.sendGroupRaw(alice, group.copy(epoch = 1), "hi carol")
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertTrue(stale.bodyAsText().contains("stale_group"))

        ws.webSocket("/v1/gateway", request = { bearerAuth(bob.token) }) {
            while (nextFrame().t != "ready") Unit
            assertEquals(HttpStatusCode.NoContent, http.delete("/v1/groups/${group.id}/members/${bob.id}") { bearerAuth(alice.token) }.status)
            val removed = nextFrame()
            assertEquals("group_removed", removed.t)
            assertEquals(group.id, removed.groupId)
        }

        group = http.get("/v1/groups/${group.id}") { bearerAuth(alice.token) }.body()
        assertEquals(3, group.epoch)
        assertEquals(HttpStatusCode.Accepted, http.sendGroupRaw(alice, group, "bob's gone").status)
        assertEquals(1, http.inbox(carol).size)
        assertEquals(1, http.inbox(bob).size) // only the one from before he was removed

        // Bob is out: the group no longer exists for him.
        assertEquals(HttpStatusCode.NotFound, http.get("/v1/groups/${group.id}") { bearerAuth(bob.token) }.status)
        assertEquals(HttpStatusCode.NotFound, http.sendGroupRaw(bob, group, "let me back").status)
        assertTrue(http.get("/v1/groups") { bearerAuth(bob.token) }.body<List<GroupDto>>().isEmpty())
    }

    @Test
    fun `only the owner manages the group, the owner leaving hands it over, the last one out deletes it`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val (alice, _) = http.signalUser(app)
        val (bob, _) = http.signalUser(app)
        val (carol, _) = http.signalUser(app)
        val (dave, _) = http.signalUser(app)
        http.befriend(alice, bob)
        http.befriend(alice, carol)
        val group = http.createGroup(alice, listOf(bob, carol))

        assertEquals(HttpStatusCode.Forbidden, http.rename(bob, group, "mine now").status)
        assertEquals(HttpStatusCode.Forbidden, http.postJson("/v1/groups/${group.id}/members", mapOf("userIds" to listOf(dave.id)), bob.token).status)
        assertEquals(HttpStatusCode.Forbidden, http.delete("/v1/groups/${group.id}/members/${carol.id}") { bearerAuth(bob.token) }.status)
        assertEquals("Raid night", http.rename(alice, group, "Raid night").body<GroupDto>().name)
        assertNull(http.rename(alice, group, "  ").body<GroupDto>().name)

        // Adding people follows their "who can DM me" setting.
        val refused = http.postJson("/v1/groups/${group.id}/members", mapOf("userIds" to listOf(dave.id)), alice.token)
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertTrue(refused.bodyAsText().contains("group_add_not_allowed"))
        http.setPolicy(dave, "EVERYONE")
        assertEquals(4, http.postJson("/v1/groups/${group.id}/members", mapOf("userIds" to listOf(dave.id)), alice.token).body<GroupDto>().members.size)

        // Strangers can't see the group at all.
        val (eve, _) = http.signalUser(app)
        assertEquals(HttpStatusCode.NotFound, http.get("/v1/groups/${group.id}") { bearerAuth(eve.token) }.status)

        // Alice leaves: someone who was there from the start takes over.
        http.delete("/v1/groups/${group.id}/members/${alice.id}") { bearerAuth(alice.token) }
        val handedOver = http.get("/v1/groups/${group.id}") { bearerAuth(bob.token) }.body<GroupDto>()
        assertTrue(handedOver.ownerId in setOf(bob.id, carol.id))

        for (user in listOf(bob, carol, dave)) {
            assertEquals(HttpStatusCode.NoContent, http.delete("/v1/groups/${group.id}/members/${user.id}") { bearerAuth(user.token) }.status)
        }
        val left = app.deps.db.tx { query("SELECT 1 FROM groups WHERE id = ?", UUID.fromString(group.id)) { true } }
        assertTrue(left.isEmpty())
    }

    @Test
    fun `group size and input are checked`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val (alice, _) = http.signalUser(app)
        val (bob, _) = http.signalUser(app)
        http.befriend(alice, bob)

        val tooMany = http.postJson("/v1/groups", mapOf("memberIds" to List(25) { UUID.randomUUID().toString() }), alice.token)
        assertEquals(HttpStatusCode.BadRequest, tooMany.status)
        assertTrue(tooMany.bodyAsText().contains("group_full"))
        assertEquals(HttpStatusCode.BadRequest, http.postJson("/v1/groups", mapOf("memberIds" to listOf(alice.id)), alice.token).status)
        assertEquals(HttpStatusCode.BadRequest, http.postJson("/v1/groups", CreateGroupRequest("x".repeat(101), listOf(bob.id)), alice.token).status)

        // Group messages must be sender-key ciphertext.
        val group = http.createGroup(alice, listOf(bob))
        val wrongType = http.postJson(
            "/v1/groups/${group.id}/messages",
            SendGroupMessageRequest(type = 2, body = b64(byteArrayOf(1)), clientId = UUID.randomUUID().toString(), epoch = group.epoch),
            alice.token,
        )
        assertEquals(HttpStatusCode.BadRequest, wrongType.status)
    }

    @Test
    fun `typing reaches online members live, and blocks hold inside groups`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val ws = createClient { install(WebSockets) }
        val (alice, _) = http.signalUser(app)
        val (bob, _) = http.signalUser(app)
        val (carol, _) = http.signalUser(app)
        http.befriend(alice, bob)
        http.befriend(alice, carol)
        val group = http.createGroup(alice, listOf(bob, carol))

        ws.webSocket("/v1/gateway", request = { bearerAuth(bob.token) }) {
            while (nextFrame().t != "ready") Unit
            ws.webSocket("/v1/gateway", request = { bearerAuth(alice.token) }) {
                val typing = GatewayFrame("ephemeral", Envelope(0, "", 7, b64(byteArrayOf(9, 9, 9)), 0), groupId = group.id)
                send(Frame.Text(json.encodeToString(GatewayFrame.serializer(), typing)))
                delay(300)
            }
            val live = nextFrame()
            assertEquals(0L, live.envelope!!.id)
            assertEquals(group.id, live.envelope!!.groupId)
            assertEquals(alice.id, live.envelope!!.senderId)
        }
        assertTrue(http.inbox(bob).isEmpty()) // typing is never stored

        // Carol blocks Bob: they stop receiving each other's group messages; Alice still gets both.
        http.put("/v1/blocks/${bob.id}") { bearerAuth(carol.token) }
        http.sendGroupRaw(bob, group, "from bob")
        http.sendGroupRaw(carol, group, "from carol")
        assertEquals(setOf(bob.id, carol.id), http.inbox(alice).map { it.senderId }.toSet())
        assertTrue(http.inbox(carol).isEmpty())
        assertTrue(http.inbox(bob).isEmpty())
    }

    // --- helpers ---

    private suspend fun HttpClient.createGroup(owner: TestUser, members: List<TestUser>, name: String? = null): GroupDto {
        val response = postJson("/v1/groups", CreateGroupRequest(name, members.map { it.id }), owner.token)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.body()
    }

    private suspend fun HttpClient.rename(user: TestUser, group: GroupDto, name: String): HttpResponse = patch("/v1/groups/${group.id}") {
        bearerAuth(user.token)
        contentType(ContentType.Application.Json)
        setBody(mapOf("name" to name))
    }

    /** What the app does before its first group message: one 1:1 envelope per member carrying its sender key. */
    private suspend fun HttpClient.shareSenderKey(
        from: TestUser,
        signal: SignalTestClient,
        distributionId: UUID,
        group: GroupDto,
        to: List<TestUser>,
    ) {
        val distribution = signal.senderKeyFor(distributionId).serialize()
        for (member in to) {
            if (!signal.store.containsSession(SignalProtocolAddress(member.id, SignalTestClient.DEVICE))) {
                signal.startSession(get("/v1/keys/${member.id}?groupId=${group.id}") { bearerAuth(from.token) }.body())
            }
            val sent = sendPairwise(from, member, signal.encryptBytes(member.id, distribution), group.id)
            assertEquals(HttpStatusCode.Accepted, sent.status, sent.bodyAsText())
        }
    }

    private suspend fun HttpClient.sendPairwise(from: TestUser, to: TestUser, message: CiphertextMessage, groupId: String?): HttpResponse =
        postJson(
            "/v1/messages",
            SendMessageRequest(to.id, message.type, b64(message.serialize()), UUID.randomUUID().toString(), groupId = groupId),
            from.token,
        )

    private suspend fun HttpClient.sendGroup(from: TestUser, group: GroupDto, message: CiphertextMessage): HttpResponse =
        postJson(
            "/v1/groups/${group.id}/messages",
            SendGroupMessageRequest(message.type, b64(message.serialize()), UUID.randomUUID().toString(), group.epoch),
            from.token,
        )

    /** For tests about who receives what: the bytes don't need to be real ciphertext. */
    private suspend fun HttpClient.sendGroupRaw(from: TestUser, group: GroupDto, label: String): HttpResponse =
        postJson(
            "/v1/groups/${group.id}/messages",
            SendGroupMessageRequest(CiphertextMessage.SENDERKEY_TYPE, b64(label.toByteArray()), UUID.randomUUID().toString(), group.epoch),
            from.token,
        )

    private suspend fun HttpClient.inbox(user: TestUser): List<Envelope> = get("/v1/messages") { bearerAuth(user.token) }.body()

    private suspend fun HttpClient.ack(user: TestUser, envelope: Envelope) =
        postJson("/v1/messages/ack", mapOf("ids" to listOf(envelope.id)), user.token)
}
