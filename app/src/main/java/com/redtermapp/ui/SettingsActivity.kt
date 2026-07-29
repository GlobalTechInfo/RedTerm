package com.redtermapp.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
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

        findViewById<ImageButton>(R.id.add_distro_button).setOnClickListener {
            startActivity(Intent(this@SettingsActivity, WelcomeActivity::class.java).apply {
                putExtra(WelcomeActivity.EXTRA_SELECT_ONLY, true)
            })
        }
    }

    private fun populateDistroList() {
        val container = findViewById<LinearLayout>(R.id.distro_list)
        container.removeAllViews()

        val installed = installer.getInstalledDistros()
        if (installed.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No distros installed. Tap + to add one."
                setTextColor(0xFF6C7086.toInt())
                textSize = 14f
                setPadding(8, 8, 8, 8)
            })
            return
        }

        for (name in installed) {
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 8) }
                setCardBackgroundColor(tc(R.attr.extraKeysBg, 0xFF181825.toInt()))
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
                    setPadding(20, 20, 20, 20)
                    addView(TextView(context).apply {
                        text = name.replaceFirstChar { it.uppercase() }
                        setTextColor(tc(R.attr.terminalText, 0xFFCDD6F4.toInt()))
                        textSize = 18f
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    })
                    addView(TextView(context).apply {
                        text = "Launch \u203A"
                        setTextColor(0xFF89B4FA.toInt())
                        textSize = 18f
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
        val theme = getSharedPreferences("settings", Context.MODE_PRIVATE).getString("theme", "default")
        when (theme) {
            "green" -> setTheme(R.style.Theme_RedTermApp_Green)
            "light" -> setTheme(R.style.Theme_RedTermApp_Light)
            else -> setTheme(R.style.Theme_RedTermApp)
        }
    }
}
