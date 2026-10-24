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
import com.discordlite.server.push.NoPush
import com.discordlite.server.push.Pusher
import com.discordlite.server.social.SocialService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/**
 * One encrypted message as the server sees it: who it's from, who it's for, and opaque bytes.
 * [type] is libsignal's ciphertext type (3 = first message of a session, 2 = after that,
 * 7 = a group message encrypted with the sender's sender key). [groupId] is set when the
 * envelope belongs to a group — either a group message, or a 1:1 envelope sent in a group's
 * context (sharing a sender key, a read receipt).
 */
@Serializable
data class Envelope(
    val id: Long,
    val senderId: String,
    val type: Int,
    val body: String,
    val sentAt: Long,
    val groupId: String? = null,
)

/**
 * [registrationId] is the recipient registration id the sender's session was built for. If the
 * recipient has since reinstalled (new keys, new id), the server answers `stale_session` so the
 * sender rebuilds the session instead of sending something that can't be decrypted.
 *
 * With [groupId], this is a 1:1 envelope between two members of that group (a sender key being
 * shared, a receipt). Then group membership is what allows it, not "who can DM me", and no DM is
 * started.
 */
@Serializable
data class SendMessageRequest(
    val recipientId: String,
    val type: Int,
    val body: String,
    val clientId: String,
    val registrationId: Int? = null,
    val groupId: String? = null,
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
 * recipient's "who can DM me" setting; once a DM exists it keeps working. Group messages go to
 * whoever is in the group at the moment they're sent, minus anyone blocked either way.
 */
class MessageService(
    private val db: Db,
    private val keys: KeyService,
    private val social: SocialService,
    private val gateway: Gateway,
    private val groups: GroupService,
    private val pusher: Pusher = NoPush,
    private val scope: CoroutineScope? = null,
) {

    suspend fun bundleFor(requester: UUID, target: UUID, groupId: String? = null): PreKeyBundleDto {
        checkCanMessage(requester, target, parseGroup(groupId))
        return db.tx { with(keys) { takeBundle(target) } }
    }

    suspend fun send(sender: UUID, request: SendMessageRequest): SendMessageResponse {
        val recipient = parseUser(request.recipientId)
        val clientId = runCatching { UUID.fromString(request.clientId) }.getOrNull() ?: throw badRequest("invalid_client_id")
        if (request.type !in ALLOWED_TYPES) throw badRequest("invalid_type")
        val body = runCatching { unb64(request.body) }.getOrNull() ?: throw badRequest("invalid_body")
        if (body.isEmpty() || body.size > MAX_BODY_BYTES) throw badRequest("invalid_body")
        val groupId = parseGroup(request.groupId)
        checkCanMessage(sender, recipient, groupId)
        if (request.registrationId != null) {
            val current = db.tx { queryOne("SELECT registration_id FROM identity_keys WHERE user_id = ?", recipient) { it.getInt("registration_id") } }
            if (current != null && current != request.registrationId) throw ApiException(HttpStatusCode.Gone, "stale_session")
        }

        val envelope = db.tx {
            if (groupId == null) {
                exec(
                    "INSERT INTO dm_threads (user_low, user_high) VALUES (LEAST(?::uuid, ?::uuid), GREATEST(?::uuid, ?::uuid)) ON CONFLICT DO NOTHING",
                    sender, recipient, sender, recipient,
                )
            }
            // Retrying with the same clientId never creates a duplicate.
            queryOne(
                """
                INSERT INTO message_queue (recipient_id, sender_id, type, body, client_id, group_id) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (sender_id, client_id, recipient_id) DO NOTHING
                RETURNING $COLUMNS
                """.trimIndent(),
                recipient, sender, request.type, body, clientId, groupId,
            ) { it.toEnvelope() }?.let { it to true }
                ?: (
                    queryOne(
                        "SELECT $COLUMNS FROM message_queue WHERE sender_id = ? AND client_id = ? AND recipient_id = ?",
                        sender, clientId, recipient,
                    ) { it.toEnvelope() } ?: throw ApiException(HttpStatusCode.Conflict, "already_delivered")
                    ) to false
        }
        if (envelope.second) deliver(recipient, envelope.first)
        return SendMessageResponse(envelope.first.id, envelope.first.sentAt)
    }

    /**
     * Queues one group message for every other member, all in one statement so nobody who joins
     * or leaves halfway through gets (or misses) it. Retrying with the same clientId never
     * duplicates.
     */
    suspend fun sendToGroup(sender: UUID, groupIdRaw: String?, request: SendGroupMessageRequest): SendMessageResponse {
        val groupId = parseGroup(groupIdRaw) ?: throw notFound("group_not_found")
        val clientId = runCatching { UUID.fromString(request.clientId) }.getOrNull() ?: throw badRequest("invalid_client_id")
        if (request.type != SENDER_KEY_TYPE) throw badRequest("invalid_type")
        val body = runCatching { unb64(request.body) }.getOrNull() ?: throw badRequest("invalid_body")
        if (body.isEmpty() || body.size > MAX_BODY_BYTES) throw badRequest("invalid_body")

        val queued = db.tx {
            val epoch = with(groups) { lockGroup(groupId) }
            if (!with(groups) { isMember(sender, groupId) }) throw notFound("group_not_found")
            if (epoch != request.epoch) throw staleGroup()
            query(
                """
                INSERT INTO message_queue (recipient_id, sender_id, type, body, client_id, group_id)
                SELECT m.user_id, ?, ?, ?, ?, m.group_id FROM group_members m
                WHERE m.group_id = ? AND m.user_id <> ?
                  AND NOT EXISTS (
                      SELECT 1 FROM blocks b
                      WHERE (b.blocker_id = m.user_id AND b.blocked_id = ?) OR (b.blocker_id = ? AND b.blocked_id = m.user_id)
                  )
                ON CONFLICT (sender_id, client_id, recipient_id) DO NOTHING
                RETURNING recipient_id, $COLUMNS
                """.trimIndent(),
                sender, request.type, body, clientId, groupId, sender, sender, sender,
            ) { it.uuid("recipient_id") to it.toEnvelope() }
        }
        queued.forEach { (recipient, envelope) -> deliver(recipient, envelope) }
        val first = queued.minByOrNull { it.second.id }?.second
        return SendMessageResponse(first?.id ?: 0, first?.sentAt ?: System.currentTimeMillis())
    }

    private suspend fun deliver(recipient: UUID, envelope: Envelope) {
        gateway.deliver(recipient, envelope)
        // Not connected right now: wake their phone (content-free push) so it fetches this.
        if (!gateway.isOnline(recipient)) scope?.launch { runCatching { pusher.wake(recipient) } }
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

    /** Like [relayEphemeral], for typing in a group: one sender-key ciphertext to every online member. */
    suspend fun relayGroupEphemeral(sender: UUID, groupIdRaw: String?, type: Int?, bodyRaw: String?) {
        val groupId = parseGroup(groupIdRaw) ?: return
        if (type != SENDER_KEY_TYPE || bodyRaw == null) return
        val body = runCatching { unb64(bodyRaw) }.getOrNull() ?: return
        if (body.isEmpty() || body.size > MAX_EPHEMERAL_BYTES) return
        val recipients = db.tx {
            query(
                """
                SELECT m.user_id FROM group_members m
                WHERE m.group_id = ? AND m.user_id <> ?
                  AND EXISTS (SELECT 1 FROM group_members s WHERE s.group_id = m.group_id AND s.user_id = ?)
                  AND NOT EXISTS (
                      SELECT 1 FROM blocks b
                      WHERE (b.blocker_id = m.user_id AND b.blocked_id = ?) OR (b.blocker_id = ? AND b.blocked_id = m.user_id)
                  )
                """.trimIndent(),
                groupId, sender, sender, sender, sender,
            ) { it.uuid("user_id") }
        }
        val envelope = Envelope(0, sender.toString(), type, bodyRaw, System.currentTimeMillis(), groupId.toString())
        recipients.filter(gateway::isOnline).forEach { gateway.deliver(it, envelope) }
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

    private suspend fun checkCanMessage(sender: UUID, recipient: UUID, groupId: UUID? = null) {
        if (sender == recipient) throw badRequest("cannot_message_self")
        if (!social.isVisible(sender, recipient)) throw notFound("user_not_found")
        if (groupId != null) {
            val bothIn = db.tx { with(groups) { isMember(sender, groupId) && isMember(recipient, groupId) } }
            if (!bothIn) throw notFound("group_not_found")
            return
        }
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

    private fun parseGroup(raw: String?): UUID? =
        raw?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: throw notFound("group_not_found") }

    private fun ResultSet.toEnvelope() = Envelope(
        id = getLong("id"),
        senderId = uuid("sender_id").toString(),
        type = getInt("type"),
        body = b64(getBytes("body")),
        sentAt = getTimestamp("created_at").time,
        groupId = getObject("group_id", UUID::class.java)?.toString(),
    )

    private companion object {
        const val COLUMNS = "id, sender_id, type, body, created_at, group_id"
        val ALLOWED_TYPES = setOf(2, 3) // libsignal WHISPER_TYPE, PREKEY_TYPE
        const val SENDER_KEY_TYPE = 7 // libsignal SENDERKEY_TYPE
        const val MAX_BODY_BYTES = 64 * 1024
        const val MAX_EPHEMERAL_BYTES = 4 * 1024
    }
}
