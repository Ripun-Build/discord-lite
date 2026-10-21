package com.discordlite.server

import com.discordlite.server.db.query
import com.discordlite.server.messaging.Envelope
import com.discordlite.server.messaging.GatewayFrame
import com.discordlite.server.messaging.PreKeyBundleDto
import com.discordlite.server.messaging.PreKeyCounts
import com.discordlite.server.messaging.SendMessageRequest
import com.discordlite.server.messaging.SendMessageResponse
import com.discordlite.server.messaging.b64
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
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
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.signal.libsignal.protocol.message.CiphertextMessage
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessagingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `end-to-end encrypted message is relayed but never readable by the server`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val (alice, aliceSignal) = http.signalUser(app)
        val (bob, bobSignal) = http.signalUser(app)
        http.befriend(alice, bob)

        val bundle = http.get("/v1/keys/${bob.id}") { bearerAuth(alice.token) }.body<PreKeyBundleDto>()
        aliceSignal.startSession(bundle)
        val secret = "meet at the usual spot 🔒 ${UUID.randomUUID()}"
        val sent = http.sendEncrypted(alice, bob, aliceSignal.encrypt(bob.id, secret))
        assertEquals(HttpStatusCode.Accepted, sent.status)

        // What the server holds is ciphertext: the plaintext appears nowhere in its database.
        val stored = app.deps.db.tx {
            query("SELECT body FROM message_queue WHERE recipient_id = ?", UUID.fromString(bob.id)) { it.getBytes("body") }
        }
        assertEquals(1, stored.size)
        assertFalse(String(stored.single(), Charsets.ISO_8859_1).contains(secret.substringBefore(" 🔒")))

        val waiting = http.get("/v1/messages") { bearerAuth(bob.token) }.body<List<Envelope>>()
        assertEquals(CiphertextMessage.PREKEY_TYPE, waiting.single().type)
        assertEquals(alice.id, waiting.single().senderId)
        assertEquals(secret, bobSignal.decrypt(waiting.single()))

        // Once acknowledged, the server deletes it.
        http.postJson("/v1/messages/ack", mapOf("ids" to waiting.map { it.id }), bob.token)
        assertTrue(http.get("/v1/messages") { bearerAuth(bob.token) }.body<List<Envelope>>().isEmpty())
    }

    @Test
    fun `messages arrive in real time over the gateway and replies continue the session`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val ws = createClient {
            install(ContentNegotiation) { json() }
            install(WebSockets)
        }
        val (alice, aliceSignal) = http.signalUser(app)
        val (bob, bobSignal) = http.signalUser(app)
        http.befriend(alice, bob)

        ws.webSocket("/v1/gateway", request = { bearerAuth(bob.token) }) {
            assertEquals("ready", nextFrame().t) // nothing waiting yet

            aliceSignal.startSession(http.get("/v1/keys/${bob.id}") { bearerAuth(alice.token) }.body())
            http.sendEncrypted(alice, bob, aliceSignal.encrypt(bob.id, "gg, you up?"))

            val live = nextFrame()
            assertEquals("message", live.t)
            assertEquals("gg, you up?", bobSignal.decrypt(live.envelope!!))
            send(Frame.Text(json.encodeToString(GatewayFrame.serializer(), GatewayFrame("ack", ids = listOf(live.envelope!!.id)))))

            // Bob answers on the same session — no pre-key needed this time.
            val reply = bobSignal.encrypt(alice.id, "yeah, hopping on")
            assertEquals(CiphertextMessage.WHISPER_TYPE, reply.type)
            http.sendEncrypted(bob, alice, reply)
            val forAlice = http.get("/v1/messages") { bearerAuth(alice.token) }.body<List<Envelope>>()
            assertEquals("yeah, hopping on", aliceSignal.decrypt(forAlice.single()))
        }
        // The ack over the socket deleted Bob's copy.
        assertTrue(http.get("/v1/messages") { bearerAuth(bob.token) }.body<List<Envelope>>().isEmpty())
    }

    @Test
    fun `messages sent while offline are delivered on connect`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val ws = createClient { install(WebSockets) }
        val (alice, aliceSignal) = http.signalUser(app)
        val (bob, bobSignal) = http.signalUser(app)
        http.befriend(alice, bob)
        aliceSignal.startSession(http.get("/v1/keys/${bob.id}") { bearerAuth(alice.token) }.body())
        listOf("one", "two", "three").forEach { http.sendEncrypted(alice, bob, aliceSignal.encrypt(bob.id, it)) }

        ws.webSocket("/v1/gateway", request = { bearerAuth(bob.token) }) {
            val got = List(3) { nextFrame() }
            assertEquals(listOf("one", "two", "three"), got.map { bobSignal.decrypt(it.envelope!!) })
            assertEquals("ready", nextFrame().t)
        }
    }

    @Test
    fun `typing events are relayed live but never stored, and only within an existing DM`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val ws = createClient { install(WebSockets) }
        val (alice, aliceSignal) = http.signalUser(app)
        val (bob, bobSignal) = http.signalUser(app)
        val (carol, _) = http.signalUser(app)
        http.befriend(alice, bob)
        aliceSignal.startSession(http.get("/v1/keys/${bob.id}") { bearerAuth(alice.token) }.body())
        http.sendEncrypted(alice, bob, aliceSignal.encrypt(bob.id, "hey"))

        ws.webSocket("/v1/gateway", request = { bearerAuth(bob.token) }) {
            val first = nextFrame()
            bobSignal.decrypt(first.envelope!!)
            send(Frame.Text(json.encodeToString(GatewayFrame.serializer(), GatewayFrame("ack", ids = listOf(first.envelope!!.id)))))
            assertEquals("ready", nextFrame().t)

            // A stranger's "typing" never reaches Bob (no DM between them).
            ws.webSocket("/v1/gateway", request = { bearerAuth(carol.token) }) {
                val fake = GatewayFrame("ephemeral", Envelope(0, "", 2, b64(byteArrayOf(1, 2, 3)), 0), to = bob.id)
                send(Frame.Text(json.encodeToString(GatewayFrame.serializer(), fake)))
            }
            // Alice's encrypted typing event arrives with id 0 (not stored, not acknowledged).
            ws.webSocket("/v1/gateway", request = { bearerAuth(alice.token) }) {
                val typing = aliceSignal.encrypt(bob.id, "{\"kind\":\"typing\",\"state\":\"on\"}")
                val frame = GatewayFrame("ephemeral", Envelope(0, "", typing.type, b64(typing.serialize()), 0), to = bob.id)
                send(Frame.Text(json.encodeToString(GatewayFrame.serializer(), frame)))
                kotlinx.coroutines.delay(300)
            }
            val live = nextFrame()
            assertEquals(0L, live.envelope!!.id)
            assertEquals(alice.id, live.envelope!!.senderId)
            assertTrue(bobSignal.decrypt(live.envelope!!).contains("typing"))
        }
        assertTrue(http.get("/v1/messages") { bearerAuth(bob.token) }.body<List<Envelope>>().isEmpty())
    }

    @Test
    fun `each bundle uses up a one-time key, then falls back to the last-resort key`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val (alice, _) = http.signalUser(app, oneTime = 2, kyber = 1)
        val (bob, _) = http.signalUser(app)
        http.befriend(alice, bob)

        val first = http.get("/v1/keys/${alice.id}") { bearerAuth(bob.token) }.body<PreKeyBundleDto>()
        val second = http.get("/v1/keys/${alice.id}") { bearerAuth(bob.token) }.body<PreKeyBundleDto>()
        val third = http.get("/v1/keys/${alice.id}") { bearerAuth(bob.token) }.body<PreKeyBundleDto>()
        assertNotEquals(first.oneTimePreKey!!.keyId, second.oneTimePreKey!!.keyId)
        assertNull(third.oneTimePreKey)
        assertNotEquals(first.kyberPreKey.keyId, second.kyberPreKey.keyId) // one-time Kyber, then last resort
        assertEquals(second.kyberPreKey.keyId, third.kyberPreKey.keyId)

        val counts = http.get("/v1/keys/count") { bearerAuth(alice.token) }.body<PreKeyCounts>()
        assertEquals(PreKeyCounts(oneTime = 0, kyber = 0), counts)
    }

    @Test
    fun `who-can-DM-me decides new DMs, existing DMs continue, blocks stop everything`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val (alice, aliceSignal) = http.signalUser(app)
        val (bob, _) = http.signalUser(app)

        // Default is "friends & server members": a stranger can't start a DM.
        val refused = http.get("/v1/keys/${bob.id}") { bearerAuth(alice.token) }
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertTrue(refused.bodyAsText().contains("dm_not_allowed"))

        http.setPolicy(bob, "EVERYONE")
        aliceSignal.startSession(http.get("/v1/keys/${bob.id}") { bearerAuth(alice.token) }.body())
        assertEquals(HttpStatusCode.Accepted, http.sendEncrypted(alice, bob, aliceSignal.encrypt(bob.id, "hi")).status)

        // Bob tightens his setting later; the DM that already exists keeps working.
        http.setPolicy(bob, "NOBODY")
        assertEquals(HttpStatusCode.Accepted, http.sendEncrypted(alice, bob, aliceSignal.encrypt(bob.id, "still here")).status)

        // A block ends it, and looks like the account is gone.
        http.put("/v1/blocks/${alice.id}") { bearerAuth(bob.token) }
        val blocked = http.sendEncrypted(alice, bob, aliceSignal.encrypt(bob.id, "hello?"))
        assertEquals(HttpStatusCode.NotFound, blocked.status)
    }

    @Test
    fun `after a reinstall the sender is told its session is stale`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val (alice, aliceSignal) = http.signalUser(app)
        val (bob, _) = http.signalUser(app)
        http.befriend(alice, bob)
        val bundle = http.get("/v1/keys/${bob.id}") { bearerAuth(alice.token) }.body<PreKeyBundleDto>()
        aliceSignal.startSession(bundle)

        // Bob reinstalls: new identity, new registration id.
        val bobAgain = SignalTestClient(bob.id)
        http.put("/v1/keys") {
            bearerAuth(bob.token)
            contentType(ContentType.Application.Json)
            setBody(bobAgain.keyUpload())
        }
        val message = aliceSignal.encrypt(bob.id, "hello?")
        val stale = http.postJson(
            "/v1/messages",
            SendMessageRequest(bob.id, message.type, b64(message.serialize()), UUID.randomUUID().toString(), registrationId = bundle.registrationId),
            alice.token,
        )
        assertEquals(HttpStatusCode.Gone, stale.status)
        assertTrue(stale.bodyAsText().contains("stale_session"))

        // Alice rebuilds from Bob's new keys, and the message gets through.
        val fresh = http.get("/v1/keys/${bob.id}") { bearerAuth(alice.token) }.body<PreKeyBundleDto>()
        val bobAddress = org.signal.libsignal.protocol.SignalProtocolAddress(bob.id, 1)
        aliceSignal.store.deleteSession(bobAddress)
        // Like the app: accept the new identity (and show "security code changed").
        aliceSignal.store.saveIdentity(bobAddress, org.signal.libsignal.protocol.IdentityKey(com.discordlite.server.messaging.unb64(fresh.identityKey)))
        aliceSignal.startSession(fresh)
        val retry = aliceSignal.encrypt(bob.id, "hello?")
        val ok = http.postJson(
            "/v1/messages",
            SendMessageRequest(bob.id, retry.type, b64(retry.serialize()), UUID.randomUUID().toString(), registrationId = fresh.registrationId),
            alice.token,
        )
        assertEquals(HttpStatusCode.Accepted, ok.status)
        assertEquals("hello?", bobAgain.decrypt(http.get("/v1/messages") { bearerAuth(bob.token) }.body<List<Envelope>>().single()))
    }

    @Test
    fun `resending with the same client id never duplicates`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val (alice, aliceSignal) = http.signalUser(app)
        val (bob, _) = http.signalUser(app)
        http.befriend(alice, bob)
        aliceSignal.startSession(http.get("/v1/keys/${bob.id}") { bearerAuth(alice.token) }.body())
        val message = aliceSignal.encrypt(bob.id, "once")
        val clientId = UUID.randomUUID().toString()
        val a = http.sendEncrypted(alice, bob, message, clientId).body<SendMessageResponse>()
        val b = http.sendEncrypted(alice, bob, message, clientId).body<SendMessageResponse>()
        assertEquals(a.id, b.id)
        assertEquals(1, http.get("/v1/messages") { bearerAuth(bob.token) }.body<List<Envelope>>().size)
    }

    @Test
    fun `malformed keys and messages are rejected`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val user = http.newUser(app)
        val good = SignalTestClient(user.id).keyUpload()
        val badIdentity = http.put("/v1/keys") {
            bearerAuth(user.token)
            contentType(ContentType.Application.Json)
            setBody(good.copy(identityKey = b64(ByteArray(10))))
        }
        assertEquals(HttpStatusCode.BadRequest, badIdentity.status)

        val other = http.newUser(app)
        val badType = http.postJson(
            "/v1/messages",
            SendMessageRequest(other.id, type = 9, body = b64(byteArrayOf(1)), clientId = UUID.randomUUID().toString()),
            user.token,
        )
        assertEquals(HttpStatusCode.BadRequest, badType.status)
    }

    // --- helpers ---

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.nextFrame(): GatewayFrame =
        withTimeout(5_000) {
            val frame = incoming.receive() as Frame.Text
            json.decodeFromString(GatewayFrame.serializer(), frame.readText())
        }

    private suspend fun HttpClient.signalUser(app: TestApp, oneTime: Int = 5, kyber: Int = 3): Pair<TestUser, SignalTestClient> {
        val user = newUser(app)
        val signal = SignalTestClient(user.id)
        val upload = put("/v1/keys") {
            bearerAuth(user.token)
            contentType(ContentType.Application.Json)
            setBody(signal.keyUpload(oneTime, kyber))
        }
        assertEquals(HttpStatusCode.NoContent, upload.status, upload.bodyAsText())
        return user to signal
    }

    private suspend fun HttpClient.befriend(a: TestUser, b: TestUser) {
        postJson("/v1/friends/requests", mapOf("userId" to b.id), a.token)
        post("/v1/friends/requests/${a.id}/accept") { bearerAuth(b.token) }
    }

    private suspend fun HttpClient.setPolicy(user: TestUser, policy: String) {
        patch("/v1/me") {
            bearerAuth(user.token)
            contentType(ContentType.Application.Json)
            setBody(mapOf("dmPolicy" to policy))
        }
    }

    private suspend fun HttpClient.sendEncrypted(
        from: TestUser,
        to: TestUser,
        message: CiphertextMessage,
        clientId: String = UUID.randomUUID().toString(),
    ): HttpResponse = postJson(
        "/v1/messages",
        SendMessageRequest(to.id, message.type, b64(message.serialize()), clientId),
        from.token,
    )

}
