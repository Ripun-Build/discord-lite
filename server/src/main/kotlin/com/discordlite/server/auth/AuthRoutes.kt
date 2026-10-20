package com.discordlite.server.auth

import com.discordlite.server.common.StatusResponse
import com.discordlite.server.plugins.AuthRateLimit
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.authRoutes(auth: AuthService, tokens: TokenService) = route("/v1/auth") {
    rateLimit(AuthRateLimit) {
        post("/signup") {
            auth.signUp(call.receive())
            call.respond(HttpStatusCode.Accepted, StatusResponse("verification_sent"))
        }
        post("/verify") { call.respond(auth.verifyEmail(call.receive())) }
        post("/resend") {
            auth.resendVerification(call.receive())
            call.respond(HttpStatusCode.Accepted, StatusResponse("verification_sent"))
        }
        post("/login") { call.respond(auth.login(call.receive())) }
        post("/password/forgot") {
            auth.forgotPassword(call.receive())
            call.respond(HttpStatusCode.Accepted, StatusResponse("reset_sent"))
        }
        post("/password/reset") {
            auth.resetPassword(call.receive())
            call.respond(HttpStatusCode.NoContent)
        }
    }
    post("/refresh") { call.respond(tokens.refresh(call.receive<RefreshRequest>().refreshToken)) }
    post("/logout") {
        tokens.revoke(call.receive<RefreshRequest>().refreshToken)
        call.respond(HttpStatusCode.NoContent)
    }
}
