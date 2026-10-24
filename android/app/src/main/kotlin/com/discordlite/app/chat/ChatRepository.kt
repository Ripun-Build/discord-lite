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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import com.discordlite.app.data.AppSettings
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

/**
 * Outgoing: SENDING → SENT (server has it) → DELIVERED (on their phone) → READ (they opened it).
 * Incoming: RECEIVED → SEEN (you opened the chat). NOTICE is a system line in the chat.
 */
enum class MessageStatus { SENDING, SENT, DELIVERED, READ, FAILED, RECEIVED, SEEN, NOTICE }

data class ChatMessage(
    val localId: Long,
    val peerId: String,
    val clientId: String,
    val fromMe: Boolean,
    val body: String,
    val sentAt: Long,
    val status: MessageStatus,
)

/** A newly received message, for notifications. */
data class IncomingText(val peerId: String, val displayName: String, val text: String)

data class Conversation(
    val peerId: String,
    val displayName: String,
    val username: String,
    val lastMessage: String,
    val lastAt: Long,
    val unread: Int,
)

/**
 * What actually gets encrypted. The server only ever sees the ciphertext of this JSON, so it can't
 * tell a message from a receipt or a typing event.
 *
 * - `text`: a chat message ([id], [text]).
 * - `receipt`: [state] "delivered" or "read" for the message [ids].
 * - `typing`: [state] "on" or "off". Sent as an ephemeral event, never stored.
 */
