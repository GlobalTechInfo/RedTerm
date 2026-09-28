package com.redtermapp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.redtermapp.R
import com.redtermapp.util.AppLog
import java.io.File

/**
 * Shows the app log and lets it be copied or shared.
 *
 * Logcat is not reachable from Settings and is lost when the process dies, so
 * this is the practical way to report a failure that ends a process immediately.
 */
class AppLogActivity : AppCompatActivity() {

    private lateinit var view: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)

        val pad = (12 * resources.displayMetrics.density).toInt()
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        ScreenToolbar.install(this, root, getString(R.string.diagnostics))

        view = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 10f
            setTextIsSelectable(true)
        }
        root.addView(
            ScrollView(this).apply { addView(view) },
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        setContentView(root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val text = AppLog.tail(this)
        view.text = if (text.isBlank()) getString(R.string.app_log_empty) else text
        view.post {
            (view.parent as? ScrollView)?.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(android.view.Menu.NONE, 1, 1, R.string.copy_log)
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(android.view.Menu.NONE, 2, 2, R.string.share_log)
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(android.view.Menu.NONE, 3, 3, R.string.capture_log)
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(android.view.Menu.NONE, 4, 4, R.string.download_log)
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(android.view.Menu.NONE, 5, 5, R.string.clear_log)
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
            1 -> {
                val clip = getSystemService(ClipboardManager::class.java)
                clip.setPrimaryClip(ClipData.newPlainText("redterm-log", view.text.toString()))
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
                return true
            }
            2 -> {
                shareLog()
                return true
            }
            3 -> {
                // Pull in the whole process log, including anything the support
                // libraries logged, then refresh. While it runs the title says
                // what is happening, then goes back to the screen's own name.
                setTitle(R.string.app_log_capturing)
                Thread({
                    val captured = AppLog.captureProcessLog(this)
                    AppLog.i(this, "applog", "captured ${captured.length} chars of logcat")
                    runOnUiThread { reload() }
                }, "redterm-logcat").apply { isDaemon = true }.start()
                Toast.makeText(this, R.string.capturing_log, Toast.LENGTH_SHORT).show()
                return true
            }
            4 -> {
                downloadLog()
                return true
            }
            5 -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.clear_log)
                    .setMessage(R.string.clear_log_confirm)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        AppLog.clear(this)
                        reload()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    /** Writes the log to the shared Downloads folder, like the crash reports. */
    private fun downloadLog() {
        if (!com.redtermapp.util.StoragePermission.isAccessible(this)) {
            com.redtermapp.util.StoragePermission.requestAccess(this)
            return
        }
        Thread({
            val stamp = java.text.SimpleDateFormat(
                "yyyyMMdd-HHmmss", java.util.Locale.US
            ).format(java.util.Date())
            val downloads = java.io.File(
                android.os.Environment.getExternalStorageDirectory(),
                "Download"
            )
            if (!downloads.exists()) downloads.mkdirs()
            val target = java.io.File(downloads, "RedTerm-log-$stamp.txt")
            val ok = try {
                AppLog.captureProcessLog(this)
                AppLog.logFile(this).copyTo(target, overwrite = true)
                true
            } catch (e: Exception) {
                AppLog.e(this, "applog", "could not save the log: ${e.message}")
                false
            }
            runOnUiThread {
                if (ok) {
                    Toast.makeText(
                        this, getString(R.string.log_saved_to, target.name), Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(this, R.string.log_save_failed, Toast.LENGTH_LONG).show()
                }
            }
        }, "redterm-log-save").apply { isDaemon = true }.start()
    }

    /**
     * Shares the log as a file.
     *
     * Sharing the file rather than the text keeps the formatting intact, and
     * uses the same FileProvider the backup share already uses.
     */
    private fun shareLog() {
        val source: File = AppLog.logFile(this)
        if (!source.exists() || source.length() == 0L) {
            Toast.makeText(this, R.string.app_log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        if (!com.redtermapp.util.StoragePermission.isAccessible(this)) {
            com.redtermapp.util.StoragePermission.requestAccess(this)
            return
        }
        val dir = java.io.File(
            android.os.Environment.getExternalStorageDirectory(), "RedTerm/logs"
        ).apply { if (!exists()) mkdirs() }
        val target = java.io.File(dir, "redterm-app.log")
        try {
            source.copyTo(target, overwrite = true)
        } catch (e: Exception) {
            Toast.makeText(
                this, getString(R.string.share_failed), Toast.LENGTH_SHORT
            ).show()
            return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "${applicationContext.packageName}.fileprovider", target
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(android.content.Intent.createChooser(intent, getString(R.string.share_log)))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show()
        }
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
