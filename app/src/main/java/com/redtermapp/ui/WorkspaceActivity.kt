package com.redtermapp.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.redtermapp.R
import com.redtermapp.distro.DistroInstaller

/**
 * Workspaces: a named bundle of the distro shells, SSH servers, theme and
 * startup-hook a user reaches for together, opened in one tap.
 *
 * The screen is built in code like the other tool screens. A workspace's
 * sessions open through [TerminalActivity] with a single intent extra, which
 * is the only path that can start a proot session from nothing.
 */
class WorkspaceActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var editor: LinearLayout
    private lateinit var nameField: EditText
    private lateinit var distroGroup: LinearLayout
    private lateinit var serverGroup: LinearLayout
    private lateinit var themeSpinner: Spinner
    private lateinit var hookCheck: CheckBox
    private lateinit var splitCheck: CheckBox
    private lateinit var pinCheck: CheckBox

    private val installer by lazy { DistroInstaller(applicationContext) }

    private var workspaces: List<WorkspaceStore.Workspace> = emptyList()
    private var editing: WorkspaceStore.Workspace? = null
    private var editingId: String? = null
    private val distroChecks = mutableListOf<CheckBox>()
    private val serverChecks = mutableListOf<CheckBox>()

    /** The theme keys [DiskUsageActivity] cycles; "" is the app default. */
    private val themes by lazy {
        listOf(
            "" to getString(R.string.workspace_theme_default),
            "red" to "Red",
            "amoled" to "AMOLED",
            "green" to "Green",
            "light" to "Light",
            "dracula" to "Dracula",
            "nord" to "Nord",
            "tokyo" to "Tokyo Night",
            "gruvbox" to "Gruvbox"
        )
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
            setText(R.string.workspaces)
            textSize = 20f
        })
        body.addView(ScreenWidgets.primaryButton(this, R.string.workspace_new) {
            beginEdit(null)
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list)
        editor = buildEditor()
        editor.visibility = View.GONE
        body.addView(editor)

        scroll = ScrollView(this).apply { addView(body) }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        ScreenToolbar.install(this, root, getString(R.string.workspaces))
        setContentView(root)
        refresh()
    }

    private fun buildEditor(): LinearLayout {
        val pad = (8 * resources.displayMetrics.density).toInt()
        val e = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(cardColor())
        }
        nameField = EditText(this).apply {
            hint = getString(R.string.workspace_name_hint)
            setSingleLine(true)
            setTextColor(context.cardTextColor())
            setHintTextColor(context.mutedTextColor())
        }
        e.addView(nameField)
        e.addView(sectionLabel(getString(R.string.workspace_distros)))
        distroGroup = group()
        e.addView(distroGroup)
        e.addView(sectionLabel(getString(R.string.workspace_servers)))
        serverGroup = group()
        e.addView(serverGroup)
        e.addView(sectionLabel(getString(R.string.workspace_theme)))
        themeSpinner = Spinner(this)
        e.addView(themeSpinner)
        hookCheck = CheckBox(this).apply { setText(R.string.workspace_hook) }
        e.addView(hookCheck)
        splitCheck = CheckBox(this).apply {
            setText(R.string.workspace_split)
            setTextColor(cardTextColor())
        }
        e.addView(splitCheck)
        pinCheck = CheckBox(this).apply {
            setText(R.string.workspace_pin)
            setTextColor(cardTextColor())
        }
        e.addView(pinCheck)
        e.addView(ScreenWidgets.primaryButton(this, R.string.workspace_save) { save() })
        return e
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
    }

    private fun group() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((8 * resources.displayMetrics.density).toInt(), 0, 0, 0)
    }

    private fun refresh() {
        workspaces = WorkspaceStore.list(this)
        list.removeAllViews()
        if (workspaces.isEmpty()) {
            list.addView(TextView(this).apply {
                setText(R.string.workspace_empty)
                textSize = 14f
            })
        }
        for (w in workspaces) list.addView(card(w))
        buildGroups()
    }

    private fun card(w: WorkspaceStore.Workspace): View {
        val pad = (10 * resources.displayMetrics.density).toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(cardColor())
            val m = (8 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, m) }
        }
        val top = ScreenWidgets.headerRow(this).apply {
            addView(TextView(this@WorkspaceActivity).apply {
                text = w.name
                textSize = 16f
                setTextColor(cardTextColor())
            })
            addView(TextView(this@WorkspaceActivity).apply {
                text = sessionsLabel(w.sessionCount())
                textSize = 12f
                setTextColor(mutedTextColor())
                val mr = (16 * resources.displayMetrics.density).toInt()
                setPadding(0, 0, mr, 0)
            })
        }
        card.addView(top)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(ScreenWidgets.actionButton(this, R.string.workspace_launch) { launch(w) })
        row.addView(ScreenWidgets.actionButton(this, R.string.workspace_edit) { beginEdit(w) })
        row.addView(ScreenWidgets.dangerButton(this, R.string.workspace_delete) {
            WorkspaceStore.delete(this@WorkspaceActivity, w.id)
            refresh()
        })
        card.addView(row)
        return card
    }

    private fun sessionsLabel(n: Int): String =
        resources.getQuantityString(R.plurals.workspace_sessions, n, n)

    private fun buildGroups() {
        val installed = runCatching { installer.getInstalledDistros() }.getOrDefault(emptyList())
        val servers = SshStore.load(this)
        distroGroup.removeAllViews()
        distroChecks.clear()
        if (installed.isEmpty()) {
            distroGroup.addView(dim(getString(R.string.workspace_none)))
        } else {
            for (d in installed) {
                distroChecks.add(CheckBox(this).apply {
                    text = d
                    setTextColor(cardTextColor())
                    setOnCheckedChangeListener { _, _ -> }
                })
                distroGroup.addView(distroChecks.last())
            }
        }
        serverGroup.removeAllViews()
        serverChecks.clear()
        if (servers.isEmpty()) {
            serverGroup.addView(dim(getString(R.string.workspace_no_servers)))
        } else {
            for (s in servers) {
                serverChecks.add(CheckBox(this).apply {
                    text = s.label
                    setTextColor(cardTextColor())
                    setOnCheckedChangeListener { _, _ -> }
                })
                serverGroup.addView(serverChecks.last())
            }
        }
        themeSpinner.adapter = ScreenWidgets.spinner(this, themes.map { it.second }).adapter
        themeSpinner.setSelection(0)
    }

    private fun dim(s: String) = TextView(this).apply {
        text = s
        setTextColor(mutedTextColor())
    }

    private fun beginEdit(w: WorkspaceStore.Workspace?) {
        buildGroups()
        if (w == null) {
            editing = null
            editingId = null
            nameField.setText("")
            distroChecks.forEach { it.isChecked = false }
            serverChecks.forEach { it.isChecked = false }
            themeSpinner.setSelection(0)
            hookCheck.isChecked = false
            splitCheck.isChecked = false
            pinCheck.isChecked = false
        } else {
            editing = w
            editingId = w.id
            nameField.setText(w.name)
            distroChecks.forEach { it.isChecked = it.text.toString() in w.cleanDistros }
            serverChecks.forEach {
                it.isChecked = serversId(it) in w.serverIds
            }
            val idx = themes.indexOfFirst { it.first == w.theme }.coerceAtLeast(0)
            themeSpinner.setSelection(idx)
            hookCheck.isChecked = w.enableStartupHook
            splitCheck.isChecked = w.split
            pinCheck.isChecked = w.pinned
        }
        editor.visibility = View.VISIBLE
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun serversId(c: CheckBox): String {
        val label = c.text.toString()
        return SshStore.load(this).firstOrNull { it.label == label }?.id ?: ""
    }

    private fun save() {
        val name = nameField.text.toString().trim().ifEmpty { "Workspace" }
        val distros = distroChecks.filter { it.isChecked }.map { it.text.toString() }
        val serverIds = serverChecks.filter { it.isChecked }.map { serversId(it) }.filter { it.isNotEmpty() }
        val theme = themes[themeSpinner.selectedItemPosition].first.ifEmpty { null }
        val id = editingId ?: WorkspaceStore.newWorkspace(name).id
        val updated = WorkspaceStore.Workspace(
            id = id,
            name = name,
            distros = distros,
            serverIds = serverIds,
            theme = theme,
            snippetIds = editing?.snippetIds ?: emptyList(),
            enableStartupHook = hookCheck.isChecked,
            split = splitCheck.isChecked,
            pinned = pinCheck.isChecked
        )
        WorkspaceStore.upsert(this, updated)
        editor.visibility = View.GONE
        refresh()
    }

    private fun launch(w: WorkspaceStore.Workspace) {
        startActivity(
            Intent(this, TerminalActivity::class.java)
                .putExtra(TerminalActivity.EXTRA_WORKSPACE_ID, w.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
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
