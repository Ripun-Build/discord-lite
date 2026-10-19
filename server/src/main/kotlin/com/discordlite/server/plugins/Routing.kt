package com.discordlite.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(val status: String, val version: String)

fun Application.configureRouting() {
    val version = environment.config.propertyOrNull("app.version")?.getString() ?: "dev"

    routing {
        get("/health") {
            call.respond(HealthResponse(status = "ok", version = version))
        }
    }
}
