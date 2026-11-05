package com.redtermapp.util.api

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.redtermapp.util.api.ApiRequestParser.ApiRequest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * `termux-job-scheduler`: schedule a job to fire on a period (or once),
 * cancel one by id, cancel all, and list the pending ones. The reference
 * is built on `JobScheduler`, which the platform has deprecated, so this
 * uses `WorkManager` — the API it was replaced by — and the scheduled
 * job is a [JobRunWorker] that makes the firing observable.
 */
object JobScheduleApi : ApiFeature {

    override val name = "job-scheduler"
    override val permissions = emptySet<String>()

    private const val TAG_PREFIX = "redterm-job:"
    private const val MIN_PERIOD_MS = 15 * 60 * 1000L // WorkManager's floor

    override fun run(context: Context, request: ApiRequest): String {
        val wm = WorkManager.getInstance(context)
        if (request.bool("cancel_all") == true) {
            wm.cancelAllWorkByTag(TAG_PREFIX.trimEnd(':'))
            return "cancelled all redterm jobs\n"
        }
        if (request.bool("cancel") == true) {
            val id = request.str("job_id")
            if (id.isNullOrBlank()) return "ERROR: job id not passed\n"
            val uuid = try {
                UUID.fromString(id)
            } catch (e: IllegalArgumentException) {
                return "ERROR: '$id' is not a job id\n"
            }
            wm.cancelWorkById(uuid)
            return "cancelled\n"
        }
        return if (request.str("script").isNullOrBlank()) {
            list(wm)
        } else {
            schedule(wm, request)
        }
    }

    private fun schedule(wm: WorkManager, request: ApiRequest): String {
        val tag = request.str("script") ?: return "ERROR: no job name given\n"
        val uniqueTag = TAG_PREFIX + tag
        val periodMs = (request.int("period_ms") ?: 0).toLong()
        val input = Data.Builder().putString(JobRunWorker.KEY_JOB_TAG, tag).build()
        val constraints = androidx.work.Constraints.Builder()
            .setRequiresBatteryNotLow(request.bool("battery_not_low") == true)
            .setRequiresStorageNotLow(request.bool("storage_not_low") == true)
            .setRequiresCharging(request.bool("charging") == true)
            .build()

        if (periodMs > 0) {
            val req = androidx.work.PeriodicWorkRequest.Builder(
                JobRunWorker::class.java,
                periodMs.coerceAtLeast(MIN_PERIOD_MS),
                TimeUnit.MILLISECONDS
            )
                .setInputData(input)
                .setConstraints(constraints)
                .build()
            wm.enqueueUniquePeriodicWork(uniqueTag, ExistingPeriodicWorkPolicy.REPLACE, req)
            return "scheduled '$tag' every ${periodMs.coerceAtLeast(MIN_PERIOD_MS)} ms\n"
        }
        val req = OneTimeWorkRequest.Builder(JobRunWorker::class.java)
            .setInputData(input)
            .setConstraints(constraints)
            .addTag(uniqueTag)
            .build()
        wm.enqueueUniqueWork(uniqueTag, ExistingWorkPolicy.REPLACE, req)
        return "scheduled '$tag' once\n"
    }

    private fun list(wm: WorkManager): String {
        val sb = StringBuilder("[")
        var first = true
        val all = runCatching {
            wm.getWorkInfos(androidx.work.WorkQuery.fromStates(*androidx.work.WorkInfo.State.values())).get()
        }.getOrNull() ?: emptyList()
        for (info in all) {
            val jobTag = info.tags.firstOrNull { it.startsWith(TAG_PREFIX) } ?: continue
            if (!first) sb.append(",")
            first = false
            sb.append('{')
                .append("\"id\":").append(json(info.id.toString()))
                .append(",\"state\":").append(json(info.state.name))
                .append(",\"tag\":").append(json(jobTag.substringAfter(':')))
                .append('}')
        }
        sb.append(']')
        return sb.toString() + "\n"
    }

    private fun json(s: String): String = "\"${s.replace("\"", "\\\"")}\""
}
