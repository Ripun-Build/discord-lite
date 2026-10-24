package com.discordlite.server.servers

import com.discordlite.server.common.badRequest
import com.discordlite.server.common.forbidden
import com.discordlite.server.common.notFound
import com.discordlite.server.db.Db
import com.discordlite.server.db.exec
import com.discordlite.server.db.query
import com.discordlite.server.db.queryOne
import com.discordlite.server.db.uuid
import com.discordlite.server.messaging.Gateway
import com.discordlite.server.messaging.GatewayFrame
import com.discordlite.server.push.NoPush
import com.discordlite.server.push.Pusher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/** What a role can let its members do. A member's powers are all their roles combined. */
object Permissions {
    const val MANAGE_SERVER = 1L
    const val MANAGE_CHANNELS = 2L
    const val MANAGE_ROLES = 4L
    const val KICK_MEMBERS = 8L
    const val MANAGE_MESSAGES = 16L
    const val ADD_MEMBERS = 32L
    const val ALL = MANAGE_SERVER or MANAGE_CHANNELS or MANAGE_ROLES or KICK_MEMBERS or MANAGE_MESSAGES or ADD_MEMBERS
}

@Serializable data class CategoryDto(val id: String, val name: String, val position: Int)

/** [unread]: messages from others since you last read it; [mentions]: of those, how many @mention you. */
@Serializable
data class ChannelDto(val id: String, val name: String, val categoryId: String? = null, val position: Int, val unread: Boolean = false, val mentions: Int = 0)

@Serializable
data class RoleDto(val id: String, val name: String, val color: Int, val permissions: Long, val position: Int, val isDefault: Boolean, val memberCount: Int)

@Serializable
data class ServerSummary(val id: String, val name: String, val ownerId: String, val unread: Boolean, val mentions: Int)

/** Everything the app needs to draw a server. [myPermissions] already includes owner powers. */
@Serializable
data class ServerDto(
    val id: String,
    val name: String,
    val ownerId: String,
    val myPermissions: Long,
    val categories: List<CategoryDto>,
    val channels: List<ChannelDto>,
    val roles: List<RoleDto>,
    val memberCount: Int,
    val createdAt: Long,
)

@Serializable
data class ServerMemberDto(val id: String, val username: String, val displayName: String, val roleIds: List<String>, val joinedAt: Long)

@Serializable
data class ChannelMessageDto(
    val id: Long,
    val serverId: String,
    val channelId: String,
    val authorId: String,
    val authorUsername: String,
    val authorDisplayName: String,
    val body: String,
    val createdAt: Long,
    val mentionIds: List<String> = emptyList(),
    /** The author's own id for it, so their app can match it to the copy it showed while sending. */
    val clientId: String? = null,
)

/** An @mention of you that your phone hasn't shown yet. */
@Serializable
data class MentionDto(val message: ChannelMessageDto, val serverName: String, val channelName: String)

@Serializable data class CreateServerRequest(val name: String, val template: String = "gaming", val memberIds: List<String> = emptyList())
@Serializable data class RenameServerRequest(val name: String)
@Serializable data class AddServerMembersRequest(val userIds: List<String>)
@Serializable data class CreateChannelRequest(val name: String, val categoryId: String? = null)
@Serializable data class UpdateChannelRequest(val name: String? = null, val categoryId: String? = null, val uncategorized: Boolean = false)
@Serializable data class CategoryRequest(val name: String)
@Serializable data class RoleRequest(val name: String, val color: Int = 0, val permissions: Long = 0)
@Serializable data class SetMemberRolesRequest(val roleIds: List<String>)
@Serializable data class SendChannelMessageRequest(val body: String, val clientId: String)
@Serializable data class ReadRequest(val lastReadId: Long)
@Serializable data class MentionAckRequest(val messageIds: List<Long>)

/**
 * Discord-style servers: text channels in categories, members, and roles with permissions.
 *
 * Rules:
 * - Anything about a server you're not in answers "not found".
 * - People are added (Phase 4; invite links come in Phase 5) by a member with "Add members",
 *   and only from their own friends list.
 * - The owner can do everything and can't leave (they delete the server instead).
 * - Nobody can hand out powers they don't have. Kicking or changing the roles of someone who
 *   has management powers is for the owner only.
 * - Messages are plain text (not end-to-end encrypted). @username mentions notify that person.
 */
