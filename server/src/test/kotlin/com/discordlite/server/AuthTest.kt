package com.discordlite.server

import com.discordlite.server.auth.AuthResponse
import com.discordlite.server.auth.Session
import com.discordlite.server.users.Me
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthTest {

    @Test
    fun `sign up, verify by email code, then read own profile`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val user = http.newUser(app)

        val me = http.get("/v1/me") { bearerAuth(user.token) }
        assertEquals(HttpStatusCode.OK, me.status)
        val body = me.body<Me>()
        assertEquals(user.username, body.username)
        assertEquals(user.email, body.email)
        assertEquals(user.username, body.displayName) // defaults to the username
    }

    @Test
    fun `protected routes reject missing or bad tokens`() = testApplication {
        startApp()
        val http = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, http.get("/v1/me").status)
        assertEquals(HttpStatusCode.Unauthorized, http.get("/v1/me") { bearerAuth("not-a-jwt") }.status)
    }

    @Test
    fun `wrong codes lock after five attempts`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val name = uniqueName()
        val email = "$name@example.com"
        http.postJson("/v1/auth/signup", mapOf("email" to email, "username" to name, "password" to "password123"))
        val real = app.mailer.lastCode(email)
        val wrong = if (real == "000000") "111111" else "000000"

        repeat(5) {
            val r = http.postJson("/v1/auth/verify", mapOf("email" to email, "code" to wrong))
            assertEquals(HttpStatusCode.BadRequest, r.status)
            assertTrue(r.bodyAsText().contains("invalid_code"))
        }
        // Even the right code is refused once locked.
        val locked = http.postJson("/v1/auth/verify", mapOf("email" to email, "code" to real))
        assertEquals(HttpStatusCode.TooManyRequests, locked.status)
        assertTrue(locked.bodyAsText().contains("too_many_attempts"))
    }

    @Test
    fun `login needs a verified email and the right password`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val name = uniqueName()
        val email = "$name@example.com"
        http.postJson("/v1/auth/signup", mapOf("email" to email, "username" to name, "password" to "password123"))

        val unverified = http.postJson("/v1/auth/login", mapOf("email" to email, "password" to "password123"))
        assertEquals(HttpStatusCode.Forbidden, unverified.status)
        assertTrue(unverified.bodyAsText().contains("email_not_verified"))

        val user = http.newUser(app)
        val wrong = http.postJson("/v1/auth/login", mapOf("email" to user.email, "password" to "nope-nope"))
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        val unknown = http.postJson("/v1/auth/login", mapOf("email" to "nobody-${uniqueName()}@example.com", "password" to "whatever1"))
        assertEquals(HttpStatusCode.Unauthorized, unknown.status)
        assertEquals(wrong.bodyAsText(), unknown.bodyAsText()) // same answer either way

        val ok = http.postJson("/v1/auth/login", mapOf("email" to user.email, "password" to user.password))
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(user.id, ok.body<AuthResponse>().user.id)
    }

    @Test
    fun `usernames are unique, emails are never revealed`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val user = http.newUser(app)

        val takenName = http.postJson(
            "/v1/auth/signup",
            mapOf("email" to "${uniqueName()}@example.com", "username" to user.username, "password" to "password123"),
        )
        assertEquals(HttpStatusCode.Conflict, takenName.status)

        // Re-using a registered email looks like success, and only mails the real owner a notice.
        val before = app.mailer.sent.size
        val takenEmail = http.postJson(
            "/v1/auth/signup",
            mapOf("email" to user.email, "username" to uniqueName(), "password" to "password123"),
        )
        assertEquals(HttpStatusCode.Accepted, takenEmail.status)
        val notice = app.mailer.sent.drop(before).single()
        assertEquals(user.email, notice.to)
        assertFalse(Regex("\\b\\d{6}\\b").containsMatchIn(notice.body))
    }

    @Test
    fun `refresh tokens rotate and a reused token signs the session out`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val user = http.newUser(app)
        val first = user.auth.session.refreshToken

        val rotated = http.postJson("/v1/auth/refresh", mapOf("refreshToken" to first))
        assertEquals(HttpStatusCode.OK, rotated.status)
        val second = rotated.body<Session>().refreshToken

        // Someone replays the old token: rejected, and the whole session family is revoked.
        assertEquals(HttpStatusCode.Unauthorized, http.postJson("/v1/auth/refresh", mapOf("refreshToken" to first)).status)
        assertEquals(HttpStatusCode.Unauthorized, http.postJson("/v1/auth/refresh", mapOf("refreshToken" to second)).status)
    }

    @Test
    fun `logout revokes the refresh token`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val user = http.newUser(app)
        val token = user.auth.session.refreshToken
        assertEquals(HttpStatusCode.NoContent, http.postJson("/v1/auth/logout", mapOf("refreshToken" to token)).status)
        assertEquals(HttpStatusCode.Unauthorized, http.postJson("/v1/auth/refresh", mapOf("refreshToken" to token)).status)
    }

    @Test
    fun `password reset by email code signs out old sessions`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val user = http.newUser(app)

        assertEquals(HttpStatusCode.Accepted, http.postJson("/v1/auth/password/forgot", mapOf("email" to user.email)).status)
        val code = app.mailer.lastCode(user.email)
        val reset = http.postJson(
            "/v1/auth/password/reset",
            mapOf("email" to user.email, "code" to code, "newPassword" to "brand new pass 7"),
        )
        assertEquals(HttpStatusCode.NoContent, reset.status)

        val old = http.postJson("/v1/auth/login", mapOf("email" to user.email, "password" to user.password))
        assertEquals(HttpStatusCode.Unauthorized, old.status)
        val new = http.postJson("/v1/auth/login", mapOf("email" to user.email, "password" to "brand new pass 7"))
        assertEquals(HttpStatusCode.OK, new.status)
        val oldRefresh = http.postJson("/v1/auth/refresh", mapOf("refreshToken" to user.auth.session.refreshToken))
        assertEquals(HttpStatusCode.Unauthorized, oldRefresh.status)

        // Forgot-password for an unknown email answers the same and sends nothing.
        val before = app.mailer.sent.size
        val unknown = http.postJson("/v1/auth/password/forgot", mapOf("email" to "${uniqueName()}@example.com"))
        assertEquals(HttpStatusCode.Accepted, unknown.status)
        assertEquals(before, app.mailer.sent.size)
    }

    @Test
    fun `bad input gets a clear error code`() = testApplication {
        startApp()
        val http = jsonClient()
        val cases = mapOf(
            mapOf("email" to "not-an-email", "username" to uniqueName(), "password" to "password123") to "invalid_email",
            mapOf("email" to "${uniqueName()}@example.com", "username" to "No Spaces!", "password" to "password123") to "invalid_username",
            mapOf("email" to "${uniqueName()}@example.com", "username" to uniqueName(), "password" to "short") to "invalid_password",
        )
        cases.forEach { (body, code) ->
            val r = http.postJson("/v1/auth/signup", body)
            assertEquals(HttpStatusCode.BadRequest, r.status)
            assertTrue(r.bodyAsText().contains(code), "expected $code, got ${r.bodyAsText()}")
        }
    }
}
