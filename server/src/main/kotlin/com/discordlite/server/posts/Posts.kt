package com.discordlite.server.posts

import com.discordlite.server.common.badRequest
import com.discordlite.server.common.notFound
import com.discordlite.server.common.tooMany
import com.discordlite.server.db.Db
import com.discordlite.server.db.exec
import com.discordlite.server.db.query
import com.discordlite.server.db.queryOne
import com.discordlite.server.db.uuid
import com.discordlite.server.media.ImageCleaner
import com.discordlite.server.media.MediaStore
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

@Serializable data class PostAuthorDto(val id: String, val username: String, val displayName: String)

@Serializable data class PostMediaDto(val width: Int, val height: Int)

@Serializable
data class PostDto(
    val id: String,
    val author: PostAuthorDto,
    val caption: String? = null,
    val game: String? = null,
    val media: List<PostMediaDto>,
    val likeCount: Int,
    val commentCount: Int,
    val liked: Boolean,
    val saved: Boolean,
    val createdAt: Long,
)

@Serializable data class LikeResponse(val liked: Boolean, val likeCount: Int)

/** [parentId] is set on replies, and always points at a top-level comment. */
@Serializable
data class CommentDto(
    val id: String,
    val parentId: String? = null,
    val author: PostAuthorDto,
    val body: String,
    val createdAt: Long,
    val canDelete: Boolean,
)

@Serializable data class NewCommentRequest(val body: String, val parentId: String? = null)

/** Suggestions for the game tag. Anything else can be typed; only spelling of these is tidied up. */
object GameCatalog {
    const val MAX_NAME = 40

    val games = listOf(
        "Apex Legends", "BGMI", "Brawl Stars", "Call of Duty: Mobile", "Call of Duty: Warzone", "Clash Royale",
        "Counter-Strike 2", "Dota 2", "EA Sports FC", "Elden Ring", "Fortnite", "Free Fire", "Genshin Impact",
        "GTA V", "Honkai: Star Rail", "League of Legends", "Marvel Rivals", "Minecraft", "Mobile Legends",
        "Overwatch 2", "PUBG: Battlegrounds", "Roblox", "Rocket League", "Valorant",
    )

    /** "valorant " becomes "Valorant"; a game we don't list is kept as typed. Blank means no tag. */
    fun normalise(raw: String?): String? {
        val name = raw?.filterNot { it.isISOControl() }?.trim()?.replace(Regex("\\s+"), " ")?.ifEmpty { null } ?: return null
        if (name.length > MAX_NAME) throw badRequest("invalid_game")
        return games.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
    }
}

/**
 * Posts: up to five photos with a caption and a game tag. Not end-to-end encrypted.
 *
 * Who can see a post: its author; anyone signed in if the author's account is public; only accepted
 * followers if it's private. A block either way hides everything. Every read goes through [VISIBLE]
 * or [requireVisible], and photos are only served through the API after the same check.
 */
class PostService(private val db: Db, private val media: MediaStore) {

    suspend fun create(author: UUID, images: List<ByteArray>, caption: String?, game: String?): PostDto {
        val text = caption?.trim()?.ifEmpty { null }
        if (text != null && text.length > MAX_CAPTION) throw badRequest("invalid_caption")
        val tag = GameCatalog.normalise(game)
        if (images.size !in 1..MAX_PHOTOS || images.any { it.isEmpty() || it.size > ImageCleaner.MAX_UPLOAD_BYTES }) throw badRequest("invalid_image")
        val recent = db.tx {
            queryOne("SELECT COUNT(*) AS n FROM posts WHERE author_id = ? AND created_at > now() - interval '24 hours'", author) { it.getInt("n") }!!
        }
        if (recent >= MAX_PER_DAY) throw tooMany("too_many_posts")
        val clean = images.map { ImageCleaner.clean(it) ?: throw badRequest("invalid_image") }

        val id = UUID.randomUUID()
        val keys = clean.indices.map { "posts/$id/$it.jpg" }
        try {
            clean.forEachIndexed { i, image -> media.put(keys[i], image.bytes, "image/jpeg") }
            return db.tx {
                exec("INSERT INTO posts (id, author_id, caption, game) VALUES (?, ?, ?, ?)", id, author, text, tag)
                clean.forEachIndexed { i, image ->
                    exec(
                        "INSERT INTO post_media (post_id, position, media_key, width, height) VALUES (?, ?, ?, ?, ?)",
                        id, i, keys[i], image.width, image.height,
                    )
                }
                postRows("p.id = ?", listOf(id), author, limit = 1).single()
            }
        } catch (e: Exception) {
            keys.forEach { runCatching { media.delete(it) } } // don't leave orphaned files behind
            throw e
        }
    }

    /** Posts from you and the people you follow, newest first. */
    suspend fun feed(user: UUID, before: UUID?): List<PostDto> = db.tx {
        page(
            "(p.author_id = ? OR p.author_id IN (SELECT followee_id FROM follows WHERE follower_id = ? AND status = 'ACCEPTED'))",
            listOf(user, user), user, before,
        )
    }

