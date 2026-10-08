package com.redtermapp.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.redtermapp.ui.TerminalViewModel

/**
 * The one setting behind "keep sessions running".
 *
 * On: a foreground service holds a partial wake lock with no timeout, so a session
 * survives the app going to background, the screen going off and the device sleeping
 * for as long as the user leaves it — the behaviour Termux has. Off: neither is
 * started, and the app is an ordinary app whose sessions last only as long as its
 * process does.
 *
 * It used to be read in two places with two different defaults — the service assumed
 * on, the quick panel assumed off — so on a fresh install the lock was held while the
 * panel showed it as off, and the first tap on that button took the sessions down with
 * no way to tell why.
 */
object SessionKeepAwake {

    const val PREFS = "settings"
    const val KEY = "wakelock"

    /**
     * On by default.
     *
     * A session is something the user deliberately opened and expects to still be
     * there when they come back; leaving the app or locking the screen is not a
     * request to end it. The battery cost is the point of the switch.
     */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        record(context, enabled)
        if (enabled) {
            ContextCompat.startForegroundService(
                context, Intent(context, TerminalService::class.java)
            )
            return
        }
        // Off means off: no wake lock *and* no foreground service, because a
        // foreground service on its own keeps the process alive in the background.
        // Leaving it running would mean "off" still gave long-running sessions, which
        // is the opposite of what the switch says. Sessions that are open stay open
        // while the app is in front of the user; they go when the process is
        // reclaimed, as they would in any other app.
        context.stopService(Intent(context, TerminalService::class.java))
    }

    /**
     * Records the choice without touching the service.
     *
     * For use *from* the service, where starting or stopping itself would be
     * circular. Every path has to write the preference — a lock released from the
     * notification while the setting still says "on" is simply acquired again the
     * next time the service is started.
     */
    fun record(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY, enabled) }
    }

    /**
     * Whether the app should be holding itself alive at all.
     *
     * The only question a foreground-service start has to answer, so it is asked in
     * one place.
     */
    fun shouldRunPersistent(context: Context): Boolean = isEnabled(context) && hasSessions(context)

    /**
     * Whether a session is open.
     *
     * The service is started on resume when sessions exist and stopped when the last
     * one goes, so this is what tells a "release" from a "please start".
     */
    private fun hasSessions(context: Context): Boolean =
        TerminalViewModel.get(context.applicationContext as android.app.Application)
            .sessions.value.isNotEmpty()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}