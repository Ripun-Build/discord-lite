package com.discordlite.server.stories

import com.discordlite.server.common.badRequest
import com.discordlite.server.common.notFound
import com.discordlite.server.common.tooMany
import com.discordlite.server.db.Db
import com.discordlite.server.db.exec
import com.discordlite.server.db.query
import com.discordlite.server.db.queryOne
import com.discordlite.server.db.uuid
import com.discordlite.server.media.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.roundToInt

/** [seen] is whether *you* have seen it; [viewCount] is only filled in on your own stories. */
@Serializable
data class StoryDto(
    val id: String,
    val authorId: String,
    val caption: String? = null,
    val width: Int,
    val height: Int,
    val createdAt: Long,
    val expiresAt: Long,
    val seen: Boolean,
    val viewCount: Int? = null,
)

/** One person's active stories, oldest first (the order they play in). */
@Serializable
data class StoryAuthorDto(val userId: String, val username: String, val displayName: String, val stories: List<StoryDto>, val allSeen: Boolean)

@Serializable data class StoryViewerDto(val userId: String, val username: String, val displayName: String, val viewedAt: Long)

/**
 * Stories: a photo (with an optional caption) that your friends can see for 24 hours. After that
 * it stays in your private archive — only you can see it — until you delete it.
 *
 * Every upload is decoded and saved again as a plain JPEG here, whatever the phone sent: that
 * drops any leftover metadata (location, camera), shrinks huge photos, and means nothing but a
 * real picture is ever stored or served.
 */
class StoryService(private val db: Db, private val media: MediaStore) {

    suspend fun post(author: UUID, image: ByteArray, caption: String?): StoryDto {
        val text = caption?.trim()?.ifEmpty { null }
        if (text != null && text.length > MAX_CAPTION) throw badRequest("invalid_caption")
        if (image.isEmpty() || image.size > MAX_UPLOAD_BYTES) throw badRequest("invalid_image")
        val recent = db.tx {
            queryOne("SELECT COUNT(*) AS n FROM stories WHERE author_id = ? AND created_at > now() - interval '24 hours'", author) { it.getInt("n") }!!
        }
        if (recent >= MAX_PER_DAY) throw tooMany("too_many_stories")
        val clean = reencode(image) ?: throw badRequest("invalid_image")

        val id = UUID.randomUUID()
        val key = "stories/$id.jpg"
        media.put(key, clean.bytes, "image/jpeg")
        return try {
            db.tx {
                exec(
                    "INSERT INTO stories (id, author_id, media_key, width, height, caption) VALUES (?, ?, ?, ?, ?, ?)",
                    id, author, key, clean.width, clean.height, text,
                )
                storyRows("s.id = ?", listOf(id), author).single()
            }
        } catch (e: Exception) {
            media.delete(key) // don't leave an orphaned file behind
            throw e
        }
    }

    /** You (if you have a story up) first, then friends with unseen stories, then the rest. */
    suspend fun feed(user: UUID): List<StoryAuthorDto> = db.tx {
        val stories = storyRows(
            """s.created_at > now() - interval '24 hours' AND (s.author_id = ? OR s.author_id IN (${FRIENDS_OF}))""",
            listOf(user, user, user),
            user,
        )
        val authors = query(
            "SELECT id, username, display_name FROM users WHERE id = ANY(?)",
            createArrayOf("uuid", stories.map { UUID.fromString(it.authorId) }.distinct().toTypedArray()),
        ) { Triple(it.uuid("id").toString(), it.getString("username"), it.getString("display_name")) }.associateBy { it.first }
        stories.groupBy { it.authorId }.map { (authorId, list) ->
            val (_, username, name) = authors.getValue(authorId)
            val ordered = list.sortedBy { it.createdAt }
            StoryAuthorDto(authorId, username, name, ordered, ordered.all { it.seen || it.authorId == user.toString() })
        }.sortedWith(compareBy<StoryAuthorDto>({ it.userId != user.toString() }, { it.allSeen }, { -it.stories.last().createdAt }))
    }

    /** All your stories, newest first — including the ones only you can see now. */
    suspend fun archive(user: UUID): List<StoryDto> = db.tx {
        storyRows("s.author_id = ?", listOf(user), user).sortedByDescending { it.createdAt }
    }

    /** The photo, for its author always, and for their friends while the story is up. */
    suspend fun media(user: UUID, storyId: UUID): ByteArray {
        val key = db.tx { visibleStory(user, storyId)?.first } ?: throw notFound("story_not_found")
        return media.get(key) ?: throw notFound("story_not_found")
    }

    /** A friend opened it. Your own views aren't counted. */
    suspend fun view(user: UUID, storyId: UUID) = db.tx {
        val (_, author) = visibleStory(user, storyId) ?: throw notFound("story_not_found")
        if (author != user) exec("INSERT INTO story_views (story_id, viewer_id) VALUES (?, ?) ON CONFLICT DO NOTHING", storyId, user)
    }

