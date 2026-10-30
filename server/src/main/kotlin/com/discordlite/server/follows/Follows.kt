package com.discordlite.server.follows

import com.discordlite.server.common.badRequest
import com.discordlite.server.common.conflict
import com.discordlite.server.common.forbidden
import com.discordlite.server.common.notFound
import com.discordlite.server.db.Db
import com.discordlite.server.db.exec
import com.discordlite.server.db.query
import com.discordlite.server.db.queryOne
import com.discordlite.server.db.uuid
import com.discordlite.server.social.Relationship
import com.discordlite.server.social.SocialService
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/** Where you stand with someone: not following, asked to follow (private account), or following. */
@Serializable
enum class FollowState { NONE, REQUESTED, FOLLOWING }

/** Someone's profile page. [requestCount] (people waiting to follow you) is only set on your own. */
@Serializable
data class UserProfileDto(
    val id: String,
    val username: String,
    val displayName: String,
    val bio: String,
    val relationship: Relationship,
    val isPrivate: Boolean,
    val followState: FollowState,
    val followsYou: Boolean,
    val canSeePosts: Boolean,
    val postCount: Int,
    val followerCount: Int,
    val followingCount: Int,
    val requestCount: Int? = null,
)

/** A row in a followers, following or follow requests list. [followState] is yours towards them. */
@Serializable
data class FollowUserDto(val id: String, val username: String, val displayName: String, val followState: FollowState)

@Serializable data class FollowResponse(val state: FollowState)

/**
 * Following: anyone can follow a public account straight away; a private account has to approve.
 * Friends follow each other automatically, and that lasts as long as the friendship does, so
 * neither side can undo it on its own. Blocking removes follows both ways.
 */
class FollowService(private val db: Db, private val social: SocialService) {

    suspend fun profile(viewer: UUID, target: UUID): UserProfileDto {
        val user = social.profile(viewer, target) // 404 if a block is in place
        return db.tx {
            val mine = viewer == target
            val status = followStatus(viewer, target)
            val isPrivate = queryOne("SELECT is_private FROM users WHERE id = ?", target) { it.getBoolean("is_private") }!!
            UserProfileDto(
                id = user.id,
                username = user.username,
                displayName = user.displayName,
                bio = user.bio,
                relationship = user.relationship,
                isPrivate = isPrivate,
                followState = status.toState(),
                followsYou = followStatus(target, viewer) == "ACCEPTED",
                canSeePosts = mine || !isPrivate || status == "ACCEPTED",
                postCount = count("SELECT COUNT(*) AS n FROM posts WHERE author_id = ?", target),
                followerCount = count("SELECT COUNT(*) AS n FROM follows WHERE followee_id = ? AND status = 'ACCEPTED'", target),
                followingCount = count("SELECT COUNT(*) AS n FROM follows WHERE follower_id = ? AND status = 'ACCEPTED'", target),
                requestCount = if (mine) count("SELECT COUNT(*) AS n FROM follows WHERE followee_id = ? AND status = 'PENDING'", target) else null,
            )
        }
    }

    suspend fun follow(viewer: UUID, target: UUID): FollowState {
        if (viewer == target) throw badRequest("cannot_follow_self")
        social.profile(viewer, target) // 404 if a block is in place
        return db.tx {
            val isPrivate = queryOne("SELECT is_private FROM users WHERE id = ?", target) { it.getBoolean("is_private") }!!
            exec(
                "INSERT INTO follows (follower_id, followee_id, status) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                viewer, target, if (isPrivate) "PENDING" else "ACCEPTED",
            )
            followStatus(viewer, target).toState()
        }
    }

    /** Stops following them, or takes back a request. */
    suspend fun unfollow(viewer: UUID, target: UUID) = db.tx {
        if (areFriends(viewer, target)) throw conflict("friends_follow_each_other")
        exec("DELETE FROM follows WHERE follower_id = ? AND followee_id = ?", viewer, target)
    }

    /** Removes someone from your followers. They can ask again unless you block them. */
    suspend fun removeFollower(viewer: UUID, follower: UUID) = db.tx {
        if (areFriends(viewer, follower)) throw conflict("friends_follow_each_other")
        exec("DELETE FROM follows WHERE follower_id = ? AND followee_id = ?", follower, viewer)
    }

    suspend fun requests(viewer: UUID): List<FollowUserDto> = db.tx {
        userRows("f.followee_id = ? AND f.status = 'PENDING'", "f.follower_id", viewer, viewer)
    }

