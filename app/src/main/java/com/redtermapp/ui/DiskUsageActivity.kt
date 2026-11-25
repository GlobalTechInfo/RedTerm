package com.redtermapp.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import com.redtermapp.R
import com.redtermapp.distro.DistroInstaller
import java.io.File

/**
 * Storage breakdown: what each distro costs, and inside a distro, which
 * directories are responsible.
 */
class DiskUsageActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private lateinit var container: LinearLayout
    private lateinit var spinner: android.widget.Spinner
    private lateinit var deviceLine: TextView
    private val installer by lazy { DistroInstaller(applicationContext) }
    private var distros: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            setText(R.string.disk_usage)
            textSize = 20f
        })
        deviceLine = TextView(this).apply {
            textSize = 12f
            setPadding(0, 8, 0, 8)
        }
        root.addView(deviceLine)

        spinner = android.widget.Spinner(this)
        root.addView(spinner)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 0)
        }
        root.addView(
            container,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        ScreenToolbar.install(this, root, getString(R.string.disk_usage))
        setContentView(root)

        load()
    }

    private fun load() {
        showBusy()
        Thread({
            val device = deviceSpace()
            val installed = installer.getInstalledDistros()
            val sizes = installed.associateWith { measureTree(installer.getRootfsDir(it)) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                distros = installed
                deviceLine.text = device
                spinner.adapter = android.widget.ArrayAdapter(
                    this,
                    android.R.layout.simple_spinner_dropdown_item,
                    installed.map { com.redtermapp.distro.DistroBrand.displayNameFor(it) }
                )
                spinner.onItemSelectedListener = object :
                    android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long
                    ) = showDistroBreakdown(installed[position], sizes[installed[position]] ?: 0L)

                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                }
                if (installed.isEmpty()) {
                    showBusy()
                    deviceLine.text = getString(R.string.no_distros_installed)
                    container.removeAllViews()
                }
            }
        }, "redterm-disk-usage").start()
    }

    private fun deviceSpace(): String {
        val root = java.io.File(android.os.Environment.getExternalStorageDirectory(), "RedTerm")
        root.mkdirs()
        val free = installer.freeBytesAt(root)
        val stats = android.os.StatFs(root.absolutePath)
        val total = stats.totalBytes
        return getString(
            R.string.device_storage,
            DistroInstaller.formatSize(free),
            DistroInstaller.formatSize(total)
        )
    }

    private fun showBusy() {
        container.removeAllViews()
        container.addView(ProgressBar(this))
    }

    private fun showDistroBreakdown(distro: String, totalBytes: Long) {
        container.removeAllViews()
        val rootfs = installer.getRootfsDir(distro)
        val pad = (8 * resources.displayMetrics.density).toInt()
        container.addView(TextView(this).apply {
            text = getString(
                R.string.distro_total_size,
                distro, DistroInstaller.formatSize(totalBytes)
            )
            textSize = 14f
            setPadding(0, 0, 0, pad)
        })
        Thread({
            val rows = rootfs.listFiles()
                ?.map { file -> BreakdownRow(file.name, measureTree(file), file.isDirectory) }
                ?.sortedByDescending { it.bytes }
                ?.take(20)
                ?: emptyList()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                container.removeAllViews()
                container.addView(TextView(this).apply {
                    text = getString(
                        R.string.distro_total_size,
                        distro, DistroInstaller.formatSize(totalBytes)
                    )
                    textSize = 14f
                    setPadding(0, 0, 0, pad)
                })
                if (rows.isEmpty()) {
                    container.addView(TextView(this).apply {
                        setText(R.string.folder_empty)
                    })
                    return@runOnUiThread
                }
                for (row in rows) {
                    container.addView(breakdownView(row, totalBytes, pad))
                }
            }
        }, "redterm-disk-breakdown").start()
    }

    private data class BreakdownRow(val name: String, val bytes: Long, val isDirectory: Boolean)

    private fun breakdownView(row: BreakdownRow, total: Long, pad: Int): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad / 2, 0, 0)
        }
        val label = TextView(this).apply {
            text = getString(
                if (row.isDirectory) R.string.breakdown_dir_row else R.string.breakdown_file_row,
                row.name, DistroInstaller.formatSize(row.bytes)
            )
            textSize = 12f
        }
        wrapper.addView(label)
        val bar = View(this)
        val fraction = if (total > 0) (row.bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
        val params = LinearLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.9f * fraction).toInt().coerceAtLeast(2),
            (6 * resources.displayMetrics.density).toInt()
        )
        bar.layoutParams = params
        bar.setBackgroundColor("#FF33FF88".toColorInt())
        wrapper.addView(bar)
        return wrapper
    }

    /**
     * Iterative: a deep rootfs must not grow the stack.
     *
     * A rootfs is full of real symlinks: usr-merge makes /bin, /lib, /lib64 and /sbin
     * aliases of their /usr/ counterparts, and every versioned library carries a
     * libfoo.so -> libfoo.so.1 link. Following them is what inflated a 4 GB distro to
     * 42 GB: the same real files counted once per alias. No link is followed at all -
     * a link's target is counted at its real location, and a link to a directory outside
     * the rootfs is never descended. [listFiles] returns null for a directory it cannot
     * read; that directory is skipped, leaving a partial total rather than aborting the
     * whole measurement.
     */
    private fun measureTree(dir: File): Long {
        // An alias (e.g. /bin -> /usr/bin) holds no storage of its own; its target is
        // counted under the real directory. showDistroBreakdown calls this for each
        // top-level entry, so without the guard the alias rows would count their targets.
        if (isSymbolicLink(dir)) return 0L
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (isSymbolicLink(child)) continue
                if (child.isDirectory) stack.addLast(child) else total += child.length()
            }
        }
        return total
    }

    /**
     * Whether [file] is a symbolic link, without following it. [java.io.File] has no
     * symlink check and [java.nio.file] needs API 26, so this reads lstat: a link's
     * own mode carries the S_IFLNK type bits whether or not the target still exists.
     */
    private fun isSymbolicLink(file: File): Boolean = try {
        val mode = android.system.Os.lstat(file.absolutePath).st_mode
        (mode and S_IFMT) == S_IFLNK
    } catch (_: Exception) {
        false
    }

    private companion object {
        // POSIX stat file-type bits (st_mode & S_IFMT). android.system.Os exposes no
        // S_IF* constants, so they are written out in hex.
        private const val S_IFMT = 0xF000
        private const val S_IFLNK = 0xA000
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        val refresh = menu.add(android.view.Menu.NONE, 1, 1, R.string.refresh)
        refresh.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        if (item.itemId == 1) {
            load()
            return true
        }
        return super.onOptionsItemSelected(item)
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
