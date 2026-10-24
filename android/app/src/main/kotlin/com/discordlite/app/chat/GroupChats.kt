package com.discordlite.app.chat

import com.discordlite.app.crypto.SignalStore
import com.discordlite.app.crypto.b64
import com.discordlite.app.crypto.unb64
import com.discordlite.app.data.AppSettings
import com.discordlite.app.data.local.LocalDatabase
import com.discordlite.app.data.local.exec
import com.discordlite.app.data.local.int
import com.discordlite.app.data.local.long
import com.discordlite.app.data.local.queryList
import com.discordlite.app.data.local.queryOne
import com.discordlite.app.data.local.str
import com.discordlite.app.data.remote.AddMembersBody
import com.discordlite.app.data.remote.ApiError
import com.discordlite.app.data.remote.CreateGroupBody
import com.discordlite.app.data.remote.DiscordLiteApi
import com.discordlite.app.data.remote.Envelope
import com.discordlite.app.data.remote.GatewayFrame
import com.discordlite.app.data.remote.GroupResponse
import com.discordlite.app.data.remote.RenameGroupBody
import com.discordlite.app.data.remote.SendGroupMessageBody
import com.discordlite.app.data.remote.apiCall
import com.discordlite.app.data.remote.apiCallUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.GroupCipher
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage
import java.util.UUID

data class GroupMember(val id: String, val username: String, val displayName: String)

/** A group as this phone knows it. [active] is false once you've left or been removed. */
data class GroupInfo(
    val id: String,
    val name: String?,
    val title: String,
    val ownerId: String,
    val epoch: Int,
    val active: Boolean,
    val members: List<GroupMember>,
)

/**
 * End-to-end encrypted group chats, using Signal "sender keys".
 *
 * Each member has their own sender key per group. Before your first message you send it to every
 * member over your 1:1 encrypted sessions (the server only sees ciphertext going to each of them).
 * After that, each message is encrypted once and the server copies it to everyone.
 *
 * When someone leaves or is removed, the group's epoch goes up and you make a brand-new sender
 * key before your next message, so they can't read anything said after they left. People who join
 * get your current key and see only messages sent from then on.
 */
