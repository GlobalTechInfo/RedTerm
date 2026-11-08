package com.redtermapp.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.redtermapp.R
import com.redtermapp.distro.DistroInstaller
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Schedule guest jobs — commands to run inside a distro's rootfs on an
 * interval or at a fixed time. Repeating jobs run as a periodic work item,
 * one-shots as a one-time request; both report through a notification.
 */
class ScheduledJobsActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        body.addView(TextView(this).apply {
            setText(R.string.scheduled_jobs)
            textSize = 20f
        })
        body.addView(ScreenWidgets.actionButton(this, R.string.scheduled_add) { addJob() })
        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(container)

        val scroll = ScrollView(this).apply { addView(body) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        ScreenToolbar.install(this, root, getString(R.string.scheduled_jobs))
        setContentView(root)
        load()
    }

    private fun load() {
        Thread({
            val jobs = ScheduledJobs.load(applicationContext)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                container.removeAllViews()
                if (jobs.isEmpty()) {
                    container.addView(TextView(this).apply {
                        setText(R.string.scheduled_jobs_none)
                        setTextColor(mutedTextColor())
                    })
                    return@runOnUiThread
                }
                for (job in jobs) container.addView(card(job))
            }
        }, "redterm-jobs-list").start()
    }

    private fun card(job: ScheduledJob): View {
        val pad = (10 * resources.displayMetrics.density).toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(cardColor())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val b = (8 * resources.displayMetrics.density).toInt()
                setMargins(0, 0, 0, b)
            }
        }
        val sched = if (job.isRepeating)
            getString(R.string.scheduled_every, job.intervalMinutes)
        else
            getString(R.string.scheduled_once)
        val head = ScreenWidgets.headerRow(this).apply {
            addView(TextView(this@ScheduledJobsActivity).apply {
                text = job.name
                textSize = 16f
                setTextColor(cardTextColor())
            })
            addView(TextView(this@ScheduledJobsActivity).apply {
                text = sched
                textSize = 12f
                setTextColor(if (job.enabled) accentColor() else mutedTextColor())
            })
        }
        card.addView(head)
        card.addView(TextView(this).apply {
            text = getString(R.string.scheduled_job_line, job.distro, job.command)
            textSize = 12f
            setTextColor(mutedTextColor())
        })
        val next = JobSchedule.nextRun(job, System.currentTimeMillis())
        if (next > 0L) {
            card.addView(TextView(this).apply {
                text = getString(
                    R.string.scheduled_next,
                    java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(next))
                )
                textSize = 11f
                setTextColor(mutedTextColor())
            })
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(ScreenWidgets.actionButton(this,
            if (job.enabled) R.string.scheduled_disable else R.string.scheduled_enable) {
            val wm = WorkManager.getInstance(this)
            if (job.enabled) {
                wm.cancelUniqueWork(ScheduledJobWorker.tagFor(job.id))
                ScheduledJobs.update(this, job.copy(enabled = false))
            } else {
                enqueue(job)
                ScheduledJobs.update(this, job.copy(enabled = true))
            }
            load()
        })
        row.addView(ScreenWidgets.dangerButton(this, R.string.delete) {
            WorkManager.getInstance(this).cancelUniqueWork(ScheduledJobWorker.tagFor(job.id))
            ScheduledJobs.save(
                this,
                ScheduledJobs.load(this).filter { it.id != job.id }
            )
            load()
        })
        card.addView(row)
        return card
    }

    private fun addJob() {
        val distros = DistroInstaller(applicationContext).getInstalledDistros()
        val name = EditText(this).apply { hint = getString(R.string.scheduled_job_name); setTextColor(context.cardTextColor()); setHintTextColor(context.mutedTextColor()) }
        val distro = ScreenWidgets.spinner(
            this, distros.map { com.redtermapp.distro.DistroBrand.displayNameFor(it) }
        )
        val command = EditText(this).apply {
            hint = getString(R.string.scheduled_job_command)
            minLines = 2
            setTextColor(context.cardTextColor())
            setHintTextColor(context.mutedTextColor())
        }
        val interval = EditText(this).apply {
            hint = getString(R.string.scheduled_job_interval)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(context.cardTextColor())
            setHintTextColor(context.mutedTextColor())
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (12 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            addView(name)
            addView(TextView(this@ScheduledJobsActivity).apply {
                text = getString(R.string.scheduled_distro)
                setPadding(0, p, 0, 0)
            })
            addView(distro)
            addView(command)
            addView(interval)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.scheduled_add)
            .setView(panel)
            .setPositiveButton(R.string.save) { _, _ ->
                val minutes = interval.text?.toString()?.toIntOrNull() ?: 0
                val job = ScheduledJob(
                    id = System.currentTimeMillis().toString(),
                    name = name.text?.toString()?.takeIf { it.isNotBlank() } ?: "Job",
                    distro = distros.getOrElse(distro.selectedItemPosition) { distros.firstOrNull() ?: "" },
                    command = command.text?.toString()?.takeIf { it.isNotBlank() } ?: "true",
                    intervalMinutes = minutes.coerceAtLeast(0),
                    fireAtMs = if (minutes == 0) System.currentTimeMillis() + 60_000L else 0L
                )
                val list = ScheduledJobs.load(this).toMutableList()
                list.add(job)
                ScheduledJobs.save(this, list)
                if (job.enabled) enqueue(job)
                load()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun enqueue(job: ScheduledJob) {
        val wm = WorkManager.getInstance(this)
        val data = androidx.work.workDataOf(ScheduledJobWorker.KEY_ID to job.id)
        if (job.isRepeating) {
            // Periodic work cannot run more often than every 15 minutes; clamp so a
            // shorter user interval still means "as often as the system will allow".
            val minutes = job.intervalMinutes.toLong().coerceAtLeast(15L)
            val req = PeriodicWorkRequestBuilder<ScheduledJobWorker>(minutes, TimeUnit.MINUTES)
                .setInputData(data)
                .setInitialDelay(minutes, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(
                ScheduledJobWorker.tagFor(job.id),
                ExistingPeriodicWorkPolicy.REPLACE,
                req
            )
        } else {
            val req = OneTimeWorkRequestBuilder<ScheduledJobWorker>()
                .setInputData(data)
                .build()
            wm.enqueueUniqueWork(
                ScheduledJobWorker.tagFor(job.id),
                ExistingWorkPolicy.REPLACE,
                req
            )
        }
    }

    private fun applyTheme() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        when (NightModeReceiver.effectiveTheme(prefs)) {
            "red" -> setTheme(R.style.Theme_RedTermApp_Red)
            "amoled" -> setTheme(R.style.Theme_RedTermApp_AMOLED)
            "green" -> setTheme(R.style.Theme_RedTermApp_Green)
            "light" -> setTheme(R.style.Theme_RedTermApp_Light)
            "dracula" -> setTheme(R.style.Theme_RedTermApp_Dracula)
            "nord" -> setTheme(R.style.Theme_RedTermApp_Nord)
            "tokyo" -> setTheme(R.style.Theme_RedTermApp_Tokyo)
            "gruvbox" -> setTheme(R.style.Theme_RedTermApp_Gruvbox)
            else -> setTheme(R.style.Theme_RedTermApp)
        }
    }
}
