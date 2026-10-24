package com.discordlite.server

import com.discordlite.server.auth.AuthResponse
import com.discordlite.server.config.AppConfig
import com.discordlite.server.db.Db
import com.discordlite.server.mail.Mailer
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.assertEquals

/** One real Postgres for the whole test run, migrated once. Tests use unique emails/usernames. */
object TestDb {
    private val container = PostgreSQLContainer("postgres:18-alpine").apply { start() }

    val dataSource: HikariDataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = container.jdbcUrl
            username = container.username
            password = container.password
            maximumPoolSize = 5
        },
    ).also(Db::migrate)

    val config = AppConfig(
        dbUrl = container.jdbcUrl,
        dbUser = container.username,
        dbPassword = container.password,
        jwtSecret = "test-secret-that-is-at-least-32-characters",
        codePepper = "test-pepper",
        smtpHost = "unused",
        smtpPort = 0,
        mailFrom = "test@discord-lite.local",
        authRequestsPerMinute = 100_000,
    )
}

data class SentMail(val to: String, val subject: String, val body: String)

/** Keeps emails in memory so tests can read the codes. */
class CapturingMailer : Mailer {
    val sent = CopyOnWriteArrayList<SentMail>()

    override suspend fun send(to: String, subject: String, body: String) {
        sent += SentMail(to, subject, body)
    }

    fun lastCode(to: String): String =
        Regex("\\b\\d{6}\\b").find(sent.last { it.to.equals(to, ignoreCase = true) }.body)!!.value
}

class TestApp(val mailer: CapturingMailer, val deps: AppDeps)

/** Records who would have been woken by a push. */
class CapturingPusher : com.discordlite.server.push.Pusher {
    val woken = CopyOnWriteArrayList<java.util.UUID>()
    override suspend fun wake(user: java.util.UUID) {
        woken += user
    }
}

fun ApplicationTestBuilder.startApp(pusher: CapturingPusher = CapturingPusher()): TestApp {
    val mailer = CapturingMailer()
    val deps = AppDeps(TestDb.config, TestDb.dataSource, mailer, pusher)
    application { configure(deps) }
    return TestApp(mailer, deps)
}

fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient { install(ContentNegotiation) { json() } }

fun uniqueName(): String = "t" + List(10) { "abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)] }.joinToString("")

data class TestUser(val email: String, val username: String, val password: String, val auth: AuthResponse) {
    val id get() = auth.user.id
    val token get() = auth.session.accessToken
}

/** Signs up and verifies a fresh account through the real HTTP API. */
suspend fun HttpClient.newUser(app: TestApp, password: String = "correct horse 42"): TestUser {
    val username = uniqueName()
    val email = "$username@example.com"
    val signUp = post("/v1/auth/signup") {
        contentType(ContentType.Application.Json)
        setBody(mapOf("email" to email, "username" to username, "password" to password))
    }
    assertEquals(HttpStatusCode.Accepted, signUp.status)
    val verify = post("/v1/auth/verify") {
        contentType(ContentType.Application.Json)
        setBody(mapOf("email" to email, "code" to app.mailer.lastCode(email)))
    }
    assertEquals(HttpStatusCode.OK, verify.status)
    return TestUser(email, username, password, verify.body())
}

suspend fun HttpClient.postJson(path: String, body: Any, token: String? = null) = post(path) {
    contentType(ContentType.Application.Json)
    setBody(body)
    if (token != null) bearerAuth(token)
}
