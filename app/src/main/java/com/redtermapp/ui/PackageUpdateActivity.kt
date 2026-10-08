package com.redtermapp.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.redtermapp.R
import com.redtermapp.distro.DistroInstaller
import com.redtermapp.distro.DistroRunner
import com.redtermapp.distro.PackageUpdater
import com.redtermapp.util.Notifier
import com.redtermapp.util.OngoingJobs

/**
 * Runs the distro's package manager update in the background and streams the
 * real output, so a long upgrade is visible and does not need the terminal
 * kept in the foreground.
 */
class PackageUpdateActivity : AppCompatActivity() {

    companion object {
        private const val NOTIFICATION_ID = 4201
        private const val MAX_LOG_CHARS = 400_000
    }

    private lateinit var distroSpinner: Spinner
    private lateinit var planLabel: TextView
    private lateinit var logView: TextView
    private lateinit var runButton: MaterialButton
    private lateinit var progress: TextView

    private var distros: List<String> = emptyList()
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(this).apply {
            setText(R.string.package_updates)
            textSize = 20f
            setPadding(0, 0, 0, pad)
        }
        root.addView(title)

        distroSpinner = Spinner(this)
        root.addView(distroSpinner)

        planLabel = TextView(this).apply {
            textSize = 12f
            setPadding(0, pad, 0, 0)
        }
        root.addView(planLabel)

        progress = TextView(this).apply {
            textSize = 12f
            setVisibility(View.GONE)
        }
        root.addView(progress)

        logView = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(0, pad, 0, 0)
        }
        val scroll = ScrollView(this).apply {
            addView(logView)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(scroll)

        runButton = MaterialButton(this).apply { setText(R.string.check_and_update) }
        runButton.setOnClickListener { startUpdate() }
        root.addView(runButton)

        ScreenToolbar.install(this, root, getString(R.string.package_updates))
        setContentView(root)

        Notifier.ensureChannel(this)
        loadDistros()
        distroSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long
            ) = updatePlanLabel()

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    private fun loadDistros() {
        Thread({
            val installed = DistroInstaller(applicationContext).getInstalledDistros()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                distros = installed
                distroSpinner.adapter = ArrayAdapter(
                    this,
                    android.R.layout.simple_spinner_dropdown_item,
                    installed.map { com.redtermapp.distro.DistroBrand.displayNameFor(it) }
                )
                if (installed.isEmpty()) {
                    planLabel.setText(R.string.no_distros_installed)
                    runButton.isEnabled = false
                } else {
                    updatePlanLabel()
                }
            }
        }, "redterm-pkg-list").start()
    }

    private fun selectedDistro(): String? {
        val index = distroSpinner.selectedItemPosition
        return distros.getOrNull(index)
    }

    private fun updatePlanLabel() {
        val distro = selectedDistro() ?: return
        val rootfs = DistroInstaller(applicationContext).getRootfsDir(distro)
        val plan = PackageUpdater.planFor(distro, rootfs)
        planLabel.text = if (plan.packageManager == "unknown") {
            getString(R.string.package_manager_unknown, distro)
        } else {
            getString(R.string.package_manager_detected, plan.packageManager)
        }
    }

    private fun startUpdate() {
        if (running) return
        val distro = selectedDistro() ?: return
        val rootfs = DistroInstaller(applicationContext).getRootfsDir(distro)
        val plan = PackageUpdater.planFor(distro, rootfs)
        if (plan.packageManager == "unknown") {
            planLabel.text = getString(R.string.package_manager_unknown, distro)
            return
        }
        running = true
        runButton.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.text = getString(R.string.updating_packages, distro)
        logView.text = ""
        val title = getString(R.string.updating_packages, distro)
        Notifier.cancel(applicationContext, NOTIFICATION_ID)
        val cancelSignal = DistroRunner.CancelSignal()
        // Registered so the notification's Cancel button can reach the work, and
        // cleared when it finishes so a later tap cannot kill something else that has
        // since taken the same id.
        OngoingJobs.register(NOTIFICATION_ID) { cancelSignal.cancel() }
        Notifier.notify(
            applicationContext, NOTIFICATION_ID, title, getString(R.string.update_started),
            ongoing = true,
            cancelAction = getString(R.string.cancel) to Runnable { }
        )

        val runner = DistroRunner(applicationContext)
        Thread({
            var lastLine = ""
            val result = runner.run(
                distroName = distro,
                command = plan.updateCommand,
                timeoutMinutes = 30,
                onOutput = { line ->
                    lastLine = line
                    runOnUiThread { appendLog(line) }
                    // The notice shows the newest line so it visibly moves. A
                    // notification that says "started" and nothing else for half an
                    // hour is indistinguishable from one that has hung.
                    if (line.isNotBlank() && line.length < 120) {
                        Notifier.notify(
                            applicationContext, NOTIFICATION_ID, title, line,
                            ongoing = true,
                            cancelAction = getString(R.string.cancel) to Runnable { }
                        )
                    }
                },
                cancel = cancelSignal
            )
            OngoingJobs.clear(NOTIFICATION_ID)
            val summary = when {
                cancelSignal.isCancelled -> getString(R.string.update_cancelled)
                result.timedOut -> getString(R.string.update_timed_out)
                result.succeeded -> getString(R.string.update_finished)
                else -> getString(R.string.update_failed, result.output.takeLast(400))
            }
            // Before the UI block, and outside it: an update takes minutes and the
            // user will have left the page, which is exactly when they need to be
            // told. Guarding this with isDestroyed meant the only person who was ever
            // notified was the one who sat and watched it.
            Notifier.reportCompletion(
                applicationContext, NOTIFICATION_ID, title, summary
            )
            Notifier.i(applicationContext, "ssh", "update $distro: $summary (last: $lastLine)")
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                running = false
                runButton.isEnabled = true
                progress.text = summary
            }
        }, "redterm-pkg-update").start()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        val item = menu.add(android.view.Menu.NONE, 1, 1, R.string.clear_log)
        item.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        if (item.itemId == 1) {
            logView.text = ""
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun appendLog(line: String) {
        if (logView.text.length > MAX_LOG_CHARS) return
        logView.append(line + "\n")
        val scroll = logView.parent as? ScrollView
        scroll?.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun applyTheme() {
        val prefs = getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
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
