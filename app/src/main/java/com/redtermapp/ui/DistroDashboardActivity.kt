package com.redtermapp.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.redtermapp.R
import com.redtermapp.distro.DistroBrand
import com.redtermapp.distro.DistroInstaller
import com.redtermapp.distro.DistroOsRelease
import java.io.File
import java.util.Locale

/**
 * One glance at every installed distro: what it is, how much it costs in
 * storage, and when its packages were last updated. "Update now" deep-links
 * the package updater for that distro and stamps the time; "Disk" opens the
 * per-directory breakdown.
 */
class DistroDashboardActivity : AppCompatActivity() {

    private val installer by lazy { DistroInstaller(applicationContext) }
    private lateinit var container: LinearLayout
    private lateinit var deviceLine: TextView
    private val stamps by lazy { getSharedPreferences("distro_updates", android.content.Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        body.addView(TextView(this).apply {
            setText(R.string.distro_dashboard)
            textSize = 20f
        })
        deviceLine = TextView(this).apply {
            textSize = 12f
            setPadding(0, 8, 0, 8)
        }
        body.addView(deviceLine)
        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(container)

        val scroll = ScrollView(this).apply { addView(body) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        ScreenToolbar.install(this, root, getString(R.string.distro_dashboard))
        setContentView(root)
        load()
    }

    private fun load() {
        Thread({
            val installed = installer.getInstalledDistros()
            val device = deviceFree()
            val cards = installed.map { distro ->
                val rootfs = installer.getRootfsDir(distro)
                val osrel = DistroOsRelease.read(rootfs)
                Triple(
                    distro,
                    DistroOsRelease.versionOf(osrel),
                    runCatching { treeSize(rootfs) }.getOrDefault(0L)
                )
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                deviceLine.text = getString(R.string.distro_dashboard_device_free, human(device))
                container.removeAllViews()
                if (cards.isEmpty()) {
                    container.addView(TextView(this).apply {
                        setText(R.string.distro_dashboard_none)
                        setTextColor(mutedTextColor())
                    })
                }
                for ((distro, version, size) in cards) {
                    container.addView(card(distro, version, size))
                }
            }
        }, "redterm-distro-dashboard").start()
    }

    private fun card(distro: String, version: String, size: Long): View {
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
        val head = ScreenWidgets.headerRow(this).apply {
            addView(TextView(this@DistroDashboardActivity).apply {
                text = DistroBrand.displayNameFor(distro)
                textSize = 16f
                setTextColor(cardTextColor())
            })
            if (version.isNotEmpty()) {
                addView(TextView(this@DistroDashboardActivity).apply {
                    text = version
                    textSize = 12f
                    setTextColor(mutedTextColor())
                    setPadding((12 * resources.displayMetrics.density).toInt(), 0, 0, 0)
                })
            }
        }
        card.addView(head)

        val last = stamps.getLong("last_$distro", 0L)
        val lastText = if (last == 0L) getString(R.string.distro_dashboard_never)
        else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(java.util.Date(last))
        card.addView(factLine(getString(R.string.distro_dashboard_size), human(size)))
        card.addView(factLine(getString(R.string.distro_dashboard_last_update), lastText))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(ScreenWidgets.primaryButton(this, R.string.distro_dashboard_update) {
            stamps.edit { putLong("last_$distro", System.currentTimeMillis()) }
            startActivity(
                Intent(this@DistroDashboardActivity, PackageUpdateActivity::class.java)
                    .putExtra(PackageUpdateActivity.EXTRA_DISTRO, distro)
            )
            reload()
        })
        row.addView(ScreenWidgets.actionButton(this, R.string.distro_dashboard_disk) {
            startActivity(Intent(this@DistroDashboardActivity, DiskUsageActivity::class.java))
        })
        card.addView(row)
        return card
    }

    private fun factLine(label: String, value: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(this@DistroDashboardActivity).apply {
                text = label
                textSize = 12f
                setTextColor(mutedTextColor())
            })
            addView(TextView(this@DistroDashboardActivity).apply {
                text = value
                textSize = 12f
                setTextColor(cardTextColor())
                setPadding((8 * resources.displayMetrics.density).toInt(), 0, 0, 0)
            })
        }

    private fun reload() = load()

    private fun deviceFree(): Long {
        val root = installer.getInstalledDistros().firstOrNull()
            ?.let { installer.getRootfsDir(it) }?.parentFile
            ?: File(getExternalFilesDir(null)?.absolutePath ?: android.os.Environment.getExternalStorageDirectory().absolutePath)
        return try {
            android.os.StatFs(root.absolutePath).availableBytes
        } catch (_: Exception) {
            0L
        }
    }

    private fun treeSize(dir: File): Long {
        var total = 0L
        dir.walkTopDown().forEach { total += if (it.isFile) runCatching { it.length() }.getOrDefault(0L) else 0L }
        return total
    }

    private fun human(bytes: Long): String =
        when {
            bytes >= 1L shl 30 -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1073741824.0)
            bytes >= 1L shl 20 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1048576.0)
            bytes >= 1L shl 10 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
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
