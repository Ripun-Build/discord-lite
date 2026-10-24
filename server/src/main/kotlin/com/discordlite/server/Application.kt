package com.discordlite.server

import com.discordlite.server.auth.AuthService
import com.discordlite.server.auth.Codes
import com.discordlite.server.auth.TokenService
import com.discordlite.server.config.AppConfig
import com.discordlite.server.db.Db
import com.discordlite.server.mail.Mailer
import com.discordlite.server.mail.SmtpMailer
import com.discordlite.server.plugins.configureMonitoring
import com.discordlite.server.plugins.configureRouting
import com.discordlite.server.plugins.configureSecurity
import com.discordlite.server.plugins.configureSerialization
import com.discordlite.server.plugins.configureStatusPages
import com.discordlite.server.messaging.Gateway
import com.discordlite.server.messaging.GroupService
import com.discordlite.server.messaging.KeyService
import com.discordlite.server.messaging.MessageService
import com.discordlite.server.plugins.configureWebSockets
import com.discordlite.server.servers.ServerService
import com.discordlite.server.social.SocialService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.hours
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import com.discordlite.server.push.FcmPusher
import com.discordlite.server.push.NoPush
import com.discordlite.server.push.PushTokens
import com.discordlite.server.push.Pusher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory
import java.io.File
import javax.sql.DataSource

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

/** Everything the routes need, built once. Tests build their own with a test database and mailer. */
class AppDeps(val config: AppConfig, dataSource: DataSource, mailer: Mailer, pusher: Pusher? = null) {
    /** Background work that shouldn't hold up a request (e.g. sending pushes). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val db = Db(dataSource)
    val tokens = TokenService(config, db)
    val auth = AuthService(db, tokens, Codes(config.codePepper), mailer)
    val social = SocialService(db)
    val gateway = Gateway()
    val keys = KeyService(db)
    val pushTokens = PushTokens(db)
    val pusher: Pusher = pusher ?: config.firebaseServiceAccount
        ?.takeIf { File(it).isFile }
        ?.let { FcmPusher(it, pushTokens) }
        ?: NoPush.also { LoggerFactory.getLogger("Push").info("Push notifications are off (FIREBASE_SERVICE_ACCOUNT not set)") }
    val groups = GroupService(db, social, gateway)
    val messages = MessageService(db, keys, social, gateway, groups, this.pusher, scope)
    val servers = ServerService(db, gateway, this.pusher, scope)
}

fun Application.module() {
    val config = AppConfig.fromEnv()
    val dataSource = Db.dataSource(config)
    Db.migrate(dataSource)
    monitor.subscribe(ApplicationStopped) { dataSource.close() }
    configure(AppDeps(config, dataSource, SmtpMailer(config)))
}

fun Application.configure(deps: AppDeps) {
    configureSerialization()
    configureMonitoring()
    configureStatusPages()
    configureSecurity(deps.tokens, deps.config)
    configureWebSockets()
    configureRouting(deps)
    // Undelivered envelopes are dropped after 30 days.
    launch {
        while (isActive) {
            runCatching { deps.messages.purgeExpired() }
            delay(1.hours)
        }
    }
}
