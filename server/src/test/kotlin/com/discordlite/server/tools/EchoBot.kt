package com.discordlite.server.tools

import com.discordlite.server.SignalTestClient
import com.discordlite.server.auth.AuthResponse
import com.discordlite.server.messaging.GatewayFrame
import com.discordlite.server.messaging.GroupDto
import com.discordlite.server.messaging.SendGroupMessageRequest
import com.discordlite.server.servers.SendChannelMessageRequest
import com.discordlite.server.messaging.SendMessageRequest
import com.discordlite.server.messaging.b64
import com.discordlite.server.social.FriendsResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * @echobot — a development-only test friend.
 *
 * Signs in (creating the account the first time, reading its code from Mailpit), publishes real
 * Signal keys, accepts friend requests, and answers every message with "echo: …" — encrypted
 * end to end exactly like the app does. Its keys live in memory, so each run is a "new phone".
 *
 * Add it to a group and it echoes there too, with its own sender key (shared with each member over
 * 1:1 sessions first, as the app does). Add it to a server and @mention it in a channel: it answers
 * there, mentioning you back (server channels aren't end-to-end encrypted). DM it an invite link
 * and it joins that server.
 */
fun main() = runBlocking {
    val server = System.getenv("BOT_SERVER") ?: "http://localhost:8080"
    val mailpit = System.getenv("BOT_MAILPIT") ?: "http://localhost:8025"
    val email = System.getenv("BOT_EMAIL") ?: "echobot@discord-lite.local"
    val username = System.getenv("BOT_USERNAME") ?: "echobot"
    val password = System.getenv("BOT_PASSWORD") ?: "echobot-dev-password"
    // How long it "types" before answering. Set e.g. BOT_REPLY_DELAY_MS=8000 to test push: send,
    // close the app, and the reply arrives while it's in the background.
    val replyDelay = System.getenv("BOT_REPLY_DELAY_MS")?.toLongOrNull() ?: 1_500L
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(WebSockets)
    }

    suspend fun post(path: String, body: Any, token: String? = null): HttpResponse = http.post(server + path) {
        contentType(ContentType.Application.Json)
        setBody(body)
        token?.let { bearerAuth(it) }
    }

    // 1. Sign in, or create the account the first time.
    var login = post("/v1/auth/login", mapOf("email" to email, "password" to password))
    if (!login.status.isSuccess()) {
        println("Creating @$username …")
        val signUp = post("/v1/auth/signup", mapOf("email" to email, "username" to username, "password" to password, "displayName" to "Echo Bot"))
        check(signUp.status == HttpStatusCode.Accepted) { "Sign-up failed: ${signUp.bodyAsText()}" }
        delay(1_000)
        val code = latestCode(http, mailpit, email)
        login = post("/v1/auth/verify", mapOf("email" to email, "code" to code))
        check(login.status.isSuccess()) { "Verification failed: ${login.bodyAsText()}" }
    }
    val auth = login.body<AuthResponse>()
    val token = auth.session.accessToken
    val me = auth.user.id
    println("Signed in as @${auth.user.username} ($me)")

    // 2. Publish fresh Signal keys.
    val signal = SignalTestClient(me)
    val upload = http.put("$server/v1/keys") {
        contentType(ContentType.Application.Json)
        setBody(signal.keyUpload(oneTimeCount = 50, kyberCount = 20))
        bearerAuth(token)
    }
    check(upload.status == HttpStatusCode.NoContent) { "Key upload failed: ${upload.bodyAsText()}" }
    println("Published Signal keys. Add @$username as a friend in the app, then send it a message.")

    // 3. Accept friend requests every few seconds.
    launch {
        while (true) {
            runCatching {
                http.get("$server/v1/friends") { bearerAuth(token) }.body<FriendsResponse>().incoming.forEach { request ->
                    http.post("$server/v1/friends/requests/${request.id}/accept") { bearerAuth(token) }
                    println("Accepted friend request from @${request.username}")
                }
            }
            delay(3_000)
        }
    }

    fun control(kind: String, state: String, ids: List<String>? = null) = buildJsonObject {
        put("v", 1)
        put("kind", kind)
        put("id", UUID.randomUUID().toString())
        put("ts", System.currentTimeMillis())
        put("state", state)
        if (ids != null) put("ids", kotlinx.serialization.json.JsonArray(ids.map { kotlinx.serialization.json.JsonPrimitive(it) }))
    }.toString()

    // Groups: our sender key per group, and who already has it.
    val myKeys = mutableMapOf<String, UUID>()
    val sharedWith = mutableMapOf<String, MutableSet<String>>()
    val knownMembers = mutableMapOf<String, Set<String>>()

    suspend fun pairwise(to: String, groupId: String, body: String) {
        if (!signal.store.containsSession(org.signal.libsignal.protocol.SignalProtocolAddress(to, SignalTestClient.DEVICE))) {
            val bundle = http.get("$server/v1/keys/$to?groupId=$groupId") { bearerAuth(token) }
            if (!bundle.status.isSuccess()) return println("No keys for $to in group: ${bundle.bodyAsText()}")
            signal.startSession(bundle.body())
        }
        val c = signal.encryptBytes(to, body.toByteArray())
        post("/v1/messages", SendMessageRequest(to, c.type, b64(c.serialize()), UUID.randomUUID().toString(), groupId = groupId), token)
    }

    suspend fun sendToGroup(groupId: String, body: String) {
        repeat(2) {
            val group = http.get("$server/v1/groups/$groupId") { bearerAuth(token) }.body<GroupDto>()
            val members = group.members.map { it.id }.toSet() - me
            // Someone left since last time: new key, so they can't read what comes next.
            if (knownMembers[groupId]?.let { (it - members).isNotEmpty() } == true) {
                myKeys.remove(groupId)
                sharedWith.remove(groupId)
            }
            knownMembers[groupId] = members
            val distributionId = myKeys.getOrPut(groupId) { UUID.randomUUID() }
            val shared = sharedWith.getOrPut(groupId) { mutableSetOf() }
            val distribution = b64(signal.senderKeyFor(distributionId).serialize())
            (members - shared).forEach { member ->
                val keyPayload = buildJsonObject {
                    put("v", 1)
                    put("kind", "sender_key")
                    put("id", UUID.randomUUID().toString())
                    put("ts", System.currentTimeMillis())
                    put("key", distribution)
                }.toString()
                pairwise(member, groupId, keyPayload)
                shared += member
            }
            val c = signal.encryptForGroup(distributionId, body)
            val sent = post("/v1/groups/$groupId/messages", SendGroupMessageRequest(c.type, b64(c.serialize()), UUID.randomUUID().toString(), group.epoch), token)
            if (sent.status.isSuccess()) return
            if (sent.status != HttpStatusCode.Conflict) return println("Group send failed: ${sent.bodyAsText()}")
        }
    }

    // 4. Echo every message back, encrypted.
    http.webSocket("${server.replaceFirst("http", "ws")}/v1/gateway", request = { bearerAuth(token) }) {
        println("Connected. Waiting for messages… (Ctrl+C to stop)")
        for (frame in incoming) {
            if (frame !is Frame.Text) continue
            val event = json.decodeFromString(GatewayFrame.serializer(), frame.readText())
            val channelMessage = event.channelMessage
            if (event.t == "channel_message" && channelMessage != null && channelMessage.authorId != me && me in channelMessage.mentionIds) {
                println("← [#channel] \"${channelMessage.body}\"")
                launch {
                    send(Frame.Text(json.encodeToString(GatewayFrame.serializer(), GatewayFrame("channel_typing", channelId = channelMessage.channelId))))
                    delay(replyDelay)
                    val said = channelMessage.body.replace("@$username", "").trim()
                    val reply = "@${channelMessage.authorUsername} echo: $said"
                    val sent = post("/v1/channels/${channelMessage.channelId}/messages", SendChannelMessageRequest(reply, UUID.randomUUID().toString()), token)
                    println(if (sent.status.isSuccess()) "→ [#channel] \"$reply\"" else "Channel reply failed: ${sent.bodyAsText()}")
                }
                continue
            }
            if (event.t == "group") println("Group \"${event.group?.name ?: event.group?.id}\" now has ${event.group?.members?.size} members")
            val envelope = event.envelope ?: continue
            val groupId = envelope.groupId
            if (groupId != null) {
                // A group message, or a 1:1 envelope inside the group (a sender key, a receipt).
                val text = runCatching {
                    if (envelope.type == org.signal.libsignal.protocol.message.CiphertextMessage.SENDERKEY_TYPE) {
                        signal.decryptGroup(envelope)
                    } else {
                        String(signal.decryptBytes(envelope))
                    }
                }.getOrElse {
                    println("Couldn't decrypt group envelope ${envelope.id} (${it.javaClass.simpleName}) — skipping")
                    null
                }
                if (envelope.id > 0) send(Frame.Text(json.encodeToString(GatewayFrame.serializer(), GatewayFrame("ack", ids = listOf(envelope.id)))))
                val payload = text?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: continue
                when (payload["kind"]?.jsonPrimitive?.content) {
                    "sender_key" -> payload["key"]?.jsonPrimitive?.content?.let {
                        signal.acceptSenderKey(envelope.senderId, com.discordlite.server.messaging.unb64(it))
                        println("Got a sender key from ${envelope.senderId} for group $groupId")
                    }
                    "text" -> {
                        val said = payload["text"]?.jsonPrimitive?.content ?: continue
                        val messageId = payload["id"]?.jsonPrimitive?.content ?: continue
                        println("← [group] \"$said\"")
                        launch {
                            pairwise(envelope.senderId, groupId, control("receipt", "delivered", listOf(messageId)))
                            delay(replyDelay)
                            pairwise(envelope.senderId, groupId, control("receipt", "read", listOf(messageId)))
                            val reply = buildJsonObject {
                                put("v", 1)
                                put("kind", "text")
                                put("id", UUID.randomUUID().toString())
                                put("text", "echo: $said")
                                put("ts", System.currentTimeMillis())
                            }.toString()
                            sendToGroup(groupId, reply)
                            println("→ [group] \"echo: $said\"")
                        }
                    }
                }
                continue
            }
            val payload = runCatching { json.parseToJsonElement(signal.decrypt(envelope)).jsonObject }.getOrElse {
                println("Couldn't decrypt envelope ${envelope.id} type ${envelope.type} (${it.javaClass.simpleName}: ${it.message}) — skipping")
                null
            }
            if (envelope.id > 0) send(Frame.Text(json.encodeToString(GatewayFrame.serializer(), GatewayFrame("ack", ids = listOf(envelope.id)))))
            val kind = payload?.get("kind")?.jsonPrimitive?.content ?: "text" // older payloads omit the default
            if (payload == null || kind != "text") continue // receipts / typing from the app
            val text = payload["text"]?.jsonPrimitive?.content ?: continue
            val messageId = payload["id"]?.jsonPrimitive?.content ?: continue
            println("← \"$text\"")
            // An invite link in a DM: join that server (and still echo, so the link shows as a card).
            Regex("discordlite://invite/([A-Za-z0-9]{8})").find(text)?.groupValues?.get(1)?.let { code ->
                val joined = http.post("$server/v1/invites/$code/join") { bearerAuth(token) }
                println(if (joined.status.isSuccess()) "Joined a server with invite $code" else "Couldn't join with $code: ${joined.bodyAsText()}")
            }

            suspend fun encryptedTo(body: String) = signal.encrypt(envelope.senderId, body)
            suspend fun sendStored(body: String) {
                val c = encryptedTo(body)
                post("/v1/messages", SendMessageRequest(envelope.senderId, c.type, b64(c.serialize()), UUID.randomUUID().toString()), token)
            }

            // Behave like a person: delivered → typing… → read → reply.
            sendStored(control("receipt", "delivered", listOf(messageId)))
            val typing = encryptedTo(control("typing", "on"))
            send(Frame.Text(json.encodeToString(GatewayFrame.serializer(), GatewayFrame("ephemeral", com.discordlite.server.messaging.Envelope(0, "", typing.type, b64(typing.serialize()), 0), to = envelope.senderId))))
            delay(replyDelay)
            sendStored(control("receipt", "read", listOf(messageId)))

            val reply = buildJsonObject {
                put("v", 1)
                put("kind", "text")
                put("id", UUID.randomUUID().toString())
                put("text", "echo: $text")
                put("ts", System.currentTimeMillis())
            }.toString()
            val cipher = signal.encrypt(envelope.senderId, reply)
            val sent = post("/v1/messages", SendMessageRequest(envelope.senderId, cipher.type, b64(cipher.serialize()), UUID.randomUUID().toString()), token)
            println(if (sent.status.isSuccess()) "→ \"echo: $text\"" else "Reply failed: ${sent.bodyAsText()}")
        }
    }
}

/** Reads the newest verification code sent to [email] from the local Mailpit inbox. */
private suspend fun latestCode(http: HttpClient, mailpit: String, email: String): String {
    val search = Json.parseToJsonElement(http.get("$mailpit/api/v1/search?query=to:$email&limit=1").bodyAsText()).jsonObject
    val id = search["messages"]!!.let { (it as kotlinx.serialization.json.JsonArray).first().jsonObject["ID"]!!.jsonPrimitive.content }
    val text = Json.parseToJsonElement(http.get("$mailpit/api/v1/message/$id").bodyAsText()).jsonObject["Text"]!!.jsonPrimitive.content
    return Regex("\\b\\d{6}\\b").find(text)!!.value
}
