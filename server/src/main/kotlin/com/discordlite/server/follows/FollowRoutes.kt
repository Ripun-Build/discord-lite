package com.discordlite.server.follows

import com.discordlite.server.common.notFound
import com.discordlite.server.plugins.userId
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.util.UUID

/** All routes here require a signed-in user (mounted inside `authenticate`). */
fun Route.followRoutes(follows: FollowService) {
    route("/v1/users/{userId}") {
        get("/profile") { call.respond(follows.profile(call.userId, call.pathId())) }
        get("/followers") { call.respond(follows.followers(call.userId, call.pathId())) }
        get("/following") { call.respond(follows.following(call.userId, call.pathId())) }
        put("/follow") { call.respond(FollowResponse(follows.follow(call.userId, call.pathId()))) }
        delete("/follow") {
            follows.unfollow(call.userId, call.pathId())
            call.respond(HttpStatusCode.NoContent)
        }
        delete("/follower") {
            follows.removeFollower(call.userId, call.pathId())
            call.respond(HttpStatusCode.NoContent)
        }
    }

    route("/v1/follows/requests") {
        get { call.respond(follows.requests(call.userId)) }
        post("/{userId}/accept") {
            follows.acceptRequest(call.userId, call.pathId())
            call.respond(HttpStatusCode.NoContent)
        }
        delete("/{userId}") {
            follows.declineRequest(call.userId, call.pathId())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun RoutingCall.pathId(): UUID =
    runCatching { UUID.fromString(parameters["userId"]) }.getOrNull() ?: throw notFound("user_not_found")
