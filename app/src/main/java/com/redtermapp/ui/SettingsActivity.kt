package com.redtermapp.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.redtermapp.BuildConfig
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.redtermapp.R
import com.redtermapp.service.TerminalService

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

        val fontSlider = findViewById<SeekBar>(R.id.font_size_slider)
        val wakelockSwitch = findViewById<Switch>(R.id.wakelock_switch)
        val versionInfo = findViewById<TextView>(R.id.version_info)
        val themeGroup = findViewById<RadioGroup>(R.id.theme_group)

        val currentTheme = prefs.getString("theme", "default")
        when (currentTheme) {
            "green" -> themeGroup.check(R.id.theme_green)
            "light" -> themeGroup.check(R.id.theme_light)
            else -> themeGroup.check(R.id.theme_default)
        }

        themeGroup.setOnCheckedChangeListener { _, checkedId ->
            val theme = when (checkedId) {
                R.id.theme_green -> "green"
                R.id.theme_light -> "light"
                else -> "default"
            }
            prefs.edit().putString("theme", theme).apply()
            recreate()
        }

        fontSlider.progress = prefs.getInt("font_size", 14)
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

        versionInfo.text = "${getString(R.string.app_name)} v${BuildConfig.VERSION_NAME}"
    }

    private fun applyTheme() {
        val theme = getSharedPreferences("settings", Context.MODE_PRIVATE).getString("theme", "default")
        when (theme) {
            "green" -> setTheme(R.style.Theme_RedTermApp_Green)
            "light" -> setTheme(R.style.Theme_RedTermApp_Light)
            else -> setTheme(R.style.Theme_RedTermApp)
        }
    }
}
