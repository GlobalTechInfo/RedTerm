package com.redtermapp.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import android.widget.Toast
import android.view.View
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
        addDistroCards(container, installer.getInstalledDistros())
        addToolCards(container)
    }

    private fun addDistroCards(container: LinearLayout, installed: List<String>) {
        if (installed.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.no_distributions_installed)
                setTextColor(0xFF6C7086.toInt())
                textSize = 16f
                setPadding(16, 16, 16, 16)
            })
            return
        }

        for (name in installed) {
            val rootfsDir = installer.getRootfsDir(name)
            val cachedSize = installer.cachedSizeBytes(name)
            val sizeStr = if (cachedSize >= 0L) DistroInstaller.formatSize(cachedSize)
            else getString(R.string.distro_size_calculating, name)
            val sizeLabel = TextView(this).apply {
                text = sizeStr
                setTextColor(0xFF6C7086.toInt())
                textSize = 12f
            }
            if (cachedSize < 0L || installer.isSizeCacheStale(name)) {
                installer.refreshSizeCache(name) { bytes ->
                    if (bytes >= 0L) runOnUiThread { sizeLabel.text = DistroInstaller.formatSize(bytes) }
                }
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
                        addView(sizeLabel)
                    })
                    addView(TextView(context).apply {
                        text = getString(R.string.files)
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
                        text = getString(R.string.launch_chevron)
                        setTextColor(0xFF89B4FA.toInt())
                        textSize = 16f
                    })
                })
            }
            container.addView(card)
        }
    }


    private data class ToolCard(
        val iconRes: Int,
        val labelRes: Int,
        val action: (MainActivity) -> Unit
    )

    private fun toolCards(): List<ToolCard> = listOf(
        ToolCard(R.drawable.ic_tool_packages, R.string.package_updates) { activity ->
            activity.startActivity(Intent(activity, PackageUpdateActivity::class.java))
        },
        ToolCard(R.drawable.ic_tool_storage, R.string.disk_usage) { activity ->
            activity.startActivity(Intent(activity, DiskUsageActivity::class.java))
        },
        ToolCard(R.drawable.ic_tool_ssh, R.string.ssh_client) { activity ->
            activity.startActivity(Intent(activity, SshManagerActivity::class.java))
        },
        ToolCard(R.drawable.ic_tool_record, R.string.recordings) { activity ->
            activity.startActivity(Intent(activity, RecordingActivity::class.java))
        },
        ToolCard(R.drawable.ic_tool_update, R.string.base_image_update) { activity ->
            activity.startActivity(Intent(activity, BaseImageUpdateActivity::class.java))
        },
        ToolCard(R.drawable.ic_tool_backup, R.string.manage_backups) { activity ->
            activity.startActivity(Intent(activity, BackupManagerActivity::class.java))
        }
    )

    private fun addToolCards(container: LinearLayout) {
        val header = TextView(this).apply {
            text = getString(R.string.tools)
            setTextColor(tc(R.attr.terminalText, 0xFFCDD6F4.toInt()))
            textSize = 13f
            alpha = 0.7f
            val pad = (8 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        container.addView(header)
        for (tool in toolCards()) {
            container.addView(toolCard(tool))
        }
    }

    private fun toolCard(tool: ToolCard): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 12) }
            setCardBackgroundColor(tc(R.attr.extraKeysBg, 0xFF181825.toInt()))
            radius = 12f
            setOnClickListener { tool.action(this@MainActivity) }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(24, 24, 24, 24)
                addView(ImageView(context).apply {
                    setImageResource(tool.iconRes)
                    val size = (24 * resources.displayMetrics.density).toInt()
                    layoutParams = LinearLayout.LayoutParams(size, size)
                    setColorFilter(tc(R.attr.terminalText, 0xFFCDD6F4.toInt()))
                    contentDescription = getString(tool.labelRes)
                })
                addView(TextView(context).apply {
                    text = getString(tool.labelRes)
                    setTextColor(tc(R.attr.terminalText, 0xFFCDD6F4.toInt()))
                    textSize = 18f
                    setPadding((16 * resources.displayMetrics.density).toInt(), 0, 0, 0)
                    setMinimumWidth(dp(140))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
                addView(TextView(context).apply {
                    // These open a screen, they do not start a session, so
                    // calling them "Launch" was misleading next to a real
                    // installed distribution.
                    text = getString(R.string.open_chevron)
                    setTextColor(tc(R.attr.terminalText, 0xFFCDD6F4.toInt()))
                    textSize = 16f
                })
            })
        }
        return card
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * Backups live in shared storage so they survive an uninstall, which needs
     * the "all files access" grant. That grant is per-install, so after a
     * reinstall the archive list would otherwise just look empty.
     */
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
        // Backups live in shared storage so they survive uninstall/reinstall;
        // that folder is unreadable until "all files access" is granted, and the
        // grant is reset on every reinstall.
        if (!com.redtermapp.util.StoragePermission.isAccessible(this)) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.storage_access_title)
                .setMessage(R.string.storage_access_message)
                .setPositiveButton(R.string.grant_access) { _, _ ->
                    com.redtermapp.util.StoragePermission.requestAccess(this)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        val dir = java.io.File(
            android.os.Environment.getExternalStorageDirectory(), "RedTerm"
        )
        if ((!dir.exists() && !dir.mkdirs()) || !dir.canWrite()) {
            Toast.makeText(this, R.string.backup_storage_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        val existing = java.io.File(dir, "${name}_backup.tar.gz")
        if (existing.exists()) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.overwrite_backups_title)
                .setMessage(
                    getString(R.string.overwrite_one_backup_message, existing.name)
                )
                .setPositiveButton(R.string.overwrite) { _, _ ->
                    startBackup(name, dir, overwrite = true)
                }
                .setNeutralButton(R.string.skip_existing) { _, _ ->
                    startBackup(name, dir, overwrite = false)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            startBackup(name, dir, overwrite = true)
        }
    }

    private fun startBackup(name: String, dir: java.io.File, overwrite: Boolean) {
        Toast.makeText(this, "Backing up $name...", Toast.LENGTH_SHORT).show()
        Thread {
            val result = installer.backupDetailed(name, dir, overwrite)
            runOnUiThread {
                if (result.succeeded) {
                    Toast.makeText(
                        this, "Backup saved: ${result.file!!.name}", Toast.LENGTH_LONG
                    ).show()
                } else {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(R.string.backup_finished_with_errors)
                        .setMessage(getString(R.string.backup_line_failed, name, result.reason ?: ""))
                        .setPositiveButton(R.string.ok, null)
                        .show()
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
                installer.uninstallAsync(name) {
                    runOnUiThread {
                        populateDistroList()
                        RedTermWidgetProvider.updateAll(this)
                        Toast.makeText(this, "$name removed", Toast.LENGTH_SHORT).show()
                    }
                }
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