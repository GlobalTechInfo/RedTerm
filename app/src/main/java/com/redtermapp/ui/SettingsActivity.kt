package com.redtermapp.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
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
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private val installer by lazy { DistroInstaller(applicationContext) }

    companion object {
        private const val REQ_IMPORT_FONT = 2001
    }

    private val nightReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            recreate()
        }
    }

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

        androidx.core.content.ContextCompat.registerReceiver(
            this, nightReceiver,
            android.content.IntentFilter(NightModeReceiver.ACTION_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

        findViewById<View>(R.id.settings_back_btn).setOnClickListener { finish() }

        findViewById<View>(R.id.bash_templates_btn).setOnClickListener {
            startActivity(Intent(this, BashTemplatesActivity::class.java))
        }

        val fontSlider = findViewById<SeekBar>(R.id.font_size_slider)
        val wakelockSwitch = findViewById<Switch>(R.id.wakelock_switch)
        val versionInfo = findViewById<TextView>(R.id.version_info)
        val fontSpinner = findViewById<Spinner>(R.id.font_spinner)

        val currentTheme = prefs.getString("theme", "amoled")
        val themeNames = listOf("Catppuccin Dark", "AMOLED Black", "Green Terminal", "Red Terminal", "Light", "Dracula", "Nord", "Tokyo Night", "Gruvbox Dark", "Custom", "Dynamic")
        val themeValues = listOf("default", "amoled", "green", "red", "light", "dracula", "nord", "tokyo", "gruvbox", "custom", "dynamic")
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
                    NightModeReceiver.notifyChanged(this@SettingsActivity, prefs)
                    recreate()
                }
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        findViewById<TextView>(R.id.customize_theme_btn).apply {
            setOnClickListener { showColorPickerDialog(prefs) }
            visibility = if (currentTheme == "custom") android.view.View.VISIBLE else android.view.View.GONE
        }

        val customFonts = customFontFiles().map { it.name }
        val fonts = listOf("JetBrains Mono", "Fira Code", "Source Code Pro", "Ubuntu Mono", "monospace", "Droid Sans Mono", "Noto Sans Mono", "Cascadia Code") + customFonts.map { "custom:$it" }
        val fontLabels = fonts.map { if (it.startsWith("custom:")) "${fontDisplayName(it.removePrefix("custom:"))} (custom)" else it }
        val currentFont = prefs.getString("font", "monospace")
        val fontIdx = (fonts.indexOf(currentFont)).coerceAtLeast(0)
        val fontAdapter = object : ArrayAdapter<String>(this, R.layout.spinner_item, fontLabels) {
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val row = layoutInflater.inflate(R.layout.spinner_dropdown_checked, parent, false)
                row.findViewById<TextView>(R.id.dropdown_text).text = fontLabels[position]
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

        findViewById<TextView>(R.id.import_font_btn).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "font/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            try {
                startActivityForResult(intent, REQ_IMPORT_FONT)
            } catch (_: Exception) {
                val fallback = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                try {
                    startActivityForResult(fallback, REQ_IMPORT_FONT)
                } catch (_: Exception) {
                    Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show()
                }
            }
        }

        renderCustomFontList(prefs)

        fontSlider.progress = prefs.getInt("font_size", 20)
        wakelockSwitch.isChecked = prefs.getBoolean("wakelock", true)

        val nightSwitch = findViewById<Switch>(R.id.night_mode_switch)
        nightSwitch.isChecked = prefs.getBoolean("auto_night", false)
        nightSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_night", isChecked).apply()
            if (isChecked) {
                NightModeReceiver.scheduleNightMode(this, prefs)
            } else {
                NightModeReceiver.cancelNightMode(this)
            }
            NightModeReceiver.notifyChanged(this, prefs)
        }

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

        val bellSwitch = findViewById<Switch>(R.id.bell_switch)
        bellSwitch.isChecked = prefs.getBoolean("terminal_bell", true)
        bellSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("terminal_bell", isChecked).apply()
        }

        val lockSwitch = findViewById<Switch>(R.id.lock_switch)
        lockSwitch.isChecked = prefs.getBoolean("lock_enabled", false)
        lockSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                com.redtermapp.util.AppLock.setupPinDialog(this, prefs) {
                    lockSwitch.isChecked = prefs.getBoolean("lock_enabled", false)
                }
            } else {
                prefs.edit()
                    .putBoolean("lock_enabled", false)
                    .putString("lock_pin", "")
                    .apply()
            }
        }

        val row1Input = findViewById<EditText>(R.id.extra_keys_row1_input)
        val row2Input = findViewById<EditText>(R.id.extra_keys_row2_input)
        row1Input.setText(prefs.getString("extra_keys_row1", ""))
        row2Input.setText(prefs.getString("extra_keys_row2", ""))
        findViewById<TextView>(R.id.save_extra_keys_btn).setOnClickListener {
            prefs.edit()
                .putString("extra_keys_row1", row1Input.text.toString().trim())
                .putString("extra_keys_row2", row2Input.text.toString().trim())
                .apply()
            Toast.makeText(this, "Extra keys saved", Toast.LENGTH_SHORT).show()
        }
        findViewById<TextView>(R.id.reset_extra_keys_btn).setOnClickListener {
            row1Input.setText("\u2630 ESC TAB CTRL ALT \u25B2 HOME END")
            row2Input.setText("INS DEL && \u25C0 \u25BC \u25B6 \u232B")
            prefs.edit()
                .putString("extra_keys_row1", row1Input.text.toString().trim())
                .putString("extra_keys_row2", row2Input.text.toString().trim())
                .apply()
            Toast.makeText(this, "Extra keys reset", Toast.LENGTH_SHORT).show()
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
                    put("theme", prefs.getString("theme", "amoled"))
                    put("custom_bg", prefs.getInt("custom_bg", 0))
                    put("custom_text", prefs.getInt("custom_text", 0))
                    put("custom_primary", prefs.getInt("custom_primary", 0))
                    put("font", prefs.getString("font", "monospace"))
                    put("font_size", prefs.getInt("font_size", 20))
                    put("scrollback", prefs.getInt("scrollback", 4))
                    put("terminal_opacity", prefs.getInt("terminal_opacity", 10))
                    put("autohide_keys", prefs.getBoolean("autohide_keys", false))
                    put("wakelock", prefs.getBoolean("wakelock", true))
                    put("auto_night", prefs.getBoolean("auto_night", false))
                }
                val fileName = "RedTerm_config.json"
                val file = java.io.File(getExternalFilesDir(null), fileName)
                file.writeText(json.toString(2))
                Toast.makeText(this, "Config exported to $fileName", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        findViewById<TextView>(R.id.import_config_btn).setOnClickListener {
            try {
                val candidates = listOf(
                    java.io.File(android.os.Environment.getExternalStorageDirectory(), "RedTerm/RedTerm_config.json"),
                    java.io.File(getExternalFilesDir(null), "RedTerm_config.json")
                )
                val file = candidates.firstOrNull { it.exists() }
                if (file == null) {
                    Toast.makeText(this, "No RedTerm_config.json found", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val json = org.json.JSONObject(file.readText())
                val edit = prefs.edit()
                edit.putString("theme", json.optString("theme", "amoled"))
                edit.putInt("custom_bg", json.optInt("custom_bg", 0))
                edit.putInt("custom_text", json.optInt("custom_text", 0))
                edit.putInt("custom_primary", json.optInt("custom_primary", 0))
                edit.putString("font", json.optString("font", "monospace"))
                edit.putInt("font_size", json.optInt("font_size", 20))
                edit.putInt("scrollback", json.optInt("scrollback", 4))
                edit.putInt("terminal_opacity", json.optInt("terminal_opacity", 10))
                edit.putBoolean("autohide_keys", json.optBoolean("autohide_keys", false))
                edit.putBoolean("wakelock", json.optBoolean("wakelock", true))
                edit.putBoolean("auto_night", json.optBoolean("auto_night", false))
                edit.apply()
                NightModeReceiver.notifyChanged(this, prefs)
                Toast.makeText(this, "Config imported from ${file.name}", Toast.LENGTH_LONG).show()
                recreate()
            } catch (e: Exception) {
                Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        findViewById<TextView>(R.id.backup_btn).setOnClickListener {
            val installed = installer.getInstalledDistros()
            if (installed.isEmpty()) {
                Toast.makeText(this, "No distros installed", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val names = installed.map { it.replaceFirstChar { c -> c.uppercase() } }.toTypedArray()
            val selected = BooleanArray(installed.size)
            AlertDialog.Builder(this)
                .setTitle("Backup distros")
                .setMultiChoiceItems(names, selected) { _, which, isChecked -> selected[which] = isChecked }
                .setPositiveButton("Backup") { _, _ ->
                    val targets = installed.filterIndexed { i, _ -> selected[i] }
                    if (targets.isEmpty()) {
                        Toast.makeText(this, "Nothing selected", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    val outDir = backupDir()
                    val dialog = android.app.ProgressDialog(this).apply {
                        setTitle("Backing up")
                        setMessage("Creating backup archives...")
                        setIndeterminate(true)
                        setCancelable(false)
                    }
                    dialog.show()
                    Thread {
                        var done = 0
                        var failed = 0
                        for (distro in targets) {
                            try {
                                val rootfsDir = installer.getRootfsDir(distro)
                                val backupFile = java.io.File(outDir, "${distro}_backup.tar.gz")
                                val pb = ProcessBuilder(
                                    "tar", "-czf", backupFile.absolutePath,
                                    "-C", rootfsDir.parentFile?.absolutePath ?: "", rootfsDir.name
                                )
                                pb.redirectErrorStream(true)
                                val proc = pb.start()
                                proc.waitFor()
                                if (backupFile.exists() && backupFile.length() > 0) done++ else failed++
                            } catch (_: Exception) {
                                failed++
                            }
                        }
                        runOnUiThread {
                            dialog.dismiss()
                            val msg = if (failed == 0) {
                                "Backed up $done distro(s): $outDir"
                            } else {
                                "Backup: $done ok, $failed failed"
                            }
                            Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
                        }
                    }.start()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        findViewById<TextView>(R.id.restore_btn).setOnClickListener {
            val files = backupFiles()
            if (files.isEmpty()) {
                Toast.makeText(this, "No backups found in /sdcard/RedTerm", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val names = files.map { it.name }.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Restore Distro")
                .setItems(names) { _, which ->
                    val backupFile = files[which]
                    val distroName = backupFile.name.removeSuffix("_backup.tar.gz")
                    val rootfsDir = installer.getRootfsDir(distroName)
                    if (rootfsDir.exists()) {
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Overwrite $distroName?")
                            .setMessage("The existing rootfs will be deleted and replaced.")
                            .setPositiveButton("Overwrite") { _, _ ->
                                restoreDistro(backupFile, distroName, rootfsDir)
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    } else {
                        restoreDistro(backupFile, distroName, rootfsDir)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        versionInfo.text = "${getString(R.string.app_name)} v${BuildConfig.VERSION_NAME}"
    }

    private fun backupDir(): java.io.File {
        val shared = java.io.File(
            android.os.Environment.getExternalStorageDirectory(), "RedTerm"
        )
        shared.mkdirs()
        return if (shared.exists()) {
            shared
        } else {
            java.io.File(getExternalFilesDir(null), "backups").apply { mkdirs() }
        }
    }

    private fun backupFiles(): List<java.io.File> {
        val files = mutableListOf<java.io.File>()
        java.io.File(android.os.Environment.getExternalStorageDirectory(), "RedTerm")
            .listFiles { f -> f.name.endsWith("_backup.tar.gz") }
            ?.let { files.addAll(it) }
        getExternalFilesDir(null)
            ?.listFiles { f -> f.name.endsWith("_backup.tar.gz") }
            ?.let { files.addAll(it) }
        return files.distinctBy { it.name }
    }

    private fun restoreDistro(backupFile: java.io.File, distroName: String, rootfsDir: java.io.File) {
        val dialog = android.app.ProgressDialog(this).apply {
            setTitle("Restoring $distroName")
            setMessage("Extracting rootfs...")
            setIndeterminate(true)
            setCancelable(false)
        }
        dialog.show()
        Thread {
            try {
                rootfsDir.deleteRecursively()
                rootfsDir.mkdirs()
                val pb = ProcessBuilder(
                    "tar", "-xzf", backupFile.absolutePath, "-C", rootfsDir.absolutePath
                )
                pb.redirectErrorStream(true)
                val proc = pb.start()
                proc.waitFor()
                if (!java.io.File(rootfsDir, "etc/os-release").exists() &&
                    !java.io.File(rootfsDir, "bin/busybox").exists()
                ) {
                    throw RuntimeException("Backup does not look like a RedTerm distro")
                }
                installer.saveInstalled(distroName)
                installer.repairRootfs(rootfsDir)
                runOnUiThread {
                    dialog.dismiss()
                    Toast.makeText(
                        this@SettingsActivity, "$distroName restored", Toast.LENGTH_LONG
                    ).show()
                    populateDistroList()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dialog.dismiss()
                    Toast.makeText(
                        this@SettingsActivity, "Restore error: ${e.message}", Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
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

    private fun customFontFiles(): List<File> =
        File(filesDir, "fonts").listFiles { f ->
            f.isFile && (f.extension.equals("ttf", true) || f.extension.equals("otf", true))
        }?.sortedBy { it.name.lowercase() } ?: emptyList()

    private fun fontDisplayName(fileName: String): String =
        fileName.removeSuffix(".ttf").removeSuffix(".TTF").removeSuffix(".otf").removeSuffix(".OTF")

    private fun renderCustomFontList(prefs: android.content.SharedPreferences) {
        val container = findViewById<LinearLayout>(R.id.custom_fonts_list)
        container.removeAllViews()
        val files = customFontFiles()
        if (files.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No custom fonts imported"
                setTextColor(0xFF6C7086.toInt())
                textSize = 12f
                setPadding(4, 8, 4, 8)
            })
            return
        }
        for (f in files) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8, 6, 8, 6)
            }
            row.addView(TextView(this).apply {
                text = "${fontDisplayName(f.name)} (custom)"
                setTextColor(0xFFCDD6F4.toInt())
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = "Rename"
                setTextColor(0xFF89B4FA.toInt())
                textSize = 12f
                setPadding(12, 4, 10, 4)
                setOnClickListener { showRenameFontDialog(f) }
            })
            row.addView(TextView(this).apply {
                text = "Delete"
                setTextColor(0xFFFF6B6B.toInt())
                textSize = 12f
                setPadding(12, 4, 4, 4)
                setOnClickListener {
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("Remove font?")
                        .setMessage("Delete '${fontDisplayName(f.name)}'? If it is the current font, the terminal falls back to monospace.")
                        .setPositiveButton("Delete") { _, _ ->
                            f.delete()
                            if (prefs.getString("font", "monospace") == "custom:${f.name}") {
                                prefs.edit().putString("font", "monospace").apply()
                            }
                            renderCustomFontList(prefs)
                            rebuildFontSpinner(prefs)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            })
            container.addView(row)
        }
    }

    private fun showRenameFontDialog(file: File) {
        val input = EditText(this).apply {
            setText(fontDisplayName(file.name))
            setTextColor(tc(R.attr.terminalText, 0xFFCDD6F4.toInt()))
            setHintTextColor(0xFF7F849C.toInt())
            textSize = 14f
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename font")
            .setMessage("New name (extension is kept)")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                if (newName.isEmpty()) {
                    Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (newName == fontDisplayName(file.name)) return@setPositiveButton
                val ext = file.extension.lowercase().ifEmpty { "ttf" }
                val target = File(file.parentFile, "$newName.$ext")
                if (target.exists()) {
                    Toast.makeText(this, "A font with that name already exists", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                if (file.renameTo(target)) {
                    if (prefs.getString("font", "monospace") == "custom:${file.name}") {
                        prefs.edit().putString("font", "custom:${target.name}").apply()
                    }
                    renderCustomFontList(prefs)
                    rebuildFontSpinner(prefs)
                } else {
                    Toast.makeText(this, "Rename failed", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun rebuildFontSpinner(prefs: android.content.SharedPreferences) {
        val customFonts = customFontFiles().map { it.name }
        val fonts = listOf("JetBrains Mono", "Fira Code", "Source Code Pro", "Ubuntu Mono", "monospace", "Droid Sans Mono", "Noto Sans Mono", "Cascadia Code") + customFonts.map { "custom:$it" }
        val fontLabels = fonts.map { if (it.startsWith("custom:")) "${fontDisplayName(it.removePrefix("custom:"))} (custom)" else it }
        val spinner = findViewById<Spinner>(R.id.font_spinner)
        val current = prefs.getString("font", "monospace")
        val idx = (fonts.indexOf(current)).coerceAtLeast(0)
        spinner.adapter = object : ArrayAdapter<String>(this, R.layout.spinner_item, fontLabels) {
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val row = layoutInflater.inflate(R.layout.spinner_dropdown_checked, parent, false)
                row.findViewById<TextView>(R.id.dropdown_text).text = fontLabels[position]
                row.findViewById<TextView>(R.id.dropdown_check).visibility =
                    if (position == spinner.selectedItemPosition) android.view.View.VISIBLE else android.view.View.GONE
                return row
            }
        }
        spinner.setSelection(idx)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos != idx) {
                    prefs.edit().putString("font", fonts[pos]).apply()
                }
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
    }

    private fun importFonts(uri: Uri) {
        try {
            val name = uri.lastPathSegment?.substringAfterLast('/')
                ?.takeIf { it.isNotBlank() } ?: "font.ttf"
            val cleanName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                .takeIf { it.isNotBlank() } ?: "font.ttf"
            val ext = if (cleanName.substringAfterLast('.', "").equals("otf", true)) "otf" else "ttf"
            val base = cleanName.substringBeforeLast('.', cleanName)
            val dir = File(filesDir, "fonts")
            dir.mkdirs()
            var target = File(dir, cleanName)
            var counter = 1
            while (target.exists()) {
                target = File(dir, "${base}_$counter.$ext")
                counter++
            }
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT_FONT && resultCode == RESULT_OK && data != null) {
            val uris = mutableListOf<Uri>()
            data.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
            }
            if (uris.isEmpty()) data.data?.let { uris.add(it) }
            for (uri in uris) importFonts(uri)
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            renderCustomFontList(prefs)
            rebuildFontSpinner(prefs)
            Toast.makeText(this, if (uris.size > 1) "${uris.size} fonts imported" else "Font imported", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        unregisterReceiver(nightReceiver)
        super.onDestroy()
    }

    private fun applyTheme() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
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
            "custom" -> setTheme(R.style.Theme_RedTermApp_Custom)
            else -> setTheme(R.style.Theme_RedTermApp)
        }
    }
}
