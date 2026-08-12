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

        findViewById<ImageButton>(R.id.main_back_btn).setOnClickListener { finish() }

        val onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<Button>(R.id.settings_button).setOnClickListener { onSettings() }
        findViewById<ImageButton>(R.id.settings_gear).setOnClickListener { onSettings() }

        findViewById<Button>(R.id.new_session_button).setOnClickListener {
            val distros = installer.getInstalledDistros()
            when (distros.size) {
                0 -> Toast.makeText(this, "No distros installed. Add one first.", Toast.LENGTH_SHORT).show()
                1 -> TerminalActivity.launch(this, distros.first())
                else -> {
                    val names = distros.map { it.replaceFirstChar { c -> c.uppercase() } }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("Select distro")
                        .setItems(names) { _, which ->
                            TerminalActivity.launch(this, distros[which])
                        }
                        .show()
                }
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
                ).apply { setMargins(0, 0, 0, 12) }
                setCardBackgroundColor(tc(R.attr.extraKeysBg, 0xFF181825.toInt()))
                radius = 12f
                setOnClickListener {
                    TerminalActivity.launch(this@MainActivity, name)
                }
                setOnLongClickListener {
                    showDistroMenu(name)
                    true
                }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(24, 24, 24, 24)
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                        addView(TextView(context).apply {
                            text = name.replaceFirstChar { it.uppercase() }
                            setTextColor(tc(R.attr.terminalText, 0xFFCDD6F4.toInt()))
                            textSize = 18f
                        })
                        addView(TextView(context).apply {
                            text = sizeStr
                            setTextColor(0xFF6C7086.toInt())
                            textSize = 12f
                        })
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

    private fun showDistroMenu(name: String) {
        val items = arrayOf("Launch", "Files", "Backup now", "Home shortcut", "Reset to default", "Remove")
        AlertDialog.Builder(this)
            .setTitle(name.replaceFirstChar { it.uppercase() })
            .setItems(items) { _, which ->
                when (which) {
                    0 -> TerminalActivity.launch(this, name)
                    1 -> startActivity(Intent(this, FileBrowserActivity::class.java).apply {
                        putExtra("distro", name)
                    })
                    2 -> backupDistro(name)
                    3 -> createHomeShortcut(name)
                    4 -> resetDistro(name)
                    5 -> confirmDelete(name)
                }
            }
            .show()
    }

    private fun createHomeShortcut(name: String) {
        val intent = Intent(this, TerminalActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(TerminalActivity.EXTRA_DISTRO, name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val info = androidx.core.content.pm.ShortcutInfoCompat.Builder(this, "launch_$name")
                .setShortLabel(name.replaceFirstChar { it.uppercase() })
                .setLongLabel("Open $name in RedTerm")
                .setIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(this, R.mipmap.ic_launcher))
                .setIntent(intent)
                .build()
            androidx.core.content.pm.ShortcutManagerCompat.requestPinShortcut(this, info, null)
            Toast.makeText(this, "Pin the shortcut from the system dialog", Toast.LENGTH_LONG).show()
        } else {
            val addIntent = Intent("com.android.launcher.action.INSTALL_SHORTCUT").apply {
                putExtra("android.intent.extra.shortcut.INTENT", intent)
                putExtra("android.intent.extra.shortcut.NAME", name.replaceFirstChar { it.uppercase() })
                putExtra(
                    "android.intent.extra.shortcut.ICON_RESOURCE",
                    Intent.ShortcutIconResource.fromContext(this@MainActivity, R.mipmap.ic_launcher)
                )
            }
            sendBroadcast(addIntent)
            Toast.makeText(this, "Shortcut added to home screen", Toast.LENGTH_LONG).show()
        }
    }

    private fun resetDistro(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Reset $name to default?")
            .setMessage("Wipes installed packages, caches and shell configs, restoring the freshly extracted base. The next launch will run first-time setup again.")
            .setPositiveButton("Reset") { _, _ ->
                val dialog = AlertDialog.Builder(this)
                    .setTitle("Resetting $name")
                    .setMessage("Restoring base files...")
                    .setCancelable(false)
                    .show()
                Thread {
                    val ok = try {
                        kotlinx.coroutines.runBlocking {
                            installer.resetToDefault(name) { }
                        }
                    } catch (e: Exception) {
                        false
                    }
                    runOnUiThread {
                        dialog.dismiss()
                        if (ok) {
                            Toast.makeText(this, "$name reset — next launch runs setup again", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this, "Reset failed. Check network and try again.", Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun backupDistro(name: String) {
        Toast.makeText(this, "Backing up $name...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val rootfsDir = installer.getRootfsDir(name)
                var dir = java.io.File(
                    android.os.Environment.getExternalStorageDirectory(), "RedTerm"
                )
                dir.mkdirs()
                if (!dir.exists()) dir = java.io.File(getExternalFilesDir(null), "backups").apply { mkdirs() }
                val backupFile = java.io.File(dir, "${name}_backup.tar.gz")
                val pb = ProcessBuilder(
                    "tar", "-czf", backupFile.absolutePath,
                    "-C", rootfsDir.parentFile?.absolutePath ?: "", rootfsDir.name
                )
                pb.redirectErrorStream(true)
                val proc = pb.start()
                proc.waitFor()
                runOnUiThread {
                    if (backupFile.exists() && backupFile.length() > 0) {
                        Toast.makeText(
                            this, "Backup saved: ${backupFile.absolutePath}", Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(this, "Backup failed", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Backup error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun confirmDelete(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Remove $name?")
            .setMessage("This will delete the rootfs, cached files and all data for $name, and kill any running session for it.")
            .setPositiveButton("Delete") { _, _ ->
                com.redtermapp.ui.TerminalViewModel.get(application).removeSessionsForDistro(name)
                installer.uninstall(name)
                populateDistroList()
                RedTermWidgetProvider.updateAll(this)
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