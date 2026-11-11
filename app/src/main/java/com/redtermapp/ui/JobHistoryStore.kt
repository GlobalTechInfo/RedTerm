package com.redtermapp.ui

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * The record of scheduled-job runs, newest first.
 *
 * The worker appends one entry per run and trims the record, so a job that
 * fires every fifteen minutes for a month stays bounded. A job that has
 * never run has no entries, which the screen says plainly instead of
 * showing a stale "last run" it never had.
 *
 * [encode]/[decode] and [outputTailOf] are pure and unit-tested; the prefs
 * round trip around them is the only I/O.
 */
object JobHistoryStore {

    /** One execution of a job. [exitCode] is -1 when the run did not start. */
    data class JobRun(
        val jobId: String,
        val jobName: String,
        val distro: String,
        val command: String,
        val atMs: Long,
        val exitCode: Int,
        val outputTail: String
    )

    const val MAX_RUNS = 200
    const val TAIL_CHARS = 300

    private const val PREFS = "job_history"
    private const val KEY = "runs"

    fun recent(context: Context, limit: Int = 20): List<JobRun> =
        decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))
            .take(limit)

    fun forJob(context: Context, jobId: String, limit: Int = 20): List<JobRun> =
        recent(context, MAX_RUNS).filter { it.jobId == jobId }.take(limit)

    fun add(context: Context, run: JobRun) {
        val runs = (decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))
            .toMutableList()).also { it.add(0, run) }
        if (runs.size > MAX_RUNS) runs.removeAt(runs.size - 1)
        save(context, runs)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(KEY) }
    }

    /** Drop the record of one job: its runs are meaningless without the job. */
    fun removeJob(context: Context, jobId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val runs = decode(prefs.getString(KEY, null)).filterNot { it.jobId == jobId }
        save(context, runs)
    }

    private fun save(context: Context, runs: List<JobRun>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY, encode(runs))
        }
    }

    fun encode(runs: List<JobRun>): String =
        JSONArray().apply {
            for (r in runs) {
                put(
                    JSONObject()
                        .put("jobId", r.jobId)
                        .put("jobName", r.jobName)
                        .put("distro", r.distro)
                        .put("command", r.command)
                        .put("atMs", r.atMs)
                        .put("exitCode", r.exitCode)
                        .put("outputTail", r.outputTail)
                )
            }
        }.toString()

    /** A corrupt record is no history, not a crash in the job screen. */
    fun decode(json: String?): List<JobRun> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                if (o.optString("jobId").isBlank()) return@mapNotNull null
                JobRun(
                    jobId = o.getString("jobId"),
                    jobName = o.optString("jobName", ""),
                    distro = o.optString("distro", ""),
                    command = o.optString("command", ""),
                    atMs = o.optLong("atMs"),
                    exitCode = o.optInt("exitCode", -1),
                    outputTail = o.optString("outputTail", "")
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * The end of an output, which is the part that explains a failure.
     *
     * The tail, not the head: the interesting thing about a failed run is
     * what it died doing, and the error is at the end. Trimmed to a few
     * lines so a notification and a dialog row can carry it without
     * scrolling.
     */
    fun outputTailOf(output: String, maxChars: Int = TAIL_CHARS): String {
        val text = output.trim()
        if (text.length <= maxChars) return text
        val cut = text.substring(text.length - maxChars)
        // Break at the next line so the tail starts at a line, not mid-word.
        return "\n" + cut.substringAfter('\n')
    }
}
