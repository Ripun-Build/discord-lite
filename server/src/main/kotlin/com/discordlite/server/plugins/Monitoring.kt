package com.discordlite.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import org.slf4j.event.Level

fun Application.configureMonitoring() {
    install(CallLogging) {
        level = Level.INFO
        // Log method, path and status only — never headers, bodies or client IPs.
        format { call -> "${call.request.httpMethod.value} ${call.request.path()} -> ${call.response.status()}" }
    }
}
