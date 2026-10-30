package com.discordlite.app.stories

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.discordlite.app.data.remote.DiscordLiteApi
import com.discordlite.app.data.remote.StoryAuthorResponse
import com.discordlite.app.data.remote.StoryResponse
import com.discordlite.app.data.remote.StoryViewerResponse
import com.discordlite.app.data.remote.apiCall
import com.discordlite.app.data.remote.apiCallUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Stories: photos your friends can see for 24 hours (then only you, in your archive). Not end-to-end
 * encrypted. Photos are fetched through our API (never a public link) and cached in memory only.
 */
class StoriesRepository(private val context: Context, private val api: DiscordLiteApi) {
    private val _feed = MutableStateFlow<List<StoryAuthorResponse>>(emptyList())

    /** You first (if you have a story up), then friends with unseen stories, then the rest. */
    val feed: StateFlow<List<StoryAuthorResponse>> = _feed.asStateFlow()

    /** ~24 MB of decoded photos, so swiping back and forth doesn't re-download. */
    private val images = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun refresh() {
        _feed.value = apiCall { api.stories() }
    }

    suspend fun archive(): List<StoryResponse> = apiCall { api.storyArchive() }

    /** Prepares the photo on the phone, uploads it, and puts it at the front of your stories. */
    suspend fun post(uri: Uri, caption: String) {
        val jpeg = withContext(Dispatchers.Default) { prepare(uri) }
        val part = MultipartBody.Part.createFormData("image", "story.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
        val text = caption.trim().takeIf { it.isNotEmpty() }?.toRequestBody("text/plain".toMediaType())
        apiCall { api.postStory(part, text) }
        runCatching { refresh() }
    }

    /** Marks it seen here at once and tells the server (which counts it for the author). */
    suspend fun markSeen(story: StoryResponse) {
        if (story.seen) return
        _feed.update { authors ->
            authors.map { author ->
                if (author.userId != story.authorId) return@map author
                val stories = author.stories.map { if (it.id == story.id) it.copy(seen = true) else it }
                author.copy(stories = stories, allSeen = stories.all { it.seen })
            }
        }
        runCatching { apiCallUnit { api.viewStory(story.id) } }
    }

    suspend fun viewers(storyId: String): List<StoryViewerResponse> = apiCall { api.storyViewers(storyId) }

    suspend fun delete(storyId: String) {
        apiCallUnit { api.deleteStory(storyId) }
        images.remove(storyId)
        _feed.update { authors ->
            authors.map { it.copy(stories = it.stories.filterNot { s -> s.id == storyId }) }.filter { it.stories.isNotEmpty() }
        }
    }

    /** The photo, from memory if we've shown it before. */
    suspend fun image(storyId: String): ImageBitmap {
        images.get(storyId)?.let { return it.asImageBitmap() }
        val bytes = apiCall { api.storyMedia(storyId).bytes() }
        val bitmap = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } ?: error("Not an image")
        images.put(storyId, bitmap)
        return bitmap.asImageBitmap()
    }

    fun clear() {
        _feed.value = emptyList()
        images.evictAll()
    }

    /**
     * Turns the picked photo upright, scales it to at most 1920 px and re-saves it as JPEG.
     * Writing a fresh JPEG from pixels leaves the original's metadata (location, camera) behind.
     * (The server re-saves it again, so this is belt and braces — and a much smaller upload.)
     */
    private fun prepare(uri: Uri): ByteArray {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val longest = max(info.size.width, info.size.height)
            if (longest > MAX_SIDE) {
                val scale = MAX_SIDE.toFloat() / longest
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
    }

    private companion object {
        const val MAX_SIDE = 1920
    }
}
