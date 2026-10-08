package com.redtermapp.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import java.lang.ref.WeakReference
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

    /** Writes to the app log. Here so callers need only import this one object. */
    fun i(context: Context, tag: String, message: String) = AppLog.i(context, tag, message)

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
        ongoing: Boolean = false,
        /**
         * A stop action. Added whenever the work can be stopped, because an ongoing
         * notification cannot be swiped away: without this the only way to get rid of
         * one is to kill the process.
         */
        cancelAction: Pair<String, Runnable>? = null
    ) {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_OPERATIONS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOngoing(ongoing)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (cancelAction != null) {
            val actionIntent = Intent(context, CancelReceiver::class.java).apply {
                action = CancelReceiver.ACTION
                putExtra(CancelReceiver.EXTRA_ID, id)
            }
            val pending = PendingIntent.getBroadcast(
                context, id, actionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                NotificationCompat.Action.Builder(null, cancelAction.first, pending).build()
            )
        }
        val notification = builder.build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS can be denied; a background job must not crash
            // just because it could not post a notification.
        }
    }

    /**
     * Reports the end of long-running work.
     *
     * Both halves are deliberate. The notification always goes to the system, because
     * the user is expected to have navigated away from the screen that started the
     * job — reporting only while that screen is still in front is a notification that
     * never reaches anyone who does what a notification is for. The toast comes from
     * the application context rather than the activity, so it appears over whichever
     * screen the user has since moved to instead of being lost with the old one.
     *
     * Safe to call from a worker thread: nothing here touches a view.
     */
    fun reportCompletion(
        context: Context,
        id: Int,
        title: String,
        message: String,
        showToast: Boolean = true
    ) {
        notify(context.applicationContext, id, title, message)
        // A toast only when something is actually on screen to show it on.
        //
        // From Android 11 a text toast is confined to this app's own window, so with
        // the app in the background one is silently discarded — posting it anyway
        // looks like it works and tells the user nothing. The notification above is
        // the mechanism for a backgrounded app; the toast is the one that appears
        // over whichever page of the app they have moved on to.
        if (showToast && isAppForeground(context)) {
            // Posted to the main thread because this is called from the worker that
            // did the work. A Toast constructs against the calling thread's Looper and
            // throws on a thread that has none, which crashed the app on every
            // completed recording.
            mainHandler.post {
                android.widget.Toast
                    .makeText(context.applicationContext, message, android.widget.Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    /**
     * Whether any of this app's activities is currently visible.
     *
     * Tracked from the activity lifecycle rather than asked of the system, because
     * there is no public way to ask and `ProcessLifecycleOwner` is a dependency this
     * app does not have.
     */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun isAppForeground(context: Context): Boolean = synchronized(resumedActivities) {
        resumedActivities.removeAll { it.get() == null }
        resumedActivities.isNotEmpty()
    }

    /**
     * Activities currently resumed.
     *
     * Weak references, so an activity that is destroyed while paused cannot be held
     * alive by this set — `Activity.isActive` would have been the obvious way to
     * prune them, and it was removed in API 31.
     */
    private val resumedActivities = mutableSetOf<WeakReference<Activity>>()

    private fun resumed(activity: Activity) {
        synchronized(resumedActivities) {
            resumedActivities.removeAll { it.get() == null || it.get() === activity }
            resumedActivities.add(WeakReference(activity))
        }
    }

    private fun paused(activity: Activity) {
        synchronized(resumedActivities) {
            resumedActivities.removeAll { it.get() == null || it.get() === activity }
        }
    }

    /**
     * Watches every activity in the app.
     *
     * Registered once from the Application rather than from the screens that happen to
     * report something. Asking each screen to remember itself meant only three of them
     * did, so a toast was suppressed on every other page — which is most of them, and
     * all of the ones a user sits on while something finishes.
     */
    fun trackLifecycles(application: android.app.Application) {
        application.registerActivityLifecycleCallbacks(
            object : android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) { resumed(activity) }
                override fun onActivityPaused(activity: Activity) { paused(activity) }
                override fun onActivityCreated(activity: Activity, b: android.os.Bundle?) {}
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, b: android.os.Bundle) {}
                override fun onActivityDestroyed(activity: Activity) { paused(activity) }
            }
        )
    }

    /**
     * Removes a notification outright.
     *
     * Needed because an ongoing notification cannot be swiped away, so a job that
     * raised one and then never replaced it — because the screen that would have
     * replaced it had been left — left something on the notification bar that only
     * went away when the process did. Clearing it when the work starts means a stuck
     * one cannot survive even if the replacement never arrives.
     */
    fun cancel(context: Context, id: Int) {
        try {
            NotificationManagerCompat.from(context).cancel(id)
        } catch (_: SecurityException) {
            // As above: nothing to do, and nothing worth crashing over.
        }
    }
}
