package com.redtermapp.ui

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.redtermapp.R
import com.redtermapp.distro.DistroInstaller
import java.io.File

class FileBrowserActivity : AppCompatActivity() {

    private var currentDir: File? = null
    private lateinit var pathLabel: TextView
    private lateinit var fileList: ListView
    private lateinit var distroName: String

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_browser)

        distroName = intent?.getStringExtra("distro") ?: "alpine"
        val rootfsDir = DistroInstaller(applicationContext).getRootfsDir(distroName)

        pathLabel = findViewById(R.id.file_path)
        fileList = findViewById(R.id.file_list)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.file_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Files: $distroName"

        currentDir = rootfsDir
        loadDir(rootfsDir)
    }

    private fun loadDir(dir: File) {
        currentDir = dir
        pathLabel.text = dir.absolutePath

        val entries = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        val names = mutableListOf<String>()
        if (dir.parentFile != null) names.add("..")

        for (f in entries) {
            val icon = if (f.isDirectory) "\uD83D\uDCC1" else "\uD83D\uDCC4"
            val size = if (f.isFile) " (${formatSize(f.length())})" else ""
            names.add("$icon${f.name}$size")
        }

        fileList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names).apply {
            setDropDownViewResource(android.R.layout.simple_list_item_1)
        }

        fileList.setOnItemClickListener { _: AdapterView<*>, _: View, pos: Int, _: Long ->
            val parent = dir.parentFile
            if (pos == 0 && parent != null) {
                loadDir(parent)
                return@setOnItemClickListener
            }
            val idx = if (dir.parentFile != null) pos - 1 else pos
            if (idx in entries.indices) {
                val sel = entries[idx]
                if (sel.isDirectory) {
                    loadDir(sel)
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1000 -> "$bytes B"
        bytes < 1_000_000 -> "${bytes / 1000} KB"
        bytes < 1_000_000_000 -> "${"%.1f".format(bytes / 1_000_000.0)} MB"
        else -> "${"%.2f".format(bytes / 1_000_000_000.0)} GB"
    }

    private fun applyTheme() {
        val prefs = getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        val theme = NightModeReceiver.effectiveTheme(prefs)
        when (theme) {
            "red" -> setTheme(R.style.Theme_RedTermApp_Red)
            "amoled" -> setTheme(R.style.Theme_RedTermApp_AMOLED)
            "green" -> setTheme(R.style.Theme_RedTermApp_Green)
            "light" -> setTheme(R.style.Theme_RedTermApp_Light)
            "dracula" -> setTheme(R.style.Theme_RedTermApp_Dracula)
            "nord" -> setTheme(R.style.Theme_RedTermApp_Nord)
            "tokyo" -> setTheme(R.style.Theme_RedTermApp_Tokyo)
            "gruvbox" -> setTheme(R.style.Theme_RedTermApp_Gruvbox)
            "custom" -> {
                setTheme(R.style.Theme_RedTermApp_Custom)
                val bg = prefs.getInt("custom_bg", 0xFF1E1E2E.toInt())
                window.decorView.setBackgroundColor(bg)
            }
            else -> setTheme(R.style.Theme_RedTermApp)
        }
    }
}
