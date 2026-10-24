package com.discordlite.server.plugins

import com.discordlite.server.auth.TokenService
import com.discordlite.server.common.ErrorResponse
import com.discordlite.server.config.AppConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.response.respond
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

const val ACCESS_AUTH = "access"
val AuthRateLimit = RateLimitName("auth")
val InviteRateLimit = RateLimitName("invites")

fun Application.configureSecurity(tokens: TokenService, config: AppConfig) {
    install(Authentication) {
        jwt(ACCESS_AUTH) {
            verifier(tokens.verifier)
            validate { credential -> credential.payload.subject?.let { JWTPrincipal(credential.payload) } }
            challenge { _, _ -> call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized")) }
        }
    }
    install(RateLimit) {
        // Slows password guessing and code brute-forcing. Counters live in memory only — no IP log.
        register(AuthRateLimit) {
            rateLimiter(limit = config.authRequestsPerMinute, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteAddress }
        }
        // Looking up invite codes, per account: stops anyone scanning for valid codes by guessing.
        register(InviteRateLimit) {
            rateLimiter(limit = config.inviteLookupsPerMinute, refillPeriod = 1.minutes)
            requestKey { call -> call.principal<JWTPrincipal>()?.payload?.subject ?: call.request.origin.remoteAddress }
        }
    }
}

/** The signed-in user's id. Only valid inside `authenticate(ACCESS_AUTH)`. */
val ApplicationCall.userId: UUID
    get() = UUID.fromString(principal<JWTPrincipal>()!!.payload.subject)
