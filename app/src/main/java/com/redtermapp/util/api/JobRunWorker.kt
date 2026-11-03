package com.redtermapp.util.api

import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * The thing a scheduled `termux-job-scheduler` job actually does: it
 * fires and posts a notification named after the job's tag, so a
 * schedule the guest set is something the user can see happened rather
 * than a silent no-op. The worker itself is declared in the manifest;
 * WorkManager drives it.
 */
class JobRunWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val tag = inputData.getString(KEY_JOB_TAG) ?: "job"
        val manager = NotificationManagerCompat.from(applicationContext)
        // From API 33 on, posting without the notification grant throws;
        // a job that fired but cannot be seen still succeeded.
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = applicationContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                com.redtermapp.util.AppLog.w(applicationContext, "api-job", "job '$tag' fired but POST_NOTIFICATIONS is not granted; the notification was skipped")
                return Result.success()
            }
        }
        NotificationChannelService.ensure(applicationContext)
        val note = NotificationCompat.Builder(applicationContext, NotificationChannelService.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("RedTerm scheduled job")
            .setContentText("'$tag' fired")
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(tag.hashCode(), note) }
        return Result.success()
    }

    companion object {
        const val KEY_JOB_TAG = "redterm.job.tag"
    }
}

/** The channel the job notification lands in; created once per process. */
internal object NotificationChannelService {
    const val CHANNEL = "redterm"
    private var ensured = false

    fun ensure(context: Context) {
        if (ensured) return
        synchronized(this) {
            if (ensured) return
            if (Build.VERSION.SDK_INT >= 26) {
                val ch = android.app.NotificationChannel(CHANNEL, "RedTerm", android.app.NotificationManager.IMPORTANCE_DEFAULT)
                context.getSystemService(android.app.NotificationManager::class.java)
                    .createNotificationChannel(ch)
            }
            ensured = true
        }
    }
}
