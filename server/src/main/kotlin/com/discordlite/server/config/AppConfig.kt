package com.discordlite.server.config

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

data class AppConfig(
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val jwtSecret: String,
    val codePepper: String,
    val smtpHost: String,
    val smtpPort: Int,
    val mailFrom: String,
    val jwtIssuer: String = "discord-lite",
    val accessTokenTtl: Duration = 15.minutes,
    val refreshTokenTtl: Duration = 30.days,
    val authRequestsPerMinute: Int = 20,
    val inviteLookupsPerMinute: Int = 30,
    /** Path to a Firebase service-account key. Null = push notifications are off. */
    val firebaseServiceAccount: String? = null,
) {
    companion object {
        /** Reads the same variables as docker-compose, so `.env` is the single source of secrets. */
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            fun required(key: String): String =
                env[key]?.takeIf { it.isNotBlank() && it != "change-me" }
                    ?: error("$key is not set. Copy .env.example to .env and fill it in.")

            val jwtSecret = required("JWT_SECRET")
            require(jwtSecret.length >= 32) { "JWT_SECRET must be at least 32 characters" }
            return AppConfig(
                dbUrl = env["DB_URL"] ?: "jdbc:postgresql://localhost:5432/${env["POSTGRES_DB"] ?: "discordlite"}",
                dbUser = env["POSTGRES_USER"] ?: "discordlite",
                dbPassword = required("POSTGRES_PASSWORD"),
                jwtSecret = jwtSecret,
                codePepper = required("CODE_PEPPER"),
                smtpHost = env["SMTP_HOST"] ?: "localhost",
                smtpPort = env["SMTP_PORT"]?.toInt() ?: 1025,
                mailFrom = env["MAIL_FROM"] ?: "discord-lite <no-reply@discord-lite.local>",
                firebaseServiceAccount = env["FIREBASE_SERVICE_ACCOUNT"]?.takeIf { it.isNotBlank() },
            )
        }
    }
}
