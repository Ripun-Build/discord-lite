package com.discordlite.server.messaging

import com.discordlite.server.common.badRequest
import com.discordlite.server.common.conflict
import com.discordlite.server.common.forbidden
import com.discordlite.server.common.notFound
import com.discordlite.server.db.Db
import com.discordlite.server.db.exec
import com.discordlite.server.db.query
import com.discordlite.server.db.queryOne
import com.discordlite.server.db.uuid
import com.discordlite.server.social.SocialService
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.util.UUID

@Serializable
data class GroupMember(val id: String, val username: String, val displayName: String)

/** A group as its members see it. [epoch] changes whenever someone joins or leaves. */
@Serializable
data class GroupDto(
    val id: String,
    val name: String? = null,
    val ownerId: String,
    val epoch: Int,
    val members: List<GroupMember>,
    val createdAt: Long,
)

@Serializable
data class CreateGroupRequest(val name: String? = null, val memberIds: List<String>)

@Serializable
data class RenameGroupRequest(val name: String? = null)

@Serializable
data class AddMembersRequest(val userIds: List<String>)

/**
 * A message for everyone in a group, encrypted once with the sender's Signal sender key
 * ([type] 7). [epoch] is the group version the sender encrypted for; if people have joined or
 * left since, the server answers `stale_group` so the sender refreshes first.
 */
@Serializable
data class SendGroupMessageRequest(val type: Int, val body: String, val clientId: String, val epoch: Int)

/**
 * Group DMs: who is in which group, and who may change it.
 *
 * Rules: the creator owns the group; only the owner renames it and adds or removes people; anyone
 * can leave. Adding someone follows their "who can DM me" setting, like starting a DM does. When
 * the owner leaves, the longest-standing member takes over; when the last person leaves, the group
 * is deleted (and anything still queued for it with it).
 */
class GroupService(private val db: Db, private val social: SocialService, private val gateway: Gateway) {

    suspend fun create(owner: UUID, request: CreateGroupRequest): GroupDto {
        val name = cleanName(request.name)
        val invited = parseUsers(request.memberIds) - owner
        if (invited.isEmpty()) throw badRequest("group_needs_members")
        if (invited.size + 1 > MAX_MEMBERS) throw badRequest("group_full")
        invited.forEach { checkCanAdd(owner, it) }
        val id = UUID.randomUUID()
        val group = db.tx {
            exec("INSERT INTO groups (id, owner_id, name) VALUES (?, ?, ?)", id, owner, name)
            (invited + owner).forEach { exec("INSERT INTO group_members (group_id, user_id) VALUES (?, ?)", id, it) }
            load(id)!!
        }
        announce(group)
        return group
    }

    /** Every group [user] is in, newest first. */
    suspend fun list(user: UUID): List<GroupDto> = db.tx {
        query(
            "SELECT g.id FROM groups g JOIN group_members m ON m.group_id = g.id WHERE m.user_id = ? ORDER BY g.created_at DESC",
            user,
        ) { it.uuid("id") }.mapNotNull { load(it) }
    }

    suspend fun get(user: UUID, groupId: UUID): GroupDto = db.tx { memberView(user, groupId) }

    suspend fun rename(user: UUID, groupId: UUID, request: RenameGroupRequest): GroupDto {
        val name = cleanName(request.name)
        val group = db.tx {
            lockGroup(groupId)
            requireOwner(user, memberView(user, groupId))
            exec("UPDATE groups SET name = ? WHERE id = ?", name, groupId)
            load(groupId)!!
        }
        announce(group)
        return group
    }

    suspend fun addMembers(user: UUID, groupId: UUID, request: AddMembersRequest): GroupDto {
        val current = db.tx { memberView(user, groupId) }
        requireOwner(user, current)
        val already = current.members.map { UUID.fromString(it.id) }.toSet()
        val invited = parseUsers(request.userIds) - already
        if (invited.isEmpty()) return current
        if (already.size + invited.size > MAX_MEMBERS) throw badRequest("group_full")
        invited.forEach { checkCanAdd(user, it) }
        val group = db.tx {
            lockGroup(groupId)
            // Checked again under the lock: someone may have joined or left in the meantime.
            val locked = memberView(user, groupId)
            requireOwner(user, locked)
            val toAdd = invited - locked.members.map { UUID.fromString(it.id) }.toSet()
            if (toAdd.isEmpty()) return@tx locked
            if (locked.members.size + toAdd.size > MAX_MEMBERS) throw badRequest("group_full")
            toAdd.forEach { exec("INSERT INTO group_members (group_id, user_id) VALUES (?, ?)", groupId, it) }
            exec("UPDATE groups SET epoch = epoch + 1 WHERE id = ?", groupId)
            load(groupId)!!
        }
        if (group.epoch != current.epoch) announce(group)
        return group
    }

