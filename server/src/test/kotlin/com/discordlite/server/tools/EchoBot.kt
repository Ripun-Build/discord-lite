package com.discordlite.server.tools

import com.discordlite.server.SignalTestClient
import com.discordlite.server.auth.AuthResponse
import com.discordlite.server.messaging.GatewayFrame
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
 */
fun main() = runBlocking {
    val server = System.getenv("BOT_SERVER") ?: "http://localhost:8080"
    val mailpit = System.getenv("BOT_MAILPIT") ?: "http://localhost:8025"
    val email = System.getenv("BOT_EMAIL") ?: "echobot@discord-lite.local"
    val username = System.getenv("BOT_USERNAME") ?: "echobot"
    val password = System.getenv("BOT_PASSWORD") ?: "echobot-dev-password"
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

    // 4. Echo every message back, encrypted.
    http.webSocket("${server.replaceFirst("http", "ws")}/v1/gateway", request = { bearerAuth(token) }) {
        println("Connected. Waiting for messages… (Ctrl+C to stop)")
        for (frame in incoming) {
            if (frame !is Frame.Text) continue
            val event = json.decodeFromString(GatewayFrame.serializer(), frame.readText())
            val envelope = event.envelope ?: continue
            val text = runCatching {
                val payload = json.parseToJsonElement(signal.decrypt(envelope)).jsonObject
                payload["text"]?.jsonPrimitive?.content
            }.getOrElse {
                println("Couldn't decrypt a message (${it.javaClass.simpleName}) — skipping")
                null
            }
            send(Frame.Text(json.encodeToString(GatewayFrame.serializer(), GatewayFrame("ack", ids = listOf(envelope.id)))))
            if (text == null) continue
            println("← \"$text\"")

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
