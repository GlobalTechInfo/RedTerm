package com.redtermapp.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import androidx.core.app.NotificationCompat
import com.redtermapp.R
import com.redtermapp.RedTermApp
import com.redtermapp.ui.TerminalActivity
import java.io.File

class TerminalService : Service() {

    companion object {
        const val ACTION_ACQUIRE = "com.redtermapp.action.ACQUIRE_WAKELOCK"
        const val ACTION_RELEASE = "com.redtermapp.action.RELEASE_WAKELOCK"
        const val ACTION_EXIT = "com.redtermapp.action.EXIT"

        private const val TAG = "TerminalService"

        /** How often the tick runs, in milliseconds. */
        private const val TICK_MILLIS = 1000L

        /**
         * The lock's own countdown. Re-armed every [WAKE_LOCK_REARM_AFTER_MILLIS],
         * so it is many times over before it could expire.
         */
        private const val WAKE_LOCK_REFRESH_MILLIS = 5 * 60 * 1000L

        /** Comfortably inside [WAKE_LOCK_REFRESH_MILLIS]. */
        private const val WAKE_LOCK_REARM_AFTER_MILLIS = 60 * 1000L
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val lastCpuTicks = HashMap<Int, Long>()
    private var cpuPct = 0
    private val cpuHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val cpuRunnable = object : Runnable {
        override fun run() {
            updateCpuLoad()
            keepWakeLockHeld()
            cpuHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (!SessionKeepAwake.isEnabled(this)) {
            // Started with the switch off — from the quick settings tile, say. Nothing
            // to hold, so no notification and no wake lock: with the switch off the app
            // is meant to be an ordinary app, not a foreground service in disguise.
            stopSelf()
            return
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, TerminalActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, RedTermApp.CHANNEL_TERMINAL)
            .setContentTitle("RedTerm")
            .setContentText("Starting...")
            .setSmallIcon(com.redtermapp.R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        startForeground(RedTermApp.NOTIF_ID_TERMINAL, notif)
        acquireWakeLock()
        updateNotification()
        cpuHandler.postDelayed(cpuRunnable, 1000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // Both go through the setting, not just the lock. Flipping the lock on
            // its own left the preference saying "on", so the lock was acquired again
            // the moment the service was next started — the button appeared to do
            // nothing.
            ACTION_ACQUIRE -> {
                SessionKeepAwake.record(this, true)
                acquireWakeLock()
                updateNotification()
            }
            ACTION_RELEASE -> {
                // Off means off, so the service goes too: a foreground service on its
                // own keeps the process alive, which is the thing being switched off.
                SessionKeepAwake.record(this, false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_EXIT -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                Process.killProcess(Process.myPid())
            }
            else -> {
                if (wakelockEnabled()) acquireWakeLock() else releaseWakeLock()
                updateNotification()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cpuHandler.removeCallbacks(cpuRunnable)
        releaseWakeLock()
        super.onDestroy()
    }

    private fun updateCpuLoad() {
        val sessions = com.redtermapp.ui.TerminalViewModel.get(application).sessions.value
        val pids = mutableListOf<Int>()
        for (s in sessions) {
            val pid = s.pid
            if (pid > 0) {
                pids.add(pid)
                collectChildren(pid, pids)
            }
        }
        val current = HashMap<Int, Long>()
        for (pid in pids) {
            current[pid] = readCpuTicks(pid)
        }
        val now = current.values.sum()
        val prev = lastCpuTicks.values.sum()
        val delta = (now - prev).coerceAtLeast(0)
        lastCpuTicks.clear()
        lastCpuTicks.putAll(current)
        val pct = delta.toInt().coerceIn(0, 400)
        if (pct != cpuPct) {
            cpuPct = pct
            updateNotification()
        }
    }

    private fun collectChildren(pid: Int, out: MutableList<Int>) {
        try {
            val childrenFile = File("/proc/$pid/task/$pid/children")
            if (!childrenFile.exists()) return
            for (child in childrenFile.readText().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                val childPid = child.toIntOrNull() ?: continue
                out.add(childPid)
                collectChildren(childPid, out)
            }
        } catch (_: Exception) {
        }
    }

    private fun readCpuTicks(pid: Int): Long {
        return try {
            val stat = File("/proc/$pid/stat").readText()
            val after = stat.substringAfterLast(")")
            val parts = after.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.size < 15) 0L else (parts[11].toLongOrNull() ?: 0L) + (parts[12].toLongOrNull() ?: 0L)
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Holds the CPU awake for as long as a session is open.
     *
     * `acquire(30 * 60 * 1000)` was here with nothing re-arming it, and it was the
     * reason a session still died at half an hour: a wake lock with a timeout releases
     * *itself* when the timeout expires, so after thirty minutes the guarantee was
     * silently gone and a connection with the screen off stalled.
     *
     * So the timeout is now short and re-armed continuously by [keepWakeLockHeld]
     * rather than long and left to lapse. What actually ends the lock is this service
     * dying or the user switching it off — the countdown is only there so an unbounded
     * acquire cannot outlive a wedged process, and it is refreshed many times over
     * before it could ever expire.
     */
    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RedTermApp:TerminalWakeLock"
        ).apply { setReferenceCounted(false) }
        wakeLock?.acquire(WAKE_LOCK_REFRESH_MILLIS)
        ticksSinceRearm = 0
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /**
     * Keeps the lock alive, and restores it if anything took it away.
     *
     * Two jobs. Re-arming resets the countdown well before it could expire, so the
     * session is not left without a guarantee even momentarily. And re-acquiring when
     * the lock is *not* held covers the vendors that drop a partial lock anyway — some
     * OEM kernels release it under memory pressure regardless of the timeout, and the
     * symptom is the same mysterious mid-session stall.
     *
     * Runs on the existing one-second tick, so there is no second timer to stop and
     * nothing to leak.
     */
    private fun keepWakeLockHeld() {
        if (!wakelockEnabled()) return
        if (wakeLock?.isHeld != true) {
            acquireWakeLock()
            android.util.Log.i(TAG, "wake lock was not held; re-acquired")
            return
        }
        if (++ticksSinceRearm * TICK_MILLIS >= WAKE_LOCK_REARM_AFTER_MILLIS) {
            // acquire() again on a held lock restarts its countdown.
            wakeLock?.acquire(WAKE_LOCK_REFRESH_MILLIS)
            ticksSinceRearm = 0
        }
    }

    private var ticksSinceRearm = 0

    /** The one place the setting is read, so the two callers cannot disagree. */
    private fun wakelockEnabled(): Boolean = SessionKeepAwake.isEnabled(this)

    private fun updateNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, TerminalActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isHeld = wakeLock?.isHeld == true
        val wakelockStatus = if (isHeld) "\u25CF" else "\u25CB"

        val builder = NotificationCompat.Builder(this, RedTermApp.CHANNEL_TERMINAL)
            .setContentTitle("RedTerm - ${sessionTitle()}")
            .setContentText("CPU $cpuPct% | $wakelockStatus Wake lock | Tap to open")
            .setSmallIcon(com.redtermapp.R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (isHeld) {
            val releaseIntent = Intent(this, TerminalService::class.java).apply { action = ACTION_RELEASE }
            val releasePI = PendingIntent.getService(
                this, 1, releaseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(NotificationCompat.Action.Builder(null, "Release", releasePI).build())
        } else {
            val acquireIntent = Intent(this, TerminalService::class.java).apply { action = ACTION_ACQUIRE }
            val acquirePI = PendingIntent.getService(
                this, 2, acquireIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(NotificationCompat.Action.Builder(null, "Acquire", acquirePI).build())
        }

        val exitIntent = Intent(this, TerminalService::class.java).apply { action = ACTION_EXIT }
        val exitPI = PendingIntent.getService(
            this, 3, exitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(NotificationCompat.Action.Builder(null, "Exit", exitPI).build())

        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(RedTermApp.NOTIF_ID_TERMINAL, builder.build())
    }

    /**
     * What the running sessions actually are.
     *
     * This used to list the installed directories and take the first one, so the
     * notification said "Alpine" on a device whose only open session was an SSH
     * connection, and "Alpine" on a device with four sessions where the user was
     * looking at Debian. With several sessions of several servers open, the only
     * honest short answer is how many there are.
     */
    private fun sessionTitle(): String {
        val sessions = com.redtermapp.ui.TerminalViewModel.get(application).sessions.value
        if (sessions.isEmpty()) return "Terminal"
        val labels = sessions.mapNotNull { session ->
            when (val d = com.redtermapp.ui.TerminalViewModel.get(application).descriptorFor(session)) {
                is com.redtermapp.ui.SessionDescriptor.Local ->
                    d.distro.replaceFirstChar { it.uppercase() }
                is com.redtermapp.ui.SessionDescriptor.Ssh -> d.label.ifBlank { d.host }
                else -> session.mSessionName.takeIf { it.isNotBlank() }
            }
        }.distinct()
        return if (labels.size == 1) {
            labels.first()
        } else {
            resources.getQuantityString(
                R.plurals.sessions_notification, labels.size, labels.size
            )
        }
    }
}
