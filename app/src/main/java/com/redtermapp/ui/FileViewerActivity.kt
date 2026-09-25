package com.redtermapp.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.redtermapp.R
import java.io.File

class FileViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"
        private const val MAX_PREVIEW_BYTES = 2L * 1024L * 1024L
        private const val BINARY_SNIFF_BYTES = 8192
    }

    private var loadedFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_viewer)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.viewer_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_back_chip)

        val path = intent?.getStringExtra(EXTRA_PATH)
        val file = path?.let { File(it) }
        if (file == null || !file.isFile || !file.canRead()) {
            supportActionBar?.title = getString(R.string.open_file_failed)
            findViewById<TextView>(R.id.viewer_content).text = getString(R.string.open_file_failed)
            finish()
            return
        }

        loadedFile = file
        supportActionBar?.title = file.name
        findViewById<TextView>(R.id.viewer_path).text = file.absolutePath
        loadPreview(file)
    }

    private fun loadPreview(file: File) {
        val content = findViewById<TextView>(R.id.viewer_content)
        val status = findViewById<TextView>(R.id.viewer_status)
        Thread({
            val length = file.length()
            val readBytes = minOf(length, MAX_PREVIEW_BYTES)
            val bytes = try {
                file.inputStream().use { input ->
                    val buffer = ByteArray(readBytes.toInt())
                    var filled = 0
                    while (filled < buffer.size) {
                        val read = input.read(buffer, filled, buffer.size - filled)
                        if (read == -1) break
                        filled += read
                    }
                    buffer.copyOf(filled)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    content.text = "${e.message ?: "Unable to read file"}"
                }
                return@Thread
            }

            val binary = bytes.size > 0 &&
                bytes.copyOf(minOf(bytes.size, BINARY_SNIFF_BYTES))
                    .any { it == 0.toByte() }

            val text = if (binary) {
                runOnUiThread {
                    content.text = getString(R.string.file_is_binary, file.name)
                }
                return@Thread
            } else {
                String(bytes, Charsets.UTF_8)
            }

            runOnUiThread {
                if (binary) return@runOnUiThread
                val truncated = length > MAX_PREVIEW_BYTES
                content.text = if (truncated) {
                    getString(R.string.file_truncated, MAX_PREVIEW_BYTES / 1024 / 1024) + "\n\n" + text
                } else {
                    text
                }
                status.visibility = if (truncated) android.view.View.VISIBLE else android.view.View.GONE
                status.text = if (truncated) {
                    getString(R.string.file_size_and_preview, length, MAX_PREVIEW_BYTES)
                } else {
                    getString(R.string.file_size_format, length)
                }
            }
        }, "redterm-file-viewer").apply { isDaemon = true }.start()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, 1, 1, R.string.action_copy)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        if (item.itemId == 1) {
            val text = findViewById<TextView>(R.id.viewer_content).text.toString()
            if (text.isNotEmpty()) {
                val clip = getSystemService(android.content.ClipboardManager::class.java)
                clip.setPrimaryClip(android.content.ClipData.newPlainText(loadedFile?.name ?: "file", text))
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
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
            "custom" -> setTheme(R.style.Theme_RedTermApp_Custom)
            else -> setTheme(R.style.Theme_RedTermApp)
        }
    }
}
