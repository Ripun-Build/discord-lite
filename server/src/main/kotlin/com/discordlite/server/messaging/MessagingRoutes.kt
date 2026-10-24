package com.discordlite.server.messaging

import com.discordlite.server.common.notFound
import com.discordlite.server.plugins.userId
import com.discordlite.server.push.PushTokenRequest
import com.discordlite.server.push.PushTokens
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.util.UUID

/** All routes here require a signed-in user (mounted inside `authenticate`). */
fun Route.messagingRoutes(keys: KeyService, messages: MessageService, groups: GroupService, gateway: Gateway, pushTokens: PushTokens) {
    route("/v1/push/token") {
        put {
            pushTokens.save(call.userId, call.receive<PushTokenRequest>().token)
            call.respond(HttpStatusCode.NoContent)
        }
        delete {
            pushTokens.remove(call.userId)
            call.respond(HttpStatusCode.NoContent)
        }
    }

    route("/v1/keys") {
        put {
            keys.upload(call.userId, call.receive())
            call.respond(HttpStatusCode.NoContent)
        }
        post("/topup") {
            keys.topUp(call.userId, call.receive())
            call.respond(HttpStatusCode.NoContent)
        }
        get("/count") { call.respond(keys.counts(call.userId)) }
        get("/{userId}") {
            val target = runCatching { UUID.fromString(call.parameters["userId"]) }.getOrNull() ?: throw notFound("user_not_found")
            // ?groupId= : fetching keys of a fellow group member (allowed by membership, not "who can DM me").
            call.respond(messages.bundleFor(call.userId, target, call.request.queryParameters["groupId"]))
        }
    }

    route("/v1/messages") {
        post { call.respond(HttpStatusCode.Accepted, messages.send(call.userId, call.receive())) }
        get { call.respond(messages.pending(call.userId)) }
        post("/ack") {
            messages.ack(call.userId, call.receive<AckRequest>().ids)
            call.respond(HttpStatusCode.NoContent)
        }
    }

    route("/v1/groups") {
        get { call.respond(groups.list(call.userId)) }
        post { call.respond(HttpStatusCode.Created, groups.create(call.userId, call.receive())) }
        route("/{groupId}") {
            get { call.respond(groups.get(call.userId, call.groupId())) }
            patch { call.respond(groups.rename(call.userId, call.groupId(), call.receive())) }
            post("/members") { call.respond(groups.addMembers(call.userId, call.groupId(), call.receive())) }
            // Your own id = leave the group.
            delete("/members/{userId}") {
                val target = runCatching { UUID.fromString(call.parameters["userId"]) }.getOrNull() ?: throw notFound("not_a_member")
                groups.removeMember(call.userId, call.groupId(), target)
                call.respond(HttpStatusCode.NoContent)
            }
            post("/messages") {
                call.respond(HttpStatusCode.Accepted, messages.sendToGroup(call.userId, call.parameters["groupId"], call.receive()))
            }
        }
    }

    /**
     * Real-time connection. On connect: every waiting envelope, then `ready`, then live envelopes
     * as they arrive. The client acks what it has stored; acked envelopes are deleted.
     */
    webSocket("/v1/gateway") {
        val user = call.userId
        gateway.register(user, this)
        try {
            messages.pending(user).forEach { gateway.send(this, GatewayFrame("message", it)) }
            gateway.send(this, GatewayFrame("ready"))
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val parsed = runCatching { gateway.json.decodeFromString(GatewayFrame.serializer(), frame.readText()) }.getOrNull() ?: continue
                when (parsed.t) {
                    "ack" -> messages.ack(user, parsed.ids.orEmpty())
                    "ephemeral" -> if (parsed.groupId != null) {
                        messages.relayGroupEphemeral(user, parsed.groupId, parsed.envelope?.type, parsed.envelope?.body)
                    } else {
                        messages.relayEphemeral(user, parsed.to, parsed.envelope?.type, parsed.envelope?.body)
                    }
                }
            }
        } finally {
            gateway.unregister(user, this)
        }
    }
}

private fun RoutingCall.groupId(): UUID =
    runCatching { UUID.fromString(parameters["groupId"]) }.getOrNull() ?: throw notFound("group_not_found")
