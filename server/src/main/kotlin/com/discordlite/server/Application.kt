package com.discordlite.server

import com.discordlite.server.plugins.configureMonitoring
import com.discordlite.server.plugins.configureRouting
import com.discordlite.server.plugins.configureSerialization
import com.discordlite.server.plugins.configureStatusPages
import io.ktor.server.application.Application

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    configureSerialization()
    configureMonitoring()
    configureStatusPages()
    configureRouting()
}
