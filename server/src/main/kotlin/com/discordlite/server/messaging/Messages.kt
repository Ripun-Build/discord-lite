package com.discordlite.server.messaging

import com.discordlite.server.common.ApiException
import com.discordlite.server.common.badRequest
import com.discordlite.server.common.forbidden
import com.discordlite.server.common.notFound
import com.discordlite.server.db.Db
import com.discordlite.server.db.exec
import com.discordlite.server.db.query
import com.discordlite.server.db.queryOne
import com.discordlite.server.db.uuid
import com.discordlite.server.social.SocialService
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/**
 * One encrypted message as the server sees it: who it's from, who it's for, and opaque bytes.
 * [type] is libsignal's ciphertext type (3 = first message of a session, 2 = after that).
 */
@Serializable
data class Envelope(val id: Long, val senderId: String, val type: Int, val body: String, val sentAt: Long)

/**
 * [registrationId] is the recipient registration id the sender's session was built for. If the
 * recipient has since reinstalled (new keys, new id), the server answers `stale_session` so the
 * sender rebuilds the session instead of sending something that can't be decrypted.
 */
@Serializable
data class SendMessageRequest(
    val recipientId: String,
    val type: Int,
    val body: String,
    val clientId: String,
    val registrationId: Int? = null,
)

@Serializable
data class SendMessageResponse(val id: Long, val sentAt: Long)

@Serializable
data class AckRequest(val ids: List<Long>)

/**
 * Relays end-to-end encrypted messages. The server stores each envelope only until the recipient
 * acknowledges it, then deletes it. It never sees message text.
 *
 * Rules: blocked either way → looks like the user doesn't exist. Starting a new DM follows the
 * recipient's "who can DM me" setting; once a DM exists it keeps working.
 */
class MessageService(
    private val db: Db,
    private val keys: KeyService,
    private val social: SocialService,
    private val gateway: Gateway,
) {

    suspend fun bundleFor(requester: UUID, target: UUID): PreKeyBundleDto {
        checkCanMessage(requester, target)
        return db.tx { with(keys) { takeBundle(target) } }
    }

    suspend fun send(sender: UUID, request: SendMessageRequest): SendMessageResponse {
        val recipient = parseUser(request.recipientId)
        val clientId = runCatching { UUID.fromString(request.clientId) }.getOrNull() ?: throw badRequest("invalid_client_id")
        if (request.type !in ALLOWED_TYPES) throw badRequest("invalid_type")
        val body = runCatching { unb64(request.body) }.getOrNull() ?: throw badRequest("invalid_body")
        if (body.isEmpty() || body.size > MAX_BODY_BYTES) throw badRequest("invalid_body")
        checkCanMessage(sender, recipient)
        if (request.registrationId != null) {
            val current = db.tx { queryOne("SELECT registration_id FROM identity_keys WHERE user_id = ?", recipient) { it.getInt("registration_id") } }
            if (current != null && current != request.registrationId) throw ApiException(HttpStatusCode.Gone, "stale_session")
        }

        val envelope = db.tx {
            exec(
                "INSERT INTO dm_threads (user_low, user_high) VALUES (LEAST(?::uuid, ?::uuid), GREATEST(?::uuid, ?::uuid)) ON CONFLICT DO NOTHING",
                sender, recipient, sender, recipient,
            )
            // Retrying with the same clientId never creates a duplicate.
            queryOne(
                """
                INSERT INTO message_queue (recipient_id, sender_id, type, body, client_id) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (sender_id, client_id) DO NOTHING
                RETURNING $COLUMNS
                """.trimIndent(),
                recipient, sender, request.type, body, clientId,
            ) { it.toEnvelope() }?.let { it to true }
                ?: (queryOne("SELECT $COLUMNS FROM message_queue WHERE sender_id = ? AND client_id = ?", sender, clientId) { it.toEnvelope() }
                    ?: throw ApiException(HttpStatusCode.Conflict, "already_delivered")) to false
        }
        if (envelope.second) gateway.deliver(recipient, envelope.first)
        return SendMessageResponse(envelope.first.id, envelope.first.sentAt)
    }

    /**
     * Passes an encrypted, short-lived event (typing) straight to [recipient] if they're
     * connected. Nothing is stored. Only between people who already have a DM, and never across a
     * block. Delivered with id 0, which tells the app not to acknowledge it.
     */
    suspend fun relayEphemeral(sender: UUID, recipientRaw: String?, type: Int?, bodyRaw: String?) {
        val recipient = runCatching { UUID.fromString(recipientRaw) }.getOrNull() ?: return
        if (type !in ALLOWED_TYPES || bodyRaw == null) return
        val body = runCatching { unb64(bodyRaw) }.getOrNull() ?: return
        if (body.isEmpty() || body.size > MAX_EPHEMERAL_BYTES) return
        if (!gateway.isOnline(recipient) || social.isBlocked(sender, recipient)) return
        val hasThread = db.tx {
            queryOne(
                "SELECT 1 FROM dm_threads WHERE user_low = LEAST(?::uuid, ?::uuid) AND user_high = GREATEST(?::uuid, ?::uuid)",
                sender, recipient, sender, recipient,
            ) { true } == true
        }
        if (!hasThread) return
        gateway.deliver(recipient, Envelope(0, sender.toString(), type!!, bodyRaw, System.currentTimeMillis()))
    }

    /** Envelopes waiting for [user], oldest first. */
    suspend fun pending(user: UUID, limit: Int = 200): List<Envelope> = db.tx { pendingFor(user, limit) }

    fun Connection.pendingFor(user: UUID, limit: Int): List<Envelope> =
        query("SELECT $COLUMNS FROM message_queue WHERE recipient_id = ? ORDER BY id LIMIT ?", user, limit) { it.toEnvelope() }

    /** The recipient has stored these locally; delete them from the server. */
    suspend fun ack(user: UUID, ids: List<Long>) {
        if (ids.isEmpty()) return
        db.tx {
            exec("DELETE FROM message_queue WHERE recipient_id = ? AND id = ANY(?)", user, createArrayOf("bigint", ids.toTypedArray()))
        }
    }

    /** Drops envelopes nobody collected for 30 days. */
    suspend fun purgeExpired(): Int = db.tx {
        exec("DELETE FROM message_queue WHERE created_at < now() - interval '30 days'")
    }

    private suspend fun checkCanMessage(sender: UUID, recipient: UUID) {
        if (sender == recipient) throw badRequest("cannot_message_self")
        if (!social.isVisible(sender, recipient)) throw notFound("user_not_found")
        val existing = db.tx {
            queryOne(
                "SELECT 1 FROM dm_threads WHERE user_low = LEAST(?::uuid, ?::uuid) AND user_high = GREATEST(?::uuid, ?::uuid)",
                sender, recipient, sender, recipient,
            ) { true } == true
        }
        if (!existing && !social.canStartDm(sender, recipient)) throw forbidden("dm_not_allowed")
    }

    private fun parseUser(raw: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrNull() ?: throw notFound("user_not_found")

    private fun ResultSet.toEnvelope() = Envelope(
        id = getLong("id"),
        senderId = uuid("sender_id").toString(),
        type = getInt("type"),
        body = b64(getBytes("body")),
        sentAt = getTimestamp("created_at").time,
    )

    private companion object {
        const val COLUMNS = "id, sender_id, type, body, created_at"
        val ALLOWED_TYPES = setOf(2, 3) // libsignal WHISPER_TYPE, PREKEY_TYPE
        const val MAX_BODY_BYTES = 64 * 1024
        const val MAX_EPHEMERAL_BYTES = 4 * 1024
    }
}
