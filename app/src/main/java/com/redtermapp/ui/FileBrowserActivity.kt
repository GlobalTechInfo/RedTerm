package com.redtermapp.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.edit
import androidx.core.graphics.drawable.toDrawable
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.redtermapp.R
import com.redtermapp.distro.DistroInstaller
import java.io.File
import java.io.IOException

class FileBrowserActivity : AppCompatActivity() {

    private companion object {
        val IMAGE_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "bmp", "webp", "heic", "heif", "avif"
        )
        val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "webm", "mkv", "3gp", "ogv", "mov")
        const val MAX_SEARCH_RESULTS = 2000
        const val SEARCH_DEBOUNCE_MS = 350L
        const val PREFS = "file_browser"
        const val KEY_SHOW_HIDDEN = "show_hidden"
    }

    private var currentDir: File? = null
    private lateinit var rootfsDir: File
    private lateinit var pathLabel: TextView
    private lateinit var fileList: ListView
    private lateinit var distroName: String
    private lateinit var rowAdapter: RowAdapter
    private var rows: List<Row> = emptyList()
    private var showHidden = false
    private var searching = false

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_browser)

        distroName = intent?.getStringExtra("distro") ?: "alpine"
        rootfsDir = DistroInstaller(applicationContext).getRootfsDir(distroName)

        pathLabel = findViewById(R.id.file_path)
        fileList = findViewById(R.id.file_list)

        val toolbar = findViewById<Toolbar>(R.id.file_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_back_chip)

        showHidden = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(KEY_SHOW_HIDDEN, false)

        rowAdapter = RowAdapter()
        fileList.adapter = rowAdapter

        // Up-navigating is the expected behaviour inside a file manager, both for
        // the toolbar arrow and for the system back gesture.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!goUp()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        val startPath = intent?.getStringExtra("path")
        currentDir = if (startPath != null && File(startPath).isDirectory) {
            File(startPath)
        } else {
            rootfsDir
        }
        loadDir(currentDir!!)
    }

    /** Goes one level up; returns false when already at the rootfs root. */
    private fun goUp(): Boolean {
        if (searching) {
            searching = false
            searchView = null
            currentDir?.let { loadDir(it) }
            return true
        }
        val dir = currentDir ?: return false
        val parent = dir.parentFile ?: return false
        if (parent.absolutePath.length < rootfsDir.absolutePath.length) return false
        loadDir(parent)
        return true
    }


    private fun onRowTapped(pos: Int) {
        val row = rows.getOrNull(pos) ?: return
        if (row.isParent) {
            goUp()
            return
        }
        val file = row.file ?: return
        if (file.isDirectory) loadDir(file) else openFile(file)
    }

    private fun loadDir(dir: File) {
        currentDir = dir
        hideSearchBar()
        searching = false

        val children = dir.listFiles()
        if (children == null) {
            rowAdapter.submit(emptyList())
            pathLabel.text = dir.absolutePath
            return
        }
        val visible = children
            .filter { showHidden || !it.name.startsWith(".") }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        val newRows = mutableListOf<Row>()
        if (dir.parentFile != null) newRows.add(Row(null, true))
        visible.forEach { newRows.add(Row(it, false)) }
        rowAdapter.submit(newRows)
        pathLabel.text = dir.absolutePath
    }


    private fun openFile(file: File) {
        startActivity(Intent(this, FileViewerActivity::class.java).apply {
            putExtra(FileViewerActivity.EXTRA_PATH, file.absolutePath)
        })
    }

    // ------------------------------------------------------------------ adapter
    private data class Row(val file: File?, val isParent: Boolean)

    private fun isImage(file: File): Boolean =
        file.extension.lowercase() in IMAGE_EXTENSIONS

    private fun isVideo(file: File): Boolean =
        file.extension.lowercase() in VIDEO_EXTENSIONS

    private fun kindOf(file: File): String = when {
        isImage(file) -> getString(R.string.kind_image)
        isVideo(file) -> getString(R.string.kind_video)
        else -> ""
    }

    private fun iconFor(file: File): String = when {
        isImage(file) -> "\uD83C\uDF9E"
        isVideo(file) -> "\uD83C\uDFAC"
        else -> "\uD83D\uDCC4"
    }

    /** Press feedback without giving the row a persistent background. */
    private fun makeRowRipple(): android.graphics.drawable.Drawable =
        android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x40FFFFFF),
            null,
            android.graphics.Color.WHITE.toDrawable()
        )

    private inner class RowAdapter : BaseAdapter() {
        private var items: List<Row> = emptyList()

        fun submit(newRows: List<Row>) {
            items = newRows
            rows = newRows
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun areAllItemsEnabled(): Boolean = true
        override fun isEnabled(position: Int): Boolean = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = if (convertView == null) {
                LayoutInflater.from(this@FileBrowserActivity)
                    .inflate(R.layout.file_row, parent, false)
                    .apply { background = makeRowRipple() }
            } else {
                convertView
            }
            val row = items[position]
            val icon = view.findViewById<TextView>(R.id.row_icon)
            val name = view.findViewById<TextView>(R.id.row_name)
            val detail = view.findViewById<TextView>(R.id.row_detail)
            val actions = view.findViewById<ImageButton>(R.id.row_actions)
            val clickTarget = view.findViewById<View>(R.id.row_click_target)
            val file = row.file

            // Rows handle their own taps. Relying on the ListView's own item
            // click dispatch is unreliable once the row contains a child button,
            // so the listener is bound here and rebound on every recycle.
            clickTarget.setOnClickListener { onRowTapped(position) }

            if (row.isParent || file == null) {
                icon.text = ""
                icon.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    R.drawable.ic_folder_up, 0, 0, 0
                )
                name.text = getString(R.string.parent_folder)
                detail.text = ""
                actions.visibility = View.GONE
                actions.setOnClickListener(null)
                return view
            }

            icon.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
            icon.text = if (file.isDirectory) "\uD83D\uDCC1" else iconFor(file)
            name.text = file.name
            detail.text = if (file.isDirectory) {
                getString(R.string.folder_detail)
            } else {
                getString(R.string.file_detail, formatSize(file.length()), kindOf(file))
            }
            actions.visibility = View.VISIBLE
            actions.setOnClickListener { showItemMenu(file) }
            return view
        }
    }

    // -------------------------------------------------------------------- search
    private var searchView: EditText? = null
    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val searchTrigger = Runnable {
        val edit = searchView ?: return@Runnable
        showSearch(edit.text.toString())
    }

    private fun showSearch(query: String) {
        val startDir = currentDir ?: return
        if (query.isBlank()) {
            searching = false
            loadDir(startDir)
            return
        }
        // A rootfs can hold hundreds of thousands of files, so walk it in the
        // background and cap the result set.
        searching = true
        pathLabel.text = getString(R.string.searching, query)
        rowAdapter.submit(emptyList())
        Thread({
            val found = ArrayList<File>()
            var truncated = false
            val stack = ArrayDeque<File>()
            stack.addLast(startDir)
            while (stack.isNotEmpty()) {
                val dir = stack.removeLast()
                val children = try {
                    dir.listFiles()
                } catch (_: Exception) {
                    null
                } ?: continue
                for (child in children) {
                    if (child.name.contains(query, ignoreCase = true)) {
                        if (found.size >= MAX_SEARCH_RESULTS) {
                            truncated = true
                            break
                        }
                        found.add(child)
                    }
                    if (child.isDirectory) stack.addLast(child)
                }
                if (truncated) break
            }
            found.sortBy { it.name.lowercase() }
            val results = found
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (results.isEmpty()) {
                    pathLabel.text = getString(R.string.no_matches_for_query, query)
                    rowAdapter.submit(emptyList())
                } else {
                    pathLabel.text = if (truncated) {
                        resources.getQuantityString(
                            R.plurals.search_results_capped, results.size, results.size, query
                        )
                    } else {
                        resources.getQuantityString(
                            R.plurals.match_count_for_query, results.size, results.size, query
                        )
                    }
                    rowAdapter.submit(results.map { Row(it, false) })
                }
            }
        }, "redterm-file-search").start()
    }

    private fun showSearchBar() {
        if (searchView != null) return
        val container = findViewById<FrameLayout>(R.id.file_search_container)
        val edit = EditText(this).apply {
            hint = getString(R.string.search_hint, currentDir?.name ?: "/")
            setSingleLine(true)
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(0x99FFFFFF.toInt())
            setBackgroundColor(0xFF2A2A3E.toInt())
            setPadding(dp(12), dp(8), dp(8), dp(8))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    searchHandler.removeCallbacks(searchTrigger)
                    searchHandler.postDelayed(searchTrigger, SEARCH_DEBOUNCE_MS)
                }
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                    searchHandler.removeCallbacks(searchTrigger)
                    showSearch(text.toString())
                    true
                } else {
                    false
                }
            }
        }
        container.addView(edit, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = android.view.Gravity.CENTER })
        container.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
        )
        searchView = edit
        edit.requestFocus()
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(edit, 0)
    }

    private fun hideSearchBar() {
        val edit = searchView ?: return
        searchHandler.removeCallbacks(searchTrigger)
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(edit.windowToken, 0)
        (edit.parent as? ViewGroup)?.removeView(edit)
        searchView = null
        findViewById<FrameLayout>(R.id.file_search_container)
            .layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0
            )
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.file_browser_menu, menu)
        return true
    }

    // ---------------------------------------------------------------- clipboard
    private var clipboard: ClipEntry? = null

    private data class ClipEntry(val file: File, val move: Boolean)

    private fun copyToClipboard(file: File, move: Boolean) {
        clipboard = ClipEntry(file, move)
        val verb = getString(if (move) R.string.cut_file else R.string.copy_file)
        Toast.makeText(this, getString(R.string.copied_to_clipboard, verb, file.name), Toast.LENGTH_SHORT).show()
        invalidateOptionsMenu()
    }

    override fun onPrepareOptionsMenu(menu: android.view.Menu): Boolean {
        menu.findItem(R.id.action_paste)?.isVisible = clipboard != null
        menu.findItem(R.id.action_toggle_hidden)?.setTitle(
            if (showHidden) R.string.hide_hidden_files else R.string.show_hidden_files
        )
        return super.onPrepareOptionsMenu(menu)
    }

    private fun pasteHere(move: Boolean) {
        val entry = clipboard ?: return
        val destDir = currentDir ?: return
        val dest = File(destDir, entry.file.name)
        Thread {
            val message = try {
                if (entry.file.absolutePath.startsWith(destDir.absolutePath + File.separator)) {
                    getString(R.string.paste_into_itself)
                } else {
                    if (dest.exists()) throw IOException(getString(R.string.already_exists, dest.name))
                    if (move) {
                        if (!entry.file.renameTo(dest)) {
                            copyEntry(entry.file, dest)
                            entry.file.deleteRecursively()
                        }
                        clipboard = null
                        getString(R.string.moved_item, entry.file.name)
                    } else {
                        copyEntry(entry.file, dest)
                        getString(R.string.copied_item, entry.file.name)
                    }
                }
            } catch (e: Exception) {
                e.message ?: getString(R.string.operation_failed)
            }
            runOnUiThread {
                loadDir(destDir)
                invalidateOptionsMenu()
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun copyEntry(src: File, dest: File) {
        src.copyRecursively(dest, overwrite = false)
    }

    // --------------------------------------------------------------- operations
    private fun promptForName(title: String, initial: String, onName: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSelection(text.length)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                when {
                    name.isEmpty() ->
                        Toast.makeText(this, R.string.name_required, Toast.LENGTH_SHORT).show()
                    name.contains('/') ->
                        Toast.makeText(this, R.string.name_invalid, Toast.LENGTH_SHORT).show()
                    else -> onName(name)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun newFolder() {
        val dir = currentDir ?: return
        promptForName(getString(R.string.new_folder), getString(R.string.default_folder_name)) { name ->
            val target = File(dir, name)
            if (target.exists()) {
                Toast.makeText(
                    this, getString(R.string.already_exists, name), Toast.LENGTH_SHORT
                ).show()
                return@promptForName
            }
            Thread {
                val ok = target.mkdirs()
                runOnUiThread {
                    if (ok) loadDir(dir)
                    else Toast.makeText(this, R.string.mkdir_failed, Toast.LENGTH_LONG).show()
                }
            }.start()
        }
    }

    private fun renameItem(file: File) {
        promptForName(getString(R.string.rename), file.name) { name ->
            val dir = file.parentFile ?: return@promptForName
            val target = File(dir, name)
            if (target.exists()) {
                Toast.makeText(
                    this, getString(R.string.already_exists, name), Toast.LENGTH_SHORT
                ).show()
                return@promptForName
            }
            Thread {
                val ok = file.renameTo(target)
                runOnUiThread {
                    if (ok) loadDir(dir)
                    else Toast.makeText(this, R.string.rename_failed, Toast.LENGTH_LONG).show()
                }
            }.start()
        }
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_item, file.name))
            .setMessage(
                if (file.isDirectory) getString(R.string.delete_folder_warning)
                else getString(R.string.delete_file_warning)
            )
            .setPositiveButton(R.string.delete) { _, _ ->
                val dir = file.parentFile
                Thread {
                    val ok = file.deleteRecursively()
                    runOnUiThread {
                        if (ok && dir != null) loadDir(dir)
                        else Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openInTerminal(file: File) {
        val inner = file.absolutePath
            .removePrefix(rootfsDir.absolutePath)
            .ifEmpty { "/" }
        TerminalActivity.launch(this, distroName, inner)
    }

    private fun isProbablyText(file: File): Boolean = try {
        file.inputStream().use { input ->
            val head = ByteArray(4096)
            var filled = 0
            while (filled < head.size) {
                val read = input.read(head, filled, head.size - filled)
                if (read == -1) break
                filled += read
            }
            head.copyOf(filled).none { it == 0.toByte() }
        }
    } catch (_: Exception) {
        false
    }

    private fun showItemMenu(file: File) {
        val isTextFile = !file.isDirectory && file.canWrite() && isProbablyText(file)
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (file.isDirectory) {
            labels.add(getString(R.string.open_in_terminal))
            actions.add { openInTerminal(file) }
        }
        labels.add(getString(R.string.rename))
        actions.add { renameItem(file) }
        if (isTextFile) {
            labels.add(getString(R.string.action_edit))
            actions.add {
                startActivity(
                    Intent(this, TextEditorActivity::class.java)
                        .putExtra(TextEditorActivity.EXTRA_PATH, file.absolutePath)
                )
            }
        }
        if (file.isDirectory) {
            labels.add(getString(R.string.folder_info))
            actions.add { showFolderInfo(file) }
        }
        labels.add(getString(R.string.copy_file))
        actions.add { copyToClipboard(file, move = false) }
        labels.add(getString(R.string.cut_file))
        actions.add { copyToClipboard(file, move = true) }
        labels.add(getString(R.string.delete))
        actions.add { confirmDelete(file) }

        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun showFolderInfo(dir: File) {
        AlertDialog.Builder(this)
            .setTitle(dir.name)
            .setMessage(getString(R.string.folder_info_loading))
            .setPositiveButton(android.R.string.ok, null)
            .show()
        Thread({
            var files = 0
            var folders = 0
            var bytes = 0L
            val stack = ArrayDeque<File>()
            stack.addLast(dir)
            while (stack.isNotEmpty()) {
                val current = stack.removeLast()
                val children = current.listFiles() ?: continue
                for (child in children) {
                    if (child.isDirectory) {
                        folders++
                        stack.addLast(child)
                    } else {
                        files++
                        bytes += child.length()
                    }
                }
            }
            val message = resources.getQuantityString(
                R.plurals.folder_info_line, files, files, folders, formatSize(bytes)
            )
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                AlertDialog.Builder(this)
                    .setTitle(dir.name)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }, "redterm-folder-info").start()
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                if (!goUp()) finish()
                return true
            }
            R.id.action_open_terminal_here -> {
                currentDir?.let { openInTerminal(it) }
                return true
            }
            R.id.action_search -> { showSearchBar(); return true }
            R.id.action_new_folder -> { newFolder(); return true }
            R.id.action_paste -> { pasteHere(move = false); return true }
            R.id.action_refresh -> { currentDir?.let { loadDir(it) }; return true }
            R.id.action_toggle_hidden -> {
                showHidden = !showHidden
                getSharedPreferences(PREFS, MODE_PRIVATE).edit {
                    putBoolean(KEY_SHOW_HIDDEN, showHidden)
                }
                invalidateOptionsMenu()
                currentDir?.let { loadDir(it) }
                return true
            }
            R.id.action_go_home -> {
                val home = File(rootfsDir, "root")
                loadDir(if (home.isDirectory) home else rootfsDir)
                return true
            }
            R.id.action_go_root -> { loadDir(rootfsDir); return true }
            R.id.action_folder_info -> { currentDir?.let { showFolderInfo(it) }; return true }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1000 -> "$bytes B"
        bytes < 1_000_000 -> "${bytes / 1000} KB"
        bytes < 1_000_000_000 -> "${"%.1f".format(bytes / 1_000_000.0)} MB"
        else -> "${"%.2f".format(bytes / 1_000_000_000.0)} GB"
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
