package com.discordlite.app.push

import com.discordlite.app.DiscordLiteApp
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Receives Firebase pushes. They carry nothing but `{"t":"wake"}`: on one, the app fetches the
 * encrypted envelopes from our server, decrypts them here on the phone, and shows notifications.
 */
class PushService : FirebaseMessagingService() {
    private val container get() = (application as DiscordLiteApp).container

    override fun onNewToken(token: String) {
        container.scope.launch { container.registerPushToken(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["t"] != "wake") return
        // Runs on a background thread; Android gives high-priority pushes a short window to work.
        runBlocking { withTimeoutOrNull(18_000) { container.catchUpFromPush() } }
    }
}
