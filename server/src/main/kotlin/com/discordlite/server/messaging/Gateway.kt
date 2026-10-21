package com.discordlite.server.messaging

import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One frame on the real-time connection.
 *
 * Server → client: `message` (an [Envelope]), `ready` (backlog delivered).
 * Client → server: `ack` (envelope [ids] stored on the device).
 */
@Serializable
data class GatewayFrame(
    val t: String,
    val envelope: Envelope? = null,
    val ids: List<Long>? = null,
)

/**
 * Who is connected right now, and pushes envelopes to them the moment they're queued.
 *
 * In-process for now (one server instance). Running several instances later means fanning out
 * through Redis pub/sub instead; nothing else changes.
 */
class Gateway {
    private val sessions = ConcurrentHashMap<UUID, MutableSet<WebSocketSession>>()

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun register(user: UUID, session: WebSocketSession) {
        sessions.computeIfAbsent(user) { ConcurrentHashMap.newKeySet() }.add(session)
    }

    fun unregister(user: UUID, session: WebSocketSession) {
        sessions.computeIfPresent(user) { _, set -> set.apply { remove(session) }.ifEmpty { null } }
    }

    fun isOnline(user: UUID): Boolean = sessions[user]?.isNotEmpty() == true

    suspend fun deliver(user: UUID, envelope: Envelope) {
        val text = json.encodeToString(GatewayFrame.serializer(), GatewayFrame("message", envelope))
        sessions[user]?.forEach { session -> runCatching { session.send(Frame.Text(text)) } }
    }

    suspend fun send(session: WebSocketSession, frame: GatewayFrame) {
        session.send(Frame.Text(json.encodeToString(GatewayFrame.serializer(), frame)))
    }
}
