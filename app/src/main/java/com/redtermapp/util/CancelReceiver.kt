package com.redtermapp.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles the "Cancel" button on a long-running job's notification.
 *
 * A receiver rather than a lambda, because a notification action is delivered by the
 * system to a component it can name — a PendingIntent pointing at a lambda does
 * nothing at all, which is how the button appears and then does nothing when tapped.
 */
class CancelReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val id = intent.getIntExtra(EXTRA_ID, 0)
        // Both: the notice goes away, and anything still running is told to stop.
        // Clearing it alone would leave the work running with nothing to show for it.
        OngoingJobs.cancel(id)
        Notifier.cancel(context, id)
    }

    companion object {
        const val ACTION = "com.redtermapp.action.CANCEL_JOB"
        const val EXTRA_ID = "job_id"
    }
}
