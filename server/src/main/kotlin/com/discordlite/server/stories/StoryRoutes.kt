package com.discordlite.server.stories

import com.discordlite.server.common.badRequest
import com.discordlite.server.common.notFound
import com.discordlite.server.plugins.userId
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import java.util.UUID

/** All routes here require a signed-in user (mounted inside `authenticate`). */
fun Route.storyRoutes(stories: StoryService) {
    route("/v1/stories") {
        get { call.respond(stories.feed(call.userId)) }
        get("/archive") { call.respond(stories.archive(call.userId)) }

        // multipart/form-data: "image" (the photo) and an optional "caption".
        post {
            var image: ByteArray? = null
            var caption: String? = null
            call.receiveMultipart(formFieldLimit = StoryService.MAX_UPLOAD_BYTES + 1024L).forEachPart { part ->
                when {
                    part is PartData.FileItem && part.name == "image" -> image = part.provider().readRemaining().readByteArray()
                    part is PartData.FormItem && part.name == "caption" -> caption = part.value
                }
                part.dispose()
            }
            call.respond(HttpStatusCode.Created, stories.post(call.userId, image ?: throw badRequest("invalid_image"), caption))
        }

        route("/{storyId}") {
            get("/media") {
                val bytes = stories.media(call.userId, call.storyId())
                // Private: phones may cache it briefly, shared caches never.
                call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = 3600, visibility = CacheControl.Visibility.Private))
                call.respondBytes(bytes, ContentType.Image.JPEG)
            }
            post("/view") {
                stories.view(call.userId, call.storyId())
                call.respond(HttpStatusCode.NoContent)
            }
            get("/viewers") { call.respond(stories.viewers(call.userId, call.storyId())) }
            delete {
                stories.delete(call.userId, call.storyId())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private fun RoutingCall.storyId(): UUID =
    runCatching { UUID.fromString(parameters["storyId"]) }.getOrNull() ?: throw notFound("story_not_found")
