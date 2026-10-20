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
import com.discordlite.server.social.SocialService
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import javax.sql.DataSource

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

/** Everything the routes need, built once. Tests build their own with a test database and mailer. */
class AppDeps(val config: AppConfig, dataSource: DataSource, mailer: Mailer) {
    val db = Db(dataSource)
    val tokens = TokenService(config, db)
    val auth = AuthService(db, tokens, Codes(config.codePepper), mailer)
    val social = SocialService(db)
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
    configureRouting(deps)
}
