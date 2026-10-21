package com.discordlite.server.messaging

import com.discordlite.server.common.notFound
import com.discordlite.server.plugins.userId
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.util.UUID

/** All routes here require a signed-in user (mounted inside `authenticate`). */
fun Route.messagingRoutes(keys: KeyService, messages: MessageService, gateway: Gateway) {
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
            call.respond(messages.bundleFor(call.userId, target))
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
                    "ephemeral" -> messages.relayEphemeral(user, parsed.to, parsed.envelope?.type, parsed.envelope?.body)
                }
            }
        } finally {
            gateway.unregister(user, this)
        }
    }
}
