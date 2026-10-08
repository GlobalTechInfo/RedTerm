package com.redtermapp.ui.filelist

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.edit
import androidx.core.graphics.drawable.toDrawable
import com.redtermapp.R
import com.redtermapp.ui.FileViewerActivity
import com.redtermapp.ui.NightModeReceiver
import com.redtermapp.ui.TextEditorActivity
import java.io.File

/**
 * The file browser, with everything that does not depend on where the files are.
 *
 * A distribution's rootfs and an SSH server are browsed the same way — list,
 * navigate, search, rename, delete, upload, download — so the UI lives here once
 * and each subclass only supplies a [FileSource]. The alternative, a second
 * activity for remote files, would have been a near-copy of several hundred lines
 * that would then need every future fix applied twice.
 *
 * [FileSource] calls block, so all of them happen on a worker thread and the
 * result is applied back on the main one.
 */
abstract class BaseFileBrowserActivity : AppCompatActivity() {

    protected abstract fun source(): FileSource

    /** Whether "Open in terminal" makes sense here. It does not for a server. */
    protected open val supportsOpenInTerminal: Boolean = false

    /** Extra entries added to the per-row menu, if any. */
    protected open fun rowMenuExtras(entry: FileEntry): List<Pair<String, () -> Unit>> = emptyList()

    /** Title-bar subtitle naming what is being browsed. */
    protected open fun titleSuffix(): String = ""

    protected lateinit var pathLabel: TextView
        private set
    protected lateinit var fileList: ListView
        private set
    protected lateinit var rowAdapter: RowAdapter
        private set

    protected var currentPath: String = ""
        private set
    protected var rows: List<Row> = emptyList()
        private set

    /** The entries currently listed, used for existence checks. */
    private var listed: List<FileEntry> = emptyList()

    private var showHidden = false
    private var searching = false
    private var clipboard: ClipEntry? = null

    /** A row, or the "go up" row which has no entry behind it. */
    protected data class Row(val entry: FileEntry?, val isParent: Boolean)

    private data class ClipEntry(val entry: FileEntry, val move: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_browser)

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

