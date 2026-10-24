package com.discordlite.server.social

import com.discordlite.server.common.badRequest
import com.discordlite.server.common.notFound
import com.discordlite.server.common.unauthorized
import com.discordlite.server.db.Db
import com.discordlite.server.db.exec
import com.discordlite.server.db.query
import com.discordlite.server.db.queryOne
import com.discordlite.server.db.uuid
import com.discordlite.server.users.DmPolicy
import com.discordlite.server.users.Me
import com.discordlite.server.users.UserRules
import com.discordlite.server.users.findUserById
import com.discordlite.server.users.toMe
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/** How the viewer relates to another user. */
@Serializable
enum class Relationship { NONE, FRIEND, OUTGOING, INCOMING }

/** What anyone may see about a user. Never includes the email address. */
@Serializable
data class PublicUser(
    val id: String,
    val username: String,
    val displayName: String,
    val bio: String,
    val relationship: Relationship,
)

@Serializable
data class FriendsResponse(val friends: List<PublicUser>, val incoming: List<PublicUser>, val outgoing: List<PublicUser>)

@Serializable
data class UpdateMeRequest(val displayName: String? = null, val bio: String? = null, val dmPolicy: DmPolicy? = null)

@Serializable
data class FriendRequestBody(val userId: String)

@Serializable
data class RelationshipResponse(val relationship: Relationship)

/**
 * Profiles, user search, friends and blocks.
 *
 * Blocking is silent: a blocked user can't find you in search, can't send you a request, and gets
 * the same "user not found" they would for an account that doesn't exist.
 */
class SocialService(private val db: Db) {

    suspend fun me(userId: UUID): Me = db.tx { findUserById(userId)?.toMe() } ?: throw unauthorized()

    suspend fun updateMe(userId: UUID, request: UpdateMeRequest): Me {
        val displayName = request.displayName?.trim()
        val bio = request.bio?.trim()
        if (displayName != null && displayName.length !in 1..UserRules.DISPLAY_NAME_MAX) throw badRequest("invalid_display_name")
        if (bio != null && bio.length > UserRules.BIO_MAX) throw badRequest("invalid_bio")
        return db.tx {
            exec(
                """
                UPDATE users SET display_name = COALESCE(?, display_name), bio = COALESCE(?, bio), dm_policy = COALESCE(?, dm_policy)
                WHERE id = ?
                """.trimIndent(),
                displayName, bio, request.dmPolicy?.name, userId,
            )
            findUserById(userId)?.toMe()
        } ?: throw unauthorized()
    }

    /** Prefix search on usernames ("@ma" finds "@maya"). Hides yourself and anyone blocked either way. */
    suspend fun search(viewer: UUID, rawQuery: String): List<PublicUser> {
        val q = rawQuery.trim().removePrefix("@").lowercase()
        if (q.length < 2 || !q.all { it.isLowerCase() || it.isDigit() || it == '.' || it == '_' }) return emptyList()
        val pattern = q.replace("_", "\\_") + "%"
        return db.tx {
            query(
                """
                SELECT u.id, u.username, u.display_name, u.bio, f.requester_id, f.status
                FROM users u
                LEFT JOIN friendships f
                       ON (f.requester_id = u.id AND f.addressee_id = ?) OR (f.addressee_id = u.id AND f.requester_id = ?)
                WHERE u.email_verified AND u.id <> ? AND u.username LIKE ? ESCAPE '\'
                  AND NOT EXISTS (
                      SELECT 1 FROM blocks b
                      WHERE (b.blocker_id = ? AND b.blocked_id = u.id) OR (b.blocker_id = u.id AND b.blocked_id = ?)
                  )
                ORDER BY u.username
                LIMIT 20
                """.trimIndent(),
                viewer, viewer, viewer, pattern, viewer, viewer,
            ) { it.toPublicUser(viewer) }
        }
    }

    /** One user's public profile, or 404 if they don't exist or a block is in place. */
    suspend fun profile(viewer: UUID, target: UUID): PublicUser = db.tx {
        if (!isVisibleTo(viewer, target) && viewer != target) throw notFound("user_not_found")
        queryOne(
            """
            SELECT u.id, u.username, u.display_name, u.bio, f.requester_id, f.status
            FROM users u
            LEFT JOIN friendships f
                   ON (f.requester_id = u.id AND f.addressee_id = ?) OR (f.addressee_id = u.id AND f.requester_id = ?)
            WHERE u.id = ?
            """.trimIndent(),
            viewer, viewer, target,
        ) { it.toPublicUser(viewer) } ?: throw notFound("user_not_found")
    }

    suspend fun friends(viewer: UUID): FriendsResponse {
        val all = db.tx {
            query(
                """
                SELECT u.id, u.username, u.display_name, u.bio, f.requester_id, f.status
                FROM friendships f
                JOIN users u ON u.id = CASE WHEN f.requester_id = ? THEN f.addressee_id ELSE f.requester_id END
                WHERE f.requester_id = ? OR f.addressee_id = ?
                ORDER BY lower(u.display_name)
                """.trimIndent(),
                viewer, viewer, viewer,
            ) { it.toPublicUser(viewer) }
        }
        return FriendsResponse(
            friends = all.filter { it.relationship == Relationship.FRIEND },
            incoming = all.filter { it.relationship == Relationship.INCOMING },
            outgoing = all.filter { it.relationship == Relationship.OUTGOING },
        )
    }

