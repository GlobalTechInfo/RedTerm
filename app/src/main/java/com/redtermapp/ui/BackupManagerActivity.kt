package com.redtermapp.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.redtermapp.R
import com.redtermapp.distro.DistroInstaller
import com.redtermapp.util.StoragePermission
import java.io.File

/**
 * Lists the backup archives in shared storage and offers restore, share and
 * delete for each one, plus cleanup of files left by interrupted backups.
 *
 * Archives live in /sdcard/RedTerm precisely so they outlive the app: Android
 * wipes app-specific storage on uninstall, so a backup kept there would be lost
 * exactly when the user reinstalls to restore it.
 */
class BackupManagerActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var header: TextView
    private val installer by lazy { DistroInstaller(applicationContext) }
    private var pendingAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            setText(R.string.manage_backups)
            textSize = 20f
        })
        header = TextView(this).apply {
            textSize = 12f
            setPadding(0, 6, 0, 10)
        }
        root.addView(header)
        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            container,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        ScreenToolbar.install(this, root, getString(R.string.manage_backups))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val pending = pendingAction
        if (pending != null && StoragePermission.isAccessible(this)) {
            pendingAction = null
            pending()
            return
        }
        load()
    }

    private fun load() {
        // The same gate the terminal applies at startup: if the all-files grant is not
        // held, ask for it. Kept here rather than invented per flow, because the
        // inconsistency worth fixing is a flow that behaves differently from the others —
        // and the terminal's startup prompt is the behaviour that is right.
        if (!StoragePermission.isAccessible(this)) {
            container.removeAllViews()
            header.text = ""
            container.addView(TextView(this).apply { setText(R.string.storage_access_message) })
            container.addView(
                TextView(this).apply {
                    setText(R.string.grant_access)
                    val accent = tc(androidx.appcompat.R.attr.colorPrimary, 0xFF89B4FA.toInt())
                    setTextColor(accent)
                    val inner = (16 * resources.displayMetrics.density).toInt()
                    setPadding(inner, inner, inner, inner)
                    setOnClickListener {
                        pendingAction = { load() }
                        StoragePermission.requestAccess(this@BackupManagerActivity)
                    }
                }
            )
            return
        }
        container.removeAllViews()
        container.addView(ProgressBar(this))
        Thread({
            val files = backupFiles()
            val orphans = orphanStagingFiles()
            val total = DistroInstaller.formatSize(
                files.sumOf { it.length() } + orphans.sumOf { it.length() }
            )
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                container.removeAllViews()
                header.text = getString(R.string.manage_backups_with_size, total)
                if (files.isEmpty() && orphans.isEmpty()) {
                    container.addView(TextView(this).apply { setText(R.string.no_backups_found) })
                    return@runOnUiThread
                }
                for (file in files) {
                    container.addView(backupCard(file))
                }
                if (orphans.isNotEmpty()) {
                    container.addView(orphanCard(orphans))
                }
            }
        }, "redterm-backup-list").start()
    }

    private fun backupCard(file: File): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 12) }
            setCardBackgroundColor(tc(R.attr.extraKeysBg, 0xFF181825.toInt()))
            radius = 12f
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 20, 24, 20)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    // The archive's filename is an implementation detail of this screen
                    // ("arch_backup.tar.gz"); the distro is what the user recognises.
                    // Matched on the archive's own name, so a backup whose distro has
                    // since been removed from the registry still shows its real title.
                    com.redtermapp.distro.DistroRegistry.allDistros
                        .firstOrNull { file.name.startsWith("${it.name}_") }?.let { distro ->
                        addView(DistroBadge.create(context, distro, 40))
                        addView(TextView(context).apply {
                            text = distro.displayName
                            setTextColor(tc(R.attr.terminalText, 0xFFCDD6F4.toInt()))
                            textSize = 16f
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                                gravity = android.view.Gravity.CENTER_VERTICAL
                            }
                        })
                    } ?: addView(TextView(context).apply {
                        text = file.name
                        setTextColor(tc(R.attr.terminalText, 0xFFCDD6F4.toInt()))
                        textSize = 16f
                        setMinimumWidth(dp(140))
                        maxLines = 2
                    })
                })
                addView(TextView(context).apply {
                    text = getString(
                        R.string.backup_meta,
                        file.name,
                        DistroInstaller.formatSize(file.length()),
                        java.text.SimpleDateFormat(
                            "yyyy-MM-dd HH:mm", java.util.Locale.US
                        ).format(java.util.Date(file.lastModified()))
                    )
                    setTextColor(tc(R.attr.terminalText, 0xFFCDD6F4.toInt()))
                    setTextColor(tc(R.attr.terminalText, 0xFFCDD6F4.toInt()))
                    textSize = 12f
                    alpha = 0.7f
                    setPadding(0, 4, 0, 8)
                })
                addView(TextView(context).apply {
                    text = getString(R.string.tap_for_actions)
                    setTextColor(tc(R.attr.terminalText, 0xFFCDD6F4.toInt()))
                    textSize = 14f
                    setPadding(0, 6, 0, 0)
                })
            })
        }
        card.setOnClickListener { actions(file) }
        return card
    }

    private fun orphanCard(orphans: List<File>): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 12) }
            setCardBackgroundColor(tc(R.attr.extraKeysBg, 0xFF181825.toInt()))
            radius = 12f
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 20, 24, 20)
                addView(TextView(context).apply {
                    text = resources.getQuantityString(
                        R.plurals.orphans_title, orphans.size, orphans.size,
                        DistroInstaller.formatSize(orphans.sumOf { it.length() })
                    )
                    setTextColor(tc(R.attr.terminalText, 0xFFCDD6F4.toInt()))
                    textSize = 15f
                })
                addView(TextView(context).apply {
                    text = resources.getQuantityString(
                        R.plurals.clean_up_orphans_message, orphans.size, orphans.size
                    )
                    setTextColor(tc(R.attr.terminalText, 0xFFCDD6F4.toInt()))
                    textSize = 12f
                    alpha = 0.7f
                    setPadding(0, 4, 0, 8)
                })
            })
        }
        card.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.clean_up_orphans_title)
                .setMessage(
                    resources.getQuantityString(
                        R.plurals.clean_up_orphans_message, orphans.size, orphans.size
                    )
                )
                .setPositiveButton(R.string.delete) { _, _ ->
                    Thread {
                        var removed = 0
                        for (f in orphans) if (f.delete()) removed++
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                resources.getQuantityString(
                                    R.plurals.orphans_deleted, removed, removed
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                            load()
                        }
                    }.start()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        return card
    }

    private fun actions(file: File) {
        val distroName = file.name.removeSuffix("_backup.tar.gz")
        val labels = arrayOf(
            getString(R.string.restore_distro),
            getString(R.string.share),
            getString(R.string.delete_backup)
        )
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> confirmRestore(file, distroName)
                    1 -> share(file)
                    2 -> confirmDelete(file)
                }
            }
            .show()
    }

    private fun confirmRestore(file: File, distroName: String) {
        val rootfsDir = installer.getRootfsDir(distroName)
        if (!rootfsDir.exists()) {
            restore(file, distroName, rootfsDir)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.overwrite_distro_title)
            .setMessage(getString(R.string.overwrite_distro_message, distroName))
            .setPositiveButton(R.string.overwrite) { _, _ ->
                restore(file, distroName, rootfsDir)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun restore(file: File, distroName: String, rootfsDir: File) {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }
        val progress = TextView(this).apply {
            text = getString(R.string.restoring_distro, distroName)
            setPadding(pad, pad, pad, pad)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(progress)
            addView(
                bar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.restore_distro)
            .setView(box)
            .setCancelable(false)
            .create()
        dialog.show()
        Thread({
            val result = DistroRestore(this, installer).restore(
                file, distroName, rootfsDir
            ) { line, fraction, total ->
                runOnUiThread {
                    progress.text = line
                    bar.max = total
                    bar.progress = (fraction * total).toInt()
                }
            }
            runOnUiThread {
                dialog.dismiss()
                val message = when {
                    result.succeeded -> getString(R.string.restore_ok, distroName)
                    result.originalKept -> getString(
                        R.string.restore_failed_kept, result.error ?: ""
                    )
                    else -> getString(R.string.restore_failed, result.error ?: "")
                }
                // A failed restore's message is tar's own output, which is long and is
                // the only description of what went wrong. setMessage would make it
                // unselectable and unscrollable, so it could not be read, copied or shared.
                if (result.succeeded) {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.restore_distro)
                        .setMessage(message)
                        .setPositiveButton(R.string.ok, null)
                        .show()
                } else {
                    ToolOutputDialog.show(this, getString(R.string.restore_distro), message)
                }
            }
        }, "redterm-restore").start()
    }

    private fun share(file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "${applicationContext.packageName}.fileprovider", file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/gzip"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(android.content.Intent.createChooser(intent, getString(R.string.share)))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_item, file.name))
            .setMessage(R.string.delete_backup_warning)
            .setPositiveButton(R.string.delete) { _, _ ->
                Thread {
                    val ok = file.delete()
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            if (ok) R.string.backup_deleted else R.string.delete_failed,
                            Toast.LENGTH_SHORT
                        ).show()
                        load()
                    }
                }.start()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun backupFiles(): List<File> {
        val files = mutableListOf<File>()
        File(android.os.Environment.getExternalStorageDirectory(), "RedTerm")
            .listFiles { f -> f.name.endsWith("_backup.tar.gz") }
            ?.let { files.addAll(it) }
        return files.sortedBy { it.name }
    }

    private fun orphanStagingFiles(): List<File> {
        val found = mutableListOf<File>()
        File(android.os.Environment.getExternalStorageDirectory(), "RedTerm")
            .listFiles { f ->
                f.name.endsWith("_backup.tar.gz.part") || f.name.endsWith("_backup.tar.gz.old")
            }
            ?.let { found.addAll(it) }
        return found
    }

    private fun tc(attr: Int, default: Int): Int {
        val ta = theme.obtainStyledAttributes(intArrayOf(attr))
        val c = ta.getColor(0, default)
        ta.recycle()
        return c
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
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
            else -> setTheme(R.style.Theme_RedTermApp)
        }
    }
}
