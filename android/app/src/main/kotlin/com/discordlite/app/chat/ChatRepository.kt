package com.discordlite.app.chat

import com.discordlite.app.crypto.SignalStore
import com.discordlite.app.crypto.b64
import com.discordlite.app.crypto.unb64
import com.discordlite.app.data.SocialRepository
import com.discordlite.app.data.local.LocalDatabase
import com.discordlite.app.data.local.exec
import com.discordlite.app.data.local.int
import com.discordlite.app.data.local.long
import com.discordlite.app.data.local.queryList
import com.discordlite.app.data.local.queryOne
import com.discordlite.app.data.local.str
import com.discordlite.app.data.remote.ApiError
import com.discordlite.app.data.remote.DiscordLiteApi
import com.discordlite.app.data.remote.Envelope
import com.discordlite.app.data.remote.PreKeyBundleResponse
import com.discordlite.app.data.remote.SendMessageBody
import com.discordlite.app.data.remote.apiCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import java.util.UUID

enum class MessageStatus { SENDING, SENT, FAILED, RECEIVED, NOTICE }

data class ChatMessage(
    val localId: Long,
    val peerId: String,
    val clientId: String,
    val fromMe: Boolean,
    val body: String,
    val sentAt: Long,
    val status: MessageStatus,
)

data class Conversation(
    val peerId: String,
    val displayName: String,
    val username: String,
    val lastMessage: String,
    val lastAt: Long,
    val unread: Int,
)

/**
 * What actually gets encrypted. The server only ever sees the ciphertext of this JSON.
 * [kind] leaves room for receipts and typing (next part of Phase 2) without a protocol change.
 */
@Serializable
data class ChatPayload(val v: Int = 1, val kind: String = "text", val id: String, val text: String? = null, val ts: Long)

/**
 * One-to-one end-to-end encrypted chat.
 *
 * Send: save locally as "sending" → build a Signal session from the friend's pre-key bundle if
 * there isn't one → encrypt → upload. Network failures stay "sending" and retry on reconnect.
 * Receive: decrypt → save → the gateway then acknowledges, and the server deletes its copy.
 */
