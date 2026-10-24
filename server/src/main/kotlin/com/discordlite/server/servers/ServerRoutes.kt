package com.discordlite.server.servers

import com.discordlite.server.common.notFound
import com.discordlite.server.plugins.InviteRateLimit
import com.discordlite.server.plugins.userId
import io.ktor.server.plugins.ratelimit.rateLimit
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
fun Route.serverRoutes(servers: ServerService) {
    route("/v1/servers") {
        get { call.respond(servers.list(call.userId)) }
        post { call.respond(HttpStatusCode.Created, servers.create(call.userId, call.receive())) }
        route("/{serverId}") {
            get { call.respond(servers.get(call.userId, call.id("serverId"))) }
            patch { call.respond(servers.rename(call.userId, call.id("serverId"), call.receive())) }
            delete {
                servers.delete(call.userId, call.id("serverId"))
                call.respond(HttpStatusCode.NoContent)
            }

            get("/members") { call.respond(servers.members(call.userId, call.id("serverId"))) }
            post("/members") { call.respond(servers.addMembers(call.userId, call.id("serverId"), call.receive())) }
            // Your own id = leave the server.
            delete("/members/{userId}") {
                servers.removeMember(call.userId, call.id("serverId"), call.id("userId"))
                call.respond(HttpStatusCode.NoContent)
            }
            put("/members/{userId}/roles") {
                servers.setMemberRoles(call.userId, call.id("serverId"), call.id("userId"), call.receive())
                call.respond(HttpStatusCode.NoContent)
            }

            post("/roles") { call.respond(servers.createRole(call.userId, call.id("serverId"), call.receive())) }
            patch("/roles/{roleId}") { call.respond(servers.updateRole(call.userId, call.id("serverId"), call.id("roleId"), call.receive())) }
            delete("/roles/{roleId}") { call.respond(servers.deleteRole(call.userId, call.id("serverId"), call.id("roleId"))) }

            post("/invites") { call.respond(HttpStatusCode.Created, servers.createInvite(call.userId, call.id("serverId"), call.receive())) }
            get("/invites") { call.respond(servers.invites(call.userId, call.id("serverId"))) }

            get("/bans") { call.respond(servers.bans(call.userId, call.id("serverId"))) }
            put("/bans/{userId}") {
                servers.ban(call.userId, call.id("serverId"), call.id("userId"))
                call.respond(HttpStatusCode.NoContent)
            }
            delete("/bans/{userId}") {
                servers.unban(call.userId, call.id("serverId"), call.id("userId"))
                call.respond(HttpStatusCode.NoContent)
            }

            post("/channels") { call.respond(servers.createChannel(call.userId, call.id("serverId"), call.receive())) }
            post("/categories") { call.respond(servers.createCategory(call.userId, call.id("serverId"), call.receive())) }
            patch("/categories/{categoryId}") {
                call.respond(servers.renameCategory(call.userId, call.id("serverId"), call.id("categoryId"), call.receive()))
            }
            delete("/categories/{categoryId}") { call.respond(servers.deleteCategory(call.userId, call.id("serverId"), call.id("categoryId"))) }
        }
    }

    route("/v1/channels/{channelId}") {
        patch { call.respond(servers.updateChannel(call.userId, call.id("channelId"), call.receive())) }
        delete { call.respond(servers.deleteChannel(call.userId, call.id("channelId"))) }
        get("/messages") {
            val before = call.request.queryParameters["before"]?.toLongOrNull()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            call.respond(servers.messages(call.userId, call.id("channelId"), before, limit))
        }
        post("/messages") { call.respond(HttpStatusCode.Created, servers.send(call.userId, call.id("channelId"), call.receive())) }
        delete("/messages/{messageId}") {
            val messageId = call.parameters["messageId"]?.toLongOrNull() ?: throw notFound("message_not_found")
            servers.deleteMessage(call.userId, call.id("channelId"), messageId)
            call.respond(HttpStatusCode.NoContent)
        }
        post("/read") {
            servers.read(call.userId, call.id("channelId"), call.receive<ReadRequest>().lastReadId)
            call.respond(HttpStatusCode.NoContent)
        }
    }

    route("/v1/invites/{code}") {
        rateLimit(InviteRateLimit) {
            get { call.respond(servers.previewInvite(call.userId, call.code())) }
            post("/join") { call.respond(servers.joinInvite(call.userId, call.code())) }
        }
        delete {
            servers.deleteInvite(call.userId, call.code())
            call.respond(HttpStatusCode.NoContent)
        }
    }

    route("/v1/mentions") {
        get { call.respond(servers.pendingMentions(call.userId)) }
        post("/ack") {
            servers.ackMentions(call.userId, call.receive<MentionAckRequest>().messageIds)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun RoutingCall.code(): String = parameters["code"].orEmpty()

private fun RoutingCall.id(name: String): UUID =
    runCatching { UUID.fromString(parameters[name]) }.getOrNull() ?: throw notFound("${name.removeSuffix("Id")}_not_found")