class GroupChats(
    private val api: DiscordLiteApi,
    private val store: SignalStore,
    private val local: LocalDatabase,
    private val chat: ChatRepository,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val selfId: () -> String?,
) {
    /** Hands an encrypted group typing event to the live connection. */
    var sendEphemeral: ((groupId: String, type: Int, body: String) -> Unit)? = null

    private val _typing = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    /** Who is typing in each group right now (group id → member ids). */
    val typing: StateFlow<Map<String, Set<String>>> = _typing.asStateFlow()
    private val typingTimeouts = mutableMapOf<Pair<String, String>, Job>()
    private val lastTypingSent = mutableMapOf<String, Pair<Boolean, Long>>()

    private val groupLock = Mutex() // applies group changes one at a time
    private val sendLock = Mutex() // one group send at a time, so a sender key is made and shared once
    private val db get() = local.db

    fun group(groupId: String): Flow<GroupInfo?> = chat.changes.map { loadGroup(groupId) }.flowOn(Dispatchers.IO)

    suspend fun isGroup(id: String): Boolean = withContext(Dispatchers.IO) { db.queryOne("SELECT 1 FROM groups WHERE id = ?", id) { true } == true }

    // --- Keeping groups in step with the server ---

    /** Fetches every group you're in. Groups that are gone from the list mean you were removed. */
    suspend fun sync() {
        val remote = runCatching { apiCall { api.groups() } }.getOrNull() ?: return
        remote.forEach { apply(it) }
        val ids = remote.map { it.id }.toSet()
        val stale = withContext(Dispatchers.IO) { db.queryList("SELECT id FROM groups WHERE active = 1") { it.str("id") } }
        stale.filter { it !in ids }.forEach { removed(it) }
    }

    /** Live changes from the gateway. */
    suspend fun onFrame(frame: GatewayFrame) {
        when (frame.t) {
            "group" -> frame.group?.let { apply(it) }
            "group_removed" -> frame.groupId?.let { removed(it) }
        }
    }

    /** Saves the server's view of a group and adds notices for what changed ("Bea joined"). */
    suspend fun apply(group: GroupResponse) {
        val me = selfId() ?: return
        groupLock.withLock {
            withContext(Dispatchers.IO) {
                val old = loadGroup(group.id)
                db.exec(
                    "INSERT OR REPLACE INTO groups (id, name, owner_id, epoch, active) VALUES (?, ?, ?, ?, 1)",
                    group.id, group.name, group.ownerId, group.epoch,
                )
                db.exec("DELETE FROM group_members WHERE group_id = ?", group.id)
                group.members.forEachIndexed { i, m ->
                    db.exec(
                        "INSERT INTO group_members (group_id, user_id, username, display_name, position) VALUES (?, ?, ?, ?, ?)",
                        group.id, m.id, m.username, m.displayName, i,
                    )
                }
                val members = group.members.map { GroupMember(it.id, it.username, it.displayName) }
                val title = titleFor(group.name, members, me)
                db.exec(
                    "INSERT OR IGNORE INTO conversations (peer_id, display_name, username, last_at, is_group) VALUES (?, ?, '', ?, 1)",
                    group.id, title, group.createdAt,
                )
                db.exec("UPDATE conversations SET display_name = ?, is_group = 1 WHERE peer_id = ?", title, group.id)

                fun nameOf(id: String) = if (id == me) "You" else members.firstOrNull { it.id == id }?.displayName ?: old?.members?.firstOrNull { it.id == id }?.displayName ?: "Someone"
                val owner = nameOf(group.ownerId)
                when {
                    old == null && group.ownerId == me -> chat.notice(group.id, "You created this group.")
                    old == null -> chat.notice(group.id, "$owner added you to this group.")
                    !old.active -> chat.notice(group.id, "$owner added you back to this group.")
                    else -> {
                        val before = old.members.map { it.id }.toSet()
                        val after = members.map { it.id }.toSet()
                        (after - before).forEach { chat.notice(group.id, "$owner added ${nameOf(it)}.") }
                        (before - after).forEach { chat.notice(group.id, "${nameOf(it)} ${if (it == me) "are" else "is"} no longer in this group.") }
                        if (old.name != group.name) chat.notice(group.id, if (group.name != null) "Group renamed to “${group.name}”." else "Group name removed.")
                        if (old.ownerId != group.ownerId) chat.notice(group.id, "$owner ${if (group.ownerId == me) "are" else "is"} now the group owner.")
                    }
                }
                // Someone left: make a fresh sender key before the next message, so they can't read it.
                val gone = old?.members.orEmpty().map { it.id }.toSet() - members.map { it.id }.toSet()
                if (gone.isNotEmpty()) forgetMyKey(group.id)
            }
        }
        chat.bump()
    }

    /** You left, or the owner removed you. The chat stays, read-only. */
    suspend fun removed(groupId: String) {
        groupLock.withLock {
            withContext(Dispatchers.IO) {
                val wasActive = db.queryOne("SELECT active FROM groups WHERE id = ?", groupId) { it.int("active") == 1 } ?: return@withContext
                if (!wasActive) return@withContext
                db.exec("UPDATE groups SET active = 0 WHERE id = ?", groupId)
                forgetMyKey(groupId)
                chat.notice(groupId, "You're no longer in this group.")
            }
        }
        _typing.value = _typing.value - groupId
        chat.bump()
    }

    suspend fun create(name: String, memberIds: List<String>): String {
        val group = apiCall { api.createGroup(CreateGroupBody(name.trim().ifEmpty { null }, memberIds)) }
        apply(group)
        return group.id
    }

    suspend fun rename(groupId: String, name: String) = apply(apiCall { api.renameGroup(groupId, RenameGroupBody(name.trim().ifEmpty { null })) })

    suspend fun addMembers(groupId: String, userIds: List<String>) = apply(apiCall { api.addGroupMembers(groupId, AddMembersBody(userIds)) })

    suspend fun removeMember(groupId: String, userId: String) {
        apiCallUnit { api.removeGroupMember(groupId, userId) }
        if (userId == selfId()) removed(groupId) else apply(apiCall { api.group(groupId) })
    }

    suspend fun leave(groupId: String) {
        removeMember(groupId, selfId() ?: return)
    }

    // --- Opening, reading, typing ---

    suspend fun open(groupId: String) {
        chat.openPeer = groupId
        chat.onChatOpened?.invoke(groupId)
        withContext(Dispatchers.IO) { db.exec("UPDATE conversations SET unread = 0 WHERE peer_id = ?", groupId) }
        chat.bump()
        markSeen(groupId)
    }

    /** Marks messages seen and, if you allow read receipts, tells each sender privately. */
    private suspend fun markSeen(groupId: String) {
        val unseen = withContext(Dispatchers.IO) {
            val rows = db.queryList(
                "SELECT client_id, sender_id FROM messages WHERE peer_id = ? AND from_me = 0 AND status = ? AND sender_id IS NOT NULL",
                groupId, MessageStatus.RECEIVED.name,
            ) { it.str("sender_id") to it.str("client_id") }
            if (rows.isNotEmpty()) {
                db.exec("UPDATE messages SET status = ? WHERE peer_id = ? AND from_me = 0 AND status = ?", MessageStatus.SEEN.name, groupId, MessageStatus.RECEIVED.name)
            }
            rows
        }
        if (unseen.isEmpty()) return
        chat.bump()
        if (settings.readReceipts) {
            unseen.groupBy({ it.first }, { it.second }).forEach { (sender, ids) -> sendReceipt(groupId, sender, "read", ids) }
        }
    }

    /** Like 1:1 typing, but one sender-key ciphertext for the whole group. Skipped until everyone has your key. */
    fun onTyping(groupId: String, typing: Boolean) {
        if (!settings.typingIndicators) return
        val now = System.currentTimeMillis()
        val (lastState, lastAt) = lastTypingSent[groupId] ?: (false to 0L)
        if (typing == lastState && (!typing || now - lastAt < TYPING_RESEND_MS)) return
        lastTypingSent[groupId] = typing to now
        scope.launch {
            runCatching {
                val group = withContext(Dispatchers.IO) { loadGroup(groupId) } ?: return@launch
                if (!group.active) return@launch
                val distributionId = withContext(Dispatchers.IO) { myKey(groupId) } ?: return@launch
                if (withContext(Dispatchers.IO) { unsharedMembers(group, distributionId) }.isNotEmpty()) return@launch
                val payload = chat.encode(ChatPayload(kind = "typing", id = UUID.randomUUID().toString(), ts = now, state = if (typing) "on" else "off"))
                val message = chat.crypto.withLock { withContext(Dispatchers.Default) { GroupCipher(store, self()).encrypt(distributionId, payload) } }
                sendEphemeral?.invoke(groupId, message.type, b64(message.serialize()))
            }
        }
    }

    // --- Sending ---

    suspend fun send(groupId: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val clientId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            db.exec(
                "INSERT INTO messages (peer_id, client_id, from_me, body, sent_at, status) VALUES (?, ?, 1, ?, ?, ?)",
                groupId, clientId, body, now, MessageStatus.SENDING.name,
            )
            db.exec("UPDATE conversations SET last_message = ?, last_at = ? WHERE peer_id = ?", "You: $body", now, groupId)
        }
        chat.bump()
        lastTypingSent[groupId] = false to now
        deliver(groupId, clientId, body, now)
    }

    suspend fun retry(message: ChatMessage) {
        chat.setStatus(message.peerId, message.clientId, MessageStatus.SENDING)
        deliver(message.peerId, message.clientId, message.body, message.sentAt)
    }

    /** Group messages still waiting from an offline period. */
    suspend fun retryPending() {
        val pending = withContext(Dispatchers.IO) {
            db.queryList(
                """
                SELECT m.peer_id, m.client_id, m.body, m.sent_at FROM messages m JOIN groups g ON g.id = m.peer_id
                WHERE m.from_me = 1 AND m.status = ? AND g.active = 1 ORDER BY m.sent_at
                """.trimIndent(),
                MessageStatus.SENDING.name,
            ) { arrayOf(it.str("peer_id"), it.str("client_id"), it.str("body"), it.long("sent_at").toString()) }
        }
        pending.forEach { (group, client, body, at) -> deliver(group, client, body, at.toLong()) }
    }

    private suspend fun deliver(groupId: String, clientId: String, text: String, sentAt: Long) {
        try {
            sendToGroup(groupId, chat.encode(ChatPayload(id = clientId, text = text, ts = sentAt)), clientId)
            chat.setStatus(groupId, clientId, MessageStatus.SENT)
        } catch (e: ApiError) {
            // No connection: stays "sending" and goes out on reconnect.
            if (e.code != "network") chat.setStatus(groupId, clientId, MessageStatus.FAILED)
            if (e.code == "group_not_found") removed(groupId)
        } catch (e: Exception) {
            chat.setStatus(groupId, clientId, MessageStatus.FAILED)
        }
    }

    /**
     * Shares our sender key with anyone who doesn't have it yet, encrypts once, and uploads. If
     * people joined or left since our last look (`stale_group`), refresh the group and try again.
     */
    private suspend fun sendToGroup(groupId: String, payload: ByteArray, clientId: String) = sendLock.withLock {
        repeat(2) { attempt ->
            val group = withContext(Dispatchers.IO) { loadGroup(groupId) }?.takeIf { it.active } ?: throw ApiError(404, "group_not_found")
            val distributionId = shareSenderKey(group)
            val message = chat.crypto.withLock { withContext(Dispatchers.Default) { GroupCipher(store, self()).encrypt(distributionId, payload) } }
            try {
                apiCall { api.sendGroupMessage(groupId, SendGroupMessageBody(message.type, b64(message.serialize()), clientId, group.epoch)) }
                return@withLock
            } catch (e: ApiError) {
                if (e.code != "stale_group" || attempt > 0) throw e
                apply(apiCall { api.group(groupId) })
            }
        }
    }

    /** Makes our sender key for this group if needed and sends it, 1:1 encrypted, to members who lack it. */
    private suspend fun shareSenderKey(group: GroupInfo): UUID {
        val distributionId = withContext(Dispatchers.IO) {
            myKey(group.id) ?: UUID.randomUUID().also { db.exec("INSERT OR REPLACE INTO group_my_keys (group_id, distribution_id) VALUES (?, ?)", group.id, it.toString()) }
        }
        val missing = withContext(Dispatchers.IO) { unsharedMembers(group, distributionId) }
        if (missing.isEmpty()) return distributionId
        val distribution = chat.crypto.withLock { withContext(Dispatchers.Default) { GroupSessionBuilder(store).create(self(), distributionId) } }
        val payload = chat.encode(ChatPayload(kind = "sender_key", id = UUID.randomUUID().toString(), ts = System.currentTimeMillis(), key = b64(distribution.serialize())))
        for (member in missing) {
            try {
                chat.sendPairwise(member.id, payload, UUID.randomUUID().toString(), group.id)
                withContext(Dispatchers.IO) {
                    db.exec("INSERT OR IGNORE INTO group_key_shared (group_id, distribution_id, user_id) VALUES (?, ?, ?)", group.id, distributionId.toString(), member.id)
                }
            } catch (e: ApiError) {
                if (e.code == "network") throw e
                // They can't get messages yet (e.g. never opened the app): send to everyone else.
                if (e.code == "no_keys") chat.explainOnce(group.id, "${member.displayName} can't get messages yet: they haven't opened discord-lite since signing up.")
            }
        }
        return distributionId
    }

    // --- Receiving ---

    /** A sender-key message for a group (a chat message or typing). */
    suspend fun receiveGroupMessage(envelope: Envelope): IncomingText? {
        val groupId = envelope.groupId ?: return null
        if (withContext(Dispatchers.IO) { loadGroup(groupId) } == null) runCatching { apply(apiCall { api.group(groupId) }) }
        val plain = runCatching {
            chat.crypto.withLock {
                withContext(Dispatchers.Default) { GroupCipher(store, address(envelope.senderId)).decrypt(unb64(envelope.body)) }
            }
        }
        var incoming: IncomingText? = null
        plain.onSuccess { bytes ->
            val payload = chat.decode(bytes) ?: return@onSuccess
            when (payload.kind) {
                "text" -> if (payload.text != null) {
                    val group = withContext(Dispatchers.IO) { loadGroup(groupId) }
                    val sender = group?.members?.firstOrNull { it.id == envelope.senderId }?.displayName ?: "Someone"
                    val isNew = withContext(Dispatchers.IO) {
                        storeIncoming(groupId, envelope.senderId, sender, payload.id, payload.text, payload.ts.takeIf { it > 0 } ?: envelope.sentAt)
                    }
                    setTyping(groupId, envelope.senderId, false)
                    if (isNew) {
                        scope.launch {
                            sendReceipt(groupId, envelope.senderId, "delivered", listOf(payload.id))
                            if (chat.openPeer == groupId) markSeen(groupId)
                        }
                        incoming = IncomingText(groupId, group?.title ?: "Group", "$sender: ${payload.text}")
                    }
                }
                "typing" -> setTyping(groupId, envelope.senderId, payload.state == "on")
            }
        }.onFailure { error ->
            if (error !is DuplicateMessageException && envelope.id > 0) {
                withContext(Dispatchers.IO) { chat.notice(groupId, "A message from ${memberName(groupId, envelope.senderId)} couldn't be decrypted. Ask them to send it again.") }
            }
        }
        chat.bump()
        return incoming
    }

    /** A 1:1 envelope sent inside a group: someone's sender key, or a receipt for our message. */
    suspend fun receivePairwise(envelope: Envelope, plain: Result<ByteArray>) {
        val groupId = envelope.groupId ?: return
        plain.onSuccess { bytes ->
            val payload = chat.decode(bytes) ?: return@onSuccess
            when (payload.kind) {
                "sender_key" -> payload.key?.let { key ->
                    chat.crypto.withLock {
                        withContext(Dispatchers.Default) {
                            GroupSessionBuilder(store).process(address(envelope.senderId), SenderKeyDistributionMessage(unb64(key)))
                        }
                    }
                }
                "receipt" -> withContext(Dispatchers.IO) { applyReceipt(groupId, envelope.senderId, payload.state, payload.ids.orEmpty()) }
            }
        }.onFailure { error ->
            if (error !is DuplicateMessageException && envelope.id > 0 && withContext(Dispatchers.IO) { loadGroup(groupId) } != null) {
                withContext(Dispatchers.IO) { chat.notice(groupId, "Couldn't set up encryption with ${memberName(groupId, envelope.senderId)}. Their next messages may not show.") }
            }
        }
        chat.bump()
    }

    private suspend fun sendReceipt(groupId: String, to: String, state: String, ids: List<String>) {
        runCatching {
            val payload = chat.encode(ChatPayload(kind = "receipt", id = UUID.randomUUID().toString(), ts = System.currentTimeMillis(), state = state, ids = ids))
            chat.sendPairwise(to, payload, UUID.randomUUID().toString(), groupId)
        }
    }

    /**
     * A message shows Delivered / Read once everyone who can receive it has received / read it.
     * Members we couldn't share our key with (no encryption keys yet) aren't waited for.
     */
    private fun applyReceipt(groupId: String, from: String, state: String?, ids: List<String>) {
        if (state != "delivered" && state != "read") return
        val me = selfId() ?: return
        val reachable = db.queryOne(
            """
            SELECT COUNT(*) AS n FROM group_key_shared s JOIN group_my_keys k ON k.group_id = s.group_id AND k.distribution_id = s.distribution_id
            JOIN group_members m ON m.group_id = s.group_id AND m.user_id = s.user_id
            WHERE s.group_id = ?
            """.trimIndent(),
            groupId,
        ) { it.int("n") } ?: 0
        val others = reachable.takeIf { it > 0 }
            ?: db.queryOne("SELECT COUNT(*) AS n FROM group_members WHERE group_id = ? AND user_id <> ?", groupId, me) { it.int("n") } ?: return
        ids.take(MAX_RECEIPT_IDS).forEach { id ->
            // Never downgrade: once read, stays read.
            db.exec(
                """
                INSERT INTO group_receipts (group_id, client_id, user_id, state) VALUES (?, ?, ?, ?)
                ON CONFLICT (group_id, client_id, user_id) DO UPDATE SET state = CASE WHEN state = 'read' THEN 'read' ELSE excluded.state END
                """.trimIndent(),
                groupId, id, from, state,
            )
            val (delivered, read) = db.queryOne(
                "SELECT COUNT(*) AS d, SUM(state = 'read') AS r FROM group_receipts WHERE group_id = ? AND client_id = ?",
                groupId, id,
            ) { it.int("d") to it.int("r") } ?: (0 to 0)
            val (target, upgradable) = when {
                read >= others -> MessageStatus.READ to listOf(MessageStatus.SENDING, MessageStatus.SENT, MessageStatus.DELIVERED)
                delivered >= others -> MessageStatus.DELIVERED to listOf(MessageStatus.SENDING, MessageStatus.SENT)
                else -> return@forEach
            }
            db.exec(
                "UPDATE messages SET status = ? WHERE peer_id = ? AND client_id = ? AND from_me = 1 AND status IN (${upgradable.joinToString(",") { "?" }})",
                target.name, groupId, id, *upgradable.map { it.name }.toTypedArray(),
            )
        }
    }

    private fun storeIncoming(groupId: String, senderId: String, senderName: String, clientId: String, text: String, sentAt: Long): Boolean {
        val existing = db.queryOne("SELECT 1 FROM messages WHERE peer_id = ? AND client_id = ? AND from_me = 0", groupId, clientId) { true }
        if (existing == true) return false
        db.exec(
            "INSERT INTO messages (peer_id, client_id, from_me, body, sent_at, status, sender_id) VALUES (?, ?, 0, ?, ?, ?, ?)",
            groupId, clientId, text, sentAt, MessageStatus.RECEIVED.name, senderId,
        )
        val unread = if (chat.openPeer == groupId) 0 else 1
        db.exec(
            "UPDATE conversations SET last_message = ?, last_at = MAX(last_at, ?), unread = unread + ? WHERE peer_id = ?",
            "${senderName.substringBefore(' ')}: $text", sentAt, unread, groupId,
        )
        return true
    }

    private fun setTyping(groupId: String, userId: String, on: Boolean) {
        val key = groupId to userId
        typingTimeouts.remove(key)?.cancel()
        fun update(change: (Set<String>) -> Set<String>) {
            val next = change(_typing.value[groupId].orEmpty())
            _typing.value = if (next.isEmpty()) _typing.value - groupId else _typing.value + (groupId to next)
        }
        if (on && settings.typingIndicators) {
            update { it + userId }
            typingTimeouts[key] = scope.launch {
                delay(TYPING_TIMEOUT_MS)
                update { it - userId }
            }
        } else {
            update { it - userId }
        }
    }

    // --- Local storage ---

    private fun loadGroup(groupId: String): GroupInfo? {
        val me = selfId()
        val members = db.queryList("SELECT * FROM group_members WHERE group_id = ? ORDER BY position", groupId) {
            GroupMember(it.str("user_id"), it.str("username"), it.str("display_name"))
        }
        return db.queryOne("SELECT * FROM groups WHERE id = ?", groupId) {
            val name = if (it.isNull(it.getColumnIndexOrThrow("name"))) null else it.str("name")
            GroupInfo(groupId, name, titleFor(name, members, me), it.str("owner_id"), it.int("epoch"), it.int("active") == 1, members)
        }
    }

    private fun myKey(groupId: String): UUID? =
        db.queryOne("SELECT distribution_id FROM group_my_keys WHERE group_id = ?", groupId) { UUID.fromString(it.str("distribution_id")) }

    private fun unsharedMembers(group: GroupInfo, distributionId: UUID): List<GroupMember> {
        val shared = db.queryList(
            "SELECT user_id FROM group_key_shared WHERE group_id = ? AND distribution_id = ?", group.id, distributionId.toString(),
        ) { it.str("user_id") }.toSet()
        return group.members.filter { it.id != selfId() && it.id !in shared }
    }

    private fun forgetMyKey(groupId: String) {
        db.exec("DELETE FROM group_my_keys WHERE group_id = ?", groupId)
        db.exec("DELETE FROM group_key_shared WHERE group_id = ?", groupId)
    }

    private fun memberName(groupId: String, userId: String): String =
        db.queryOne("SELECT display_name FROM group_members WHERE group_id = ? AND user_id = ?", groupId, userId) { it.str("display_name") } ?: "someone"

    private fun self() = address(selfId() ?: error("Not signed in"))

    private fun address(user: String) = SignalProtocolAddress(user, DEVICE)

    companion object {
        private const val DEVICE = 1
        private const val TYPING_RESEND_MS = 3_000L
        private const val TYPING_TIMEOUT_MS = 6_000L
        private const val MAX_RECEIPT_IDS = 200
        const val SENDER_KEY_TYPE = CiphertextMessage.SENDERKEY_TYPE

        /** "Squad", or the other members' first names: "Bea, Cal & Dee", "Bea, Cal +3". */
        fun titleFor(name: String?, members: List<GroupMember>, me: String?): String {
            if (!name.isNullOrBlank()) return name
            val others = members.filter { it.id != me }.map { it.displayName.substringBefore(' ') }
            return when (others.size) {
                0 -> "Just you"
                1 -> others[0]
                2 -> "${others[0]} & ${others[1]}"
                3 -> "${others[0]}, ${others[1]} & ${others[2]}"
                else -> "${others[0]}, ${others[1]} +${others.size - 2}"
            }
        }
    }
}
