package com.discordlite.app.posts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.discordlite.app.data.remote.CommentResponse
import com.discordlite.app.data.remote.DiscordLiteApi
import com.discordlite.app.data.remote.FollowState
import com.discordlite.app.data.remote.FollowUserResponse
import com.discordlite.app.data.remote.NewCommentBody
import com.discordlite.app.data.remote.PostResponse
import com.discordlite.app.data.remote.UserProfileResponse
import com.discordlite.app.data.remote.apiCall
import com.discordlite.app.data.remote.apiCallUnit
import com.discordlite.app.media.PhotoPrep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Posts and followers. Not end-to-end encrypted. Photos come through our API (never a public link)
 * and are cached in memory only.
 *
 * [posts] holds the latest copy of every post we've seen, by id. Screens keep lists of ids and read
 * the post from there, so a like in one place shows up everywhere straight away.
 */
class PostsRepository(private val context: Context, private val api: DiscordLiteApi, private val scope: CoroutineScope) {
    private val _posts = MutableStateFlow<Map<String, PostResponse>>(emptyMap())
    val posts: StateFlow<Map<String, PostResponse>> = _posts.asStateFlow()

    private val _feed = MutableStateFlow<List<String>>(emptyList())

    /** Ids of the posts from you and the people you follow, newest first. */
    val feed: StateFlow<List<String>> = _feed.asStateFlow()

    private var feedHasMore = true
    private var games: List<String>? = null

    /** ~48 MB of decoded photos, so scrolling back doesn't re-download. */
    private val images = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun keep(list: List<PostResponse>): List<String> {
        _posts.update { known -> known + list.associateBy { it.id } }
        return list.map { it.id }
    }

    suspend fun refreshFeed() {
        val page = apiCall { api.postFeed() }
        feedHasMore = page.size >= PAGE_SIZE
        _feed.value = keep(page)
    }

    suspend fun loadMoreFeed() {
        val last = _feed.value.lastOrNull() ?: return
        if (!feedHasMore) return
        val page = apiCall { api.postFeed(last) }
        feedHasMore = page.size >= PAGE_SIZE
        val ids = keep(page)
        _feed.update { (it + ids).distinct() }
    }

    /** One page of someone's posts; pass the last id you have to get the next. */
    suspend fun byUser(userId: String, before: String? = null): List<String> = keep(apiCall { api.userPosts(userId, before) })

    suspend fun saved(before: String? = null): List<String> = keep(apiCall { api.savedPosts(before) })

    /** The post, loading it if we haven't seen it. Fails if it's gone or you can't see it. */
    suspend fun load(postId: String): PostResponse = apiCall { api.post(postId) }.also { keep(listOf(it)) }

    /** Suggestions for the game tag. */
    suspend fun games(): List<String> = games ?: apiCall { api.games() }.also { games = it }

