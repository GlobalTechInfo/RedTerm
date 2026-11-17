package com.redtermapp.ui

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.redtermapp.R
import com.redtermapp.ui.filelist.FileEntry
import com.redtermapp.ui.filelist.OpResult
import com.redtermapp.ui.filelist.SftpSource
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

/**
 * A parallel, resumable transfer queue over one SFTP server. Files are
 * handed out in capped batches by [TransferQueue]; each transfer is
 * [TransferPlan]-checked against the destination so a restarted batch
 * resumes where it left off instead of redoing work, and a finished file
 * is skipped.
 */
class TransferQueueActivity : AppCompatActivity() {

    private val servers by lazy { SshStore.load(applicationContext) }
    private var source: SftpSource? = null
    private lateinit var serverSpinner: Spinner
    private lateinit var concurrency: Spinner
    private lateinit var remoteDir: EditText
    private val selected = LinkedHashMap<String, FileEntry>()
    private val localPicks = LinkedHashMap<String, File>()
    private lateinit var fileContainer: LinearLayout
    private lateinit var status: TextView
    private var running = false

    private val pickLocal = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.filterNotNull().forEach { uri -> localPicks[uri.toString()] = pickFile(uri) }
        renderLocal()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        body.addView(TextView(this).apply {
            setText(R.string.transfer_queue)
            textSize = 20f
        })

