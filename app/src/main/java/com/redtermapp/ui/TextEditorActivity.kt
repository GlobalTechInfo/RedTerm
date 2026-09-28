package com.redtermapp.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.redtermapp.R
import java.io.File

/**
 * Minimal in-app editor for text files inside a distro rootfs.
 *
 * Saving stages a temporary file and renames it over the original, so an
 * interrupted write can never truncate a config the user cares about. The
 * original permission bits are re-applied to the staged file before the rename,
 * because rename replaces the inode rather than its metadata.
 */
class TextEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"
        private const val MAX_EDIT_BYTES = 4L * 1024L * 1024L
        private const val TEMP_SUFFIX = ".redterm-edit.tmp"
    }

    private var target: File? = null
    private var originalText: String? = null
    private lateinit var editor: android.widget.EditText
    private lateinit var status: TextView
    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_editor)

        val toolbar = findViewById<Toolbar>(R.id.editor_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_back_chip)

        editor = findViewById(R.id.editor_content)
        status = findViewById(R.id.editor_status)

        val path = intent?.getStringExtra(EXTRA_PATH)
        val file = path?.let { File(it) }
        if (file == null || !file.isFile) {
            Toast.makeText(this, R.string.open_file_failed, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (!file.canWrite()) {
            Toast.makeText(this, R.string.editor_read_only, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (file.length() > MAX_EDIT_BYTES) {
            Toast.makeText(this, R.string.editor_too_large, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        target = file
        supportActionBar?.title = file.name
        findViewById<TextView>(R.id.editor_path).text = file.absolutePath
        load(file)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (hasChanges()) confirmDiscard() else finish()
            }
        })
    }

    private fun load(file: File) {
        status.text = getString(R.string.editor_loading)
        Thread({
            val text = try {
                String(file.readBytes(), Charsets.UTF_8)
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = getString(R.string.editor_load_failed, e.message ?: "")
                }
                return@Thread
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                originalText = text
                editor.setText(text)
                editor.setSelection(0)
                status.text = resources.getQuantityString(R.plurals.editor_size, text.length, text.length)
                invalidateOptionsMenu()
            }
        }, "redterm-editor-load").start()
    }

    private fun hasChanges(): Boolean {
        val original = originalText ?: return false
        return editor.text.toString() != original
    }

    private fun confirmDiscard() {
        AlertDialog.Builder(this)
            .setTitle(R.string.editor_discard_title)
            .setMessage(R.string.editor_discard_message)
            .setPositiveButton(R.string.discard) { _, _ -> finish() }
            .setNegativeButton(R.string.keep_editing, null)
            .show()
    }

    private fun save() {
        val file = target ?: return
        if (saving) return
        if (!hasChanges()) {
            Toast.makeText(this, R.string.editor_no_changes, Toast.LENGTH_SHORT).show()
            return
        }
        saving = true
        status.text = getString(R.string.editor_saving)
        val text = editor.text.toString()
        Thread({
            val result = try {
                writeAtomically(file, text)
                null
            } catch (e: Exception) {
                e.message ?: getString(R.string.editor_save_failed)
            }
            runOnUiThread {
                saving = false
                if (result == null) {
                    originalText = text
                    status.text = resources.getQuantityString(R.plurals.editor_saved, file.length().toInt(), file.length())
                    invalidateOptionsMenu()
                    Toast.makeText(this, R.string.editor_saved_short, Toast.LENGTH_SHORT).show()
                } else {
                    status.text = getString(R.string.editor_save_failed, result)
                    Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                }
            }
        }, "redterm-editor-save").start()
    }

    private fun writeAtomically(file: File, text: String) {
        val temp = File(file.parentFile, file.name + TEMP_SUFFIX)
        try {
            temp.writeText(text, Charsets.UTF_8)
            // Re-apply the original mode: rename replaces the inode, so the
            // staged file would otherwise land as a private 0600 file and, more
            // importantly, would drop any executable bit.
            val mode = try {
                android.system.Os.stat(file.absolutePath).st_mode
            } catch (_: Exception) {
                null
            }
            if (mode != null) {
                try {
                    android.system.Os.chmod(temp.absolutePath, mode and 0xFFF)
                } catch (_: Exception) {
                }
            }
            if (!temp.renameTo(file)) {
                throw java.io.IOException(getString(R.string.editor_replace_failed))
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, 1, 1, R.string.action_save)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(Menu.NONE, 2, 2, R.string.action_revert)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(2)?.isEnabled = hasChanges() && !saving
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                if (hasChanges()) confirmDiscard() else finish()
                return true
            }
            1 -> { save(); return true }
            2 -> {
                val original = originalText
                if (original != null) {
                    editor.setText(original)
                    status.text = getString(R.string.editor_reverted)
                }
                return true
            }
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
