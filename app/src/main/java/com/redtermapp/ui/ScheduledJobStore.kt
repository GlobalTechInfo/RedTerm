package com.redtermapp.ui

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * A guest job to run in a distro's rootfs on a schedule. Repeating jobs run
 * every [intervalMinutes]; a one-shot job (interval 0) fires at [fireAtMs].
 *
 * The model and the "is it due / when is next" rules are pure so they are
 * unit-tested; only the persistence and the actual running touch the device.
 */
data class ScheduledJob(
    val id: String,
    val name: String,
    val distro: String,
    val command: String,
    val intervalMinutes: Int,
    val fireAtMs: Long = 0L,
    val enabled: Boolean = true,
    val lastRunMs: Long = 0L,
    /** Consecutive failed runs; a success resets it to zero. Drives the retry backoff. */
    val consecutiveFails: Int = 0
) {
    val isRepeating: Boolean get() = intervalMinutes > 0

    companion object {
        fun toJson(j: ScheduledJob): String = JSONObject().apply {
            put("id", j.id)
            put("name", j.name)
            put("distro", j.distro)
            put("command", j.command)
            put("interval", j.intervalMinutes)
            put("fireAt", j.fireAtMs)
            put("enabled", j.enabled)
            put("lastRun", j.lastRunMs)
            put("fails", j.consecutiveFails)
        }.toString()

        fun fromJson(s: String): ScheduledJob? =
            runCatching {
                val o = JSONObject(s)
                ScheduledJob(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    distro = o.optString("distro"),
                    command = o.optString("command"),
                    intervalMinutes = o.optInt("interval", 0),
                    fireAtMs = o.optLong("fireAt", 0L),
                    enabled = o.optBoolean("enabled", true),
                    lastRunMs = o.optLong("lastRun", 0L),
                    consecutiveFails = o.optInt("fails", 0)
                )
            }.getOrNull()

        fun encodeAll(jobs: List<ScheduledJob>): String =
            JSONArray().apply { jobs.forEach { put(JSONObject(toJson(it))) } }.toString()

        fun decodeAll(s: String): List<ScheduledJob> =
            runCatching {
                val arr = JSONArray(s)
                (0 until arr.length()).mapNotNull { fromJson(arr.optString(it)) }
            }.getOrDefault(emptyList())
    }
}

/** Pure scheduling decisions, independent of the device clock. */
object JobSchedule {

    private const val MIN_MS = 60_000L

    /** True when a repeating job should run now, or a one-shot is overdue. */
    fun dueNow(job: ScheduledJob, nowMs: Long): Boolean = when {
        !job.enabled -> false
        job.isRepeating -> {
            val period = job.intervalMinutes.toLong() * MIN_MS
            period > 0 && nowMs - job.lastRunMs >= period
        }
        else -> job.fireAtMs > 0L && job.fireAtMs <= nowMs && job.lastRunMs < job.fireAtMs
    }

    /** When a job will next run; 0 when it will not (disabled or one-shot already fired). */
    fun nextRun(job: ScheduledJob, nowMs: Long): Long = when {
        !job.enabled -> 0L
        job.isRepeating -> maxOf(nowMs, job.lastRunMs) + job.intervalMinutes.toLong() * MIN_MS
        else -> if (job.fireAtMs > nowMs) job.fireAtMs else 0L
    }

    /** A one-shot job that has fired is kept but no longer due. */
    fun isSatisfied(job: ScheduledJob): Boolean =
        !job.isRepeating && job.lastRunMs >= job.fireAtMs && job.fireAtMs > 0L

    /**
     * How many consecutive failures a job retries before falling back to its
     * schedule and waiting for the user.
     *
     * Six: enough to ride out a transient blip (network down, a service
     * still booting), few enough that a genuinely broken job stops hammering
     * every few minutes forever.
     */
    const val MAX_CONSECUTIVE_FAILURES = 6

    /**
     * The delay before the Nth retry after consecutive failures.
     *
     * Doubling, 1-2-4-8, capped at fifteen: the first retry is quick because
     * the most common cause is "the distro was still starting", and the cap
     * means a dead job costs one run every fifteen minutes, not a storm.
     * [f] is 1-based: the first failure waits one minute.
     */
    fun retryDelayMinutes(f: Int): Int = when {
        f <= 0 -> 0
        f == 1 -> 1
        f == 2 -> 2
        f == 3 -> 4
        f == 4 -> 8
        else -> 15
    }

    /** Whether a failed run still earns an immediate retry, or must wait for the schedule. */
    fun shouldRetry(consecutiveFails: Int): Boolean =
        consecutiveFails in 1..MAX_CONSECUTIVE_FAILURES
}

/** Persistence for scheduled jobs: survives process death, unlike in-memory state. */
object ScheduledJobs {
    private const val PREFS = "scheduled_jobs"
    private const val KEY = "jobs"

    fun load(context: Context): List<ScheduledJob> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "")
            ?.let(ScheduledJob::decodeAll)
            ?: emptyList()

    fun save(context: Context, jobs: List<ScheduledJob>) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY, ScheduledJob.encodeAll(jobs)) }

    fun update(context: Context, job: ScheduledJob): List<ScheduledJob> {
        val jobs = load(context).toMutableList()
        val i = jobs.indexOfFirst { it.id == job.id }
        if (i >= 0) jobs[i] = job else jobs.add(job)
        save(context, jobs)
        return jobs
    }
}