@Serializable
data class ChatPayload(
    val v: Int = 1,
    val kind: String = "text",
    val id: String,
    val text: String? = null,
    val ts: Long,
    val state: String? = null,
    val ids: List<String>? = null,
)

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
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val selfId: () -> String?,
) {
    /** Hands an encrypted typing event to the live connection (set once the gateway exists). */
    var sendEphemeral: ((to: String, type: Int, body: String) -> Unit)? = null

    private val _typing = MutableStateFlow<Set<String>>(emptySet())

    /** Friends typing to you right now. Each entry clears itself after a few seconds. */
    val typing: StateFlow<Set<String>> = _typing.asStateFlow()
    private val typingTimeouts = mutableMapOf<String, Job>()
    private val lastTypingSent = mutableMapOf<String, Pair<Boolean, Long>>()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true // the payload format is explicit: every field is always written
    }
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

    /** Called when a chat opens, e.g. to clear its notification. */
    var onChatOpened: ((String) -> Unit)? = null

    suspend fun open(peerId: String) {
        openPeer = peerId
        onChatOpened?.invoke(peerId)
        withContext(Dispatchers.IO) {
            ensureConversation(peerId)
            db.exec("UPDATE conversations SET unread = 0 WHERE peer_id = ?", peerId)
        }
        bump()
        markSeen(peerId)
    }

    /** Marks their messages as seen and, if you allow it, tells them you've read them. */
    private suspend fun markSeen(peerId: String) {
        val unseen = withContext(Dispatchers.IO) {
            val ids = db.queryList("SELECT client_id FROM messages WHERE peer_id = ? AND from_me = 0 AND status = ?", peerId, MessageStatus.RECEIVED.name) { it.str("client_id") }
            if (ids.isNotEmpty()) db.exec("UPDATE messages SET status = ? WHERE peer_id = ? AND from_me = 0 AND status = ?", MessageStatus.SEEN.name, peerId, MessageStatus.RECEIVED.name)
            ids
        }
        if (unseen.isNotEmpty()) {
            bump()
            if (settings.readReceipts) sendReceipt(peerId, "read", unseen)
        }
    }

    /**
     * Call as the user types. Sends "on" at most every few seconds and "off" once when they stop.
     * Only to friends you already have an encrypted session with; skipped if turned off.
     */
    fun onTyping(peerId: String, typing: Boolean) {
        if (!settings.typingIndicators) return
        val now = System.currentTimeMillis()
        val (lastState, lastAt) = lastTypingSent[peerId] ?: (false to 0L)
        if (typing == lastState && (!typing || now - lastAt < TYPING_RESEND_MS)) return
        lastTypingSent[peerId] = typing to now
        scope.launch {
            runCatching {
                val payload = json.encodeToString(
                    ChatPayload.serializer(),
                    ChatPayload(kind = "typing", id = UUID.randomUUID().toString(), ts = now, state = if (typing) "on" else "off"),
                ).toByteArray()
                val message = crypto.withLock {
                    val address = SignalProtocolAddress(peerId, DEVICE)
                    if (!withContext(Dispatchers.IO) { store.containsSession(address) }) return@launch
                    withContext(Dispatchers.Default) { SessionCipher(store, selfAddress(), address).encrypt(payload) }
                }
                sendEphemeral?.invoke(peerId, message.type, b64(message.serialize()))
            }
        }
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
        lastTypingSent[peerId] = false to now // sending a message ends "typing" on their side
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
            sendEncrypted(peerId, payload, clientId)
            setStatus(peerId, clientId, MessageStatus.SENT)
        } catch (e: ApiError) {
            // No connection: leave it "sending"; it goes out when the gateway reconnects.
            if (e.code != "network") setStatus(peerId, clientId, MessageStatus.FAILED)
            if (e.code == "no_keys") explainNoKeys(peerId)
            if (e.code == "dm_not_allowed") explainOnce(peerId, "This person only accepts messages from some people. Ask them to add you as a friend.")
        } catch (e: Exception) {
            setStatus(peerId, clientId, MessageStatus.FAILED)
        }
    }

    /**
     * Encrypts and uploads. If the friend has reinstalled since our session was built, the server
     * says `stale_session`: drop the old session, build a new one from their new keys (which
     * shows "security code changed"), and send once more.
     */
    private suspend fun sendEncrypted(peerId: String, payload: ByteArray, clientId: String) {
        repeat(2) { attempt ->
            val (message, registrationId) = crypto.withLock { encrypt(peerId, payload) }
            try {
                apiCall { api.sendMessage(SendMessageBody(peerId, message.type, b64(message.serialize()), clientId, registrationId)) }
                return
            } catch (e: ApiError) {
                if (e.code != "stale_session" || attempt > 0) throw e
                crypto.withLock { withContext(Dispatchers.IO) { store.deleteSession(SignalProtocolAddress(peerId, DEVICE)) } }
            }
        }
    }

    /** Returns the ciphertext plus the recipient registration id the session was built for. */
    private suspend fun encrypt(peerId: String, payload: ByteArray): Pair<CiphertextMessage, Int> {
        val self = selfAddress()
        val address = SignalProtocolAddress(peerId, DEVICE)
        if (!withContext(Dispatchers.IO) { store.containsSession(address) }) {
            val bundle = apiCall { api.preKeyBundle(peerId) }
            withContext(Dispatchers.Default) { SessionBuilder(store, address, self).process(bundle.toLibsignal()) }
        }
        return withContext(Dispatchers.Default) {
            val cipher = SessionCipher(store, self, address)
            cipher.encrypt(payload) to cipher.remoteRegistrationId
        }
    }

    /**
     * Decrypts and stores one incoming envelope. Always returns normally so the gateway can
     * acknowledge it — a message that can't be decrypted leaves a notice instead of looping.
     */
    suspend fun receive(envelope: Envelope): IncomingText? {
        var incoming: IncomingText? = null
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
                when (payload?.kind) {
                    "text" -> if (payload.text != null) {
                        val isNew = storeIncoming(envelope.senderId, payload.id, payload.text, payload.ts.takeIf { it > 0 } ?: envelope.sentAt)
                        setTyping(envelope.senderId, false)
                        if (isNew) {
                            afterIncoming(envelope.senderId, payload.id)
                            val name = db.queryOne("SELECT display_name FROM conversations WHERE peer_id = ?", envelope.senderId) { it.str("display_name") }
                            incoming = IncomingText(envelope.senderId, name ?: "Someone", payload.text)
                        }
                    }
                    "receipt" -> applyReceipt(envelope.senderId, payload.state, payload.ids.orEmpty())
                    "typing" -> setTyping(envelope.senderId, payload.state == "on")
                }
            }.onFailure { error ->
                // Typing events (id 0) are only hints; a stale one failing to decrypt isn't worth a notice.
                if (error !is DuplicateMessageException && envelope.id > 0) {
                    notice(envelope.senderId, "A message couldn't be decrypted. Ask them to send it again.")
                }
            }
        }
        bump()
        return incoming
    }

    private suspend fun explainNoKeys(peerId: String) =
        explainOnce(peerId, "Can't send yet: they haven't opened discord-lite since signing up, so there are no encryption keys to send to.")

    /** Adds a notice unless the latest notice in this chat already says the same thing. */
    private suspend fun explainOnce(peerId: String, text: String) {
        withContext(Dispatchers.IO) {
            val last = db.queryOne(
                "SELECT body FROM messages WHERE peer_id = ? AND status = ? ORDER BY sent_at DESC, local_id DESC LIMIT 1",
                peerId, MessageStatus.NOTICE.name,
            ) { it.str("body") }
            if (last != text) notice(peerId, text)
        }
        bump()
    }

    /** Called by the key store when a contact's identity key changed (new phone or reinstall). */
    fun onIdentityChanged(peerId: String) {
        notice(peerId, "Security code changed. Your friend may have reinstalled the app or switched phones.")
        bump()
    }

    /** A new message arrived: say it was delivered, and read too if their chat is open. */
    private fun afterIncoming(peerId: String, clientId: String) {
        scope.launch {
            sendReceipt(peerId, "delivered", listOf(clientId))
            if (openPeer == peerId) markSeen(peerId)
        }
    }

    /** Upgrades the status of your messages; never moves backwards (read stays read). */
    private fun applyReceipt(peerId: String, state: String?, ids: List<String>) {
        if (ids.isEmpty()) return
        val (target, from) = when (state) {
            "delivered" -> MessageStatus.DELIVERED to listOf(MessageStatus.SENDING, MessageStatus.SENT)
            "read" -> MessageStatus.READ to listOf(MessageStatus.SENDING, MessageStatus.SENT, MessageStatus.DELIVERED)
            else -> return
        }
        val idMarks = ids.take(MAX_RECEIPT_IDS).joinToString(",") { "?" }
        val fromMarks = from.joinToString(",") { "?" }
        db.exec(
            "UPDATE messages SET status = ? WHERE peer_id = ? AND from_me = 1 AND client_id IN ($idMarks) AND status IN ($fromMarks)",
            target.name, peerId, *ids.take(MAX_RECEIPT_IDS).toTypedArray(), *from.map { it.name }.toTypedArray(),
        )
    }

    private fun setTyping(peerId: String, on: Boolean) {
        typingTimeouts.remove(peerId)?.cancel()
        if (on && settings.typingIndicators) {
            _typing.value = _typing.value + peerId
            typingTimeouts[peerId] = scope.launch {
                delay(TYPING_TIMEOUT_MS)
                _typing.value = _typing.value - peerId
            }
        } else {
            _typing.value = _typing.value - peerId
        }
    }

    /** Receipts travel as ordinary encrypted messages, so the server can't tell them apart. */
    private suspend fun sendReceipt(peerId: String, state: String, ids: List<String>) {
        runCatching {
            val payload = json.encodeToString(
                ChatPayload.serializer(),
                ChatPayload(kind = "receipt", id = UUID.randomUUID().toString(), ts = System.currentTimeMillis(), state = state, ids = ids),
            ).toByteArray()
            sendEncrypted(peerId, payload, UUID.randomUUID().toString())
        }
    }

    /** Returns false if this message was already stored (a duplicate delivery). */
    private fun storeIncoming(peerId: String, clientId: String, text: String, sentAt: Long): Boolean {
        val existing = db.queryOne("SELECT 1 FROM messages WHERE peer_id = ? AND client_id = ? AND from_me = 0", peerId, clientId) { true }
        if (existing == true) return false
        db.exec(
            "INSERT INTO messages (peer_id, client_id, from_me, body, sent_at, status) VALUES (?, ?, 0, ?, ?, ?)",
            peerId, clientId, text, sentAt, MessageStatus.RECEIVED.name,
        )
        val unread = if (openPeer == peerId) 0 else 1
        db.exec(
            "UPDATE conversations SET last_message = ?, last_at = MAX(last_at, ?), unread = unread + ? WHERE peer_id = ?",
            text, sentAt, unread, peerId,
        )
        return true
    }

    private fun notice(peerId: String, text: String) {
        // Place it just before any message still being sent, so it reads before the message it concerns.
        val pending = db.queryOne(
            "SELECT MIN(sent_at) AS at FROM messages WHERE peer_id = ? AND from_me = 1 AND status = ?",
            peerId, MessageStatus.SENDING.name,
        ) { c -> c.getLong(0).takeIf { !c.isNull(0) } }
        db.exec(
            "INSERT INTO messages (peer_id, client_id, from_me, kind, body, sent_at, status) VALUES (?, ?, 0, 'notice', ?, ?, ?)",
            peerId, UUID.randomUUID().toString(), text, pending?.minus(1) ?: System.currentTimeMillis(), MessageStatus.NOTICE.name,
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
        const val TYPING_RESEND_MS = 3_000L
        const val TYPING_TIMEOUT_MS = 6_000L
        const val MAX_RECEIPT_IDS = 200
    }
}
