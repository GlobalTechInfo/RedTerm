package com.redtermapp.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import com.redtermapp.R
import com.redtermapp.distro.DistroInstaller
import com.redtermapp.util.AppLog
import com.redtermapp.util.api.ApiHelper
import java.io.File

/**
 * The user-managed /root/.startup.local hook, with a face on it.
 *
 * The launcher runs the hook only when it exists and the session is
 * interactive, and RedTerm never rewrites it — so this screen is the only
 * place that creates, edits, deletes, or regenerates it. "Write safe
 * starter" is refusal-aware: a file the user has hand-written (no RedTerm
 * marker) is never clobbered.
 */
class StartupHookActivity : AppCompatActivity() {

    private val installer by lazy { DistroInstaller(applicationContext) }
    private var distros: List<String> = emptyList()
    private var current: String? = null

    private lateinit var spinner: Spinner
    private lateinit var statusLine: TextView
    private lateinit var preview: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        body.addView(TextView(this).apply {
            setText(R.string.startup_hook)
            textSize = 20f
        })
        body.addView(sectionLabel(getString(R.string.startup_hook_distro)))
        spinner = Spinner(this)
        body.addView(spinner)
        statusLine = TextView(this).apply {
            textSize = 13f
            setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        body.addView(statusLine)
        body.addView(sectionLabel("/root/.startup.local"))
        preview = TextView(this).apply {
            textSize = 12f
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
            setBackgroundColor(cardColor())
            setTextColor(cardTextColor())
            setTypeface(android.graphics.Typeface.MONOSPACE)
        }
        body.addView(preview)

        val rowButtons = listOf(
            ScreenWidgets.primaryButton(this, R.string.startup_hook_write) { writeStarter() },
            ScreenWidgets.actionButton(this, R.string.startup_hook_edit) { edit() },
            ScreenWidgets.dangerButton(this, R.string.startup_hook_delete) { delete() },
            ScreenWidgets.actionButton(this, R.string.startup_hook_refresh) { reload() }
        )
        val row = ScreenWidgets.buttonGrid(this, rowButtons)
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = pad }
        body.addView(row)

        val scroll = ScrollView(this).apply { addView(body) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        ScreenToolbar.install(this, root, getString(R.string.startup_hook))
        setContentView(root)

        distros = runCatching { installer.getInstalledDistros() }.getOrDefault(emptyList())
        spinner.adapter = ScreenWidgets.spinner(this, distros).adapter
        spinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    current = distros.getOrNull(position)
                    reload()
                }

                override fun onNothingSelected(p: android.widget.AdapterView<*>?) = Unit
            }
    }

    private fun hookFile(): File? {
        val d = current ?: return null
        return File(installer.getRootfsDir(d), "root/.startup.local")
    }

    private fun reload() {
        val f = hookFile()
        when {
            f == null -> {
                statusLine.setText(R.string.workspace_none)
                preview.setText(R.string.startup_hook_preview_empty)
            }
            !f.exists() -> {
                statusLine.text = getString(R.string.startup_hook_status_absent)
                preview.setText(R.string.startup_hook_preview_empty)
            }
            else -> {
                val content = runCatching { f.readText() }.getOrNull() ?: ""
                statusLine.text =
                    if (content.contains(MARKER)) getString(R.string.startup_hook_ours)
                    else getString(R.string.startup_hook_user)
                preview.text = content
            }
        }
    }

    private fun writeStarter() {
        val f = hookFile() ?: return
        if (f.exists() && !f.readText().contains(MARKER)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.startup_hook)
                .setMessage(R.string.startup_hook_refused)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        try {
            f.parentFile?.mkdirs()
            f.writeText(ApiHelper.startupStarter())
            AppLog.i(this, "startup-hook", "safe starter written to ${f.absolutePath}")
            AlertDialog.Builder(this)
                .setTitle(R.string.startup_hook)
                .setMessage(R.string.startup_hook_written)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            reload()
        } catch (e: Exception) {
            AppLog.e(this, "startup-hook", "could not write the hook: ${e.javaClass.simpleName}: ${e.message}")
            AlertDialog.Builder(this)
                .setTitle(R.string.startup_hook)
                .setMessage("Could not write the hook: ${e.message}")
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun edit() {
        val f = hookFile() ?: return
        if (!f.exists()) {
            writeStarter()
            return
        }
        startActivity(
            Intent(this, TextEditorActivity::class.java)
                .putExtra(TextEditorActivity.EXTRA_PATH, f.absolutePath)
        )
    }

    private fun delete() {
        val f = hookFile() ?: return
        if (!f.exists()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.startup_hook)
            .setMessage("Delete /root/.startup.local for ${current}?")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (f.delete()) {
                    AppLog.i(this, "startup-hook", "hook removed from ${f.absolutePath}")
                    statusLine.setText(R.string.startup_hook_deleted)
                    reload()
                } else {
                    AppLog.e(this, "startup-hook", "delete of ${f.absolutePath} failed")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, 0)
    }

    /** The marker that makes a hook file RedTerm's own to replace. */
    private companion object {
        const val MARKER = "RedTerm user startup hook"
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
