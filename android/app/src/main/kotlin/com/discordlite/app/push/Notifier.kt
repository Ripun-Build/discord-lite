package com.discordlite.app.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.discordlite.app.MainActivity
import com.discordlite.app.R
import com.discordlite.app.chat.IncomingText
import com.discordlite.app.data.AppSettings
import com.discordlite.app.servers.MentionAlert

/**
 * Shows message notifications, built on the phone from already-decrypted messages.
 *
 * - "Hide notification content" on: just "New message" — no name, no text.
 * - On the lock screen the text is always hidden (a public version says "New message").
 */
class Notifier(private val context: Context, private val settings: AppSettings) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New direct messages"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun show(messages: List<IncomingText>) {
        if (messages.isEmpty() || !canNotify()) return
        messages.groupBy { it.peerId }.forEach { (peerId, fromPeer) ->
            val latest = fromPeer.last()
            val hidden = settings.hideNotificationContent
            val title = if (hidden) "discord-lite" else latest.displayName
            val text = when {
                hidden -> "New message"
                fromPeer.size == 1 -> latest.text
                else -> "${fromPeer.size} new messages · ${latest.text}"
            }
            val publicVersion = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("discord-lite")
                .setContentText("New message")
                .build()
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setAutoCancel(true)
                .setContentIntent(openChat(peerId))
                .build()
            @Suppress("MissingPermission") // checked in canNotify()
            manager.notify(idFor(peerId), notification)
        }
    }

    /**
     * "Bea in #general" — someone @mentioned you in a server. Server channels aren't end-to-end
     * encrypted, but the same privacy settings apply: hidden content shows only "New mention".
     */
    fun showMentions(alerts: List<MentionAlert>) {
        if (alerts.isEmpty() || !canNotify()) return
        alerts.groupBy { it.channelId }.forEach { (channelId, inChannel) ->
            val latest = inChannel.last()
            val hidden = settings.hideNotificationContent
            val key = "channel:${latest.serverId}:$channelId"
            val publicVersion = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("discord-lite")
                .setContentText("New mention")
                .build()
            val text = if (hidden) "New mention" else latest.text
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(if (hidden) "discord-lite" else latest.title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setAutoCancel(true)
                .setContentIntent(openChat(key))
                .build()
            @Suppress("MissingPermission") // checked in canNotify()
            manager.notify(idFor(key), notification)
        }
    }

    /** Opening a chat clears its notification. */
    fun clear(peerId: String) = manager.cancel(idFor(peerId))

    fun clearAll() = manager.cancelAll()

    private fun openChat(peerId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_CHAT, peerId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, idFor(peerId), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun idFor(peerId: String) = peerId.hashCode()

    companion object {
        const val CHANNEL = "messages"
        const val EXTRA_OPEN_CHAT = "open_chat"
    }
}