    suspend fun acceptRequest(viewer: UUID, follower: UUID) = db.tx {
        val updated = exec(
            "UPDATE follows SET status = 'ACCEPTED' WHERE follower_id = ? AND followee_id = ? AND status = 'PENDING'",
            follower, viewer,
        )
        if (updated == 0) throw notFound("request_not_found")
    }

    suspend fun declineRequest(viewer: UUID, follower: UUID) = db.tx {
        val deleted = exec("DELETE FROM follows WHERE follower_id = ? AND followee_id = ? AND status = 'PENDING'", follower, viewer)
        if (deleted == 0) throw notFound("request_not_found")
    }

    /** Who follows [target]. On a private account only you and your followers can look. */
    suspend fun followers(viewer: UUID, target: UUID): List<FollowUserDto> {
        requireListVisible(viewer, target)
        return db.tx { userRows("f.followee_id = ? AND f.status = 'ACCEPTED'", "f.follower_id", viewer, target) }
    }

    /** Who [target] follows. On a private account only you and your followers can look. */
    suspend fun following(viewer: UUID, target: UUID): List<FollowUserDto> {
        requireListVisible(viewer, target)
        return db.tx { userRows("f.follower_id = ? AND f.status = 'ACCEPTED'", "f.followee_id", viewer, target) }
    }

    private suspend fun requireListVisible(viewer: UUID, target: UUID) {
        if (!profile(viewer, target).canSeePosts) throw forbidden("private_account")
    }

    private fun Connection.userRows(where: String, userColumn: String, viewer: UUID, subject: UUID): List<FollowUserDto> =
        query(
            """
            SELECT u.id, u.username, u.display_name, mine.status AS my_status
            FROM follows f
            JOIN users u ON u.id = $userColumn
            LEFT JOIN follows mine ON mine.follower_id = ? AND mine.followee_id = u.id
            WHERE $where AND NOT EXISTS (
                SELECT 1 FROM blocks b WHERE (b.blocker_id = ? AND b.blocked_id = u.id) OR (b.blocker_id = u.id AND b.blocked_id = ?)
            )
            ORDER BY f.created_at DESC
            LIMIT $LIST_LIMIT
            """.trimIndent(),
            viewer, subject, viewer, viewer,
        ) { it.toFollowUser() }

    private fun ResultSet.toFollowUser() =
        FollowUserDto(uuid("id").toString(), getString("username"), getString("display_name"), getString("my_status").toState())

    private fun Connection.followStatus(follower: UUID, followee: UUID): String? =
        queryOne("SELECT status FROM follows WHERE follower_id = ? AND followee_id = ?", follower, followee) { it.getString("status") }

    private fun Connection.areFriends(a: UUID, b: UUID): Boolean =
        queryOne(
            """
            SELECT 1 FROM friendships
            WHERE status = 'ACCEPTED' AND ((requester_id = ? AND addressee_id = ?) OR (requester_id = ? AND addressee_id = ?))
            """.trimIndent(),
            a, b, b, a,
        ) { true } == true

    private fun Connection.count(sql: String, id: UUID): Int = queryOne(sql, id) { it.getInt("n") }!!

    private fun String?.toState() = when (this) {
        "ACCEPTED" -> FollowState.FOLLOWING
        "PENDING" -> FollowState.REQUESTED
        else -> FollowState.NONE
    }

    private companion object {
        const val LIST_LIMIT = 200
    }
}

/** New friends follow each other, even on private accounts, whatever was there before. */
fun Connection.followEachOther(a: UUID, b: UUID) {
    listOf(a to b, b to a).forEach { (follower, followee) ->
        exec(
            """
            INSERT INTO follows (follower_id, followee_id, status) VALUES (?, ?, 'ACCEPTED')
            ON CONFLICT (follower_id, followee_id) DO UPDATE SET status = 'ACCEPTED'
            """.trimIndent(),
            follower, followee,
        )
    }
}

/** Unfriending or blocking clears follows and requests in both directions. */
fun Connection.dropFollows(a: UUID, b: UUID) {
    exec("DELETE FROM follows WHERE (follower_id = ? AND followee_id = ?) OR (follower_id = ? AND followee_id = ?)", a, b, b, a)
}

/** An account going public lets in everyone who was waiting. */
fun Connection.acceptPendingFollows(user: UUID) {
    exec("UPDATE follows SET status = 'ACCEPTED' WHERE followee_id = ? AND status = 'PENDING'", user)
}