    /** "Seen by" — only for the author. */
    suspend fun viewers(user: UUID, storyId: UUID): List<StoryViewerDto> = db.tx {
        requireAuthor(user, storyId)
        query(
            """
            SELECT u.id, u.username, u.display_name, v.viewed_at FROM story_views v JOIN users u ON u.id = v.viewer_id
            WHERE v.story_id = ? ORDER BY v.viewed_at DESC
            """.trimIndent(),
            storyId,
        ) { StoryViewerDto(it.uuid("id").toString(), it.getString("username"), it.getString("display_name"), it.getTimestamp("viewed_at").time) }
    }

    /** Deletes the story and its photo for good. */
    suspend fun delete(user: UUID, storyId: UUID) {
        val key = db.tx {
            val key = requireAuthor(user, storyId)
            exec("DELETE FROM stories WHERE id = ?", storyId)
            key
        }
        media.delete(key)
    }

    // --- Helpers ---

    /** (media key, author) if [user] may see the story right now; null otherwise. */
    private fun Connection.visibleStory(user: UUID, storyId: UUID): Pair<String, UUID>? =
        queryOne(
            """
            SELECT media_key, author_id FROM stories s
            WHERE s.id = ? AND (s.author_id = ? OR (s.created_at > now() - interval '24 hours' AND s.author_id IN (${FRIENDS_OF})))
            """.trimIndent(),
            storyId, user, user, user,
        ) { it.getString("media_key") to it.uuid("author_id") }

    private fun Connection.requireAuthor(user: UUID, storyId: UUID): String =
        queryOne("SELECT media_key FROM stories WHERE id = ? AND author_id = ?", storyId, user) { it.getString("media_key") }
            ?: throw notFound("story_not_found")

    private fun Connection.storyRows(where: String, params: List<Any?>, viewer: UUID): List<StoryDto> =
        query(
            """
            SELECT s.id, s.author_id, s.caption, s.width, s.height, s.created_at,
                   EXISTS (SELECT 1 FROM story_views v WHERE v.story_id = s.id AND v.viewer_id = ?) AS seen,
                   (SELECT COUNT(*) FROM story_views v WHERE v.story_id = s.id) AS views
            FROM stories s WHERE $where
            """.trimIndent(),
            viewer, *params.toTypedArray(),
        ) { it.toStory(viewer) }

    private fun ResultSet.toStory(viewer: UUID): StoryDto {
        val author = uuid("author_id")
        val created = getTimestamp("created_at").time
        return StoryDto(
            id = uuid("id").toString(),
            authorId = author.toString(),
            caption = getString("caption"),
            width = getInt("width"),
            height = getInt("height"),
            createdAt = created,
            expiresAt = created + LIFETIME_MS,
            seen = getBoolean("seen"),
            viewCount = if (author == viewer) getInt("views") else null,
        )
    }

    private class CleanImage(val bytes: ByteArray, val width: Int, val height: Int)

    /** Decodes any common image format and writes a fresh, metadata-free JPEG (max 1920 px). */
    private suspend fun reencode(input: ByteArray): CleanImage? = withContext(Dispatchers.Default) {
        runCatching {
            ImageIO.createImageInputStream(ByteArrayInputStream(input)).use { stream ->
                val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: return@runCatching null
                try {
                    reader.input = stream
                    // Check the size before decoding, so a tiny file claiming to be enormous can't eat memory.
                    val w = reader.getWidth(0)
                    val h = reader.getHeight(0)
                    if (w <= 0 || h <= 0 || w.toLong() * h > MAX_PIXELS) return@runCatching null
                    val source = reader.read(0)
                    val scale = minOf(1.0, MAX_SIDE.toDouble() / max(w, h))
                    val outW = max(1, (w * scale).roundToInt())
                    val outH = max(1, (h * scale).roundToInt())
                    val out = BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB)
                    out.createGraphics().apply {
                        setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                        drawImage(source, 0, 0, outW, outH, java.awt.Color.BLACK, null)
                        dispose()
                    }
                    val bytes = ByteArrayOutputStream()
                    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
                    ImageIO.createImageOutputStream(bytes).use { output ->
                        writer.output = output
                        val param = writer.defaultWriteParam.apply {
                            compressionMode = ImageWriteParam.MODE_EXPLICIT
                            compressionQuality = 0.85f
                        }
                        writer.write(null, IIOImage(out, null, null), param)
                    }
                    writer.dispose()
                    CleanImage(bytes.toByteArray(), outW, outH)
                } finally {
                    reader.dispose()
                }
            }
        }.getOrNull()
    }

    companion object {
        const val LIFETIME_MS = 24 * 3600_000L
        const val MAX_CAPTION = 200
        const val MAX_UPLOAD_BYTES = 12 * 1024 * 1024
        const val MAX_PER_DAY = 30
        const val MAX_SIDE = 1920
        const val MAX_PIXELS = 50_000_000L

        /** Ids of [user]'s friends (pass the user id twice). Blocking someone also unfriends them. */
        private const val FRIENDS_OF = """
            SELECT CASE WHEN f.requester_id = ? THEN f.addressee_id ELSE f.requester_id END FROM friendships f
            WHERE f.status = 'ACCEPTED' AND ? IN (f.requester_id, f.addressee_id)"""
    }
}
