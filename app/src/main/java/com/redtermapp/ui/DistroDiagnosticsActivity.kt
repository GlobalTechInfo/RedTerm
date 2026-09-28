package com.redtermapp.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.redtermapp.R
import com.redtermapp.distro.DistroDiagnostics
import com.redtermapp.distro.DistroInstaller

class DistroDiagnosticsActivity : AppCompatActivity() {

    private var report: DistroDiagnostics.Report? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_distro_diagnostics)

        val distroName = intent?.getStringExtra(EXTRA_DISTRO).orEmpty()
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.diag_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_back_chip)
        supportActionBar?.title = getString(R.string.diagnose_title, distroName)

        val installer = DistroInstaller(applicationContext)
        val rootfs = installer.getRootfsDir(distroName)

        val summary = findViewById<TextView>(R.id.diag_summary)
        val subtitle = findViewById<TextView>(R.id.diag_subtitle)
        val container = findViewById<LinearLayout>(R.id.diag_checks)

        Thread({
            val result = DistroDiagnostics.inspect(applicationContext, distroName, rootfs)
            runOnUiThread {
                report = result
                summary.text = when {
                    result.failures > 0 -> getString(R.string.diagnose_summary_problems)
                    result.warnings > 0 -> getString(R.string.diagnose_summary_warnings)
                    else -> getString(R.string.diagnose_summary_ok)
                }
                summary.setTextColor(
                    when {
                        result.failures > 0 -> 0xFFFF6B6B.toInt()
                        result.warnings > 0 -> 0xFFF9E2AF.toInt()
                        else -> 0xFFA6E3A1.toInt()
                    }
                )
                subtitle.text = buildString {
                    append(result.arch)
                    append("  ·  ")
                    append(result.rootfsPath)
                    if (result.sizeBytes >= 0) {
                        append("\n")
                        append(getString(R.string.diagnose_size, DistroInstaller.formatSize(result.sizeBytes)))
                    }
                    append("  ·  ")
                    append(getString(R.string.diagnose_free, DistroInstaller.formatSize(result.freeBytes)))
                }
                container.removeAllViews()
                for (check in result.checks) {
                    container.addView(checkRow(check))
                }
            }
        }, "redterm-diagnostics").apply { isDaemon = true }.start()

        findViewById<TextView>(R.id.diag_copy).setOnClickListener {
            val r = report ?: return@setOnClickListener
            val text = buildString {
                appendLine("RedTerm diagnostics: ${r.distro}")
                appendLine("arch: ${r.arch}")
                appendLine("rootfs: ${r.rootfsPath}")
                for (c in r.checks) {
                    appendLine("[${c.status}] ${c.name}: ${c.detail}")
                }
            }
            val clip = getSystemService(android.content.ClipboardManager::class.java)
            clip.setPrimaryClip(android.content.ClipData.newPlainText("RedTerm diagnostics", text))
            Toast.makeText(this, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkRow(check: DistroDiagnostics.Check): View {
        val (marker, color) = when (check.status) {
            DistroDiagnostics.Status.OK -> "OK" to 0xFFA6E3A1.toInt()
            DistroDiagnostics.Status.WARN -> "WARN" to 0xFFF9E2AF.toInt()
            DistroDiagnostics.Status.FAIL -> "FAIL" to 0xFFFF6B6B.toInt()
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, 10, 0, 10)
            addView(TextView(context).apply {
                text = marker
                setTextColor(color)
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            addView(TextView(context).apply {
                text = getString(R.string.diagnose_check_line, check.name, check.detail)
                setTextColor(0xFFCDD6F4.toInt())
                textSize = 13f
                setLineSpacing(0f, 1.15f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun applyTheme() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        when (NightModeReceiver.effectiveTheme(prefs)) {
            "red" -> setTheme(R.style.Theme_RedTermApp_Red)
            "amoled" -> setTheme(R.style.Theme_RedTermApp_AMOLED)
            "green" -> setTheme(R.style.Theme_RedTermApp_Green)
            "light" -> setTheme(R.style.Theme_RedTermApp_Light)
            "dracula" -> setTheme(R.style.Theme_RedTermApp_Dracula)
            "nord" -> setTheme(R.style.Theme_RedTermApp_Nord)
            "tokyo" -> setTheme(R.style.Theme_RedTermApp_Tokyo)
            "gruvbox" -> setTheme(R.style.Theme_RedTermApp_Gruvbox)
            "custom" -> setTheme(R.style.Theme_RedTermApp_Custom)
            else -> setTheme(R.style.Theme_RedTermApp)
        }
    }

    companion object {
        const val EXTRA_DISTRO = "distro"
    }
}
