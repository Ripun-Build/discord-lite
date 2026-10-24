package com.discordlite.app.servers

import com.discordlite.app.data.remote.AddMembersBody
import com.discordlite.app.data.remote.ApiError
import com.discordlite.app.data.remote.CategoryBody
import com.discordlite.app.data.remote.ChannelMessageResponse
import com.discordlite.app.data.remote.CreateChannelBody
import com.discordlite.app.data.remote.CreateServerBody
import com.discordlite.app.data.remote.DiscordLiteApi
import com.discordlite.app.data.remote.GatewayFrame
import com.discordlite.app.data.remote.MentionAckBody
import com.discordlite.app.data.remote.ReadBody
import com.discordlite.app.data.remote.RenameServerBody
import com.discordlite.app.data.remote.RoleBody
import com.discordlite.app.data.remote.RoleIdsBody
import com.discordlite.app.data.remote.SendChannelMessageBody
import com.discordlite.app.data.remote.ServerMemberResponse
import com.discordlite.app.data.remote.ServerResponse
import com.discordlite.app.data.remote.ServerSummaryResponse
import com.discordlite.app.data.remote.UpdateChannelBody
import com.discordlite.app.data.remote.apiCall
import com.discordlite.app.data.remote.apiCallUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** A message in a channel. [pending] while it's being sent; [failed] if that didn't work. */
data class ChannelMessage(
    val id: Long,
    val clientId: String?,
    val channelId: String,
    val authorId: String,
    val authorUsername: String,
    val authorName: String,
    val body: String,
    val createdAt: Long,
    val mentionsMe: Boolean,
    val pending: Boolean = false,
    val failed: Boolean = false,
)

/** One channel's loaded history. [complete] once the oldest message has been loaded. */
data class ChannelHistory(val messages: List<ChannelMessage> = emptyList(), val loaded: Boolean = false, val complete: Boolean = false)

/** An @mention of you to show as a notification. */
data class MentionAlert(val serverId: String, val channelId: String, val title: String, val text: String)

/**
 * Servers, channels and their messages. Server channels are not end-to-end encrypted, so nothing
 * here touches Signal; history is fetched from the server and kept in memory only (nothing about
 * servers is written to the phone's storage).
 *
 * Live updates arrive over the gateway ([onFrame]): new and deleted messages, typing, and
 * "this server changed" (then it's fetched again).
 */
