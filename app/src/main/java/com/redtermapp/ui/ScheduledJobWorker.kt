package com.redtermapp.ui

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.redtermapp.distro.DistroRunner
import com.redtermapp.util.Notifier

/**
 * Runs one scheduled guest job. WorkManager enqueues a periodic or one-time
 * request; this worker loads the job by id, executes the command inside the
 * distro's rootfs via [DistroRunner], records when it ran so a restart does
 * not double-fire, and tells the user through a notification.
 */
class ScheduledJobWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.success()
        val context = applicationContext
        val job = ScheduledJobs.load(context).firstOrNull { it.id == id }
            ?: return Result.success()
        if (!JobSchedule.dueNow(job, System.currentTimeMillis())) return Result.success()

        val runner = DistroRunner(context)
        val result = runner.run(job.distro, job.command, timeoutMinutes = 20)

        // Record the run so the next due check and any missed-window catch-up
        // compare against when we actually did the work, not when the request
        // was enqueued.
        ScheduledJobs.update(
            context,
            job.copy(lastRunMs = System.currentTimeMillis())
        )

        val ok = result.exitCode == 0
        Notifier.reportCompletion(
            context,
            id.hashCode(),
            job.name,
            if (ok) "job finished" else "job exited ${result.exitCode}",
            showToast = false
        )
        return Result.success()
    }

    companion object {
        const val KEY_ID = "job_id"

        fun tagFor(id: String) = "redterm-job-$id"
    }
}
