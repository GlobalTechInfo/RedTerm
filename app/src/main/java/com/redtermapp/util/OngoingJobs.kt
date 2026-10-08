package com.redtermapp.util

import java.util.concurrent.ConcurrentHashMap

/**
 * The cancel handles for jobs that are currently running, by notification id.
 *
 * The notification's Cancel button is delivered by the system to a receiver with no
 * access to the thread that started the work, so the handle it has to reach is kept
 * here rather than in the activity that began the job — the activity may well have
 * been destroyed by the time the button is pressed, which is the normal case.
 */
object OngoingJobs {

    private val jobs = ConcurrentHashMap<Int, () -> Unit>()

    /** [onCancel] runs when the job is cancelled, not when it is registered. */
    fun register(id: Int, onCancel: () -> Unit) {
        jobs[id] = onCancel
    }

    fun clear(id: Int) {
        jobs.remove(id)
    }

    fun cancel(id: Int) {
        jobs.remove(id)?.invoke()
    }

    fun isRunning(id: Int): Boolean = jobs.containsKey(id)
}
