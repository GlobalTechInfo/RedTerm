package com.redtermapp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.redtermapp.R

/**
 * The snippet library: search, recents, and per-item send/copy/edit/delete.
 *
 * "Send to terminal" hands the text to [TerminalActivity] through
 * [TerminalActivity.EXTRA_TYPE_TEXT] and marks the snippet used, so the next
 * time it sorts to the top of the list. A copy is also offered, because the
 * terminal that is focused may not be the one on screen.
 */
class SnippetLibraryActivity : AppCompatActivity() {

    private lateinit var search: EditText
    private lateinit var list: LinearLayout
    private var shown: List<SnippetStore.Snippet> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        body.addView(TextView(this).apply {
            setText(R.string.snippets)
            textSize = 20f
        })
        search = EditText(this).apply {
            hint = getString(R.string.snippet_search)
            setSingleLine(true)
            setTextColor(context.cardTextColor())
            setHintTextColor(context.mutedTextColor())
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                filter(search.text.toString())
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
        body.addView(search)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list)
        body.addView(ScreenWidgets.primaryButton(this, R.string.snippet_new) { edit(null) })

        val scroll = ScrollView(this).apply { addView(body) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        ScreenToolbar.install(this, root, getString(R.string.snippets))
        setContentView(root)
        filter("")
    }

    private fun filter(query: String) {
        shown = SnippetStore.byRecency(SnippetStore.search(SnippetStore.list(this), query))
        rebuild()
    }

    private fun rebuild() {
        list.removeAllViews()
        if (shown.isEmpty()) {
            list.addView(TextView(this).apply {
                setText(R.string.snippet_none)
                setTextColor(mutedTextColor())
            })
            return
        }
        for (s in shown) list.addView(row(s))
    }

    private fun row(s: SnippetStore.Snippet): View {
        val pad = (10 * resources.displayMetrics.density).toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(cardColor())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val b = (8 * resources.displayMetrics.density).toInt()
                setMargins(0, 0, 0, b)
            }
        }
        val head = ScreenWidgets.headerRow(this).apply {
            addView(TextView(this@SnippetLibraryActivity).apply {
                text = s.name
                textSize = 15f
                setTextColor(cardTextColor())
            })
            if (s.folder.isNotEmpty()) {
                addView(ScreenWidgets.chip(this@SnippetLibraryActivity, s.folder, accentColor()))
            }
        }
        card.addView(head)
        card.addView(TextView(this).apply {
            text = s.content.take(160)
            textSize = 12f
            setTextColor(mutedTextColor())
            maxLines = 3
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(ScreenWidgets.actionButton(this, R.string.snippet_send) { send(s) })
        row.addView(ScreenWidgets.actionButton(this, R.string.snippet_copy) { copy(s) })
        row.addView(ScreenWidgets.actionButton(this, R.string.snippet_edit) { edit(s) })
        row.addView(ScreenWidgets.dangerButton(this, R.string.snippet_delete) { delete(s) })
        card.addView(row)
        return card
    }

    private fun send(s: SnippetStore.Snippet) {
        SnippetStore.touch(this, s.id)
        startActivity(
            Intent(this, TerminalActivity::class.java)
                .putExtra(TerminalActivity.EXTRA_TYPE_TEXT, s.content)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        filter(search.text.toString())
    }

    private fun copy(s: SnippetStore.Snippet) {
        runCatching {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText(s.name, s.content))
        }.onSuccess {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.snippets)
                .setMessage(R.string.snippet_copied)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun edit(s: SnippetStore.Snippet?) {
        val name = EditText(this).apply { hint = getString(R.string.snippet_name); setText(s?.name ?: ""); setTextColor(context.cardTextColor()); setHintTextColor(context.mutedTextColor()) }
        val content = EditText(this).apply { hint = getString(R.string.snippet_content); setText(s?.content ?: ""); setTextColor(context.cardTextColor()); setHintTextColor(context.mutedTextColor()) }
        val folder = EditText(this).apply { hint = getString(R.string.snippet_folder); setText(s?.folder ?: ""); setTextColor(context.cardTextColor()); setHintTextColor(context.mutedTextColor()) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, p / 2)
            addView(name); addView(content); addView(folder)
        }
        AlertDialog.Builder(this)
            .setTitle(if (s == null) getString(R.string.snippet_new) else s.name)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val nm = name.text.toString().trim()
                if (nm.isEmpty()) return@setPositiveButton
                val id = s?.id ?: SnippetStore.newSnippet(nm, "").id
                SnippetStore.upsert(
                    this,
                    SnippetStore.Snippet(id, nm, content.text.toString(), folder.text.toString().trim())
                )
                filter(this.search.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun delete(s: SnippetStore.Snippet) {
        AlertDialog.Builder(this)
            .setTitle(R.string.snippets)
            .setMessage("Delete \"$s.name\"?")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                SnippetStore.delete(this, s.id)
                filter(search.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
