package com.redtermapp.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.redtermapp.BuildConfig
import com.redtermapp.R
import com.redtermapp.distro.DistroInstaller
import com.redtermapp.service.TerminalService

class SettingsActivity : AppCompatActivity() {

    private val installer by lazy { DistroInstaller(applicationContext) }

    private fun tc(attr: Int, default: Int): Int {
        val ta = theme.obtainStyledAttributes(intArrayOf(attr))
        val c = ta.getColor(0, default)
        ta.recycle()
        return c
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

        val fontSlider = findViewById<SeekBar>(R.id.font_size_slider)
        val wakelockSwitch = findViewById<Switch>(R.id.wakelock_switch)
        val versionInfo = findViewById<TextView>(R.id.version_info)
        val fontSpinner = findViewById<Spinner>(R.id.font_spinner)

        val currentTheme = prefs.getString("theme", "red")
        val themeNames = listOf("Catppuccin Dark", "AMOLED Black", "Green Terminal", "Red Terminal", "Light", "Dracula", "Nord", "Tokyo Night", "Gruvbox Dark", "Custom")
        val themeValues = listOf("default", "amoled", "green", "red", "light", "dracula", "nord", "tokyo", "gruvbox", "custom")
        val themeSpinner = findViewById<Spinner>(R.id.theme_spinner)
        val themeIdx = (themeValues.indexOf(currentTheme)).coerceAtLeast(0)
        val themeAdapter = object : ArrayAdapter<String>(this, R.layout.spinner_item, themeNames) {
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val row = layoutInflater.inflate(R.layout.spinner_dropdown_checked, parent, false)
                row.findViewById<TextView>(R.id.dropdown_text).text = themeNames[position]
                row.findViewById<TextView>(R.id.dropdown_check).visibility =
                    if (position == themeSpinner.selectedItemPosition) android.view.View.VISIBLE else android.view.View.GONE
                return row
            }
        }
        themeSpinner.adapter = themeAdapter
        themeSpinner.setSelection(themeIdx)
        themeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos != themeIdx) {
                    prefs.edit().putString("theme", themeValues[pos]).apply()
                    findViewById<TextView>(R.id.customize_theme_btn).visibility =
                        if (themeValues[pos] == "custom") android.view.View.VISIBLE else android.view.View.GONE
                    recreate()
                }
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        findViewById<TextView>(R.id.customize_theme_btn).apply {
            setOnClickListener { showColorPickerDialog(prefs) }
            visibility = if (currentTheme == "custom") android.view.View.VISIBLE else android.view.View.GONE
        }

        val fonts = listOf("JetBrains Mono", "Fira Code", "Source Code Pro", "Ubuntu Mono", "monospace", "Droid Sans Mono", "Noto Sans Mono", "Cascadia Code")
        val currentFont = prefs.getString("font", "monospace")
        val fontIdx = (fonts.indexOf(currentFont)).coerceAtLeast(0)
        val fontAdapter = object : ArrayAdapter<String>(this, R.layout.spinner_item, fonts) {
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val row = layoutInflater.inflate(R.layout.spinner_dropdown_checked, parent, false)
                row.findViewById<TextView>(R.id.dropdown_text).text = fonts[position]
                row.findViewById<TextView>(R.id.dropdown_check).visibility =
                    if (position == fontSpinner.selectedItemPosition) android.view.View.VISIBLE else android.view.View.GONE
                return row
            }
        }
        fontSpinner.adapter = fontAdapter
        fontSpinner.setSelection(fontIdx)
        fontSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos != fontIdx) {
                    prefs.edit().putString("font", fonts[pos]).apply()
                }
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        fontSlider.progress = prefs.getInt("font_size", 20)
        wakelockSwitch.isChecked = prefs.getBoolean("wakelock", true)

        fontSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                prefs.edit().putInt("font_size", progress).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        wakelockSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("wakelock", isChecked).apply()
            if (!isChecked) {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                am?.killBackgroundProcesses(packageName)
                stopService(Intent(this, TerminalService::class.java))
            }
        }

        val scrollbackSlider = findViewById<SeekBar>(R.id.scrollback_slider)
        val autohideSwitch = findViewById<Switch>(R.id.autohide_keys_switch)
        val opacitySlider = findViewById<SeekBar>(R.id.opacity_slider)

        scrollbackSlider.progress = prefs.getInt("scrollback", 4)
        autohideSwitch.isChecked = prefs.getBoolean("autohide_keys", false)
        opacitySlider.progress = prefs.getInt("terminal_opacity", 10)

        scrollbackSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                prefs.edit().putInt("scrollback", progress).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        autohideSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("autohide_keys", isChecked).apply()
        }

        opacitySlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                prefs.edit().putInt("terminal_opacity", progress).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        findViewById<TextView>(R.id.export_config_btn).setOnClickListener {
            try {
                val json = org.json.JSONObject().apply {
                    put("theme", prefs.getString("theme", "red"))
                    put("custom_bg", prefs.getInt("custom_bg", 0))
                    put("custom_text", prefs.getInt("custom_text", 0))
                    put("custom_primary", prefs.getInt("custom_primary", 0))
                    put("font", prefs.getString("font", "monospace"))
                    put("font_size", prefs.getInt("font_size", 20))
                    put("scrollback", prefs.getInt("scrollback", 4))
                    put("terminal_opacity", prefs.getInt("terminal_opacity", 10))
                    put("autohide_keys", prefs.getBoolean("autohide_keys", false))
                    put("wakelock", prefs.getBoolean("wakelock", true))
                }
                val fileName = "RedTerm_config.json"
                val file = java.io.File(getExternalFilesDir(null), fileName)
                file.writeText(json.toString(2))
                Toast.makeText(this, "Config exported to $fileName", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        findViewById<TextView>(R.id.backup_btn).setOnClickListener {
            val installed = installer.getInstalledDistros()
            if (installed.isEmpty()) {
                Toast.makeText(this, "No distros installed", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val distro = installed[0]
            try {
                val rootfsDir = installer.getRootfsDir(distro)
                val backupFile = java.io.File(getExternalFilesDir(null), "${distro}_backup.tar.gz")
                val pb = ProcessBuilder(
                    "tar", "-czf", backupFile.absolutePath, "-C", rootfsDir.parentFile?.absolutePath ?: "", rootfsDir.name
                )
                pb.redirectErrorStream(true)
                val proc = pb.start()
                proc.waitFor()
                if (backupFile.exists()) {
                    Toast.makeText(this, "Backup saved: ${backupFile.name} (${backupFile.length() / 1024 / 1024} MB)", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Backup failed", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Backup error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        findViewById<TextView>(R.id.restore_btn).setOnClickListener {
            val dir = getExternalFilesDir(null)
            val files = dir?.listFiles { f -> f.name.endsWith(".tar.gz") }?.toList() ?: emptyList()
            if (files.isEmpty()) {
                Toast.makeText(this, "No backup files found", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val names = files.map { it.name }.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Restore Distro")
                .setItems(names) { _, which ->
                    try {
                        val backupFile = files[which]
                        val distroName = backupFile.name.removeSuffix("_backup.tar.gz")
                        val rootfsDir = installer.getRootfsDir(distroName)
                        rootfsDir.mkdirs()
                        val pb = ProcessBuilder("tar", "-xzf", backupFile.absolutePath, "-C", rootfsDir.absolutePath)
                        pb.redirectErrorStream(true)
                        val proc = pb.start()
                        proc.waitFor()
                        Toast.makeText(this, "$distroName restored", Toast.LENGTH_LONG).show()
                        populateDistroList()
                    } catch (e: Exception) {
                        Toast.makeText(this, "Restore error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        versionInfo.text = "${getString(R.string.app_name)} v${BuildConfig.VERSION_NAME}"
    }

    private fun showColorPickerDialog(prefs: android.content.SharedPreferences) {
        val colors = intArrayOf(
            prefs.getInt("custom_bg", 0xFF1E1E2E.toInt()),
            prefs.getInt("custom_text", 0xFFCDD6F4.toInt()),
            prefs.getInt("custom_primary", 0xFF89B4FA.toInt())
        )

        val bgPreview = android.widget.TextView(this).apply {
            text = "  Background  "
            textSize = 16f
            setPadding(16, 16, 16, 16)
        }
        val textPreview = android.widget.TextView(this).apply {
            text = "  Text Color  "
            textSize = 16f
            setPadding(16, 16, 16, 16)
        }
        val primaryPreview = android.widget.TextView(this).apply {
            text = "  Primary/Accent  "
            textSize = 16f
            setPadding(16, 16, 16, 16)
        }

        fun updatePreviews() {
            bgPreview.setBackgroundColor(colors[0])
            bgPreview.setTextColor(colors[1])
            textPreview.setBackgroundColor(colors[0])
            textPreview.setTextColor(colors[1])
            primaryPreview.setBackgroundColor(colors[0])
            primaryPreview.setTextColor(colors[2])
        }
        updatePreviews()

        fun makeColorPicker(label: String, colorIndex: Int): android.widget.LinearLayout {
            val initial = colors[colorIndex]
            val r = (initial shr 16) and 0xFF
            val g = (initial shr 8) and 0xFF
            val b = initial and 0xFF
            val layout = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(0, 8, 0, 8)
            }
            val labelTv = android.widget.TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(0xFFCDD6F4.toInt())
            }
            layout.addView(labelTv)

            val sr = SeekBar(this).apply { max = 255; progress = r }
            val sg = SeekBar(this).apply { max = 255; progress = g }
            val sb = SeekBar(this).apply { max = 255; progress = b }
            val rv = android.widget.TextView(this).apply { text = "$r"; setTextColor(0xFFCDD6F4.toInt()); textSize = 12f; layoutParams = android.widget.LinearLayout.LayoutParams(36, -2) }
            val gv = android.widget.TextView(this).apply { text = "$g"; setTextColor(0xFFCDD6F4.toInt()); textSize = 12f; layoutParams = android.widget.LinearLayout.LayoutParams(36, -2) }
            val bv = android.widget.TextView(this).apply { text = "$b"; setTextColor(0xFFCDD6F4.toInt()); textSize = 12f; layoutParams = android.widget.LinearLayout.LayoutParams(36, -2) }

            val update = {
                colors[colorIndex] = 0xFF000000.toInt() or (sr.progress shl 16) or (sg.progress shl 8) or sb.progress
                updatePreviews()
            }

            val listener = object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, f: Boolean) {
                    when (s) { sr -> rv.text = "$p"; sg -> gv.text = "$p"; sb -> bv.text = "$p" }
                    update()
                }
                override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
            }
            sr.setOnSeekBarChangeListener(listener)
            sg.setOnSeekBarChangeListener(listener)
            sb.setOnSeekBarChangeListener(listener)

            for ((seek, valTv, name) in listOf(Triple(sr, rv, "R"), Triple(sg, gv, "G"), Triple(sb, bv, "B"))) {
                val row = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                row.addView(android.widget.TextView(this).apply {
                    text = name; setTextColor(0xFFCDD6F4.toInt()); textSize = 12f
                    layoutParams = android.widget.LinearLayout.LayoutParams(24, -2)
                })
                row.addView(seek.apply { layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f) })
                row.addView(valTv)
                layout.addView(row)
            }
            return layout
        }

        val scroll = android.widget.ScrollView(this)
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(bgPreview)
            addView(makeColorPicker("Background", 0))
            addView(textPreview)
            addView(makeColorPicker("Text", 1))
            addView(primaryPreview)
            addView(makeColorPicker("Primary", 2))
        }
        scroll.addView(container)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Custom Theme Colors")
            .setView(scroll)
            .setPositiveButton("Apply") { _, _ ->
                prefs.edit()
                    .putInt("custom_bg", colors[0])
                    .putInt("custom_text", colors[1])
                    .putInt("custom_primary", colors[2])
                    .apply()
                recreate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun onAddDistroClick(v: View) {
        startActivity(Intent(this, WelcomeActivity::class.java).apply {
            putExtra(WelcomeActivity.EXTRA_SELECT_ONLY, true)
        })
    }

    private fun populateDistroList() {
        val container = findViewById<LinearLayout>(R.id.distro_list)
        container.removeAllViews()

        val installed = installer.getInstalledDistros()
        if (installed.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No distros installed yet"
                setTextColor(0xFF6C7086.toInt())
                textSize = 14f
                setPadding(4, 8, 4, 8)
            })
            return
        }

        for (name in installed) {
            val rootfsDir = installer.getRootfsDir(name)
            val sizeBytes = rootfsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val sizeStr = when {
                sizeBytes < 1_000_000 -> "${sizeBytes / 1000} KB"
                sizeBytes < 1_000_000_000 -> "${"%.1f".format(sizeBytes / 1_000_000.0)} MB"
                else -> "${"%.2f".format(sizeBytes / 1_000_000_000.0)} GB"
            }
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 8) }
                setCardBackgroundColor(tc(R.attr.terminalBg, 0xFF1E1E2E.toInt()))
                radius = 12f
                setOnClickListener {
                    TerminalActivity.launch(this@SettingsActivity, name)
                }
                setOnLongClickListener {
                    confirmDelete(name)
                    true
                }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(16, 16, 16, 16)
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                        addView(TextView(context).apply {
                            text = name.replaceFirstChar { it.uppercase() }
                            setTextColor(tc(R.attr.terminalText, 0xFFCDD6F4.toInt()))
                            textSize = 16f
                        })
                        addView(TextView(context).apply {
                            text = sizeStr
                            setTextColor(0xFF6C7086.toInt())
                            textSize = 11f
                            setPadding(0, 2, 0, 0)
                        })
                    })
                    addView(TextView(context).apply {
                        text = "Launch \u203A"
                        setTextColor(0xFF89B4FA.toInt())
                        textSize = 16f
                    })
                })
            }
            container.addView(card)
        }
    }

    private fun confirmDelete(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Remove $name?")
            .setMessage("This will delete the rootfs and all data for $name.")
            .setPositiveButton("Delete") { _, _ ->
                installer.uninstall(name)
                populateDistroList()
                Toast.makeText(this, "$name removed", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        populateDistroList()
    }

    private fun applyTheme() {
        val theme = getSharedPreferences("settings", Context.MODE_PRIVATE).getString("theme", "red")
        when (theme) {
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
