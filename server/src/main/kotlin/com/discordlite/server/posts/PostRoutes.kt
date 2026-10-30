package com.discordlite.server.posts

import com.discordlite.server.common.badRequest
import com.discordlite.server.common.notFound
import com.discordlite.server.media.ImageCleaner
import com.discordlite.server.plugins.CommentRateLimit
import com.discordlite.server.plugins.userId
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import java.util.UUID

/** All routes here require a signed-in user (mounted inside `authenticate`). */
fun Route.postRoutes(posts: PostService) {
    get("/v1/users/{userId}/posts") {
        val author = call.id("userId", "user_not_found")
        call.respond(posts.byAuthor(call.userId, author, call.before()))
    }

    route("/v1/posts") {
        get("/feed") { call.respond(posts.feed(call.userId, call.before())) }
        get("/saved") { call.respond(posts.saved(call.userId, call.before())) }
        get("/games") { call.respond(GameCatalog.games) }

        // multipart/form-data: "image" once per photo, in order, plus optional "caption" and "game".
        post {
            val images = ArrayList<ByteArray>()
            var caption: String? = null
            var game: String? = null
            call.receiveMultipart(formFieldLimit = ImageCleaner.MAX_UPLOAD_BYTES + 1024L).forEachPart { part ->
                when {
                    part is PartData.FileItem && part.name == "image" -> {
                        if (images.size >= PostService.MAX_PHOTOS) throw badRequest("invalid_image")
                        images += part.provider().readRemaining().readByteArray()
                    }
                    part is PartData.FormItem && part.name == "caption" -> caption = part.value
                    part is PartData.FormItem && part.name == "game" -> game = part.value
                }
                part.dispose()
            }
            call.respond(HttpStatusCode.Created, posts.create(call.userId, images, caption, game))
        }

        route("/{postId}") {
            get { call.respond(posts.get(call.userId, call.postId())) }
            delete {
                posts.delete(call.userId, call.postId())
                call.respond(HttpStatusCode.NoContent)
            }
            get("/media/{position}") {
                val position = call.parameters["position"]?.toIntOrNull()?.takeIf { it in 0 until PostService.MAX_PHOTOS } ?: throw notFound("post_not_found")
                val bytes = posts.photo(call.userId, call.postId(), position)
                // Private: phones may cache it briefly, shared caches never.
                call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = 3600, visibility = CacheControl.Visibility.Private))
                call.respondBytes(bytes, ContentType.Image.JPEG)
            }
            put("/like") { call.respond(posts.setLiked(call.userId, call.postId(), true)) }
            delete("/like") { call.respond(posts.setLiked(call.userId, call.postId(), false)) }
            put("/save") {
                posts.setSaved(call.userId, call.postId(), true)
                call.respond(HttpStatusCode.NoContent)
            }
            delete("/save") {
                posts.setSaved(call.userId, call.postId(), false)
                call.respond(HttpStatusCode.NoContent)
            }
            route("/comments") {
                get { call.respond(posts.comments(call.userId, call.postId())) }
                rateLimit(CommentRateLimit) {
                    post { call.respond(HttpStatusCode.Created, posts.addComment(call.userId, call.postId(), call.receive())) }
                }
                delete("/{commentId}") {
                    posts.deleteComment(call.userId, call.postId(), call.id("commentId", "comment_not_found"))
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}

private fun RoutingCall.postId(): UUID = id("postId", "post_not_found")

private fun RoutingCall.id(name: String, error: String): UUID =
    runCatching { UUID.fromString(parameters[name]) }.getOrNull() ?: throw notFound(error)

private fun RoutingCall.before(): UUID? =
    request.queryParameters["before"]?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: throw badRequest("invalid_cursor") }