    /** Prepares each photo on the phone, uploads them and puts the post at the top of your feed. */
    suspend fun create(uris: List<Uri>, caption: String, game: String) {
        val parts = uris.map { uri ->
            val jpeg = withContext(Dispatchers.Default) { PhotoPrep.prepare(context, uri) }
            MultipartBody.Part.createFormData("image", "photo.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
        }
        val plain = "text/plain".toMediaType()
        val post = apiCall {
            api.createPost(parts, caption.trim().takeIf { it.isNotEmpty() }?.toRequestBody(plain), game.trim().takeIf { it.isNotEmpty() }?.toRequestBody(plain))
        }
        val ids = keep(listOf(post))
        _feed.update { (ids + it).distinct() }
    }

    suspend fun delete(postId: String) {
        apiCallUnit { api.deletePost(postId) }
        _feed.update { it - postId }
        _posts.update { it - postId }
        repeat(MAX_PHOTOS) { images.remove("$postId/$it") }
    }

    /** Shown straight away, and put back if the server says no. */
    suspend fun setLiked(postId: String, liked: Boolean) {
        val before = _posts.value[postId] ?: return
        if (before.liked == liked) return
        change(postId) { it.copy(liked = liked, likeCount = (it.likeCount + if (liked) 1 else -1).coerceAtLeast(0)) }
        runCatching { apiCall { if (liked) api.likePost(postId) else api.unlikePost(postId) } }
            .onSuccess { result -> change(postId) { it.copy(liked = result.liked, likeCount = result.likeCount) } }
            .onFailure { change(postId) { it.copy(liked = before.liked, likeCount = before.likeCount) } }
    }

    /** Shown straight away, and put back if the server says no. */
    suspend fun setSaved(postId: String, saved: Boolean) {
        change(postId) { it.copy(saved = saved) }
        runCatching { apiCallUnit { if (saved) api.savePost(postId) else api.unsavePost(postId) } }
            .onFailure { change(postId) { it.copy(saved = !saved) } }
    }

    suspend fun comments(postId: String): List<CommentResponse> = apiCall { api.comments(postId) }

    suspend fun addComment(postId: String, body: String, parentId: String?): CommentResponse =
        apiCall { api.addComment(postId, NewCommentBody(body, parentId)) }.also {
            change(postId) { post -> post.copy(commentCount = post.commentCount + 1) }
        }

    /** [removed] counts the comment plus any replies that go with it. */
    suspend fun deleteComment(postId: String, commentId: String, removed: Int) {
        apiCallUnit { api.deleteComment(postId, commentId) }
        change(postId) { it.copy(commentCount = (it.commentCount - removed).coerceAtLeast(0)) }
    }

    private fun change(postId: String, edit: (PostResponse) -> PostResponse) {
        _posts.update { known -> known[postId]?.let { known + (postId to edit(it)) } ?: known }
    }

    /** A photo, from memory if we've shown it before. */
    suspend fun image(postId: String, position: Int): ImageBitmap {
        val key = "$postId/$position"
        images.get(key)?.let { return it.asImageBitmap() }
        val bytes = apiCall { api.postMedia(postId, position).bytes() }
        val bitmap = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } ?: error("Not an image")
        images.put(key, bitmap)
        return bitmap.asImageBitmap()
    }

    // --- Followers ---

    suspend fun profile(userId: String): UserProfileResponse = apiCall { api.userProfile(userId) }

    suspend fun follow(userId: String): FollowState = apiCall { api.follow(userId) }.state.also { refreshFeedSoon() }

    suspend fun unfollow(userId: String) {
        apiCallUnit { api.unfollow(userId) }
        refreshFeedSoon()
    }

    suspend fun removeFollower(userId: String) = apiCallUnit { api.removeFollower(userId) }

    suspend fun followers(userId: String): List<FollowUserResponse> = apiCall { api.followers(userId) }

    suspend fun following(userId: String): List<FollowUserResponse> = apiCall { api.following(userId) }

    suspend fun followRequests(): List<FollowUserResponse> = apiCall { api.followRequests() }

    suspend fun acceptFollow(userId: String) = apiCallUnit { api.acceptFollow(userId) }

    suspend fun declineFollow(userId: String) = apiCallUnit { api.declineFollow(userId) }

    /** Who you follow changed, so the feed did too. */
    fun refreshFeedSoon() {
        scope.launch { runCatching { refreshFeed() } }
    }

    fun clear() {
        _feed.value = emptyList()
        _posts.value = emptyMap()
        feedHasMore = true
        games = null
        images.evictAll()
    }

    companion object {
        const val MAX_PHOTOS = 5
        const val MAX_CAPTION = 500
        const val MAX_COMMENT = 500
        const val MAX_GAME = 40
        const val PAGE_SIZE = 20
    }
}

/** Sharing a post into a chat sends this link (end-to-end encrypted, like any message); the app shows a card. */
object PostLinks {
    private const val PREFIX = "discordlite://post/"
    private val pattern = Regex("discordlite://post/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})")

    fun link(postId: String) = "$PREFIX$postId"

    /** The post linked in a message, if any. */
    fun idIn(text: String): String? = pattern.find(text)?.groupValues?.get(1)

    /** True when the message is just the link, so only the card needs showing. */
    fun isOnlyLink(text: String) = pattern.matchEntire(text.trim()) != null

    /** What the chat list shows for a message (group previews start with "You: " or a name). */
    fun preview(text: String) = pattern.replace(text, "Shared a post")
}
