package com.redtermapp.util.api

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.StatusBarNotification
import com.redtermapp.util.api.ApiRequestParser.ApiRequest

/**
 * `termux-notification` and friends. Posting on API 33+ needs the
 * notification grant, so that check is per-op inside [run]: listing,
 * removing and clearing are not blocked by it, which is also what
 * `termux-notification-list` expects when the user has denied
 * notifications.
 */
object NotificationApi : ApiFeature {

    override val name = "notification"
    override val permissions = emptySet<String>()

    private const val CHANNEL_ID = "redterm"

    override fun run(context: Context, request: ApiRequest): String {
        val extras = request.extras
        val op = extras.str("op") ?: "post"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return when (op) {
            "post" -> post(context, manager, extras)
            "remove" -> {
                val id = extras.int("id")
                    ?: return "ERROR: notification remove needs --ei id\n"
                manager.cancel(id)
                ""
            }
            "clear" -> {
                manager.cancelAll()
                ""
            }
            "list" -> buildList(manager.activeNotifications.toList())
            else -> "ERROR: unknown notification op '$op'\n"
        }
    }

    private fun post(context: Context, manager: NotificationManager, extras: ApiRequestParser.ApiExtras): String {
        if (Build.VERSION.SDK_INT < 26) return "ERROR: notifications need Android 8.0 or newer\n"
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return "ERROR: post a notification needs the notification grant; allow it in the system Settings, RedTerm, Notifications\n"
        }
        val channel = NotificationChannel(CHANNEL_ID, "RedTerm", NotificationManager.IMPORTANCE_DEFAULT)
        manager.createNotificationChannel(channel)
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(extras.str("title") ?: "RedTerm")
            .setContentText(extras.str("message") ?: "")
        val id = extras.int("id") ?: java.util.UUID.randomUUID().hashCode()
        extras.str("group")?.let { builder.setGroup(it) }
        manager.notify(id, builder.build())
        return "{\"id\":$id}"
    }

    fun buildList(notifications: List<StatusBarNotification>): String {
        val sb = StringBuilder("[")
        notifications.forEachIndexed { i, n ->
            val text = n.notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            val title = n.notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            if (i > 0) sb.append(',')
            sb.append("{\"id\":").append(n.id)
            sb.append(",\"tag\":").append(json(n.tag ?: ""))
            sb.append(",\"package\":").append(json(n.packageName ?: ""))
            if (title != null) sb.append(",\"title\":").append(json(title))
            if (text != null) sb.append(",\"text\":").append(json(text))
            sb.append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    private fun json(value: String): String =
        ApiJson.string(value)
}