class ChatRepository(
    private val api: DiscordLiteApi,
    private val store: SignalStore,
    private val local: LocalDatabase,
    private val social: SocialRepository,
    private val selfId: () -> String?,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val crypto = Mutex() // libsignal session state must not be used from two places at once
    private val changes = MutableStateFlow(0)
    private val db get() = local.db

    /** The chat currently on screen; its incoming messages don't count as unread. */
    @Volatile var openPeer: String? = null

    val conversations: Flow<List<Conversation>> = changes.map {
        db.queryList("SELECT * FROM conversations ORDER BY last_at DESC") {
            Conversation(it.str("peer_id"), it.str("display_name"), it.str("username"), it.str("last_message"), it.long("last_at"), it.int("unread"))
        }
    }.flowOn(Dispatchers.IO)

    fun messages(peerId: String): Flow<List<ChatMessage>> = changes.map {
        db.queryList("SELECT * FROM messages WHERE peer_id = ? ORDER BY sent_at, local_id", peerId) {
            ChatMessage(
                it.long("local_id"), it.str("peer_id"), it.str("client_id"), it.int("from_me") == 1, it.str("body"), it.long("sent_at"),
                MessageStatus.valueOf(it.str("status")),
            )
        }
    }.flowOn(Dispatchers.IO)

    fun conversation(peerId: String): Flow<Conversation?> = conversations.map { list -> list.firstOrNull { it.peerId == peerId } }

    suspend fun open(peerId: String) {
        openPeer = peerId
        withContext(Dispatchers.IO) {
            ensureConversation(peerId)
            db.exec("UPDATE conversations SET unread = 0 WHERE peer_id = ?", peerId)
        }
        bump()
    }

    fun close(peerId: String) {
        if (openPeer == peerId) openPeer = null
    }

    suspend fun send(peerId: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val clientId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            ensureConversation(peerId)
            db.exec(
                "INSERT INTO messages (peer_id, client_id, from_me, body, sent_at, status) VALUES (?, ?, 1, ?, ?, ?)",
                peerId, clientId, body, now, MessageStatus.SENDING.name,
            )
            db.exec("UPDATE conversations SET last_message = ?, last_at = ? WHERE peer_id = ?", body, now, peerId)
        }
        bump()
        deliver(peerId, clientId, body, now)
    }

    /** Tap-to-retry on a failed message. */
    suspend fun retry(message: ChatMessage) {
        setStatus(message.peerId, message.clientId, MessageStatus.SENDING)
        deliver(message.peerId, message.clientId, message.body, message.sentAt)
    }

    /** Sends anything still waiting from a previous offline period. Called when the gateway is ready. */
    suspend fun retryPending() {
        val pending = withContext(Dispatchers.IO) {
            db.queryList("SELECT peer_id, client_id, body, sent_at FROM messages WHERE from_me = 1 AND status = ? ORDER BY sent_at", MessageStatus.SENDING.name) {
                arrayOf(it.str("peer_id"), it.str("client_id"), it.str("body"), it.long("sent_at").toString())
            }
        }
        pending.forEach { (peer, client, body, at) -> deliver(peer, client, body, at.toLong()) }
    }

    private suspend fun deliver(peerId: String, clientId: String, text: String, sentAt: Long) {
        try {
            val payload = json.encodeToString(ChatPayload.serializer(), ChatPayload(id = clientId, text = text, ts = sentAt)).toByteArray()
            val message = crypto.withLock { encrypt(peerId, payload) }
            apiCall { api.sendMessage(SendMessageBody(peerId, message.type, b64(message.serialize()), clientId)) }
            setStatus(peerId, clientId, MessageStatus.SENT)
        } catch (e: ApiError) {
            // No connection: leave it "sending"; it goes out when the gateway reconnects.
            if (e.code != "network") setStatus(peerId, clientId, MessageStatus.FAILED)
        } catch (e: Exception) {
            setStatus(peerId, clientId, MessageStatus.FAILED)
        }
    }

    private suspend fun encrypt(peerId: String, payload: ByteArray): CiphertextMessage {
        val self = selfAddress()
        val address = SignalProtocolAddress(peerId, DEVICE)
        if (!withContext(Dispatchers.IO) { store.containsSession(address) }) {
            val bundle = apiCall { api.preKeyBundle(peerId) }
            withContext(Dispatchers.Default) { SessionBuilder(store, address, self).process(bundle.toLibsignal()) }
        }
        return withContext(Dispatchers.Default) { SessionCipher(store, self, address).encrypt(payload) }
    }

    /**
     * Decrypts and stores one incoming envelope. Always returns normally so the gateway can
     * acknowledge it — a message that can't be decrypted leaves a notice instead of looping.
     */
    suspend fun receive(envelope: Envelope) {
        val plain = runCatching {
            crypto.withLock {
                withContext(Dispatchers.Default) {
                    val cipher = SessionCipher(store, selfAddress(), SignalProtocolAddress(envelope.senderId, DEVICE))
                    val bytes = unb64(envelope.body)
                    when (envelope.type) {
                        CiphertextMessage.PREKEY_TYPE -> cipher.decrypt(PreKeySignalMessage(bytes))
                        CiphertextMessage.WHISPER_TYPE -> cipher.decrypt(SignalMessage(bytes))
                        else -> error("Unknown type ${envelope.type}")
                    }
                }
            }
        }
        withContext(Dispatchers.IO) {
            ensureConversation(envelope.senderId)
            plain.onSuccess { bytes ->
                val payload = runCatching { json.decodeFromString(ChatPayload.serializer(), String(bytes)) }.getOrNull()
                if (payload?.kind == "text" && payload.text != null) {
                    storeIncoming(envelope.senderId, payload.id, payload.text, payload.ts.takeIf { it > 0 } ?: envelope.sentAt)
                }
            }.onFailure { error ->
                if (error !is DuplicateMessageException) {
                    notice(envelope.senderId, "A message couldn't be decrypted. Ask them to send it again.")
                }
            }
        }
        bump()
    }

    /** Called by the key store when a contact's identity key changed (new phone or reinstall). */
    fun onIdentityChanged(peerId: String) {
        notice(peerId, "Security code changed. Your friend may have reinstalled the app or switched phones.")
        bump()
    }

    private fun storeIncoming(peerId: String, clientId: String, text: String, sentAt: Long) {
        val existing = db.queryOne("SELECT 1 FROM messages WHERE peer_id = ? AND client_id = ? AND from_me = 0", peerId, clientId) { true }
        if (existing == true) return
        db.exec(
            "INSERT INTO messages (peer_id, client_id, from_me, body, sent_at, status) VALUES (?, ?, 0, ?, ?, ?)",
            peerId, clientId, text, sentAt, MessageStatus.RECEIVED.name,
        )
        val unread = if (openPeer == peerId) 0 else 1
        db.exec(
            "UPDATE conversations SET last_message = ?, last_at = MAX(last_at, ?), unread = unread + ? WHERE peer_id = ?",
            text, sentAt, unread, peerId,
        )
    }

    private fun notice(peerId: String, text: String) {
        db.exec(
            "INSERT INTO messages (peer_id, client_id, from_me, kind, body, sent_at, status) VALUES (?, ?, 0, 'notice', ?, ?, ?)",
            peerId, UUID.randomUUID().toString(), text, System.currentTimeMillis(), MessageStatus.NOTICE.name,
        )
    }

    /** Makes sure there's a conversation row, using the friends list or the profile API for the name. */
    private suspend fun ensureConversation(peerId: String) {
        if (db.queryOne("SELECT 1 FROM conversations WHERE peer_id = ?", peerId) { true } == true) return
        val known = social.friends.value.let { it.friends + it.incoming + it.outgoing }.firstOrNull { it.id == peerId }
        val profile = known ?: runCatching { apiCall { api.user(peerId) } }.getOrNull()
        db.exec(
            "INSERT OR IGNORE INTO conversations (peer_id, display_name, username, last_at) VALUES (?, ?, ?, ?)",
            peerId, profile?.displayName ?: "Someone", profile?.username ?: "", System.currentTimeMillis(),
        )
    }

    private suspend fun setStatus(peerId: String, clientId: String, status: MessageStatus) {
        withContext(Dispatchers.IO) {
            db.exec("UPDATE messages SET status = ? WHERE peer_id = ? AND client_id = ? AND from_me = 1", status.name, peerId, clientId)
        }
        bump()
    }

    private fun selfAddress() = SignalProtocolAddress(selfId() ?: error("Not signed in"), DEVICE)

    private fun bump() {
        changes.value++
    }

    private fun PreKeyBundleResponse.toLibsignal() = PreKeyBundle(
        registrationId,
        DEVICE,
        oneTimePreKey?.keyId ?: PreKeyBundle.NULL_PRE_KEY_ID,
        oneTimePreKey?.let { ECPublicKey(unb64(it.publicKey)) },
        signedPreKey.keyId,
        ECPublicKey(unb64(signedPreKey.publicKey)),
        unb64(signedPreKey.signature),
        IdentityKey(unb64(identityKey)),
        kyberPreKey.keyId,
        KEMPublicKey(unb64(kyberPreKey.publicKey)),
        unb64(kyberPreKey.signature),
    )

    private companion object {
        const val DEVICE = 1
    }
}