    /**
     * Sends a friend request. If they had already asked you, this accepts it instead. Repeating a
     * request is harmless.
     */
    suspend fun sendRequest(viewer: UUID, target: UUID): Relationship = db.tx {
        if (viewer == target) throw badRequest("cannot_friend_self")
        if (!isVisibleTo(viewer, target)) throw notFound("user_not_found")
        val pair = pairRow(viewer, target)
        when {
            pair == null -> {
                exec("INSERT INTO friendships (requester_id, addressee_id, status) VALUES (?, ?, 'PENDING')", viewer, target)
                Relationship.OUTGOING
            }
            pair.accepted -> Relationship.FRIEND
            pair.requester == viewer -> Relationship.OUTGOING
            else -> {
                exec("UPDATE friendships SET status = 'ACCEPTED' WHERE requester_id = ? AND addressee_id = ?", target, viewer)
                Relationship.FRIEND
            }
        }
    }

    suspend fun accept(viewer: UUID, requester: UUID) = db.tx {
        val updated = exec(
            "UPDATE friendships SET status = 'ACCEPTED' WHERE requester_id = ? AND addressee_id = ? AND status = 'PENDING'",
            requester, viewer,
        )
        if (updated == 0) throw notFound("request_not_found")
    }

    /** Declines an incoming request or cancels an outgoing one. */
    suspend fun removeRequest(viewer: UUID, other: UUID) = db.tx {
        if (deletePair(viewer, other, status = "PENDING") == 0) throw notFound("request_not_found")
    }

    suspend fun unfriend(viewer: UUID, other: UUID) = db.tx {
        if (deletePair(viewer, other, status = "ACCEPTED") == 0) throw notFound("not_friends")
    }

    /** Blocks a user and removes any friendship or pending request between you. */
    suspend fun block(viewer: UUID, target: UUID) = db.tx {
        if (viewer == target) throw badRequest("cannot_block_self")
        queryOne("SELECT 1 FROM users WHERE id = ?", target) { true } ?: throw notFound("user_not_found")
        exec("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?) ON CONFLICT DO NOTHING", viewer, target)
        deletePair(viewer, target, status = null)
    }

    suspend fun unblock(viewer: UUID, target: UUID) = db.tx {
        exec("DELETE FROM blocks WHERE blocker_id = ? AND blocked_id = ?", viewer, target)
    }

    suspend fun blocked(viewer: UUID): List<PublicUser> = db.tx {
        query(
            """
            SELECT u.id, u.username, u.display_name, u.bio, NULL::uuid AS requester_id, NULL AS status
            FROM blocks b JOIN users u ON u.id = b.blocked_id
            WHERE b.blocker_id = ? ORDER BY u.username
            """.trimIndent(),
            viewer,
        ) { it.toPublicUser(viewer) }
    }

    /**
     * May [sender] start a new DM with [recipient]? Applies blocks and the recipient's
     * "who can DM me" setting. Phase 2's message endpoints call this before accepting a new DM.
     */
    suspend fun canStartDm(sender: UUID, recipient: UUID): Boolean = db.tx {
        if (sender == recipient || blockedEitherWay(sender, recipient)) return@tx false
        val policy = findUserById(recipient)?.dmPolicy ?: return@tx false
        val friends = pairRow(sender, recipient)?.accepted == true
        when (policy) {
            DmPolicy.EVERYONE -> true
            DmPolicy.FRIENDS_AND_SERVERS -> friends || queryOne(
                "SELECT 1 FROM server_members x JOIN server_members y ON y.server_id = x.server_id WHERE x.user_id = ? AND y.user_id = ? LIMIT 1",
                sender, recipient,
            ) { true } == true
            DmPolicy.FRIENDS -> friends
            DmPolicy.NOBODY -> false
        }
    }

    /** True if either user has blocked the other. */
    suspend fun isBlocked(a: UUID, b: UUID): Boolean = db.tx { blockedEitherWay(a, b) }

    /** A verified account that hasn't blocked (and isn't blocked by) [viewer]. */
    suspend fun isVisible(viewer: UUID, target: UUID): Boolean = db.tx { isVisibleTo(viewer, target) }

    private fun Connection.isVisibleTo(viewer: UUID, target: UUID): Boolean =
        queryOne("SELECT 1 FROM users WHERE id = ? AND email_verified", target) { true } == true &&
            !blockedEitherWay(viewer, target)

    private fun Connection.blockedEitherWay(a: UUID, b: UUID): Boolean =
        queryOne(
            "SELECT 1 FROM blocks WHERE (blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?)",
            a, b, b, a,
        ) { true } == true

    private fun Connection.pairRow(a: UUID, b: UUID): PairRow? =
        queryOne(
            """
            SELECT requester_id, status FROM friendships
            WHERE (requester_id = ? AND addressee_id = ?) OR (requester_id = ? AND addressee_id = ?)
            FOR UPDATE
            """.trimIndent(),
            a, b, b, a,
        ) { PairRow(it.uuid("requester_id"), it.getString("status") == "ACCEPTED") }

    private fun Connection.deletePair(a: UUID, b: UUID, status: String?): Int =
        exec(
            """
            DELETE FROM friendships
            WHERE ((requester_id = ? AND addressee_id = ?) OR (requester_id = ? AND addressee_id = ?))
              AND (?::text IS NULL OR status = ?)
            """.trimIndent(),
            a, b, b, a, status, status,
        )

    private fun ResultSet.toPublicUser(viewer: UUID): PublicUser {
        val requester = getObject("requester_id", UUID::class.java)
        val relationship = when {
            requester == null -> Relationship.NONE
            getString("status") == "ACCEPTED" -> Relationship.FRIEND
            requester == viewer -> Relationship.OUTGOING
            else -> Relationship.INCOMING
        }
        return PublicUser(uuid("id").toString(), getString("username"), getString("display_name"), getString("bio"), relationship)
    }

    private data class PairRow(val requester: UUID, val accepted: Boolean)
}
