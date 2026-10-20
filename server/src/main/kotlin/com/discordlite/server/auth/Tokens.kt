package com.discordlite.server.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.discordlite.server.common.unauthorized
import com.discordlite.server.config.AppConfig
import com.discordlite.server.db.Db
import com.discordlite.server.db.exec
import com.discordlite.server.db.queryOne
import com.discordlite.server.db.uuid
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.util.Date
import java.util.UUID

@Serializable
data class Session(
    val accessToken: String,
    val refreshToken: String,
    /** Seconds until [accessToken] expires. */
    val expiresIn: Long,
)

/**
 * Short-lived JWT access tokens (15 min) plus opaque refresh tokens (30 days).
 *
 * Refresh tokens rotate on every use. Each sign-in starts a "family"; if an already-used token is
 * presented again, someone else has a copy, so the whole family is revoked and both parties are
 * signed out.
 */
class TokenService(private val config: AppConfig, private val db: Db) {
    private val algorithm = Algorithm.HMAC256(config.jwtSecret)

    val verifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(config.jwtIssuer)
        .withClaim("typ", "access")
        .build()

    fun accessToken(userId: UUID): String {
        val now = System.currentTimeMillis()
        return JWT.create()
            .withIssuer(config.jwtIssuer)
            .withSubject(userId.toString())
            .withClaim("typ", "access")
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(now + config.accessTokenTtl.inWholeMilliseconds))
            .sign(algorithm)
    }

    fun Connection.openSession(userId: UUID): Session = issue(userId, UUID.randomUUID())

    suspend fun startSession(userId: UUID): Session = db.tx { openSession(userId) }

    suspend fun refresh(rawToken: String): Session {
        val outcome = db.tx {
            val row = queryOne(
                """
                SELECT user_id, family_id,
                       (used_at IS NOT NULL OR revoked_at IS NOT NULL) AS dead,
                       expires_at < now() AS expired
                FROM refresh_tokens WHERE token_hash = ? FOR UPDATE
                """.trimIndent(),
                sha256(rawToken),
            ) { rs -> RefreshRow(rs.uuid("user_id"), rs.uuid("family_id"), rs.getBoolean("dead"), rs.getBoolean("expired")) }
                ?: return@tx null

            when {
                row.dead -> {
                    // Reuse detected: revoke the whole session family. Committed before we reject.
                    exec("UPDATE refresh_tokens SET revoked_at = now() WHERE family_id = ? AND revoked_at IS NULL", row.familyId)
                    null
                }
                row.expired -> null
                else -> {
                    exec("UPDATE refresh_tokens SET used_at = now() WHERE token_hash = ?", sha256(rawToken))
                    issue(row.userId, row.familyId)
                }
            }
        }
        return outcome ?: throw unauthorized("invalid_refresh_token")
    }

    /** Signs out the session this refresh token belongs to. Unknown tokens are ignored. */
    suspend fun revoke(rawToken: String) = db.tx {
        exec(
            """
            UPDATE refresh_tokens SET revoked_at = now()
            WHERE family_id = (SELECT family_id FROM refresh_tokens WHERE token_hash = ?) AND revoked_at IS NULL
            """.trimIndent(),
            sha256(rawToken),
        )
    }

    fun Connection.revokeAll(userId: UUID) {
        exec("UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL", userId)
    }

    private fun Connection.issue(userId: UUID, familyId: UUID): Session {
        val raw = randomToken()
        exec(
            "INSERT INTO refresh_tokens (token_hash, user_id, family_id, expires_at) VALUES (?, ?, ?, now() + make_interval(secs => ?))",
            sha256(raw),
            userId,
            familyId,
            config.refreshTokenTtl.inWholeSeconds.toDouble(),
        )
        return Session(accessToken(userId), raw, config.accessTokenTtl.inWholeSeconds)
    }

    private data class RefreshRow(val userId: UUID, val familyId: UUID, val dead: Boolean, val expired: Boolean)
}