class ServersRepository(
    private val api: DiscordLiteApi,
    private val scope: CoroutineScope,
    private val selfId: () -> String?,
) {
    /** Shows mention notifications (set by the app container). */
    var onMention: ((List<MentionAlert>) -> Unit)? = null
    var sendTyping: ((channelId: String) -> Unit)? = null

    private val _servers = MutableStateFlow<List<ServerSummaryResponse>>(emptyList())
    val servers: StateFlow<List<ServerSummaryResponse>> = _servers.asStateFlow()

    private val details = MutableStateFlow<Map<String, ServerResponse>>(emptyMap())
    private val members = MutableStateFlow<Map<String, List<ServerMemberResponse>>>(emptyMap())
    private val histories = MutableStateFlow<Map<String, ChannelHistory>>(emptyMap())

    private val _typing = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    /** Who is typing in each channel right now (channel id → user ids). */
    val typing: StateFlow<Map<String, Set<String>>> = _typing.asStateFlow()
    private val typingTimeouts = mutableMapOf<Pair<String, String>, Job>()
    private val lastTypingSent = mutableMapOf<String, Long>()

    /** The channel on screen: its messages are read as they arrive and its mentions don't notify. */
    @Volatile var openChannel: String? = null

    fun server(id: String): Flow<ServerResponse?> = details.map { it[id] }
    fun members(serverId: String): Flow<List<ServerMemberResponse>?> = members.map { it[serverId] }
    fun history(channelId: String): Flow<ChannelHistory> = histories.map { it[channelId] ?: ChannelHistory() }

    // --- Servers ---

    suspend fun refresh() {
        _servers.value = apiCall { api.servers() }
    }

    suspend fun load(serverId: String): ServerResponse = apiCall { api.server(serverId) }.also(::store)

    suspend fun loadMembers(serverId: String) {
        val list = apiCall { api.serverMembers(serverId) }
        members.update { it + (serverId to list) }
    }

    suspend fun create(name: String, template: String, memberIds: List<String>): String {
        val server = apiCall { api.createServer(CreateServerBody(name.trim(), template, memberIds)) }
        store(server)
        runCatching { refresh() }
        return server.id
    }

    suspend fun rename(serverId: String, name: String) = store(apiCall { api.renameServer(serverId, RenameServerBody(name.trim())) })

    suspend fun delete(serverId: String) {
        apiCallUnit { api.deleteServer(serverId) }
        forget(serverId)
    }

    suspend fun addMembers(serverId: String, userIds: List<String>) {
        store(apiCall { api.addServerMembers(serverId, AddMembersBody(userIds)) })
        runCatching { loadMembers(serverId) }
    }

    suspend fun kick(serverId: String, userId: String) {
        apiCallUnit { api.removeServerMember(serverId, userId) }
        runCatching { loadMembers(serverId) }
        runCatching { load(serverId) }
    }

    suspend fun leave(serverId: String) {
        val me = selfId() ?: return
        apiCallUnit { api.removeServerMember(serverId, me) }
        forget(serverId)
    }

    suspend fun setMemberRoles(serverId: String, userId: String, roleIds: List<String>) {
        apiCallUnit { api.setMemberRoles(serverId, userId, RoleIdsBody(roleIds)) }
        runCatching { loadMembers(serverId) }
        runCatching { load(serverId) }
    }

    suspend fun createRole(serverId: String, name: String, color: Int, permissions: Long) =
        store(apiCall { api.createRole(serverId, RoleBody(name.trim(), color, permissions)) })

    suspend fun updateRole(serverId: String, roleId: String, name: String, color: Int, permissions: Long) =
        store(apiCall { api.updateRole(serverId, roleId, RoleBody(name.trim(), color, permissions)) })

    suspend fun deleteRole(serverId: String, roleId: String) = store(apiCall { api.deleteRole(serverId, roleId) })

    suspend fun createChannel(serverId: String, name: String, categoryId: String?) = store(apiCall { api.createChannel(serverId, CreateChannelBody(name, categoryId)) })

    suspend fun renameChannel(channelId: String, name: String) = store(apiCall { api.updateChannel(channelId, UpdateChannelBody(name = name)) })

    suspend fun deleteChannel(channelId: String) = store(apiCall { api.deleteChannel(channelId) })

    suspend fun createCategory(serverId: String, name: String) = store(apiCall { api.createCategory(serverId, CategoryBody(name.trim())) })

    suspend fun renameCategory(serverId: String, categoryId: String, name: String) =
        store(apiCall { api.renameCategory(serverId, categoryId, CategoryBody(name.trim())) })

    suspend fun deleteCategory(serverId: String, categoryId: String) = store(apiCall { api.deleteCategory(serverId, categoryId) })

    // --- Messages ---

    /** The newest page, if this channel hasn't been loaded yet (or [force]). */
    suspend fun loadLatest(channelId: String, force: Boolean = false) {
        if (!force && histories.value[channelId]?.loaded == true) return
        val page = apiCall { api.channelMessages(channelId, limit = PAGE) }.map { it.toMessage() }
        histories.update { all ->
            val pending = all[channelId]?.messages.orEmpty().filter { it.pending || it.failed }
            all + (channelId to ChannelHistory(page + pending, loaded = true, complete = page.size < PAGE))
        }
    }

    /** Loads the page before the oldest loaded message. */
    suspend fun loadOlder(channelId: String) {
        val current = histories.value[channelId] ?: return
        if (current.complete) return
        val oldest = current.messages.firstOrNull { !it.pending && !it.failed }?.id ?: return
        val page = apiCall { api.channelMessages(channelId, before = oldest, limit = PAGE) }.map { it.toMessage() }
        histories.update { all ->
            val now = all[channelId] ?: return@update all
            all + (channelId to now.copy(messages = page + now.messages, complete = page.size < PAGE))
        }
    }

    suspend fun send(channelId: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val me = selfId() ?: return
        val clientId = UUID.randomUUID().toString()
        val draft = ChannelMessage(-System.nanoTime(), clientId, channelId, me, "", "", body, System.currentTimeMillis(), false, pending = true)
        upsert(draft)
        deliver(draft)
    }

    suspend fun retry(message: ChannelMessage) {
        val again = message.copy(pending = true, failed = false)
        upsert(again)
        deliver(again)
    }

    private suspend fun deliver(message: ChannelMessage) {
        try {
            val sent = apiCall { api.sendChannelMessage(message.channelId, SendChannelMessageBody(message.body, message.clientId!!)) }
            upsert(sent.toMessage())
        } catch (e: ApiError) {
            upsert(message.copy(pending = false, failed = true))
        }
    }

    suspend fun deleteMessage(message: ChannelMessage) {
        if (message.id > 0) apiCallUnit { api.deleteChannelMessage(message.channelId, message.id) }
        remove(message.channelId) { it.id == message.id && it.clientId == message.clientId }
    }

    /** You're looking at the channel: mark everything in it read (clears dots and mention badges). */
    suspend fun markRead(serverId: String, channelId: String) {
        val last = histories.value[channelId]?.messages?.lastOrNull { !it.pending && !it.failed }?.id ?: return
        runCatching { apiCallUnit { api.markChannelRead(channelId, ReadBody(last)) } }
        setChannelState(serverId, channelId, unread = false, mentionsDelta = null)
    }

    /** Throttled: one "typing" every few seconds while the draft isn't empty. */
    fun onTyping(channelId: String) {
        val now = System.currentTimeMillis()
        if (now - (lastTypingSent[channelId] ?: 0L) < TYPING_RESEND_MS) return
        lastTypingSent[channelId] = now
        sendTyping?.invoke(channelId)
    }

    /** Mentions that arrived while the app wasn't connected: notify, then tell the server they were shown. */
    suspend fun catchUpMentions() {
        val pending = runCatching { apiCall { api.mentions() } }.getOrNull() ?: return
        if (pending.isEmpty()) return
        val alerts = pending.filter { it.message.channelId != openChannel }.map {
            MentionAlert(it.message.serverId, it.message.channelId, "${it.message.authorDisplayName} in #${it.channelName}", it.message.body)
        }
        onMention?.invoke(alerts)
        runCatching { apiCallUnit { api.ackMentions(MentionAckBody(pending.map { it.message.id })) } }
    }

    // --- Live updates ---

    suspend fun onFrame(frame: GatewayFrame) {
        when (frame.t) {
            "channel_message" -> frame.channelMessage?.let { onMessage(it) }
            "channel_message_deleted" -> {
                val channel = frame.channelId ?: return
                remove(channel) { it.id == frame.messageId }
            }
            "channel_typing" -> {
                val channel = frame.channelId ?: return
                val user = frame.userId ?: return
                setTyping(channel, user)
            }
            "server" -> {
                val id = frame.serverId ?: return
                runCatching { refresh() }
                if (details.value.containsKey(id)) runCatching { load(id) }
                if (members.value.containsKey(id)) runCatching { loadMembers(id) }
            }
            "server_removed" -> frame.serverId?.let(::forget)
        }
    }

    private fun onMessage(message: ChannelMessageResponse) {
        val me = selfId()
        val mine = message.authorId == me
        if (histories.value[message.channelId]?.loaded == true || mine) upsert(message.toMessage())
        clearTyping(message.channelId, message.authorId)
        if (mine) return
        val open = openChannel == message.channelId
        if (open) {
            scope.launch { runCatching { apiCallUnit { api.markChannelRead(message.channelId, ReadBody(message.id)) } } }
            return
        }
        val mentioned = me != null && me in message.mentionIds
        setChannelState(message.serverId, message.channelId, unread = true, mentionsDelta = if (mentioned) 1 else 0)
        if (mentioned) {
            val channelName = details.value[message.serverId]?.channels?.firstOrNull { it.id == message.channelId }?.name ?: "a channel"
            onMention?.invoke(listOf(MentionAlert(message.serverId, message.channelId, "${message.authorDisplayName} in #$channelName", message.body)))
            scope.launch { runCatching { apiCallUnit { api.ackMentions(MentionAckBody(listOf(message.id))) } } }
        }
    }

    // --- Local state ---

    private fun store(server: ServerResponse) {
        details.update { it + (server.id to server) }
        _servers.update { list ->
            val summary = ServerSummaryResponse(server.id, server.name, server.ownerId, server.channels.any { it.unread }, server.channels.sumOf { it.mentions })
            if (list.any { it.id == server.id }) list.map { if (it.id == server.id) summary else it } else list + summary
        }
    }

    private fun forget(serverId: String) {
        val channels = details.value[serverId]?.channels.orEmpty().map { it.id }.toSet()
        _servers.update { list -> list.filterNot { it.id == serverId } }
        details.update { it - serverId }
        members.update { it - serverId }
        histories.update { it - channels }
    }

    /** Updates a channel's dot/badge in both the server view and the rail summary. */
    private fun setChannelState(serverId: String, channelId: String, unread: Boolean, mentionsDelta: Int?) {
        details.update { all ->
            val server = all[serverId] ?: return@update all
            val channels = server.channels.map {
                if (it.id != channelId) it else it.copy(unread = unread, mentions = if (mentionsDelta == null) 0 else it.mentions + mentionsDelta)
            }
            all + (serverId to server.copy(channels = channels))
        }
        _servers.update { list ->
            list.map { summary ->
                if (summary.id != serverId) return@map summary
                val server = details.value[serverId]
                if (server != null) {
                    summary.copy(unread = server.channels.any { it.unread }, mentions = server.channels.sumOf { it.mentions })
                } else {
                    summary.copy(unread = summary.unread || unread, mentions = summary.mentions + (mentionsDelta ?: 0))
                }
            }
        }
    }

    /** Adds or replaces a message: by server id, or by client id for the copy shown while sending. */
    private fun upsert(message: ChannelMessage) {
        histories.update { all ->
            val history = all[message.channelId] ?: ChannelHistory()
            val rest = history.messages.filterNot {
                (it.id == message.id) || (message.clientId != null && it.clientId == message.clientId)
            }
            val merged = (rest + message).sortedWith(compareBy<ChannelMessage>({ it.pending || it.failed }, { if (it.id > 0) it.id else Long.MAX_VALUE }, { it.createdAt }))
            all + (message.channelId to history.copy(messages = merged))
        }
    }

    private fun remove(channelId: String, predicate: (ChannelMessage) -> Boolean) {
        histories.update { all ->
            val history = all[channelId] ?: return@update all
            all + (channelId to history.copy(messages = history.messages.filterNot(predicate)))
        }
    }

    private fun setTyping(channelId: String, userId: String) {
        val key = channelId to userId
        typingTimeouts.remove(key)?.cancel()
        _typing.update { it + (channelId to (it[channelId].orEmpty() + userId)) }
        typingTimeouts[key] = scope.launch {
            delay(TYPING_TIMEOUT_MS)
            clearTyping(channelId, userId)
        }
    }

    private fun clearTyping(channelId: String, userId: String) {
        typingTimeouts.remove(channelId to userId)?.cancel()
        _typing.update { all ->
            val left = all[channelId].orEmpty() - userId
            if (left.isEmpty()) all - channelId else all + (channelId to left)
        }
    }

    private fun ChannelMessageResponse.toMessage() = ChannelMessage(
        id = id,
        clientId = clientId,
        channelId = channelId,
        authorId = authorId,
        authorUsername = authorUsername,
        authorName = authorDisplayName,
        body = body,
        createdAt = createdAt,
        mentionsMe = selfId() in mentionIds,
    )

    /** Signing out: forget everything. */
    fun clear() {
        _servers.value = emptyList()
        details.value = emptyMap()
        members.value = emptyMap()
        histories.value = emptyMap()
        _typing.value = emptyMap()
        openChannel = null
    }

    private companion object {
        const val PAGE = 50
        const val TYPING_RESEND_MS = 3_000L
        const val TYPING_TIMEOUT_MS = 6_000L
    }
}
