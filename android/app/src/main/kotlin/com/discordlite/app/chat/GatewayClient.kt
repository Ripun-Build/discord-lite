package com.discordlite.app.chat

import com.discordlite.app.data.remote.ApiClient
import com.discordlite.app.data.remote.Envelope
import com.discordlite.app.data.remote.GatewayFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * The live connection to the server. Envelopes are handled one at a time, in order, and each is
 * acknowledged only after it has been decrypted and saved — so nothing is lost if the app dies
 * mid-way. Drops reconnect automatically with exponential back-off (1 s → 30 s).
 */
class GatewayClient(
    http: OkHttpClient,
    baseUrl: String,
    private val scope: CoroutineScope,
    private val onEnvelope: suspend (Envelope) -> Unit,
    private val onReady: suspend () -> Unit,
) {
    enum class State { Offline, Connecting, Online }

    private val client = http.newBuilder().pingInterval(30, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
    private val url = baseUrl.replaceFirst("http", "ws") + "v1/gateway"
    private val inbox = Channel<Envelope>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(State.Offline)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var running = false
    private var attempt = 0
    private var reconnect: Job? = null

    init {
        scope.launch {
            for (envelope in inbox) {
                runCatching { onEnvelope(envelope) }
                ack(envelope.id)
            }
        }
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        connect()
    }

    @Synchronized
    fun stop() {
        running = false
        reconnect?.cancel()
        socket?.close(1000, null)
        socket = null
        _state.value = State.Offline
    }

    private fun connect() {
        _state.value = State.Connecting
        socket = client.newWebSocket(Request.Builder().url(url).build(), Listener())
    }

    private fun ack(id: Long) {
        val frame = ApiClient.json.encodeToString(GatewayFrame.serializer(), GatewayFrame("ack", ids = listOf(id)))
        socket?.send(frame)
    }

    @Synchronized
    private fun scheduleReconnect() {
        if (!running) return
        reconnect?.cancel()
        val wait = min(30_000L, 1_000L shl min(attempt, 5))
        attempt++
        reconnect = scope.launch {
            delay(wait)
            synchronized(this@GatewayClient) { if (running) connect() }
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = runCatching { ApiClient.json.decodeFromString(GatewayFrame.serializer(), text) }.getOrNull() ?: return
            when (frame.t) {
                "message" -> frame.envelope?.let { inbox.trySend(it) }
                "ready" -> {
                    _state.value = State.Online
                    scope.launch { runCatching { onReady() } }
                }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(webSocket)

        private fun dropped(webSocket: WebSocket) {
            if (socket !== webSocket) return
            socket = null
            _state.value = State.Offline
            scheduleReconnect()
        }
    }
}
