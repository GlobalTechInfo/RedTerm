package com.redtermapp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.google.android.material.color.DynamicColors
import com.redtermapp.util.CrashHandler

class RedTermApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
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
        // Before anything can post a completion toast, so "is anything on screen"
        // is answered for every activity rather than the few that remember to ask.
        com.redtermapp.util.Notifier.trackLifecycles(this)
        createNotificationChannel()
        loadRootfsCatalogue()
    }

    /**
     * Points the distro catalogue at the published images, when there are any.
     *
     * The cached index is applied synchronously and before any activity can read
     * [com.redtermapp.distro.DistroRegistry], so the catalogue is populated from the first
     * frame and never blocks on a network call. The refresh is on a background thread and
     * only replaces the catalogue if it parses and changes something, so a failure leaves
     * the built-in list in place — which is the point: an install must work when the
     * network does not, and the built-in list is a working answer.
     */
    private fun loadRootfsCatalogue() {
        com.redtermapp.distro.RootfsCatalog.applyCached(this)
        com.redtermapp.distro.RootfsCatalog.refresh(this)
    }

    /**
     * Under memory pressure, decoded icons go first.
     *
     * They are pure decoration and every one of them can be rebuilt from the APK, so
     * dropping them costs nothing but a re-decode. Not registering this would leave the
     * cache holding bitmaps the system is asking the app to release.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            com.redtermapp.distro.DistroIconStore.trim()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        com.redtermapp.distro.DistroIconStore.trim()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
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