    suspend fun byAuthor(user: UUID, author: UUID, before: UUID?): List<PostDto> = db.tx {
        page("p.author_id = ?", listOf(author), user, before)
    }

    /** Your saved posts. One you can no longer see (say, the account went private) is left out. */
    suspend fun saved(user: UUID, before: UUID?): List<PostDto> = db.tx {
        page("EXISTS (SELECT 1 FROM post_saves s WHERE s.post_id = p.id AND s.user_id = ?)", listOf(user), user, before)
    }

    suspend fun get(user: UUID, postId: UUID): PostDto = db.tx {
        postRows("p.id = ?", listOf(postId), user, limit = 1).singleOrNull() ?: throw notFound("post_not_found")
    }

    suspend fun photo(user: UUID, postId: UUID, position: Int): ByteArray {
        val key = db.tx {
            queryOne(
                """
                SELECT m.media_key FROM post_media m
                JOIN posts p ON p.id = m.post_id JOIN users u ON u.id = p.author_id
                WHERE m.post_id = ? AND m.position = ? AND $VISIBLE
                """.trimIndent(),
                postId, position, user, user, user, user,
            ) { it.getString("media_key") }
        } ?: throw notFound("post_not_found")
        return media.get(key) ?: throw notFound("post_not_found")
    }

    /** Deletes the post, its photos, likes and comments for good. Only its author can. */
    suspend fun delete(user: UUID, postId: UUID) {
        val keys = db.tx {
            queryOne("SELECT 1 FROM posts WHERE id = ? AND author_id = ?", postId, user) { true } ?: throw notFound("post_not_found")
            val keys = query("SELECT media_key FROM post_media WHERE post_id = ?", postId) { it.getString("media_key") }
            exec("DELETE FROM posts WHERE id = ?", postId)
            keys
        }
        keys.forEach { media.delete(it) }
    }

    suspend fun setLiked(user: UUID, postId: UUID, liked: Boolean): LikeResponse = db.tx {
        requireVisible(user, postId)
        if (liked) {
            exec("INSERT INTO post_likes (post_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING", postId, user)
        } else {
            exec("DELETE FROM post_likes WHERE post_id = ? AND user_id = ?", postId, user)
        }
        LikeResponse(liked, queryOne("SELECT COUNT(*) AS n FROM post_likes WHERE post_id = ?", postId) { it.getInt("n") }!!)
    }

    suspend fun setSaved(user: UUID, postId: UUID, saved: Boolean) = db.tx {
        if (saved) {
            requireVisible(user, postId)
            exec("INSERT INTO post_saves (user_id, post_id) VALUES (?, ?) ON CONFLICT DO NOTHING", user, postId)
        } else {
            exec("DELETE FROM post_saves WHERE user_id = ? AND post_id = ?", user, postId)
        }
    }

    /** Oldest first; replies come with their parentId. Comments from anyone blocked either way are hidden. */
    suspend fun comments(user: UUID, postId: UUID): List<CommentDto> = db.tx {
        val postAuthor = requireVisible(user, postId)
        val rows = query(
            """
            SELECT c.id, c.parent_id, c.author_id, c.body, c.created_at, u.username, u.display_name
            FROM post_comments c JOIN users u ON u.id = c.author_id
            WHERE c.post_id = ? AND NOT EXISTS (
                SELECT 1 FROM blocks b WHERE (b.blocker_id = ? AND b.blocked_id = c.author_id) OR (b.blocker_id = c.author_id AND b.blocked_id = ?)
            )
            ORDER BY c.created_at, c.id
            LIMIT $MAX_COMMENTS
            """.trimIndent(),
            postId, user, user,
        ) { it.toComment(user, postAuthor) }
        val shown = rows.mapTo(HashSet()) { it.id }
        rows.filter { it.parentId == null || it.parentId in shown } // a reply never appears without its comment
    }

