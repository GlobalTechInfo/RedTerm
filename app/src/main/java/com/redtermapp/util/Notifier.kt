package com.redtermapp.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.redtermapp.R
import com.redtermapp.ui.MainActivity

/**
 * Completion notices for long-running work (package updates, base image
 * updates, backups) that the user started and then walked away from.
 */
object Notifier {

    const val CHANNEL_OPERATIONS = "operations"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_OPERATIONS) != null) return
        val channel = NotificationChannel(
            CHANNEL_OPERATIONS,
            context.getString(R.string.channel_operations),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.channel_operations_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun notify(
        context: Context,
        id: Int,
        title: String,
        text: String,
        ongoing: Boolean = false
    ) {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_OPERATIONS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOngoing(ongoing)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS can be denied; a background job must not crash
            // just because it could not post a notification.
        }
    }
}
