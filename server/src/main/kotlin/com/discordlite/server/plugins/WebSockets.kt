package com.discordlite.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlin.time.Duration.Companion.seconds

fun Application.configureWebSockets() {
    install(WebSockets) {
        // Pings keep mobile connections alive through NATs and detect dead ones quickly.
        pingPeriod = 25.seconds
        timeout = 60.seconds
        maxFrameSize = 256 * 1024L
    }
}
