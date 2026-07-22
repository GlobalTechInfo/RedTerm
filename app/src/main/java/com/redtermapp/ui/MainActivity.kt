package com.redtermapp.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        populateDistroList()

        findViewById<Button>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

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
                    setPadding(24, 24, 24, 24)
                    addView(TextView(context).apply {
                        text = name.replaceFirstChar { it.uppercase() }
                        setTextColor(tc(R.attr.terminalText, 0xFFCDD6F4.toInt()))
                        textSize = 18f
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
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

    private fun applyTheme() {
        val theme = getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            .getString("theme", "default")
        when (theme) {
            "green" -> setTheme(R.style.Theme_RedTermApp_Green)
            "light" -> setTheme(R.style.Theme_RedTermApp_Light)
            else -> setTheme(R.style.Theme_RedTermApp)
        }
    }
}