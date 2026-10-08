package com.redtermapp.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import androidx.appcompat.app.AppCompatActivity
import com.redtermapp.R
import com.redtermapp.distro.DistroInstaller
import com.redtermapp.distro.SessionRecorder
import com.redtermapp.util.Notifier
import java.io.File

/**
 * Lists ANSI recordings and starts new ones. Playback is handled by
 * RecordingPlayerActivity, which renders the captured escape sequences.
 */
class RecordingActivity : AppCompatActivity() {

    companion object {
        private const val NOTIFICATION_ID = 4202
    }

    private lateinit var list: LinearLayout
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private val recorder by lazy { SessionRecorder(applicationContext) }
    private val installer by lazy { DistroInstaller(applicationContext) }
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            setText(R.string.recordings)
            textSize = 20f
        })
        status = TextView(this).apply {
            textSize = 12f
            setPadding(0, 6, 0, 6)
            visibility = View.GONE
        }
        root.addView(status)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 6, 0, 0)
        }
        root.addView(
            ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        ScreenInsets.applyBottom(root)
        ScreenToolbar.install(this, root, getString(R.string.recordings))
        setContentView(root)
        Notifier.ensureChannel(this)
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun pad() = (16 * resources.displayMetrics.density).toInt()

    private fun reload() {
        if (!recorder.isWritable()) {
            list.removeAllViews()
            list.addView(TextView(this).apply {
                setText(R.string.record_storage_unwritable)
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setTextColor(mutedTextColor())
                setPadding(pad(), pad() * 2, pad(), pad() * 2)
            })
            return
        }
        if (busy) return
        list.removeAllViews()
        val files = recorder.list()
        if (files.isEmpty()) {
            list.addView(TextView(this).apply { setText(R.string.record_no_recordings) })
            return
        }
        for (file in files) {
            list.addView(row(file))
        }
    }

    private fun row(file: File): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 12) }
            radius = 12f
            setCardBackgroundColor(cardColor())
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inner = (12 * resources.displayMetrics.density).toInt()
            setPadding(inner, inner, inner, inner)
        }
        card.addView(body)
        body.addView(TextView(this).apply {
            text = file.name
            textSize = 14f
        })
        body.addView(TextView(this).apply {
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                .format(java.util.Date(file.lastModified()))
            text = getString(
                R.string.recording_meta, DistroInstaller.formatSize(file.length()), stamp
            )
            textSize = 11f
            setPadding(0, 4, 0, 8)
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            MaterialButton(this).apply {
                setText(R.string.play)
                setOnClickListener {
                    startActivity(
                        android.content.Intent(
                            this@RecordingActivity, RecordingPlayerActivity::class.java
                        ).putExtra(RecordingPlayerActivity.EXTRA_PATH, file.absolutePath)
                    )
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            MaterialButton(this).apply {
                setText(R.string.share)
                setOnClickListener { shareRecording(file) }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            MaterialButton(this).apply {
                setText(R.string.delete)
                setOnClickListener {
                    AlertDialog.Builder(this@RecordingActivity)
                        .setTitle(getString(R.string.delete_item, file.name))
                        .setMessage(R.string.delete_file_warning)
                        .setPositiveButton(R.string.delete) { _, _ ->
                            file.delete()
                            reload()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        card.addView(row)
        return card
    }

    private fun shareRecording(file: File) {
        if (!com.redtermapp.util.StoragePermission.isAccessible(this)) {
            com.redtermapp.util.StoragePermission.requestAccess(this)
            return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "${applicationContext.packageName}.fileprovider", file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(android.content.Intent.createChooser(intent, getString(R.string.share)))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show()
        }
    }

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
