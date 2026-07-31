package com.redtermapp.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.redtermapp.R
import com.redtermapp.distro.DistroInstaller

class MainActivity : AppCompatActivity() {

    private val installer by lazy { DistroInstaller(applicationContext) }

    private fun tc(attr: Int, default: Int): Int {
        val ta = theme.obtainStyledAttributes(intArrayOf(attr))
        val c = ta.getColor(0, default)
        ta.recycle()
        return c
    }

    private val nightReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            recreate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        androidx.core.content.ContextCompat.registerReceiver(
            this, nightReceiver,
            android.content.IntentFilter(NightModeReceiver.ACTION_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        populateDistroList()

        val onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<Button>(R.id.settings_button).setOnClickListener { onSettings() }
        findViewById<ImageButton>(R.id.settings_gear).setOnClickListener { onSettings() }

        findViewById<Button>(R.id.new_session_button).setOnClickListener {
            val distros = installer.getInstalledDistros()
            if (distros.isNotEmpty()) {
                TerminalActivity.launch(this, distros.first())
            } else {
                Toast.makeText(this, "No distros installed. Add one first.", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.add_distro_button).setOnClickListener {
            startActivity(Intent(this, WelcomeActivity::class.java).apply {
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
                text = "No distributions installed.\nTap + to install one."
                setTextColor(0xFF6C7086.toInt())
                textSize = 16f
                setPadding(16, 16, 16, 16)
            })
            return
        }

        for (name in installed) {
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 12) }
                setCardBackgroundColor(tc(R.attr.extraKeysBg, 0xFF181825.toInt()))
                radius = 12f
                setOnClickListener {
                    TerminalActivity.launch(this@MainActivity, name)
                }
                setOnLongClickListener {
                    confirmDelete(name)
                    true
                }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(24, 24, 24, 24)
                    addView(TextView(context).apply {
                        text = name.replaceFirstChar { it.uppercase() }
                        setTextColor(tc(R.attr.terminalText, 0xFFCDD6F4.toInt()))
                        textSize = 18f
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    })
                    addView(TextView(context).apply {
                        text = "Files"
                        setTextColor(0xFFA6E3A1.toInt())
                        textSize = 14f
                        setPadding(0, 0, 16, 0)
                        setOnClickListener {
                            startActivity(Intent(this@MainActivity, FileBrowserActivity::class.java).apply {
                                putExtra("distro", name)
                            })
                        }
                    })
                    addView(TextView(context).apply {
                        text = "Launch ›"
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

    override fun onDestroy() {
        unregisterReceiver(nightReceiver)
        super.onDestroy()
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
            "custom" -> setTheme(R.style.Theme_RedTermApp_Custom)
            else -> setTheme(R.style.Theme_RedTermApp)
        }
    }
}