class ServerService(
    private val db: Db,
    private val gateway: Gateway,
    private val pusher: Pusher = NoPush,
    private val scope: CoroutineScope? = null,
) {

    // --- Servers ---

    suspend fun create(owner: UUID, request: CreateServerRequest): ServerDto {
        val name = cleanServerName(request.name)
        val invited = parseUsers(request.memberIds) - owner
        val id = UUID.randomUUID()
        val dto = db.tx {
            invited.forEach { checkCanAdd(owner, it) }
            exec("INSERT INTO servers (id, owner_id, name) VALUES (?, ?, ?)", id, owner, name)
            exec("INSERT INTO server_roles (id, server_id, name, is_default) VALUES (?, ?, 'everyone', true)", UUID.randomUUID(), id)
            (listOf(owner) + invited).forEach { exec("INSERT INTO server_members (server_id, user_id) VALUES (?, ?)", id, it) }
            when (request.template) {
                "blank" -> addChannel(id, "general", null, 0)
                else -> {
                    val info = addCategory(id, "Information", 0)
                    addChannel(id, "welcome", info, 0)
                    val text = addCategory(id, "Text channels", 1)
                    listOf("general", "clips", "lfg").forEachIndexed { i, channel -> addChannel(id, channel, text, i) }
                    exec(
                        "INSERT INTO server_roles (id, server_id, name, color, permissions, position) VALUES (?, ?, 'Admin', ?, ?, 1)",
                        UUID.randomUUID(), id, 0xFF5865F2.toInt(), Permissions.ALL,
                    )
                }
            }
            serverDto(owner, id)
        }
        invited.forEach { notifyChanged(it, id) }
        return dto
    }

    /** Your servers, oldest first (the order they sit in the rail). */
    suspend fun list(user: UUID): List<ServerSummary> = db.tx {
        query(
            """
            SELECT s.id, s.name, s.owner_id FROM servers s JOIN server_members m ON m.server_id = s.id
            WHERE m.user_id = ? ORDER BY m.joined_at, s.id
            """.trimIndent(),
            user,
        ) { Triple(it.uuid("id"), it.getString("name"), it.uuid("owner_id")) }.map { (id, name, ownerId) ->
            val channels = channelsFor(user, id)
            ServerSummary(id.toString(), name, ownerId.toString(), channels.any { it.unread }, channels.sumOf { it.mentions })
        }
    }

    suspend fun get(user: UUID, serverId: UUID): ServerDto = db.tx {
        requireMember(user, serverId)
        serverDto(user, serverId)
    }

    suspend fun rename(user: UUID, serverId: UUID, request: RenameServerRequest): ServerDto {
        val name = cleanServerName(request.name)
        db.tx {
            require(user, serverId, Permissions.MANAGE_SERVER)
            exec("UPDATE servers SET name = ? WHERE id = ?", name, serverId)
        }
        return changed(user, serverId)
    }

    /** Owner only. Everyone loses it at once. */
    suspend fun delete(user: UUID, serverId: UUID) {
        val members = db.tx {
            requireMember(user, serverId)
            if (ownerOf(serverId) != user) throw forbidden("not_server_owner")
            val members = memberIds(serverId)
            exec("DELETE FROM servers WHERE id = ?", serverId)
            members
        }
        members.forEach { gateway.sendTo(it, GatewayFrame("server_removed", serverId = serverId.toString())) }
    }

    // --- Members ---

    suspend fun members(user: UUID, serverId: UUID): List<ServerMemberDto> = db.tx {
        requireMember(user, serverId)
        val roles = query("SELECT user_id, role_id FROM member_roles WHERE server_id = ?", serverId) { it.uuid("user_id") to it.uuid("role_id").toString() }
            .groupBy({ it.first }, { it.second })
        query(
            """
            SELECT u.id, u.username, u.display_name, m.joined_at FROM server_members m JOIN users u ON u.id = m.user_id
            WHERE m.server_id = ? ORDER BY lower(u.display_name), u.id
            """.trimIndent(),
            serverId,
        ) {
            val id = it.uuid("id")
            ServerMemberDto(id.toString(), it.getString("username"), it.getString("display_name"), roles[id].orEmpty(), it.getTimestamp("joined_at").time)
        }
    }

    suspend fun addMembers(user: UUID, serverId: UUID, request: AddServerMembersRequest): ServerDto {
        val added = db.tx {
            require(user, serverId, Permissions.ADD_MEMBERS)
            val existing = memberIds(serverId).toSet()
            val invited = parseUsers(request.userIds) - existing
            if (existing.size + invited.size > MAX_MEMBERS) throw badRequest("server_full")
            invited.forEach { checkCanAdd(user, it) }
            invited.forEach { exec("INSERT INTO server_members (server_id, user_id) VALUES (?, ?)", serverId, it) }
            invited
        }
        added.forEach { notifyChanged(it, serverId) }
        return changed(user, serverId)
    }

    /** [target] == [user] means leaving. Otherwise a kick. */
    suspend fun removeMember(user: UUID, serverId: UUID, target: UUID) {
        db.tx {
            requireMember(user, serverId)
            val owner = ownerOf(serverId)
            if (target == user) {
                if (user == owner) throw badRequest("owner_cannot_leave")
            } else {
                require(user, serverId, Permissions.KICK_MEMBERS)
                if (!isMember(target, serverId)) throw notFound("not_a_member")
                requireAbove(user, owner, serverId, target)
            }
            exec("DELETE FROM server_members WHERE server_id = ? AND user_id = ?", serverId, target)
        }
        gateway.sendTo(target, GatewayFrame("server_removed", serverId = serverId.toString()))
        broadcastChanged(serverId)
    }

    // --- Roles ---

    suspend fun createRole(user: UUID, serverId: UUID, request: RoleRequest): ServerDto {
        val name = cleanRoleName(request.name)
        db.tx {
            val mine = require(user, serverId, Permissions.MANAGE_ROLES)
            requireGrantable(mine, request.permissions)
            // New roles start at the bottom (just above everyone), as in Discord, so they never outrank existing ones.
            val position = queryOne("SELECT COALESCE(MIN(position), 1) - 1 AS p FROM server_roles WHERE server_id = ? AND NOT is_default", serverId) { it.getInt("p") }!!
            exec(
                "INSERT INTO server_roles (id, server_id, name, color, permissions, position) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), serverId, name, request.color, request.permissions and Permissions.ALL, position,
            )
        }
        return changed(user, serverId)
    }

    suspend fun updateRole(user: UUID, serverId: UUID, roleId: UUID, request: RoleRequest): ServerDto {
        db.tx {
            val mine = require(user, serverId, Permissions.MANAGE_ROLES)
            val (current, isDefault) = roleRow(serverId, roleId)
            // You can't edit a role that has (or would get) powers you don't have.
            requireGrantable(mine, current)
            requireGrantable(mine, request.permissions)
            val name = if (isDefault) "everyone" else cleanRoleName(request.name)
            exec(
                "UPDATE server_roles SET name = ?, color = ?, permissions = ? WHERE id = ?",
                name, request.color, request.permissions and Permissions.ALL, roleId,
            )
        }
        return changed(user, serverId)
    }

    suspend fun deleteRole(user: UUID, serverId: UUID, roleId: UUID): ServerDto {
        db.tx {
            val mine = require(user, serverId, Permissions.MANAGE_ROLES)
            val (current, isDefault) = roleRow(serverId, roleId)
            if (isDefault) throw badRequest("cannot_delete_default_role")
            requireGrantable(mine, current)
            exec("DELETE FROM server_roles WHERE id = ?", roleId)
        }
        return changed(user, serverId)
    }

    /** Replaces [target]'s roles. Only roles within your own powers can be given or taken away. */
    suspend fun setMemberRoles(user: UUID, serverId: UUID, target: UUID, request: SetMemberRolesRequest) {
        db.tx {
            val mine = require(user, serverId, Permissions.MANAGE_ROLES)
            if (!isMember(target, serverId)) throw notFound("not_a_member")
            val owner = ownerOf(serverId)
            if (target != user) requireAbove(user, owner, serverId, target)
            val wanted = request.roleIds.map { runCatching { UUID.fromString(it) }.getOrNull() ?: throw notFound("role_not_found") }.toSet()
            val roles = query("SELECT id, permissions, is_default FROM server_roles WHERE server_id = ?", serverId) {
                Triple(it.uuid("id"), it.getLong("permissions"), it.getBoolean("is_default"))
            }.associateBy { it.first }
            if (wanted.any { roles[it] == null || roles[it]!!.third }) throw notFound("role_not_found")
            val current = query("SELECT role_id FROM member_roles WHERE server_id = ? AND user_id = ?", serverId, target) { it.uuid("role_id") }.toSet()
            (wanted - current).forEach {
                requireGrantable(mine, roles.getValue(it).second)
                exec("INSERT INTO member_roles (server_id, user_id, role_id) VALUES (?, ?, ?)", serverId, target, it)
            }
            (current - wanted).forEach {
                requireGrantable(mine, roles.getValue(it).second)
                exec("DELETE FROM member_roles WHERE server_id = ? AND user_id = ? AND role_id = ?", serverId, target, it)
            }
        }
        broadcastChanged(serverId)
    }

    // --- Channels and categories ---

    suspend fun createChannel(user: UUID, serverId: UUID, request: CreateChannelRequest): ServerDto {
        val name = cleanChannelName(request.name)
        db.tx {
            require(user, serverId, Permissions.MANAGE_CHANNELS)
            val category = request.categoryId?.let { categoryIn(serverId, it) }
            val position = queryOne(
                "SELECT COALESCE(MAX(position), -1) + 1 AS p FROM channels WHERE server_id = ? AND category_id IS NOT DISTINCT FROM ?",
                serverId, category,
            ) { it.getInt("p") }!!
            addChannel(serverId, name, category, position)
        }
        return changed(user, serverId)
    }

    suspend fun updateChannel(user: UUID, channelId: UUID, request: UpdateChannelRequest): ServerDto {
        val serverId = db.tx {
            val serverId = serverOfChannel(user, channelId)
            require(user, serverId, Permissions.MANAGE_CHANNELS)
            request.name?.let { exec("UPDATE channels SET name = ? WHERE id = ?", cleanChannelName(it), channelId) }
            when {
                request.uncategorized -> exec("UPDATE channels SET category_id = NULL WHERE id = ?", channelId)
                request.categoryId != null -> exec("UPDATE channels SET category_id = ? WHERE id = ?", categoryIn(serverId, request.categoryId), channelId)
            }
            serverId
        }
        return changed(user, serverId)
    }

    suspend fun deleteChannel(user: UUID, channelId: UUID): ServerDto {
        val serverId = db.tx {
            val serverId = serverOfChannel(user, channelId)
            require(user, serverId, Permissions.MANAGE_CHANNELS)
            exec("DELETE FROM channels WHERE id = ?", channelId)
            serverId
        }
        return changed(user, serverId)
    }

    suspend fun createCategory(user: UUID, serverId: UUID, request: CategoryRequest): ServerDto {
        val name = cleanCategoryName(request.name)
        db.tx {
            require(user, serverId, Permissions.MANAGE_CHANNELS)
            val position = queryOne("SELECT COALESCE(MAX(position), -1) + 1 AS p FROM channel_categories WHERE server_id = ?", serverId) { it.getInt("p") }!!
            addCategory(serverId, name, position)
        }
        return changed(user, serverId)
    }

    suspend fun renameCategory(user: UUID, serverId: UUID, categoryId: UUID, request: CategoryRequest): ServerDto {
        val name = cleanCategoryName(request.name)
        db.tx {
            require(user, serverId, Permissions.MANAGE_CHANNELS)
            if (exec("UPDATE channel_categories SET name = ? WHERE id = ? AND server_id = ?", name, categoryId, serverId) == 0) throw notFound("category_not_found")
        }
        return changed(user, serverId)
    }

    /** Its channels stay, without a category. */
    suspend fun deleteCategory(user: UUID, serverId: UUID, categoryId: UUID): ServerDto {
        db.tx {
            require(user, serverId, Permissions.MANAGE_CHANNELS)
            if (exec("DELETE FROM channel_categories WHERE id = ? AND server_id = ?", categoryId, serverId) == 0) throw notFound("category_not_found")
        }
        return changed(user, serverId)
    }

    // --- Messages ---

    /** Newest [limit] messages before [before] (or the newest overall), returned oldest first. */
    suspend fun messages(user: UUID, channelId: UUID, before: Long?, limit: Int): List<ChannelMessageDto> = db.tx {
        val serverId = serverOfChannel(user, channelId)
        withMentions(query(
            """
            SELECT * FROM (
                SELECT m.id, m.channel_id, m.author_id, m.body, m.client_id, m.created_at, u.username, u.display_name
                FROM channel_messages m JOIN users u ON u.id = m.author_id
                WHERE m.channel_id = ? AND (?::bigint IS NULL OR m.id < ?) ORDER BY m.id DESC LIMIT ?
            ) page ORDER BY id
            """.trimIndent(),
            channelId, before, before, limit.coerceIn(1, 100),
        ) { it.toMessage(serverId) })
    }

    suspend fun send(user: UUID, channelId: UUID, request: SendChannelMessageRequest): ChannelMessageDto {
        val body = request.body.trim()
        if (body.isEmpty() || body.length > MAX_MESSAGE) throw badRequest("invalid_body")
        val clientId = runCatching { UUID.fromString(request.clientId) }.getOrNull() ?: throw badRequest("invalid_client_id")
        val (message, members, isNew) = db.tx {
            val serverId = serverOfChannel(user, channelId)
            val inserted = queryOne(
                """
                INSERT INTO channel_messages (channel_id, author_id, body, client_id) VALUES (?, ?, ?, ?)
                ON CONFLICT (author_id, client_id) DO NOTHING RETURNING id
                """.trimIndent(),
                channelId, user, body, clientId,
            ) { it.getLong("id") }
            val id = inserted ?: queryOne("SELECT id FROM channel_messages WHERE author_id = ? AND client_id = ?", user, clientId) { it.getLong("id") }!!
            if (inserted != null) {
                // @username → that member (only people in this server, never yourself).
                val usernames = MENTION.findAll(body).map { it.groupValues[1].lowercase() }.toSet()
                if (usernames.isNotEmpty()) {
                    exec(
                        """
                        INSERT INTO message_mentions (message_id, user_id)
                        SELECT ?, u.id FROM users u JOIN server_members m ON m.user_id = u.id AND m.server_id = ?
                        WHERE u.username = ANY(?) AND u.id <> ? ON CONFLICT DO NOTHING
                        """.trimIndent(),
                        id, serverId, createArrayOf("text", usernames.toTypedArray()), user,
                    )
                }
                markRead(user, channelId, id)
            }
            val message = withMentions(query(
                """
                SELECT m.id, m.channel_id, m.author_id, m.body, m.client_id, m.created_at, u.username, u.display_name
                FROM channel_messages m JOIN users u ON u.id = m.author_id WHERE m.id = ?
                """.trimIndent(),
                id,
            ) { it.toMessage(serverId) }).single()
            Triple(message, memberIds(serverId), inserted != null)
        }
        if (isNew) {
            val frame = GatewayFrame("channel_message", channelMessage = message)
            members.forEach { gateway.sendTo(it, frame) }
            // Mentioned and not connected: wake their phone so it can show the mention.
            message.mentionIds.map(UUID::fromString).filterNot(gateway::isOnline).forEach { scope?.launch { runCatching { pusher.wake(it) } } }
        }
        return message
    }

    suspend fun deleteMessage(user: UUID, channelId: UUID, messageId: Long) {
        val (serverId, members) = db.tx {
            val serverId = serverOfChannel(user, channelId)
            val author = queryOne("SELECT author_id FROM channel_messages WHERE id = ? AND channel_id = ?", messageId, channelId) { it.uuid("author_id") }
                ?: throw notFound("message_not_found")
            if (author != user) require(user, serverId, Permissions.MANAGE_MESSAGES)
            exec("DELETE FROM channel_messages WHERE id = ?", messageId)
            serverId to memberIds(serverId)
        }
        val frame = GatewayFrame("channel_message_deleted", serverId = serverId.toString(), channelId = channelId.toString(), messageId = messageId)
        members.forEach { gateway.sendTo(it, frame) }
    }

    /** You've read up to [lastReadId]: clears the unread dot, and mentions up to there count as seen. */
    suspend fun read(user: UUID, channelId: UUID, lastReadId: Long) = db.tx {
        serverOfChannel(user, channelId)
        markRead(user, channelId, lastReadId)
    }

    /** "Bea is typing in #general" — passed to members who are online, never stored. */
    suspend fun relayTyping(user: UUID, channelIdRaw: String?) {
        val channelId = runCatching { UUID.fromString(channelIdRaw) }.getOrNull() ?: return
        val (serverId, members) = runCatching { db.tx { serverOfChannel(user, channelId).let { it to memberIds(it) } } }.getOrNull() ?: return
        val frame = GatewayFrame("channel_typing", serverId = serverId.toString(), channelId = channelId.toString(), userId = user.toString())
        members.filter { it != user && gateway.isOnline(it) }.forEach { gateway.sendTo(it, frame) }
    }

    /** Mentions your phone hasn't shown yet (for notifications after a wake-up). */
    suspend fun pendingMentions(user: UUID): List<MentionDto> = db.tx {
        query(
            """
            SELECT m.id, m.channel_id, m.author_id, m.body, m.client_id, m.created_at, u.username, u.display_name,
                   c.server_id, c.name AS channel_name, s.name AS server_name
            FROM message_mentions mm JOIN channel_messages m ON m.id = mm.message_id
            JOIN users u ON u.id = m.author_id JOIN channels c ON c.id = m.channel_id JOIN servers s ON s.id = c.server_id
            WHERE mm.user_id = ? AND NOT mm.notified ORDER BY m.id LIMIT 50
            """.trimIndent(),
            user,
        ) { MentionDto(it.toMessage(it.uuid("server_id")).copy(mentionIds = listOf(user.toString())), it.getString("server_name"), it.getString("channel_name")) }
    }

    suspend fun ackMentions(user: UUID, messageIds: List<Long>) {
        if (messageIds.isEmpty()) return
        db.tx { exec("UPDATE message_mentions SET notified = true WHERE user_id = ? AND message_id = ANY(?)", user, createArrayOf("bigint", messageIds.toTypedArray())) }
    }

    // --- Helpers ---

    private fun Connection.serverDto(user: UUID, serverId: UUID): ServerDto {
        val (name, owner, createdAt) = queryOne("SELECT name, owner_id, created_at FROM servers WHERE id = ?", serverId) {
            Triple(it.getString("name"), it.uuid("owner_id"), it.getTimestamp("created_at").time)
        } ?: throw notFound("server_not_found")
        val categories = query("SELECT id, name, position FROM channel_categories WHERE server_id = ? ORDER BY position, id", serverId) {
            CategoryDto(it.uuid("id").toString(), it.getString("name"), it.getInt("position"))
        }
        val roles = query(
            """
            SELECT r.id, r.name, r.color, r.permissions, r.position, r.is_default,
                   (SELECT COUNT(*) FROM member_roles mr WHERE mr.role_id = r.id) AS members
            FROM server_roles r WHERE r.server_id = ? ORDER BY r.is_default, r.position DESC, r.id
            """.trimIndent(),
            serverId,
        ) { RoleDto(it.uuid("id").toString(), it.getString("name"), it.getInt("color"), it.getLong("permissions"), it.getInt("position"), it.getBoolean("is_default"), it.getInt("members")) }
        val memberCount = queryOne("SELECT COUNT(*) AS n FROM server_members WHERE server_id = ?", serverId) { it.getInt("n") }!!
        return ServerDto(serverId.toString(), name, owner.toString(), permissionsOf(user, serverId), categories, channelsFor(user, serverId), roles, memberCount, createdAt)
    }

    private fun Connection.channelsFor(user: UUID, serverId: UUID): List<ChannelDto> =
        query(
            """
            SELECT c.id, c.name, c.category_id, c.position,
                   EXISTS (SELECT 1 FROM channel_messages m WHERE m.channel_id = c.id AND m.author_id <> ? AND m.id > COALESCE(r.last_read_id, 0)) AS unread,
                   (SELECT COUNT(*) FROM message_mentions mm JOIN channel_messages m ON m.id = mm.message_id
                    WHERE m.channel_id = c.id AND mm.user_id = ? AND m.id > COALESCE(r.last_read_id, 0)) AS mentions
            FROM channels c LEFT JOIN channel_reads r ON r.channel_id = c.id AND r.user_id = ?
            LEFT JOIN channel_categories k ON k.id = c.category_id
            -- Uncategorised channels first, then each category's channels in order.
            WHERE c.server_id = ? ORDER BY k.position NULLS FIRST, k.id NULLS FIRST, c.position, c.created_at, c.id
            """.trimIndent(),
            user, user, user, serverId,
        ) {
            ChannelDto(it.uuid("id").toString(), it.getString("name"), it.getObject("category_id", UUID::class.java)?.toString(), it.getInt("position"), it.getBoolean("unread"), it.getInt("mentions"))
        }

    private fun Connection.permissionsOf(user: UUID, serverId: UUID): Long {
        if (ownerOf(serverId) == user) return Permissions.ALL
        return query(
            """
            SELECT r.permissions FROM server_roles r WHERE r.server_id = ? AND (r.is_default OR r.id IN (
                SELECT role_id FROM member_roles WHERE server_id = ? AND user_id = ?))
            """.trimIndent(),
            serverId, serverId, user,
        ) { it.getLong("permissions") }.fold(0L) { all, p -> all or p }
    }

    /** Member check plus a permission check; returns your permissions. */
    private fun Connection.require(user: UUID, serverId: UUID, permission: Long): Long {
        requireMember(user, serverId)
        val mine = permissionsOf(user, serverId)
        if (mine and permission == 0L) throw forbidden("missing_permission")
        return mine
    }

    private fun Connection.requireMember(user: UUID, serverId: UUID) {
        if (!isMember(user, serverId)) throw notFound("server_not_found")
    }

    /** People with management powers can only be kicked or re-roled by the owner. */
    private fun Connection.requireAbove(user: UUID, owner: UUID, serverId: UUID, target: UUID) {
        if (target == owner) throw forbidden("target_is_owner")
        if (user != owner && permissionsOf(target, serverId) != 0L) throw forbidden("target_has_powers")
    }

    private fun requireGrantable(mine: Long, permissions: Long) {
        if (permissions and Permissions.ALL and mine.inv() != 0L) throw forbidden("cannot_grant_permission")
    }

    private fun Connection.isMember(user: UUID, serverId: UUID): Boolean =
        queryOne("SELECT 1 FROM server_members WHERE server_id = ? AND user_id = ?", serverId, user) { true } == true

    private fun Connection.ownerOf(serverId: UUID): UUID =
        queryOne("SELECT owner_id FROM servers WHERE id = ?", serverId) { it.uuid("owner_id") } ?: throw notFound("server_not_found")

    private fun Connection.memberIds(serverId: UUID): List<UUID> =
        query("SELECT user_id FROM server_members WHERE server_id = ?", serverId) { it.uuid("user_id") }

    /** The channel's server, if [user] is a member of it; otherwise "not found". */
    private fun Connection.serverOfChannel(user: UUID, channelId: UUID): UUID {
        val serverId = queryOne("SELECT server_id FROM channels WHERE id = ?", channelId) { it.uuid("server_id") } ?: throw notFound("channel_not_found")
        if (!isMember(user, serverId)) throw notFound("channel_not_found")
        return serverId
    }

    private fun Connection.categoryIn(serverId: UUID, raw: String): UUID {
        val id = runCatching { UUID.fromString(raw) }.getOrNull() ?: throw notFound("category_not_found")
        queryOne("SELECT 1 FROM channel_categories WHERE id = ? AND server_id = ?", id, serverId) { true } ?: throw notFound("category_not_found")
        return id
    }

    private fun Connection.roleRow(serverId: UUID, roleId: UUID): Pair<Long, Boolean> =
        queryOne("SELECT permissions, is_default FROM server_roles WHERE id = ? AND server_id = ?", roleId, serverId) {
            it.getLong("permissions") to it.getBoolean("is_default")
        } ?: throw notFound("role_not_found")

    /** Adding someone: they must be your friend, and neither of you has blocked the other. */
    private fun Connection.checkCanAdd(adder: UUID, target: UUID) {
        val visible = queryOne("SELECT 1 FROM users WHERE id = ? AND email_verified", target) { true } == true &&
            queryOne("SELECT 1 FROM blocks WHERE (blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?)", adder, target, target, adder) { true } == null
        if (!visible) throw notFound("user_not_found")
        val friends = queryOne(
            "SELECT 1 FROM friendships WHERE status = 'ACCEPTED' AND ((requester_id = ? AND addressee_id = ?) OR (requester_id = ? AND addressee_id = ?))",
            adder, target, target, adder,
        ) { true } == true
        if (!friends) throw forbidden("not_friends")
    }

    private fun Connection.addCategory(serverId: UUID, name: String, position: Int): UUID =
        UUID.randomUUID().also { exec("INSERT INTO channel_categories (id, server_id, name, position) VALUES (?, ?, ?, ?)", it, serverId, name, position) }

    private fun Connection.addChannel(serverId: UUID, name: String, category: UUID?, position: Int): UUID =
        UUID.randomUUID().also { exec("INSERT INTO channels (id, server_id, category_id, name, position) VALUES (?, ?, ?, ?, ?)", it, serverId, category, name, position) }

    private fun Connection.markRead(user: UUID, channelId: UUID, lastReadId: Long) {
        exec(
            """
            INSERT INTO channel_reads (user_id, channel_id, last_read_id) VALUES (?, ?, ?)
            ON CONFLICT (user_id, channel_id) DO UPDATE SET last_read_id = GREATEST(channel_reads.last_read_id, excluded.last_read_id)
            """.trimIndent(),
            user, channelId, lastReadId,
        )
        exec(
            "UPDATE message_mentions SET notified = true WHERE user_id = ? AND NOT notified AND message_id IN (SELECT id FROM channel_messages WHERE channel_id = ? AND id <= ?)",
            user, channelId, lastReadId,
        )
    }

    /** Fills in who each message @mentions. */
    private fun Connection.withMentions(messages: List<ChannelMessageDto>): List<ChannelMessageDto> {
        if (messages.isEmpty()) return messages
        val mentions = query(
            "SELECT message_id, user_id FROM message_mentions WHERE message_id = ANY(?)", createArrayOf("bigint", messages.map { it.id }.toTypedArray()),
        ) { it.getLong("message_id") to it.uuid("user_id").toString() }.groupBy({ it.first }, { it.second })
        return messages.map { it.copy(mentionIds = mentions[it.id].orEmpty()) }
    }

    private fun ResultSet.toMessage(serverId: UUID) = ChannelMessageDto(
        id = getLong("id"),
        serverId = serverId.toString(),
        channelId = uuid("channel_id").toString(),
        authorId = uuid("author_id").toString(),
        authorUsername = getString("username"),
        authorDisplayName = getString("display_name"),
        body = getString("body"),
        createdAt = getTimestamp("created_at").time,
        clientId = uuid("client_id").toString(),
    )

    /** Tells everyone in the server to refresh it, and returns the fresh view for [user]. */
    private suspend fun changed(user: UUID, serverId: UUID): ServerDto {
        broadcastChanged(serverId)
        return get(user, serverId)
    }

    private suspend fun broadcastChanged(serverId: UUID) {
        val members = db.tx { memberIds(serverId) }
        members.forEach { notifyChanged(it, serverId) }
    }

    private suspend fun notifyChanged(user: UUID, serverId: UUID) = gateway.sendTo(user, GatewayFrame("server", serverId = serverId.toString()))

    private fun parseUsers(raw: List<String>): Set<UUID> =
        raw.map { runCatching { UUID.fromString(it) }.getOrNull() ?: throw notFound("user_not_found") }.toSet()

    private fun cleanServerName(raw: String): String = raw.trim().takeIf { it.length in 1..100 } ?: throw badRequest("invalid_server_name")

    private fun cleanRoleName(raw: String): String = raw.trim().takeIf { it.length in 1..32 && it.lowercase() != "everyone" } ?: throw badRequest("invalid_role_name")

    private fun cleanCategoryName(raw: String): String = raw.trim().takeIf { it.length in 1..32 } ?: throw badRequest("invalid_category_name")

    /** "Looking For Group!" → "looking-for-group", like Discord. */
    private fun cleanChannelName(raw: String): String {
        val name = raw.trim().lowercase().replace(Regex("\\s+"), "-").filter { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }.trim('-').take(32)
        if (name.isEmpty()) throw badRequest("invalid_channel_name")
        return name
    }

    companion object {
        const val MAX_MEMBERS = 500
        const val MAX_MESSAGE = 4000
        private val MENTION = Regex("(?<![\\w@])@([a-zA-Z0-9._]{3,20})")
    }
}