        serverSpinner = ScreenWidgets.spinner(this, servers.map { it.label })
        serverSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long
                ) {
                    source = SftpSource(this@TransferQueueActivity, servers[position], servers[position].keyId)
                }

                override fun onNothingSelected(p: android.widget.AdapterView<*>?) = Unit
            }
        body.addView(serverSpinner)

        body.addView(ScreenWidgets.actionButton(this, R.string.transfer_list_remote) { refreshRemote() })

        remoteDir = EditText(this).apply {
            hint = getString(R.string.transfer_remote_dir)
            textSize = 13f
            // Density-scaled room below the text so it sits above the field's
            // underline instead of crowding it.
            val d = resources.displayMetrics.density
            setPadding(0, (8 * d).toInt(), 0, (18 * d).toInt())
            setTextColor(context.cardTextColor())
            setHintTextColor(context.mutedTextColor())
        }
        body.addView(remoteDir)

        concurrency = ScreenWidgets.spinner(this, listOf("1", "2", "3", "4"))
        body.addView(concurrency)

        body.addView(ScreenWidgets.actionButton(this, R.string.transfer_pick_local) {
            pickLocal.launch(arrayOf("*/*"))
        })
        fileContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(fileContainer)

        body.addView(MaterialButton(this).apply {
            setText(R.string.transfer_run)
            setOnClickListener { runQueue() }
        })
        status = TextView(this).apply {
            textSize = 12f
            setPadding(0, 6, 0, 0)
        }
        body.addView(status)

        val scroll = ScrollView(this).apply { addView(body) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        ScreenToolbar.install(this, root, getString(R.string.transfer_queue))
        setContentView(root)
        renderLocal()
    }

    private fun renderLocal() {
        for (f in localPicks.values) {
            fileContainer.addView(TextView(this).apply {
                text = getString(R.string.transfer_file_upload, f.name)
                textSize = 12f
            })
        }
    }

    private fun refreshRemote() {
        val s = source ?: return
        // A listing is a network round trip, and `home` can cost a whole
        // connection. Running it in the click is what ANR'd, so the round trip
        // happens on a worker and only the checkbox rebuild happens on main.
        val requested = remoteDir.text?.toString()?.takeIf { it.isNotBlank() }
        status.setText(R.string.transfer_listing)
        thread(name = "redterm-transfer-list") {
            val dir = requested ?: s.home
            val entries = s.list(dir)
            val err = s.lastError
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                remoteDir.setText(dir)
                fileContainer.removeAllViews()
                selected.clear()
                for (e in (entries ?: emptyList()).filter { !it.isDirectory }) {
                    val cb = CheckBox(this).apply {
                        text = getString(R.string.transfer_file_download, e.name, human(e.size))
                        textSize = 12f
                        setTextColor(cardTextColor())
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) selected[e.path] = e else selected.remove(e.path)
                        }
                    }
                    fileContainer.addView(cb)
                }
                if (entries == null) {
                    status.text = err
                } else if (selected.isEmpty()) {
                    status.setText(R.string.transfer_pick_files)
                }
            }
        }
    }

    private fun runQueue() {
        if (running) return
        val s = source ?: return
        // Read the UI state on the main thread, then drop off it. A worker must
        // not touch views or the live maps, and `s.home` can cost a connection.
        val downloads = selected.toMap()
        val remoteJobs = selected.mapValues { (_, e) -> destFor(e) }
        val uploads = localPicks.values.associateBy { it.name }
        val n = (concurrency.selectedItem as String).toInt().coerceIn(1, 4)
        val remoteDirText = remoteDir.text?.toString()?.takeIf { it.isNotBlank() }

        val tasks = selected.values.map { e ->
            TransferQueue.Task(e.path, TransferPlan.Kind.DOWNLOAD, e.name, e.size)
        } + localPicks.values.map { f ->
            TransferQueue.Task(f.name, TransferPlan.Kind.UPLOAD, f.name, f.length())
        }
        if (tasks.isEmpty()) {
            status.setText(R.string.transfer_nothing)
            return
        }

        val queue = TransferQueue(tasks, n)
        running = true
        status.setText(R.string.transfer_running)

        // The transfers and the join both run on a worker. Joining the pool on
        // the main thread is what ANR'd the screen: it sat blocked for the
        // whole transfer while the watchdog timed it out.
        thread(name = "redterm-transfer-run") {
            val pool = (1..n).map { i ->
                thread(start = true, name = "redterm-transfer-$i") {
                    while (true) {
                        val batch = queue.nextPending(1)
                        if (batch.isEmpty()) break
                        val task = batch.single()
                        when (task.kind) {
                            TransferPlan.Kind.DOWNLOAD -> {
                                val remote = downloads[task.id]
                                val dest = remoteJobs[task.id]
                                if (remote == null || dest == null) {
                                    queue.markSkipped(task.id)
                                } else {
                                    val existing = if (dest.isFile) dest.length() else 0L
                                    val plan = TransferPlan.plan(existing, remote.size)
                                    if (plan == TransferPlan.Plan.DONE) {
                                        queue.markSkipped(task.id)
                                    } else {
                                        val off = TransferPlan.startOffset(existing, remote.size)
                                        val res = s.downloadTo(remote, dest, startOffset = off) { _, _ -> }
                                        finish(queue, task, res)
                                    }
                                }
                            }
                            TransferPlan.Kind.UPLOAD -> {
                                val src = uploads[task.id]
                                if (src == null) {
                                    queue.markSkipped(task.id)
                                } else {
                                    val dir = remoteDirText ?: s.home
                                    val res = s.uploadFrom(src, "$dir/${src.name}") { _, _ -> }
                                    finish(queue, task, res)
                                }
                            }
                        }
                    }
                }
            }
            pool.forEach { it.join() }
            val done = queue.doneCount()
            val failed = queue.failedCount()
            val skipped = queue.skippedCount()
            running = false
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                status.text = getString(R.string.transfer_done, done, failed, skipped)
            }
        }
    }

    private fun finish(queue: TransferQueue, task: TransferQueue.Task, res: OpResult) {
        if (res is OpResult.Ok) queue.markDone(task.id) else queue.markFailed(task.id)
    }

    private fun destFor(e: FileEntry): File {
        val dir = getExternalFilesDir("transfers") ?: filesDir
        dir.mkdirs()
        return File(dir, e.name)
    }

    private fun pickFile(uri: Uri): File {
        val dir = getExternalFilesDir("transfers") ?: filesDir
        dir.mkdirs()
        val out = File(dir, "in-${uri.lastPathSegment ?: "file"}")
        contentResolver.openInputStream(uri)?.copyTo(out.outputStream())
        return out
    }

    private fun human(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale.getDefault(), "%.1f GB", b / 1073741824.0)
        b >= 1L shl 20 -> String.format(Locale.getDefault(), "%.1f MB", b / 1048576.0)
        else -> "$b B"
    }

    private fun applyTheme() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
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
