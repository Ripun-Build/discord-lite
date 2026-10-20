package com.discordlite.server.social

import com.discordlite.server.common.notFound
import com.discordlite.server.plugins.userId
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.util.UUID

/** All routes here require a signed-in user (mounted inside `authenticate`). */
fun Route.socialRoutes(social: SocialService) {
    route("/v1/me") {
        get { call.respond(social.me(call.userId)) }
        patch { call.respond(social.updateMe(call.userId, call.receive())) }
    }

    get("/v1/users/search") {
        call.respond(social.search(call.userId, call.request.queryParameters["q"].orEmpty()))
    }

    route("/v1/friends") {
        get { call.respond(social.friends(call.userId)) }
        post("/requests") {
            val target = parseId(call.receive<FriendRequestBody>().userId)
            call.respond(RelationshipResponse(social.sendRequest(call.userId, target)))
        }
        post("/requests/{userId}/accept") {
            social.accept(call.userId, call.pathId())
            call.respond(RelationshipResponse(Relationship.FRIEND))
        }
        delete("/requests/{userId}") {
            social.removeRequest(call.userId, call.pathId())
            call.respond(HttpStatusCode.NoContent)
        }
        delete("/{userId}") {
            social.unfriend(call.userId, call.pathId())
            call.respond(HttpStatusCode.NoContent)
        }
    }

    route("/v1/blocks") {
        get { call.respond(social.blocked(call.userId)) }
        put("/{userId}") {
            social.block(call.userId, call.pathId())
            call.respond(HttpStatusCode.NoContent)
        }
        delete("/{userId}") {
            social.unblock(call.userId, call.pathId())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun RoutingCall.pathId(): UUID = parseId(parameters["userId"])

private fun parseId(raw: String?): UUID =
    runCatching { UUID.fromString(raw) }.getOrNull() ?: throw notFound("user_not_found")
