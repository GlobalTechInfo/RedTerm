package com.redtermapp.ui

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.redtermapp.distro.DistroRunner
import com.redtermapp.util.Notifier
import java.util.concurrent.TimeUnit

/**
 * Runs one scheduled guest job. WorkManager enqueues a periodic or one-time
 * request; this worker loads the job by id, executes the command inside the
 * distro's rootfs via [DistroRunner], records the run (what, when, the exit
 * code, and the tail of the output), and tells the user through a
 * notification.
 *
 * A failure retries on its own backoff — [JobSchedule.retryDelayMinutes] —
 * so "the distro was still booting" does not have to wait a full interval.
 * A retry is a one-time request under a separate unique tag, so it cannot
 * replace the schedule itself.
 */
class ScheduledJobWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.success()
        val isRetry = inputData.getBoolean(KEY_RETRY, false)
        val context = applicationContext
        val job = ScheduledJobs.load(context).firstOrNull { it.id == id }
            ?: return Result.success()
        // A scheduled run must still be due; a retry is an explicit "run now".
        if (!isRetry && !JobSchedule.dueNow(job, System.currentTimeMillis())) {
            return Result.success()
        }

        val result = DistroRunner(context).run(job.distro, job.command, timeoutMinutes = 20)
        val ok = result.succeeded
        val fails = if (ok) 0 else job.consecutiveFails + 1
        val now = System.currentTimeMillis()
        val run = JobHistoryStore.JobRun(
            jobId = id,
            jobName = job.name,
            distro = job.distro,
            command = job.command,
            atMs = now,
            exitCode = if (result.exitCode >= 0) result.exitCode else -1,
            outputTail = JobHistoryStore.outputTailOf(result.output)
        )
        JobHistoryStore.add(context, run)
        // Both outcomes are recorded, because a "last run" that is not the
        // last one is a stale one, and the retry count must not outlive a fix.
        ScheduledJobs.update(
            context,
            job.copy(lastRunMs = now, consecutiveFails = fails)
        )

        val message = if (ok) {
            "job finished"
        } else {
            val tail = run.outputTail
            buildString {
                append(if (result.timedOut) "job timed out" else "job exited ${result.exitCode}")
                if (tail.isNotBlank()) append(" · ").append(tail)
            }
        }
        Notifier.reportCompletion(
            context,
            id.hashCode(),
            job.name,
            message,
            showToast = false
        )

        if (!ok && JobSchedule.shouldRetry(fails)) {
            scheduleRetry(context, id, JobSchedule.retryDelayMinutes(fails))
        }
        return Result.success()
    }

    /**
     * A one-time "run again" under its own tag, [delayMinutes] out.
     *
     * Separate from the schedule's tag on purpose: replacing the schedule to
     * carry a retry would drop the interval a job was set on.
     */
    private fun scheduleRetry(context: Context, id: String, delayMinutes: Int) {
        val req = androidx.work.OneTimeWorkRequestBuilder<ScheduledJobWorker>()
            .setInputData(
                androidx.work.workDataOf(KEY_ID to id, KEY_RETRY to true)
            )
            .setInitialDelay(delayMinutes.toLong(), TimeUnit.MINUTES)
            .build()
        androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
            retryTagFor(id),
            androidx.work.ExistingWorkPolicy.REPLACE,
            req
        )
    }

    companion object {
        const val KEY_ID = "job_id"
        const val KEY_RETRY = "job_retry"

        fun tagFor(id: String) = "redterm-job-$id"
        fun retryTagFor(id: String) = "redterm-job-retry-$id"
    }
}
