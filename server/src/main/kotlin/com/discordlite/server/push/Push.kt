package com.discordlite.server.push

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.discordlite.server.common.badRequest
import com.discordlite.server.db.Db
import com.discordlite.server.db.exec
import com.discordlite.server.db.queryOne
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class PushTokenRequest(val token: String)

/** Wakes a user's phone so it can fetch waiting messages. */
interface Pusher {
    suspend fun wake(user: UUID)
}

/** Used when push isn't configured (and in most tests). */
object NoPush : Pusher {
    override suspend fun wake(user: UUID) = Unit
}

/** Stores each account's Firebase token. */
class PushTokens(private val db: Db) {
    suspend fun save(user: UUID, token: String) {
        if (token.isBlank() || token.length > 4096) throw badRequest("invalid_push_token")
        db.tx {
            // A token belongs to one device; if another account used this phone before, move it.
            exec("DELETE FROM push_tokens WHERE token = ? AND user_id <> ?", token, user)
            exec(
                "INSERT INTO push_tokens (user_id, token) VALUES (?, ?) ON CONFLICT (user_id) DO UPDATE SET token = excluded.token, updated_at = now()",
                user, token,
            )
        }
    }

    suspend fun remove(user: UUID) = db.tx { exec("DELETE FROM push_tokens WHERE user_id = ?", user) }

    suspend fun tokenFor(user: UUID): String? = db.tx { queryOne("SELECT token FROM push_tokens WHERE user_id = ?", user) { it.getString("token") } }

    suspend fun forget(token: String) = db.tx { exec("DELETE FROM push_tokens WHERE token = ?", token) }
}

/**
 * Sends Firebase Cloud Messaging pushes with the HTTP v1 API.
 *
 * Privacy: the push is a bare `{"t":"wake"}` — no sender, no text, no conversation id — so Google
 * learns only that "something arrived". The app wakes up, fetches the encrypted envelopes from our
 * server, decrypts them on the phone, and only then shows a notification.
 */
class FcmPusher(serviceAccountPath: String, private val tokens: PushTokens) : Pusher {
    private val log = LoggerFactory.getLogger(FcmPusher::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val account = json.parseToJsonElement(File(serviceAccountPath).readText()).jsonObject
    private val projectId = account.getValue("project_id").jsonPrimitive.content
    private val clientEmail = account.getValue("client_email").jsonPrimitive.content
    private val tokenUri = account["token_uri"]?.jsonPrimitive?.content ?: "https://oauth2.googleapis.com/token"
    private val privateKey: RSAPrivateKey = parsePrivateKey(account.getValue("private_key").jsonPrimitive.content)

    private val tokenLock = Mutex()
    private var accessToken: String? = null
    private var accessTokenExpiresAt = 0L
    private val lastWake = ConcurrentHashMap<UUID, Long>()

    override suspend fun wake(user: UUID) {
        // A burst of messages needs one wake-up, not one per message.
        val now = System.currentTimeMillis()
        val previous = lastWake[user]
        if (previous != null && now - previous < WAKE_INTERVAL_MS) return
        lastWake[user] = now

        val deviceToken = tokens.tokenFor(user) ?: return
        val body = buildJsonObject {
            putJsonObject("message") {
                put("token", deviceToken)
                putJsonObject("data") { put("t", "wake") }
                putJsonObject("android") {
                    put("priority", "HIGH")
                    put("collapse_key", "wake")
                    put("ttl", "86400s")
                }
            }
        }.toString()

        val response = withContext(Dispatchers.IO) {
            runCatching {
                http.send(
                    HttpRequest.newBuilder(URI("https://fcm.googleapis.com/v1/projects/$projectId/messages:send"))
                        .header("Authorization", "Bearer ${accessToken()}")
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            }.getOrElse {
                log.warn("Push failed: {}", it.javaClass.simpleName)
                return@withContext null
            }
        } ?: return

        when {
            response.statusCode() in 200..299 -> Unit
            // The app was uninstalled or the token expired: stop using it.
            response.statusCode() == 404 || response.body().contains("UNREGISTERED") -> tokens.forget(deviceToken)
            else -> log.warn("Push rejected with HTTP {}", response.statusCode())
        }
    }

    /** OAuth access token for FCM, minted from the service account and cached for ~55 minutes. */
    private suspend fun accessToken(): String = tokenLock.withLock {
        val now = System.currentTimeMillis()
        accessToken?.takeIf { now < accessTokenExpiresAt }?.let { return it }
        val assertion = JWT.create()
            .withIssuer(clientEmail)
            .withAudience(tokenUri)
            .withClaim("scope", "https://www.googleapis.com/auth/firebase.messaging")
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(now + 3_600_000))
            .sign(Algorithm.RSA256(null, privateKey))
        val form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", "UTF-8") + "&assertion=$assertion"
        val response = withContext(Dispatchers.IO) {
            http.send(
                HttpRequest.newBuilder(URI(tokenUri))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }
        check(response.statusCode() == 200) { "Firebase auth failed with HTTP ${response.statusCode()}" }
        val token = json.parseToJsonElement(response.body()).jsonObject.getValue("access_token").jsonPrimitive.content
        accessToken = token
        accessTokenExpiresAt = now + 55 * 60_000
        token
    }

    private fun parsePrivateKey(pem: String): RSAPrivateKey {
        val base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replace("\\s".toRegex(), "")
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64))) as RSAPrivateKey
    }

    private companion object {
        const val WAKE_INTERVAL_MS = 2_000L
    }
}
