package com.discordlite.server.plugins

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(val error: String)

fun Application.configureStatusPages() {
    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ErrorResponse("not_found"))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled error", cause)
            // Never leak stack traces or internal messages to clients.
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal_error"))
        }
    }
}
