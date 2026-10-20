package com.discordlite.server.auth

import com.discordlite.server.common.badRequest
import com.discordlite.server.common.conflict
import com.discordlite.server.common.forbidden
import com.discordlite.server.common.tooMany
import com.discordlite.server.common.unauthorized
import com.discordlite.server.db.Db
import com.discordlite.server.db.exec
import com.discordlite.server.db.isUniqueViolation
import com.discordlite.server.db.queryOne
import com.discordlite.server.mail.Emails
import com.discordlite.server.mail.Mailer
import com.discordlite.server.users.Me
import com.discordlite.server.users.UserRules
import com.discordlite.server.users.findUserByEmail
import com.discordlite.server.users.findUserById
import com.discordlite.server.users.toMe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

@Serializable
data class SignUpRequest(val email: String, val username: String, val password: String, val displayName: String? = null)

@Serializable
data class VerifyRequest(val email: String, val code: String)

@Serializable
data class EmailRequest(val email: String)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class ResetPasswordRequest(val email: String, val code: String, val newPassword: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class AuthResponse(val session: Session, val user: Me)

/**
 * Email + password accounts.
 *
 * Responses never reveal whether an email is registered: sign-up, resend and "forgot password"
 * always answer the same way, and a failed login takes the same time whether or not the account
 * exists. Usernames are public, so "username taken" is the one thing we do say.
 */
class AuthService(
    private val db: Db,
    private val tokens: TokenService,
    private val codes: Codes,
    private val mailer: Mailer,
) {
    suspend fun signUp(request: SignUpRequest) {
        val email = request.email.trim()
        val username = request.username.trim().lowercase()
        val displayName = request.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: username
        if (email.length > 254 || !UserRules.EMAIL.matches(email)) throw badRequest("invalid_email")
        if (!UserRules.USERNAME.matches(username)) throw badRequest("invalid_username")
        if (displayName.length > UserRules.DISPLAY_NAME_MAX) throw badRequest("invalid_display_name")
        validatePassword(request.password)
        val passwordHash = withContext(Dispatchers.Default) { Passwords.hash(request.password) }

        val outcome = try {
            db.tx {
                // Free up emails/usernames held by sign-ups that were never verified.
                exec("DELETE FROM users WHERE email_verified = false AND created_at < now() - interval '24 hours'")
                val existing = findUserByEmail(email)
                if (existing?.emailVerified == true) return@tx SignUpOutcome.AlreadyRegistered

                val owner = queryOne("SELECT id FROM users WHERE username = ?", username) { it.getObject("id", UUID::class.java) }
                if (owner != null && owner != existing?.id) throw conflict("username_taken")

                val id = existing?.id ?: UUID.randomUUID()
                if (existing == null) {
                    exec(
                        "INSERT INTO users (id, email, username, display_name, password_hash) VALUES (?, ?, ?, ?, ?)",
                        id, email, username, displayName, passwordHash,
                    )
                } else {
                    // Re-signing up before verifying replaces the pending details.
                    exec(
                        "UPDATE users SET username = ?, display_name = ?, password_hash = ? WHERE id = ?",
                        username, displayName, passwordHash, id,
                    )
                }
                SignUpOutcome.SendCode(storeCode(id, CodePurpose.VERIFY_EMAIL))
            }
        } catch (e: SQLException) {
            if (e.isUniqueViolation) throw conflict("username_taken") else throw e
        }

        when (outcome) {
            is SignUpOutcome.SendCode -> mailer.send(email, "Verify your discord-lite email", Emails.verifyEmail(outcome.code))
            SignUpOutcome.AlreadyRegistered -> mailer.send(email, "Sign-up attempt on discord-lite", Emails.SIGN_UP_ATTEMPT)
        }
    }

    suspend fun verifyEmail(request: VerifyRequest): AuthResponse {
        val result = db.tx {
            val user = findUserByEmail(request.email.trim())?.takeIf { !it.emailVerified } ?: return@tx CodeCheck.Invalid to null
            val check = checkCode(user.id, CodePurpose.VERIFY_EMAIL, request.code.trim())
            if (check == CodeCheck.Ok) exec("UPDATE users SET email_verified = true WHERE id = ?", user.id)
            check to user.id
        }
        val userId = result.second.takeIf { result.first == CodeCheck.Ok } ?: throw result.first.toError()
        return startSession(userId)
    }

    /** Sends a fresh verification code. Silent for unknown or already-verified emails. */
    suspend fun resendVerification(request: EmailRequest) {
        val email = request.email.trim()
        val code = db.tx {
            val user = findUserByEmail(email)?.takeIf { !it.emailVerified } ?: return@tx null
            if (sentRecently(user.id, CodePurpose.VERIFY_EMAIL)) throw tooMany("resend_too_soon")
            storeCode(user.id, CodePurpose.VERIFY_EMAIL)
        } ?: return
        mailer.send(email, "Verify your discord-lite email", Emails.verifyEmail(code))
    }

    suspend fun login(request: LoginRequest): AuthResponse {
        val user = db.tx { findUserByEmail(request.email.trim()) }
        val valid = withContext(Dispatchers.Default) {
            if (user == null) {
                Passwords.burnTime(request.password)
                false
            } else {
                Passwords.verify(request.password, user.passwordHash)
            }
        }
        if (user == null || !valid) throw unauthorized("invalid_credentials")
        if (!user.emailVerified) {
            // Help them finish: send a code (unless one went out moments ago), then tell the app.
            runCatching { resendVerification(EmailRequest(user.email)) }
            throw forbidden("email_not_verified")
        }
        return startSession(user.id)
    }

    /** Always succeeds from the caller's point of view; mails a code only to verified accounts. */
    suspend fun forgotPassword(request: EmailRequest) {
        val email = request.email.trim()
        val code = db.tx {
            val user = findUserByEmail(email)?.takeIf { it.emailVerified } ?: return@tx null
            if (sentRecently(user.id, CodePurpose.RESET_PASSWORD)) return@tx null
            storeCode(user.id, CodePurpose.RESET_PASSWORD)
        } ?: return
        mailer.send(email, "Reset your discord-lite password", Emails.resetPassword(code))
    }

    /** Sets a new password and signs out every existing session. */
    suspend fun resetPassword(request: ResetPasswordRequest) {
        validatePassword(request.newPassword)
        val hash = withContext(Dispatchers.Default) { Passwords.hash(request.newPassword) }
        val check = db.tx {
            val user = findUserByEmail(request.email.trim())?.takeIf { it.emailVerified } ?: return@tx CodeCheck.Invalid
            val check = checkCode(user.id, CodePurpose.RESET_PASSWORD, request.code.trim())
            if (check == CodeCheck.Ok) {
                exec("UPDATE users SET password_hash = ? WHERE id = ?", hash, user.id)
                with(tokens) { revokeAll(user.id) }
            }
            check
        }
        if (check != CodeCheck.Ok) throw check.toError()
    }

    private suspend fun startSession(userId: UUID): AuthResponse = db.tx {
        val user = findUserById(userId) ?: throw unauthorized()
        AuthResponse(with(tokens) { openSession(userId) }, user.toMe())
    }

    private fun validatePassword(password: String) {
        if (password.length !in Passwords.MIN_LENGTH..Passwords.MAX_LENGTH) throw badRequest("invalid_password")
    }

    private fun Connection.storeCode(userId: UUID, purpose: CodePurpose): String {
        val code = codes.generate()
        exec(
            """
            INSERT INTO email_codes (user_id, purpose, code_hash, expires_at, attempts, sent_at)
            VALUES (?, ?, ?, now() + interval '10 minutes', 0, now())
            ON CONFLICT (user_id, purpose) DO UPDATE
                SET code_hash = excluded.code_hash, expires_at = excluded.expires_at, attempts = 0, sent_at = now()
            """.trimIndent(),
            userId, purpose.name, codes.hash(userId, purpose, code),
        )
        return code
    }

    private fun Connection.sentRecently(userId: UUID, purpose: CodePurpose): Boolean =
        queryOne(
            "SELECT sent_at > now() - interval '30 seconds' AS recent FROM email_codes WHERE user_id = ? AND purpose = ?",
            userId, purpose.name,
        ) { it.getBoolean("recent") } == true

    /** Checks a code; wrong guesses count toward a limit of 5, and a used code is deleted. */
    private fun Connection.checkCode(userId: UUID, purpose: CodePurpose, code: String): CodeCheck {
        val row = queryOne(
            "SELECT code_hash, attempts, expires_at < now() AS expired FROM email_codes WHERE user_id = ? AND purpose = ? FOR UPDATE",
            userId, purpose.name,
        ) { Triple(it.getBytes("code_hash"), it.getInt("attempts"), it.getBoolean("expired")) } ?: return CodeCheck.Invalid
        val (hash, attempts, expired) = row
        return when {
            expired -> CodeCheck.Expired
            attempts >= MAX_ATTEMPTS -> CodeCheck.Locked
            !MessageDigest.isEqual(hash, codes.hash(userId, purpose, code)) -> {
                exec("UPDATE email_codes SET attempts = attempts + 1 WHERE user_id = ? AND purpose = ?", userId, purpose.name)
                CodeCheck.Invalid
            }
            else -> {
                exec("DELETE FROM email_codes WHERE user_id = ? AND purpose = ?", userId, purpose.name)
                CodeCheck.Ok
            }
        }
    }

    private enum class CodeCheck {
        Ok, Invalid, Expired, Locked;

        fun toError() = when (this) {
            Expired -> badRequest("code_expired")
            Locked -> tooMany("too_many_attempts")
            else -> badRequest("invalid_code")
        }
    }

    private sealed interface SignUpOutcome {
        data class SendCode(val code: String) : SignUpOutcome
        data object AlreadyRegistered : SignUpOutcome
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
    }
}
