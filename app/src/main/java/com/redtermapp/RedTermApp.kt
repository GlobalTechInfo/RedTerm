package com.redtermapp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.redtermapp.util.CrashHandler

class RedTermApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.init(this)
        if (BuildConfig.DEBUG) {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }
        com.github.anrwatchdog.ANRWatchDog().start()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_TERMINAL,
            getString(R.string.notification_channel_terminal),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notification for running terminal sessions"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_TERMINAL = "terminal"
        const val NOTIF_ID_TERMINAL = 1
    }
}
