package com.example.neuralaudionode

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/** Minimal arrival notifications. Tapping opens the app (ContactsActivity). */
object Notifications {
    private const val CHANNEL = "voice_messages"
    private var nextId = 1000

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "Voice messages", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    fun newMessage(context: Context, peerShort: String) {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, ContactsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("New voice message")
            .setContentText("From $peerShort")
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(nextId++, n)
    }
}
