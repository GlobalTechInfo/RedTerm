package com.redtermapp.util.api

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.redtermapp.util.api.ApiRequestParser.ApiRequest

/**
 * `termux-notification-channel`: create a named notification channel so
 * the guest's later `termux-notification` posts land in a channel of its
 * choosing, or delete one. Channels are API 26+, so older devices get a
 * version answer rather than a crash.
 */
object NotificationChannelApi : ApiFeature {

    override val name = "notification-channel"
    override val permissions = emptySet<String>()

    override fun run(context: Context, request: ApiRequest): String {
        if (Build.VERSION.SDK_INT < 26) return "ERROR: notification channels need Android 8.0 or newer\n"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val id = request.str("id") ?: return "ERROR: a channel id is required\n"
        if (request.bool("delete") == true) {
            manager.deleteNotificationChannel(id)
            return "deleted\n"
        }
        val name = request.str("name") ?: id
        val importance = request.int("importance") ?: NotificationManager.IMPORTANCE_DEFAULT
        manager.createNotificationChannel(NotificationChannel(id, name, importance))
        return "created\n"
    }
}