        // Going up is the expected behaviour inside a file manager, both for the
        // toolbar arrow and for the system back gesture.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!goUp()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        val requested = initialPath()
        if (requested != null && isDirectory(requested)) loadDir(requested) else loadHome()
    }

    /** The path the caller asked for, if any. */
    protected open fun initialPath(): String? = null

    /**
     * Opens the source's home directory.
     *
     * Resolved on the worker, never here. For a remote source the home directory
     * is whatever the server says it is, which costs a connection — asking for it
     * on the main thread is a NetworkOnMainThreadException and an immediate crash.
     */
    protected fun loadHome() {
        hideSearchBar()
        searching = false
        background {
            val home = source().home
            main { loadDir(home) }
        }
    }

    // --------------------------------------------------------------- navigation

    /** Goes one level up; false when already at the source's root. */
    protected fun goUp(): Boolean {
        if (searching) {
            searching = false
            hideSearchBar()
            loadDir(currentPath)
            return true
        }
        val parent = FileEntry.parentOf(currentPath) ?: return false
        // Refused rather than clamped, so the root is a hard boundary: a browser
        // that silently stays put looks broken, and one that silently escapes is
        // worse.
        if (!isAtOrBelowRoot(parent)) return false
        loadDir(parent)
        return true
    }

    private fun isAtOrBelowRoot(path: String): Boolean {
        val root = source().root.trimEnd('/')
        return path == root || path.startsWith("$root/")
    }

    /**
     * Whether [path] is a directory the browser may open.
     *
     * Must not do I/O: it is called while the activity is being created. Only the
     * constant root can be compared, which is enough — the requested path has
     * already been opened by someone who knows it exists.
     */
    protected open fun isDirectory(path: String): Boolean = path == source().root

    protected fun loadDir(path: String) {
        currentPath = path
        hideSearchBar()
        searching = false
        supportActionBar?.title = buildString {
            append(source().label)
            val suffix = titleSuffix()
            if (suffix.isNotEmpty()) append(" · ").append(suffix)
        }

        background {
            val entries = source().list(path)
            val visible = entries
                ?.filter { showHidden || !it.isHidden }
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            main {
                if (entries == null) {
                    reportError(source().lastError)
                    // Keep the previous listing: replacing it with nothing would
                    // look like the folder emptied rather than the read failing.
                    return@main
                }
                val newRows = mutableListOf<Row>()
                if (FileEntry.parentOf(path) != null && isAtOrBelowRoot(FileEntry.parentOf(path)!!)) {
                    newRows.add(Row(null, true))
                }
                visible?.forEach { newRows.add(Row(it, false)) }
                listed = visible.orEmpty()
                rowAdapter.submit(newRows)
                pathLabel.text = path
            }
        }
    }

    private fun onRowTapped(position: Int) {
        val row = rows.getOrNull(position) ?: return
        if (row.isParent) {
            goUp()
            return
        }
        val entry = row.entry ?: return
        if (entry.isDirectory) {
            loadDir(entry.path)
        } else {
            openFile(entry)
        }
    }

    /**
     * Opens a file in the local viewer.
     *
     * For a remote file this means fetching it first, which can take a while for
     * anything large, so the wait is shown rather than left as a frozen screen.
     */
    protected open fun openFile(entry: FileEntry) {
        val src = source()
        if (src.canOpenLocally) {
            startActivity(Intent(this, FileViewerActivity::class.java).apply {
                putExtra(FileViewerActivity.EXTRA_PATH, entry.path)
            })
            return
        }
        val local = src.cacheCopy(entry) ?: run {
            reportError(src.lastError)
            return
        }
        startActivity(Intent(this, FileViewerActivity::class.java).apply {
            putExtra(FileViewerActivity.EXTRA_PATH, local.absolutePath)
        })
    }

    protected open fun openInTerminal(path: String) = Unit

    // ------------------------------------------------------------------ adapter

    protected inner class RowAdapter : BaseAdapter() {
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
                LayoutInflater.from(this@BaseFileBrowserActivity)
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

            // Rows handle their own taps. Relying on the ListView's own item
            // click dispatch is unreliable once the row contains a child button,
            // so the listener is bound here and rebound on every recycle.
            clickTarget.setOnClickListener { onRowTapped(position) }

            if (row.isParent || row.entry == null) {
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

            val entry = row.entry
            icon.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
            icon.text = if (entry.isDirectory) "\uD83D\uDCC1" else iconFor(entry)
            name.text = entry.name
            detail.text = if (entry.isDirectory) {
                getString(R.string.folder_detail)
            } else {
                getString(R.string.file_detail, formatSize(entry.size), kindOf(entry))
            }
            actions.visibility = View.VISIBLE
            actions.setOnClickListener { showItemMenu(entry) }
            return view
        }
    }

    /** Press feedback without giving the row a persistent background. */
    private fun makeRowRipple(): android.graphics.drawable.Drawable =
        android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x40FFFFFF),
            null,
            android.graphics.Color.WHITE.toDrawable()
        )

    private fun isImage(entry: FileEntry): Boolean = extensionOf(entry) in IMAGE_EXTENSIONS

    private fun isVideo(entry: FileEntry): Boolean = extensionOf(entry) in VIDEO_EXTENSIONS

    private fun extensionOf(entry: FileEntry): String =
        entry.name.substringAfterLast('.', "").lowercase()

    private fun kindOf(entry: FileEntry): String = when {
        isImage(entry) -> getString(R.string.kind_image)
        isVideo(entry) -> getString(R.string.kind_video)
        else -> ""
    }

    private fun iconFor(entry: FileEntry): String = when {
        isImage(entry) -> "\uD83C\uDF9E"
        isVideo(entry) -> "\uD83C\uDFAC"
        else -> "\uD83D\uDCC4"
    }

    // -------------------------------------------------------------------- search

    private var searchView: EditText? = null
    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val searchTrigger = Runnable {
        val edit = searchView ?: return@Runnable
        showSearch(edit.text.toString())
    }

    private fun showSearch(query: String) {
        if (query.isBlank()) {
            searching = false
            loadDir(currentPath)
            return
        }
        searching = true
        pathLabel.text = getString(R.string.searching, query)
        rowAdapter.submit(emptyList())
        val start = currentPath
        background {
            val (found, truncated) = source().search(start, query, MAX_SEARCH_RESULTS)
            main {
                if (found.isEmpty()) {
                    pathLabel.text = getString(R.string.no_matches_for_query, query)
                    rowAdapter.submit(emptyList())
                } else {
                    pathLabel.text = if (truncated) {
                        resources.getQuantityString(
                            R.plurals.search_results_capped, found.size, found.size, query
                        )
                    } else {
                        resources.getQuantityString(
                            R.plurals.match_count_for_query, found.size, found.size, query
                        )
                    }
                    rowAdapter.submit(found.map { Row(it, false) })
                }
            }
        }
    }

    private fun showSearchBar() {
        if (searchView != null) return
        val container = findViewById<FrameLayout>(R.id.file_search_container)
        val edit = EditText(this).apply {
            hint = getString(R.string.search_hint, currentPath)
            setSingleLine(true)
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(0x99FFFFFF.toInt())
            setBackgroundColor(0xFF2A2A3E.toInt())
            setPadding(dp(12), dp(8), dp(8), dp(8))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    searchHandler.removeCallbacks(searchTrigger)
                    searchHandler.postDelayed(searchTrigger, SEARCH_DEBOUNCE_MS)
                }
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    searchHandler.removeCallbacks(searchTrigger)
                    showSearch(text.toString())
                    true
                } else {
                    false
                }
            }
        }
        container.addView(
            edit,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        )
        container.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
        )
        searchView = edit
        edit.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .showSoftInput(edit, 0)
    }

    private fun hideSearchBar() {
        val edit = searchView ?: return
        searchHandler.removeCallbacks(searchTrigger)
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(edit.windowToken, 0)
        (edit.parent as? ViewGroup)?.removeView(edit)
        searchView = null
        findViewById<FrameLayout>(R.id.file_search_container)
            .layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0
            )
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.file_browser_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: android.view.Menu): Boolean {
        menu.findItem(R.id.action_paste)?.isVisible = clipboard != null
        menu.findItem(R.id.action_toggle_hidden)?.setTitle(
            if (showHidden) R.string.hide_hidden_files else R.string.show_hidden_files
        )
        // A server has no terminal for the app to open the path in.
        menu.findItem(R.id.action_open_terminal_here)?.isVisible = supportsOpenInTerminal
        return super.onPrepareOptionsMenu(menu)
    }

    // ---------------------------------------------------------------- operations

    protected fun promptForName(title: String, initial: String, onName: (String) -> Unit) {
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

    protected fun newFolder() {
        val dir = currentPath
        promptForName(getString(R.string.new_folder), getString(R.string.default_folder_name)) { name ->
            val target = joinPath(dir, name)
            if (target == null) return@promptForName
            if (exists(target)) {
                Toast.makeText(
                    this, getString(R.string.already_exists, name), Toast.LENGTH_SHORT
                ).show()
                return@promptForName
            }
            background {
                val result = source().mkdir(target)
                main {
                    if (result.succeeded) loadDir(dir)
                    else reportError(result.message())
                }
            }
        }
    }

    protected fun renameItem(entry: FileEntry) {
        val dir = currentPath
        promptForName(getString(R.string.rename), entry.name) { name ->
            val target = joinPath(dir, name) ?: return@promptForName
            if (target == entry.path) return@promptForName
            if (exists(target)) {
                Toast.makeText(
                    this, getString(R.string.already_exists, name), Toast.LENGTH_SHORT
                ).show()
                return@promptForName
            }
            background {
                val result = source().rename(entry.path, target)
                main {
                    if (result.succeeded) loadDir(dir)
                    else reportError(result.message())
                }
            }
        }
    }

    protected fun confirmDelete(entry: FileEntry) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_item, entry.name))
            .setMessage(
                if (entry.isDirectory) getString(R.string.delete_folder_warning)
                else getString(R.string.delete_file_warning)
            )
            .setPositiveButton(R.string.delete) { _, _ ->
                val dir = currentPath
                background {
                    val result = source().delete(entry.path, recursive = entry.isDirectory)
                    main {
                        if (result.succeeded) loadDir(dir)
                        else reportError(result.message())
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    protected fun copyToClipboard(entry: FileEntry, move: Boolean) {
        clipboard = ClipEntry(entry, move)
        val verb = getString(if (move) R.string.cut_file else R.string.copy_file)
        Toast.makeText(
            this, getString(R.string.copied_to_clipboard, verb, entry.name), Toast.LENGTH_SHORT
        ).show()
        invalidateOptionsMenu()
    }

    protected fun pasteHere(move: Boolean) {
        val clip = clipboard ?: return
        val entry = clip.entry
        val destDir = currentPath
        val name = entry.name
        val target = joinPath(destDir, name) ?: return
        val doMove = move || clip.move
        if (target == entry.path) return
        // Copying a folder onto itself would recurse into its own output. The
        // local source handled this by comparing paths; the check has to be here
        // so it applies to a remote source too.
        if (entry.isDirectory && target.startsWith(entry.path + "/")) {
            Toast.makeText(this, R.string.paste_into_itself, Toast.LENGTH_SHORT).show()
            return
        }
        background {
            val result = source().copy(entry.path, target, move = doMove)
            main {
                if (doMove && result.succeeded) clipboard = null
                loadDir(destDir)
                invalidateOptionsMenu()
                if (result.succeeded) {
                    val message = if (doMove) {
                        getString(R.string.moved_item, name)
                    } else {
                        getString(R.string.copied_item, name)
                    }
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                } else {
                    reportError(result.message())
                }
            }
        }
    }

    /** Whether [path] is already taken, checked before a create or a rename. */
    private fun exists(path: String): Boolean =
        listed.any { it.path == path } || isDirectory(path)

    protected fun showFolderInfo(path: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage(getString(R.string.folder_info_loading))
            .setPositiveButton(android.R.string.ok, null)
            .show()
        background {
            val summary = source().summarise(path)
            val message = resources.getQuantityString(
                R.plurals.folder_info_line, summary.files, summary.files,
                summary.folders, formatSize(summary.bytes)
            )
            main {
                if (isFinishing || isDestroyed) return@main
                AlertDialog.Builder(this)
                    .setTitle(name)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    protected fun showItemMenu(entry: FileEntry) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (supportsOpenInTerminal && entry.isDirectory) {
            labels.add(getString(R.string.open_in_terminal))
            actions.add { openInTerminal(entry.path) }
        }
        labels.add(getString(R.string.rename))
        actions.add { renameItem(entry) }
        if (entry.isDirectory) {
            labels.add(getString(R.string.folder_info))
            actions.add { showFolderInfo(entry.path, entry.name) }
        }
        for ((label, action) in rowMenuExtras(entry)) {
            labels.add(label)
            actions.add(action)
        }
        if (source().canOpenLocally && !entry.isDirectory && isProbablyText(entry)) {
            labels.add(getString(R.string.action_edit))
            actions.add {
                startActivity(
                    Intent(this, TextEditorActivity::class.java)
                        .putExtra(TextEditorActivity.EXTRA_PATH, entry.path)
                )
            }
        }
        labels.add(getString(R.string.copy_file))
        actions.add { copyToClipboard(entry, move = false) }
        labels.add(getString(R.string.cut_file))
        actions.add { copyToClipboard(entry, move = true) }
        labels.add(getString(R.string.delete))
        actions.add { confirmDelete(entry) }

        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    /** Reads the first few kilobytes to decide whether this is editable text. */
    private fun isProbablyText(entry: FileEntry): Boolean = try {
        val head = ByteArray(4096)
        var filled = 0
        java.io.File(entry.path).inputStream().use { input ->
            while (filled < head.size) {
                val read = input.read(head, filled, head.size - filled)
                if (read == -1) break
                filled += read
            }
        }
        head.copyOf(filled).none { it == 0.toByte() }
    } catch (_: Exception) {
        false
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                if (!goUp()) finish()
                return true
            }
            R.id.action_open_terminal_here -> {
                openInTerminal(currentPath)
                return true
            }
            R.id.action_search -> { showSearchBar(); return true }
            R.id.action_new_folder -> { newFolder(); return true }
            R.id.action_paste -> { pasteHere(move = false); return true }
            R.id.action_refresh -> { loadDir(currentPath); return true }
            R.id.action_toggle_hidden -> {
                showHidden = !showHidden
                getSharedPreferences(PREFS, MODE_PRIVATE).edit {
                    putBoolean(KEY_SHOW_HIDDEN, showHidden)
                }
                invalidateOptionsMenu()
                loadDir(currentPath)
                return true
            }
            R.id.action_go_home -> { loadHome(); return true }
            R.id.action_go_root -> { loadDir(source().root); return true }
            R.id.action_folder_info -> {
                showFolderInfo(currentPath, currentPath)
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    // -------------------------------------------------------------------- helpers

    protected fun joinPath(dir: String, name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.contains('/')) return null
        return if (dir.endsWith("/")) "$dir$trimmed" else "$dir/$trimmed"
    }

    protected fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    protected fun formatSize(bytes: Long): String = when {
        bytes < 1000 -> "$bytes B"
        bytes < 1_000_000 -> "${bytes / 1000} KB"
        bytes < 1_000_000_000 -> "${"%.1f".format(bytes / 1_000_000.0)} MB"
        else -> "${"%.2f".format(bytes / 1_000_000_000.0)} GB"
    }

    /** Runs [work] off the main thread. */
    protected fun background(name: String = "redterm-file-io", work: () -> Unit) {
        Thread({
            try {
                work()
            } catch (e: Exception) {
                main { reportError(e.message ?: e.javaClass.simpleName) }
            }
        }, name).start()
    }

    /** Runs [work] on the main thread, unless the activity is already gone. */
    protected fun main(work: () -> Unit) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) work()
        }
    }

    protected fun reportError(reason: String) {
        if (reason.isBlank() || reason == NO_KEY) return
        AlertDialog.Builder(this)
            .setTitle(R.string.file_operation_failed)
            .setMessage(reason)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    protected fun OpResult.message(): String = when (this) {
        is OpResult.Ok -> getString(R.string.operation_failed)
        is OpResult.Failed -> reason
    }

    protected fun applyTheme() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
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

    protected companion object {
        val IMAGE_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "bmp", "webp", "heic", "heif", "avif"
        )
        val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "webm", "mkv", "3gp", "ogv", "mov")
        const val MAX_SEARCH_RESULTS = 2000
        const val SEARCH_DEBOUNCE_MS = 350L
        const val PREFS = "file_browser"
        const val KEY_SHOW_HIDDEN = "show_hidden"
        const val NO_KEY = "no-key"
    }
}