    /** A reply to a reply joins the same thread, so threads stay one level deep. */
    suspend fun addComment(user: UUID, postId: UUID, request: NewCommentRequest): CommentDto {
        val body = request.body.trim()
        if (body.length !in 1..MAX_COMMENT) throw badRequest("invalid_comment")
        val parent = request.parentId?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: throw notFound("comment_not_found") }
        return db.tx {
            val postAuthor = requireVisible(user, postId)
            val thread = parent?.let { id ->
                queryOne("SELECT id, parent_id FROM post_comments WHERE id = ? AND post_id = ?", id, postId) {
                    it.getObject("parent_id", UUID::class.java) ?: it.uuid("id")
                } ?: throw notFound("comment_not_found")
            }
            val id = UUID.randomUUID()
            exec("INSERT INTO post_comments (id, post_id, author_id, parent_id, body) VALUES (?, ?, ?, ?, ?)", id, postId, user, thread, body)
            queryOne(
                """
                SELECT c.id, c.parent_id, c.author_id, c.body, c.created_at, u.username, u.display_name
                FROM post_comments c JOIN users u ON u.id = c.author_id WHERE c.id = ?
                """.trimIndent(),
                id,
            ) { it.toComment(user, postAuthor) }!!
        }
    }

    /** You can delete your own comments and any comment on your post. Replies go with their comment. */
    suspend fun deleteComment(user: UUID, postId: UUID, commentId: UUID) = db.tx {
        val allowed = queryOne(
            """
            SELECT 1 FROM post_comments c JOIN posts p ON p.id = c.post_id
            WHERE c.id = ? AND c.post_id = ? AND (c.author_id = ? OR p.author_id = ?)
            """.trimIndent(),
            commentId, postId, user, user,
        ) { true } == true
        if (!allowed) throw notFound("comment_not_found")
        exec("DELETE FROM post_comments WHERE id = ?", commentId)
    }

    // --- Helpers ---

    /** The post's author if [user] may see it; 404 otherwise, so hidden posts look like missing ones. */
    private fun Connection.requireVisible(user: UUID, postId: UUID): UUID =
        queryOne(
            "SELECT p.author_id FROM posts p JOIN users u ON u.id = p.author_id WHERE p.id = ? AND $VISIBLE",
            postId, user, user, user, user,
        ) { it.uuid("author_id") } ?: throw notFound("post_not_found")

    private fun Connection.page(where: String, params: List<Any?>, viewer: UUID, before: UUID?): List<PostDto> {
        val cursor = if (before != null) " AND (p.created_at, p.id) < (SELECT created_at, id FROM posts WHERE id = ?)" else ""
        return postRows(where + cursor, params + listOfNotNull(before), viewer, PAGE_SIZE)
    }

    private fun Connection.postRows(where: String, params: List<Any?>, viewer: UUID, limit: Int): List<PostDto> {
        val rows = query(
            """
            SELECT p.id, p.caption, p.game, p.created_at, p.author_id, u.username, u.display_name,
                   (SELECT COUNT(*) FROM post_likes l WHERE l.post_id = p.id) AS likes,
                   (SELECT COUNT(*) FROM post_comments c WHERE c.post_id = p.id) AS comments,
                   EXISTS (SELECT 1 FROM post_likes l WHERE l.post_id = p.id AND l.user_id = ?) AS liked,
                   EXISTS (SELECT 1 FROM post_saves s WHERE s.post_id = p.id AND s.user_id = ?) AS saved
            FROM posts p JOIN users u ON u.id = p.author_id
            WHERE $VISIBLE AND $where
            ORDER BY p.created_at DESC, p.id DESC
            LIMIT ?
            """.trimIndent(),
            viewer, viewer, viewer, viewer, viewer, viewer, *params.toTypedArray(), limit,
        ) { it.toPost() }
        if (rows.isEmpty()) return rows
        val photos = query(
            "SELECT post_id, width, height FROM post_media WHERE post_id = ANY(?) ORDER BY position",
            createArrayOf("uuid", rows.map { UUID.fromString(it.id) }.toTypedArray()),
        ) { it.uuid("post_id").toString() to PostMediaDto(it.getInt("width"), it.getInt("height")) }
            .groupBy({ it.first }, { it.second })
        return rows.map { it.copy(media = photos[it.id].orEmpty()) }
    }

    private fun ResultSet.toAuthor(column: String) = PostAuthorDto(uuid(column).toString(), getString("username"), getString("display_name"))

    private fun ResultSet.toPost() = PostDto(
        id = uuid("id").toString(),
        author = toAuthor("author_id"),
        caption = getString("caption"),
        game = getString("game"),
        media = emptyList(),
        likeCount = getInt("likes"),
        commentCount = getInt("comments"),
        liked = getBoolean("liked"),
        saved = getBoolean("saved"),
        createdAt = getTimestamp("created_at").time,
    )

    private fun ResultSet.toComment(viewer: UUID, postAuthor: UUID): CommentDto {
        val author = uuid("author_id")
        return CommentDto(
            id = uuid("id").toString(),
            parentId = getObject("parent_id", UUID::class.java)?.toString(),
            author = toAuthor("author_id"),
            body = getString("body"),
            createdAt = getTimestamp("created_at").time,
            canDelete = author == viewer || postAuthor == viewer,
        )
    }

    companion object {
        const val MAX_PHOTOS = 5
        const val MAX_CAPTION = 500
        const val MAX_COMMENT = 500
        const val MAX_COMMENTS = 500
        const val MAX_PER_DAY = 30
        const val PAGE_SIZE = 20

        /** Can the viewer see posts `p` by author `u`? Takes the viewer's id four times. */
        private const val VISIBLE = """(p.author_id = ? OR (
            NOT EXISTS (SELECT 1 FROM blocks b WHERE (b.blocker_id = ? AND b.blocked_id = p.author_id) OR (b.blocker_id = p.author_id AND b.blocked_id = ?))
            AND (NOT u.is_private OR EXISTS (SELECT 1 FROM follows f WHERE f.follower_id = ? AND f.followee_id = p.author_id AND f.status = 'ACCEPTED'))
        ))"""
    }
}