    /** The owner removes [target], or [target] is [user] leaving. */
    suspend fun removeMember(user: UUID, groupId: UUID, target: UUID) {
        val after = db.tx {
            lockGroup(groupId)
            val current = memberView(user, groupId)
            if (target != user) requireOwner(user, current)
            if (current.members.none { it.id == target.toString() }) throw notFound("not_a_member")
            exec("DELETE FROM group_members WHERE group_id = ? AND user_id = ?", groupId, target)
            val next = queryOne(
                "SELECT user_id FROM group_members WHERE group_id = ? ORDER BY joined_at, user_id LIMIT 1", groupId,
            ) { it.uuid("user_id") }
            if (next == null) {
                exec("DELETE FROM groups WHERE id = ?", groupId)
                null
            } else {
                exec(
                    "UPDATE groups SET epoch = epoch + 1, owner_id = CASE WHEN owner_id = ? THEN ? ELSE owner_id END WHERE id = ?",
                    target, next, groupId,
                )
                load(groupId)
            }
        }
        gateway.sendTo(target, GatewayFrame("group_removed", groupId = groupId.toString()))
        if (after != null) announce(after)
    }

    fun Connection.isMember(user: UUID, groupId: UUID): Boolean =
        queryOne("SELECT 1 FROM group_members WHERE group_id = ? AND user_id = ?", groupId, user) { true } == true

    /** Locks the group row so joins, leaves and sends happen one at a time; returns the epoch. */
    fun Connection.lockGroup(groupId: UUID): Int =
        queryOne("SELECT epoch FROM groups WHERE id = ? FOR UPDATE", groupId) { it.getInt("epoch") } ?: throw notFound("group_not_found")

    private fun Connection.memberView(user: UUID, groupId: UUID): GroupDto =
        load(groupId)?.takeIf { group -> group.members.any { it.id == user.toString() } } ?: throw notFound("group_not_found")

    private fun Connection.load(groupId: UUID): GroupDto? {
        val members = query(
            """
            SELECT u.id, u.username, u.display_name FROM group_members m
            JOIN users u ON u.id = m.user_id JOIN groups g ON g.id = m.group_id
            WHERE m.group_id = ? ORDER BY (m.user_id = g.owner_id) DESC, m.joined_at, u.id
            """.trimIndent(),
            groupId,
        ) { GroupMember(it.uuid("id").toString(), it.getString("username"), it.getString("display_name")) }
        return queryOne("SELECT id, owner_id, name, epoch, created_at FROM groups WHERE id = ?", groupId) {
            GroupDto(
                id = it.uuid("id").toString(),
                name = it.getString("name"),
                ownerId = it.uuid("owner_id").toString(),
                epoch = it.getInt("epoch"),
                members = members,
                createdAt = it.getTimestamp("created_at").time,
            )
        }
    }

    /** Same rules as starting a DM: visible, not blocked, and allowed by their setting (or you already have a DM). */
    private suspend fun checkCanAdd(adder: UUID, target: UUID) {
        if (!social.isVisible(adder, target)) throw notFound("user_not_found")
        val hasDm = db.tx {
            queryOne(
                "SELECT 1 FROM dm_threads WHERE user_low = LEAST(?::uuid, ?::uuid) AND user_high = GREATEST(?::uuid, ?::uuid)",
                adder, target, adder, target,
            ) { true } == true
        }
        if (!hasDm && !social.canStartDm(adder, target)) throw forbidden("group_add_not_allowed")
    }

    private fun requireOwner(user: UUID, group: GroupDto) {
        if (group.ownerId != user.toString()) throw forbidden("not_group_owner")
    }

    /** Tells every member who is online about the group's new state. */
    private suspend fun announce(group: GroupDto) {
        val frame = GatewayFrame("group", group = group)
        group.members.forEach { gateway.sendTo(UUID.fromString(it.id), frame) }
    }

    private fun cleanName(raw: String?): String? {
        val name = raw?.trim()?.ifEmpty { null } ?: return null
        if (name.length > NAME_MAX) throw badRequest("invalid_group_name")
        return name
    }

    private fun parseUsers(raw: List<String>): Set<UUID> =
        raw.map { runCatching { UUID.fromString(it) }.getOrNull() ?: throw notFound("user_not_found") }.toSet()

    companion object {
        const val MAX_MEMBERS = 25
        const val NAME_MAX = 100
    }
}

/** Thrown when a sender encrypted for an old version of the group. */
fun staleGroup() = conflict("stale_group")
