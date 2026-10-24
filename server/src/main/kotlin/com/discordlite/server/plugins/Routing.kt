package com.discordlite.server.plugins

import com.discordlite.server.AppDeps
import com.discordlite.server.auth.authRoutes
import com.discordlite.server.messaging.messagingRoutes
import com.discordlite.server.social.socialRoutes
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(val status: String, val version: String)

fun Application.configureRouting(deps: AppDeps) {
    val version = environment.config.propertyOrNull("app.version")?.getString() ?: "dev"

    routing {
        get("/health") {
            call.respond(HealthResponse(status = "ok", version = version))
        }
        authRoutes(deps.auth, deps.tokens)
        authenticate(ACCESS_AUTH) {
            socialRoutes(deps.social)
            messagingRoutes(deps.keys, deps.messages, deps.groups, deps.gateway, deps.pushTokens)
        }
    }
}
