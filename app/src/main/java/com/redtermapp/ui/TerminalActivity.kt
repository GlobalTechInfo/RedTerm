package com.redtermapp.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import android.os.Environment
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.view.GravityCompat
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import com.redtermapp.R
import com.redtermapp.distro.DistroInstaller
import com.redtermapp.service.TerminalService
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import java.io.File

class TerminalActivity : AppCompatActivity() {

    private lateinit var distroName: String
    private var pendingStartDir: String? = null
    private var pendingCommand: String? = null
    private var pendingSshArgs: Array<String>? = null
    private var pendingSshTitle: String? = null
    private lateinit var terminalView: TerminalView
    private lateinit var searchHighlight: SearchHighlightOverlay
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var sessionListContainer: LinearLayout

    private var terminalBackend: TerminalBackend? = null
    private var currentFontSize = 20

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private var searchMatches = mutableListOf<SearchMatch>()
    private var searchIndex = -1

    companion object {
        /** Lets the shell finish booting before the command is typed. */
        private const val POST_COMMAND_DELAY_MS = 900L

        const val EXTRA_DISTRO = "distro"
        const val EXTRA_START_DIR = "start_dir"

        const val EXTRA_COMMAND = "command"
        const val EXTRA_SSH_ARGS = "ssh_args"
        const val EXTRA_SSH_TITLE = "ssh_title"

        /**
         * Opens a terminal session running the bundled OpenSSH client.
         *
         * This deliberately bypasses proot: a saved server should connect
         * straight away, without needing a distro installed or one that happens
         * to ship openssh-client.
         */
        fun launchSsh(
            context: Context,
            args: Array<String>,
            title: String
        ) {
            context.startActivity(
                Intent(context, TerminalActivity::class.java).apply {
                    putExtra(EXTRA_SSH_ARGS, args)
                    putExtra(EXTRA_SSH_TITLE, title)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            )
        }

        /** Opens a session with a command already typed, for example `ssh host`. */
        fun launchWithCommand(
            context: Context,
            distroName: String,
            startDir: String? = null,
            command: String
        ) {
            context.startActivity(
                Intent(context, TerminalActivity::class.java).apply {
                    putExtra(EXTRA_DISTRO, distroName)
                    if (startDir != null) putExtra(EXTRA_START_DIR, startDir)
                    putExtra(EXTRA_COMMAND, command)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            )
        }

        fun launch(context: Context, distroName: String, startDir: String? = null) {
            context.startActivity(
                Intent(context, TerminalActivity::class.java).apply {
                    putExtra(EXTRA_DISTRO, distroName)
                    if (startDir != null) putExtra(EXTRA_START_DIR, startDir)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            )
        }
    }

    private val sessionModel: TerminalViewModel by lazy { TerminalViewModel.get(application) }
    private val sessions: List<TerminalSession> get() = sessionModel.sessions.value
    private val currentIndex: Int get() = sessionModel.currentIndex.value

    private val nightReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (isFinishing || isDestroyed) return
            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            applyTerminalTheme(NightModeReceiver.effectiveTheme(prefs), notifyOthers = false)
        }
    }

    private val titleHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val titleRunnable = object : Runnable {
        override fun run() {
            updateCwdTitle()
            titleHandler.postDelayed(this, 1000)
        }
    }

    private fun wireBackend(backend: TerminalBackend) {
        backend.onSessionFinished = { finishedSession -> handleSessionFinished(finishedSession) }
        backend.onLinkTap = { link, isPath -> handleLinkTap(link, isPath) }
    }

    private fun handleLinkTap(link: String, isPath: Boolean) {
        if (isPath) {
            val rootfs = DistroInstaller(applicationContext).getRootfsDir(distroName)
            val hostPath = when {
                link.startsWith("~/") -> File(rootfs, link.removePrefix("~/"))
                link.startsWith("/") -> File(rootfs, link.removePrefix("/"))
                else -> File(rootfs, link)
            }
            val exists = hostPath.exists()
            val options = mutableListOf("Copy path")
            if (exists) options.add(0, "Open in Files")
            android.app.AlertDialog.Builder(this)
                .setTitle(link)
                .setItems(options.toTypedArray()) { _, which ->
                    when (options[which]) {
                        "Open in Files" -> {
                            val target = if (hostPath.isDirectory) hostPath else hostPath.parentFile
                            if (target != null) {
                                startActivity(Intent(this, FileBrowserActivity::class.java).apply {
                                    putExtra("distro", distroName)
                                    putExtra("path", target.absolutePath)
                                })
                            }
                        }
                        else -> copyText(link)
                    }
                }
                .show()
        } else {
            android.app.AlertDialog.Builder(this)
                .setTitle(link)
                .setItems(arrayOf("Open in browser", "Copy link")) { _, which ->
                    when (which) {
                        0 -> {
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, (if (link.startsWith("http")) link else "https://$link").toUri()))
                            } catch (_: Exception) {
                                Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show()
                            }
                        }
                        else -> copyText(link)
                    }
                }
                .show()
        }
    }

    private fun copyText(text: String) {
        val clip = getSystemService(android.content.ClipboardManager::class.java)
        clip.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)
        applyKeyboardInsets()

        if (!com.redtermapp.util.StoragePermission.isAccessible(this)) {
            Toast.makeText(
                this,
                "RedTerm needs All files access to use /storage/emulated/0 in the terminal",
                Toast.LENGTH_LONG
            ).show()
            com.redtermapp.util.StoragePermission.requestAccess(this)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        distroName = intent?.getStringExtra(EXTRA_DISTRO) ?: "alpine"
        pendingStartDir = intent?.getStringExtra(EXTRA_START_DIR)
        pendingCommand = intent?.getStringExtra(EXTRA_COMMAND)?.trim()?.takeIf { it.isNotEmpty() }
        pendingSshArgs = intent?.getStringArrayExtra(EXTRA_SSH_ARGS)
        pendingSshTitle = intent?.getStringExtra(EXTRA_SSH_TITLE)
        getSharedPreferences("settings", MODE_PRIVATE)
            .edit { putString("last_distro", distroName) }
        terminalView = findViewById(R.id.terminal_view)
        searchHighlight = findViewById(R.id.search_highlight_overlay)
        searchHighlight.attachTerminalView(terminalView)
        drawerLayout = findViewById(R.id.drawer_layout)
        sessionListContainer = findViewById(R.id.session_list_container)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_back_chip)
        supportActionBar?.title = distroName.replaceFirstChar { it.uppercase() }

        setupExtraKeysRow1()
        setupExtraKeysRow2()
        setupSearchPanel()

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)

        val rootfsDir = DistroInstaller(applicationContext).getRootfsDir(distroName)
        renderDistroSize(rootfsDir)

        setupQuickPanel(prefs)
        if (prefs.getBoolean("autohide_keys", false)) {
            toggleExtraKeys(false)
        }

        findViewById<TextView>(R.id.new_session_button).setOnClickListener {
            promptForNewSession()
        }

        findViewById<TextView>(R.id.export_btn).setOnClickListener {
            exportCurrentOutput()
        }

        findViewById<TextView>(R.id.copy_selected_btn).setOnClickListener {
            copySelectedText()
        }

        findViewById<TextView>(R.id.paste_btn).setOnClickListener {
            pasteClipboard()
        }

        androidx.core.content.ContextCompat.registerReceiver(
            this, nightReceiver,
            android.content.IntentFilter(NightModeReceiver.ACTION_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // An explicit ssh request has to be honoured even when sessions are
        // already open: otherwise the intent is dropped and the activity
        // silently re-attaches whatever session was in front.
        if (pendingSshArgs != null) {
            createNewSession()
        } else if (sessions.isEmpty()) {
            createNewSession()
        } else {
            // A resumed session must still get a refreshed .startup, because it used to
            // be written only by createNewSession(): any session that outlived an app
            // update kept running the script from the day its rootfs was created, so no
            // repository or package-manager fix ever reached it.
            //
            // The file is only written, never acted on. An earlier version restarted the
            // activity when the script had changed, which hung the app on back
            // navigation: if the write did not take effect the next resume saw a "stale"
            // script again and restarted again, forever. A running shell also cannot be
            // re-fitted, so the new script is picked up the next time a session is
            // actually created, which is the normal, predictable behaviour.
            try {
                writeShellConfigs(DistroInstaller(applicationContext).getRootfsDir(distroName))
            } catch (_: Exception) {
                // Never block re-attaching an existing terminal on this.
            }
            val backend = TerminalBackend(terminalView, this).also {
                terminalBackend = it
                terminalView.setTerminalViewClient(it)
                wireBackend(it)
            }
            for (s in sessions) {
                s.updateTerminalSessionClient(backend)
            }
            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            currentFontSize = prefs.getInt("font_size", 20)
            terminalView.setTextSize(currentFontSize)
            applyFontFromPrefs(prefs)
            terminalView.setBackgroundColor(tc(R.attr.terminalBg, 0xFF1E1E2E.toInt()))
            terminalView.attachSession(sessions[currentIndex])
            terminalView.onScreenUpdated()
            terminalView.post {
                showImeWhenTerminalTapped(terminalView)
                showImeWhenTerminalTapped(terminalView)
            terminalView.requestFocus()
                terminalView.isFocusableInTouchMode = true
            }
            val target = sessions.indexOfFirst { it.mSessionName.equals(distroName, ignoreCase = true) }
            if (target >= 0 && target != currentIndex) {
                sessionModel.switchToSession(target)
                terminalView.attachSession(sessions[target])
                terminalView.onScreenUpdated()
            }
            supportActionBar?.title = sessions[currentIndex].mSessionName.ifEmpty {
                distroName.replaceFirstChar { it.uppercase() }
            }
            updateDrawer()
        }
        startForegroundService()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun tc(attr: Int, default: Int): Int {
        val ta = theme.obtainStyledAttributes(intArrayOf(attr))
        val c = ta.getColor(0, default)
        ta.recycle()
        return c
    }

    private val repeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null
    private var repeatAction: (() -> Unit)? = null

    private fun createKeyButton(label: String, action: () -> Unit): Button {
        val textColor = tc(R.attr.terminalText, 0xFFCDD6F4.toInt())
        val repeatable = label in listOf(
            "\u25B2", "UP", "\u25BC", "DOWN", "\u25C0", "LEFT", "\u25B6", "RIGHT",
            "\u232B", "BACKSPACE", "DEL", "INS"
        )
        return Button(this).apply {
            text = label
            setTextColor(textColor)
            textSize = 12f
            setBackgroundResource(0)
            setPadding(4, 4, 4, 4)
            minWidth = 0
            minimumWidth = 0
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT
            ).apply { weight = 1f; setMargins(2, 4, 2, 4); gravity = Gravity.CENTER }
            setOnTouchListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        setBackgroundColor(0xFF45475A.toInt())
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        if (repeatable) {
                            action()
                            startKeyRepeat(action)
                        }
                        false
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        setBackgroundColor(0)
                        stopKeyRepeat()
                        if (!repeatable) v.performClick()
                        true
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        setBackgroundColor(0)
                        stopKeyRepeat()
                        true
                    }
                    else -> false
                }
            }
            if (repeatable) {
                setOnClickListener { }
            } else {
                setOnClickListener { action() }
            }
        }
    }

    private fun startKeyRepeat(action: () -> Unit) {
        stopKeyRepeat()
        repeatAction = action
        repeatRunnable = object : Runnable {
            override fun run() {
                repeatAction?.invoke()
                repeatHandler.postDelayed(this, 50)
            }
        }
        repeatHandler.postDelayed(repeatRunnable!!, 400)
    }

    private fun stopKeyRepeat() {
        repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
        repeatRunnable = null
        repeatAction = null
    }

    private fun extraKeyLabels(): Pair<List<String>, List<String>> {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val d1 = "\u2630 ESC TAB CTRL ALT \u25B2 HOME END"
        val d2 = "INS DEL && | \u25C0 \u25BC \u25B6 \u232B"
        val split = { s: String -> s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() } }
        return split(prefs.getString("extra_keys_row1", d1)!!) to
            split(prefs.getString("extra_keys_row2", d2)!!)
    }

    private fun focusedTerminalView(): com.termux.view.TerminalView =
        focusedSplitView() ?: terminalView

    private fun focusedSession(): TerminalSession? {
        val view = focusedTerminalView()
        return view.mTermSession ?: splitViewSession[view] ?: session
    }

    private fun extraKeyMetaState(): Int {
        var metaState = 0
        if (ctrlActive) metaState = metaState or com.termux.terminal.KeyHandler.KEYMOD_CTRL
        if (altActive) metaState = metaState or com.termux.terminal.KeyHandler.KEYMOD_ALT
        return metaState
    }

    private fun sendSpecialKey(keyCode: Int) {
        val view = focusedTerminalView()
        if (!view.handleKeyCode(keyCode, extraKeyMetaState())) {
            focusedSession()?.writeCodePoint(false, keyCode)
        }
        Unit
    }

    private fun keyAction(label: String): () -> Unit {
        val actions: List<Pair<String, () -> Unit>> = listOf(
            "\u2630" to { drawerLayout.openDrawer(GravityCompat.START) },
            "MENU" to { drawerLayout.openDrawer(GravityCompat.START) },
            "ESC" to { sendSpecialKey(KeyEvent.KEYCODE_ESCAPE) },
            "TAB" to { sendSpecialKey(KeyEvent.KEYCODE_TAB) },
            "CTRL" to { toggleCtrl() },
            "ALT" to { toggleAlt() },
            "\u25B2" to { focusedTerminalView().handleKeyCode(KeyEvent.KEYCODE_DPAD_UP, 0); Unit },
            "UP" to { focusedTerminalView().handleKeyCode(KeyEvent.KEYCODE_DPAD_UP, 0); Unit },
            "\u25BC" to { focusedTerminalView().handleKeyCode(KeyEvent.KEYCODE_DPAD_DOWN, 0); Unit },
            "DOWN" to { focusedTerminalView().handleKeyCode(KeyEvent.KEYCODE_DPAD_DOWN, 0); Unit },
            "\u25C0" to { focusedTerminalView().handleKeyCode(KeyEvent.KEYCODE_DPAD_LEFT, 0); Unit },
            "LEFT" to { focusedTerminalView().handleKeyCode(KeyEvent.KEYCODE_DPAD_LEFT, 0); Unit },
            "\u25B6" to { focusedTerminalView().handleKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT, 0); Unit },
            "RIGHT" to { focusedTerminalView().handleKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT, 0); Unit },
            "HOME" to { focusedTerminalView().handleKeyCode(KeyEvent.KEYCODE_MOVE_HOME, 0); Unit },
            "END" to { focusedTerminalView().handleKeyCode(KeyEvent.KEYCODE_MOVE_END, 0); Unit },
            "INS" to { focusedTerminalView().handleKeyCode(KeyEvent.KEYCODE_INSERT, 0); Unit },
            "DEL" to {
                if (isSearchPanelVisible()) searchInputKey(KeyEvent.KEYCODE_FORWARD_DEL)
                else focusedTerminalView().handleKeyCode(KeyEvent.KEYCODE_FORWARD_DEL, 0)
                Unit
            },
            "\u232B" to {
                if (isSearchPanelVisible()) searchInputKey(KeyEvent.KEYCODE_DEL)
                else focusedTerminalView().handleKeyCode(KeyEvent.KEYCODE_DEL, 0)
                Unit
            },
            "BACKSPACE" to {
                if (isSearchPanelVisible()) searchInputKey(KeyEvent.KEYCODE_DEL)
                else focusedTerminalView().handleKeyCode(KeyEvent.KEYCODE_DEL, 0)
                Unit
            },
            "&&" to { focusedSession()?.write("&&"); Unit },
            "|" to { focusedSession()?.write("|"); Unit },
        )
        return actions.firstOrNull { it.first == label }?.second
            ?: { focusedSession()?.write(label) }
    }

    private fun setupExtraKeysRow1() {
        val container = findViewById<LinearLayout>(R.id.extra_keys_container)
        for (label in extraKeyLabels().first) {
            container.addView(createKeyButton(label, keyAction(label)))
        }
    }

    private fun setupExtraKeysRow2() {
        val container = findViewById<LinearLayout>(R.id.extra_keys_container_row2)
        for (label in extraKeyLabels().second) {
            container.addView(createKeyButton(label, keyAction(label)))
        }
    }

    private var ctrlActive = false
    private var altActive = false

    private fun toggleCtrl() {
        ctrlActive = !ctrlActive
        focusedBackend()?.setCtrl(ctrlActive)
        updateModifierButtons()
    }

    private fun toggleAlt() {
        altActive = !altActive
        focusedBackend()?.setAlt(altActive)
        updateModifierButtons()
    }

    private fun updateModifierButtons() {
        val row1 = findViewById<LinearLayout>(R.id.extra_keys_container)
        for (i in 0 until row1.childCount) {
            val btn = row1.getChildAt(i) as? Button ?: continue
            when (btn.text) {
                "CTRL" -> btn.setBackgroundColor(if (ctrlActive) 0xFF45475A.toInt() else 0)
                "ALT" -> btn.setBackgroundColor(if (altActive) 0xFF45475A.toInt() else 0)
            }
        }
    }

    private fun toggleExtraKeys(show: Boolean) {
        val vis = if (show) android.view.View.VISIBLE else android.view.View.GONE
        // The outer HorizontalScrollViews are what occupy space, each a fixed 40dp.
        // Hiding only the inner container left an empty 40dp strip behind, and that gap
        // is one of the things that squeezes the second row out on a landscape screen.
        findViewById<android.view.View>(R.id.extra_keys).visibility = vis
        findViewById<android.view.View>(R.id.extra_keys_row2).visibility = vis
        findViewById<LinearLayout>(R.id.extra_keys_container).visibility = vis
        findViewById<LinearLayout>(R.id.extra_keys_container_row2).visibility = vis
    }

    private fun updateExtraKeysVisibility() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (!prefs.getBoolean("autohide_keys", false)) return
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        val showing = imm.isActive(terminalView)
        toggleExtraKeys(showing)
    }

    private val session: TerminalSession?
        get() = if (currentIndex in sessions.indices) sessions[currentIndex] else null

    private fun writeShellConfigs(rootfsDir: File) {
        try {
            val osRelease = try { File(rootfsDir, "etc/os-release").readText() } catch (_: Exception) { "" }
            // Match the ID field, not a substring anywhere in the file. Rocky and
            // AlmaLinux both declare ID_LIKE="rhel centos fedora", so a
            // contains("Fedora") test matched them and sent both to Fedora's plan,
            // which wrote Fedora's repositories into their rootfs.
            val osId = Regex("""(?m)^ID="?([^"\n]+)"?""")
                .find(osRelease)?.groupValues?.getOrNull(1)?.trim()?.lowercase()
                .orEmpty()
            // ID is only usable once it is a key plan() actually knows. Arch Linux ARM
            // reports ID="archlinuxarm" and openSUSE reports "opensuse-leap", so using ID
            // verbatim missed every key and fell through to the `else` fallback, which is
            // an empty plan: no mirror setup, no download command, no repair and no
            // system update. That produced a .startup containing `update_ok=1` and
            // `then : bash`, i.e. an instant and entirely fake "Setup complete."
            val canonical = com.redtermapp.distro.DistroSetup.canonicalize(osId)
            val distro: String = when {
                canonical != null -> canonical
                osRelease.contains("Alpine", ignoreCase = true) -> "alpine"
                osRelease.contains("Ubuntu", ignoreCase = true) -> "ubuntu"
                osRelease.contains("Kali", ignoreCase = true) -> "kali"
                osRelease.contains("openSUSE", ignoreCase = true) -> "opensuse"
                osRelease.contains("Manjaro", ignoreCase = true) -> "manjaro"
                osRelease.contains("Alma", ignoreCase = true) -> "almalinux"
                osRelease.contains("Rocky", ignoreCase = true) -> "rocky"
                osRelease.contains("Arch", ignoreCase = true) -> "arch"
                osRelease.contains("Void", ignoreCase = true) -> "void"
                osRelease.contains("Fedora", ignoreCase = true) -> "fedora"
                File(rootfsDir, "etc/fedora-release").exists() -> "fedora"
                File(rootfsDir, "etc/debian_version").exists() -> "debian"
                osRelease.contains("Debian", ignoreCase = true) -> "debian"
                else -> "unknown"
            }

            val bashrc = """# ~/.bashrc
export TERM=xterm-256color
stty erase ^?
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
shopt -s histappend histreedit histverify checkwinsize cdspell dirspell
HISTSIZE=10000 HISTFILESIZE=20000
HISTCONTROL=ignoreboth:erasedups
HISTTIMEFORMAT="%F %T "
PS1='\[\e[1;32m\]\u@\h\[\e[0m\]:\[\e[1;34m\]\w\[\e[0m\]\$ '
PROMPT_COMMAND='[ ${'$'}? -eq 0 ] || printf "\a"'
if [ -d /etc/bash_completion.d ]; then
    for f in /etc/bash_completion.d/*; do
        [ -f "${'$'}f" ] && . "${'$'}f"
    done
fi
alias ls='ls --color=auto'
alias ll='ls -lah'
alias la='ls -A'
alias l='ls -CF'
alias grep='grep --color=auto'
alias ..='cd ..'
alias ...='cd ../..'
alias rm='rm -i'
alias cp='cp -i'
alias mv='mv -i'
alias df='df -h'
alias du='du -h'
alias free='free -m'
alias vi='vim'
alias nano='nano -w'
"""

            val rootDir = File(rootfsDir, "root")
            rootDir.mkdirs()

            // Write shell configs only when missing so user customizations
            // (e.g. a hand-written .bashrc) are never overwritten.
            val bashrcFile = File(rootDir, ".bashrc")
            if (!bashrcFile.exists()) {
                bashrcFile.writeText(bashrc)
            }
            val bashProfileFile = File(rootDir, ".bash_profile")
            if (!bashProfileFile.exists()) {
                bashProfileFile.writeText("""[ -f /root/.bashrc ] && . /root/.bashrc
""")
            }
            val startupMarker = com.redtermapp.distro.DistroSetup.MARKER
            val startupFile = File(rootDir, ".startup")
            val startupScript = com.redtermapp.distro.DistroSetup.buildStartupScript(distro)
            // Compared by content, not by marker. The marker is a constant, so a
            // marker-only check meant an existing .startup was never refreshed: every
            // repository fix shipped afterwards was invisible to installs that
            // already existed, so a broken rootfs stayed broken forever. .startup is
            // generated by the app (unlike .bashrc below, which is never
            // overwritten), so keeping it in step with the generator is the point.
            val needsStartupWrite = try {
                !startupFile.exists() || startupFile.readText() != startupScript
            } catch (_: Exception) {
                true
            }
            if (needsStartupWrite) {
                startupFile.writeText(startupScript)
                android.util.Log.i(
                    "TerminalActivity",
                    "refreshed .startup for $distro ($startupMarker)"
                )
            }
        } catch (e: Exception) {
            android.util.Log.w("TerminalActivity", "writeShellConfigs failed: ${e.message}")
        }
    }

    private fun repairRootfsOffMainThread(rootfsDir: File) {
        Thread({
            try {
                val repairLog = DistroInstaller(applicationContext).repairRootfs(rootfsDir)
                if (repairLog.contains("WARN") || repairLog.contains("missing")) {
                    android.util.Log.w("TerminalActivity", "Rootfs issues:\n$repairLog")
                }
            } catch (e: Throwable) {
                android.util.Log.w("TerminalActivity", "Rootfs repair skipped", e)
            }
        }, "redterm-rootfs-repair").apply { isDaemon = true }.start()
    }

    private fun renderDistroSize(rootfsDir: File) {
        val label = findViewById<TextView>(R.id.distro_size_label)
        val installer = DistroInstaller(applicationContext)
        val cached = installer.cachedSizeBytes(distroName)
        if (cached >= 0L) {
            label.text = getString(
                R.string.distro_size_format,
                distroName,
                DistroInstaller.formatSize(cached)
            )
        } else {
            label.text = getString(R.string.distro_size_calculating, distroName)
        }
        if (cached < 0L || installer.isSizeCacheStale(distroName)) {
            installer.refreshSizeCache(distroName) { bytes ->
                if (bytes < 0L) return@refreshSizeCache
                runOnUiThread {
                    label.text = getString(
                        R.string.distro_size_format,
                        distroName,
                        DistroInstaller.formatSize(bytes)
                    )
                }
            }
        }
    }

    /**
     * @param targetDistro distro to open; defaults to the one this activity is
     *   currently showing. Passing a different distro is what allows several
     *   distros (and several sessions of one distro) to be open at once.
     */
    private fun createSshSession(args: Array<String>, title: String) {
        // The client lives in app storage, which is mounted noexec on Android
        // 12+, so it is launched through proot's -L loader exactly like the
        // distro binaries. args[0] is the in-rootfs program path.
        val command = (listOf(args[0]) + args.drop(1)).joinToString(" ") { quote(it) }
        com.redtermapp.util.AppLog.i(this, "ssh", "session requested: $command")
        val launcher = com.redtermapp.util.SshClient.launcher(this, command, "ssh-session.sh")
        if (launcher == null) {
            com.redtermapp.util.AppLog.e(this, "ssh", "no usable ssh rootfs")
            showError(getString(R.string.ssh_client_unavailable))
            return
        }
        com.redtermapp.util.AppLog.i(this, "ssh", "launcher=$launcher")
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val rows = intArrayOf(500, 1000, 2000, 3000, 5000, 7500, 10000, 15000, 20000, 30000)[
            prefs.getInt("scrollback", 4).coerceIn(0, 9)
        ]
        val backend = terminalBackend ?: TerminalBackend(terminalView, this).also {
            terminalBackend = it
            terminalView.setTerminalViewClient(it)
            wireBackend(it)
        }
        val home = com.redtermapp.util.SshClient.homeDir(applicationContext)
        val env = arrayOf(
            "HOME=${home.absolutePath}",
            "TERM=xterm-256color",
            "PATH=/system/bin"
        )
        val session = TerminalSession(
            "/system/bin/sh", home.absolutePath,
            arrayOf("-c", launcher), env, rows, backend
        )
        session.mSessionName = title
        wireBackend(backend)
        sessionModel.addSession(session)
        terminalView.attachSession(session)
        terminalView.onScreenUpdated()
        supportActionBar?.title = title
        currentFontSize = prefs.getInt("font_size", 20)
        terminalView.setTextSize(currentFontSize)
        applyFontFromPrefs(prefs)
        terminalView.setBackgroundColor(tc(R.attr.terminalBg, 0xFF1E1E2E.toInt()))
        terminalView.post {
            showImeWhenTerminalTapped(terminalView)
            terminalView.requestFocus()
            terminalView.isFocusableInTouchMode = true
        }
        updateDrawer()
    }

    /**
     * Offers a fresh session for any installed distro. Picking the distro that is
     * already open still creates a second, independent session, which is what
     * makes concurrent sessions possible.
     */
    private fun promptForNewSession() {
        val installed = DistroInstaller(applicationContext).getInstalledDistros()
        if (installed.isEmpty()) {
            showError(getString(R.string.no_distros_installed))
            return
        }
        val labels = installed.map { name ->
            val open = sessions.count { it.mSessionName.equals(name, ignoreCase = true) }
            if (open > 0) {
                resources.getQuantityString(R.plurals.session_option_with_open, open, name, open)
            } else {
                name.replaceFirstChar { it.uppercase() }
            }
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.new_session)
            .setItems(labels) { _, which -> createNewSession(installed[which]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun createNewSession(targetDistro: String? = null, startDir: String? = null) {
        // An SSH session runs the bundled client directly, with no proot and no
        // distro, so it skips all of the rootfs setup below.
        if (pendingSshArgs != null) {
            val args = pendingSshArgs
            val title = pendingSshTitle ?: getString(R.string.ssh_session)
            pendingSshArgs = null
            pendingSshTitle = null
            createSshSession(args!!, title)
            return
        }
        targetDistro?.let { distroName = it }
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val scrollback = intArrayOf(500, 1000, 2000, 3000, 5000, 7500, 10000, 15000, 20000, 30000)[prefs.getInt("scrollback", 4).coerceIn(0, 9)]
        val startInner = startDir ?: pendingStartDir.also { pendingStartDir = null }
        val rootfsDir = DistroInstaller(applicationContext).getRootfsDir(distroName)
        if (!rootfsDir.exists()) {
            showError("Distro $distroName not installed.\nRun installer first.")
            return
        }

        DistroInstaller(applicationContext).refreshSizeCache(distroName)
        DistroInstaller(applicationContext).refreshNetworkConfig(distroName)
        repairRootfsOffMainThread(rootfsDir)
        // Must run before the launcher is written: it creates /root/.bashrc and
        // /root/.startup, and the launcher points ENV at the latter. Without this
        // the shell starts with no setup and no bash.
        writeShellConfigs(rootfsDir)
        // Ensure /tmp and executable binaries in rootfs (proot needs both)
        File(rootfsDir, "tmp").mkdirs()
        val busybox = File(rootfsDir, "bin/busybox")
        if (busybox.exists() && !busybox.canExecute()) {
            busybox.setExecutable(true, true)
        }
        // Also set bin/sh etc.
        for (name in listOf("sh", "ash", "bash")) {
            val f = File(rootfsDir, "bin/$name")
            if (f.exists() && !f.canExecute()) {
                f.setExecutable(true, true)
            }
        }

        val backend = terminalBackend ?: TerminalBackend(terminalView, this).also {
            terminalBackend = it
            terminalView.setTerminalViewClient(it)
            wireBackend(it)
        }

        // ---- Distro init & proot launch ----
        val startHost = startInner
            ?.let { File(rootfsDir, it.removePrefix("/")) }?.absolutePath
            ?: filesDir.absolutePath
        val launchSh = com.redtermapp.distro.ProotLaunch.writeLauncher(
            context = this,
            rootfsDir = rootfsDir,
            startInner = startInner
        )

        val args = arrayOf("-c", launchSh.absolutePath)

        val s = TerminalSession(
            "/system/bin/sh", startHost,
            args, emptyArray(),
            scrollback,
            backend
        )
        s.mSessionName = distroName

        wireBackend(backend)

        sessionModel.addSession(s)
        terminalView.attachSession(s)
        terminalView.onScreenUpdated()
        currentFontSize = prefs.getInt("font_size", 20)
        terminalView.setTextSize(currentFontSize)
        applyFontFromPrefs(prefs)
        terminalView.setBackgroundColor(tc(R.attr.terminalBg, 0xFF1E1E2E.toInt()))

        terminalView.post {
            showImeWhenTerminalTapped(terminalView)
            terminalView.requestFocus()
            terminalView.isFocusableInTouchMode = true
        }

        updateDrawer()
        RedTermWidgetProvider.updateAll(this)
        runPendingCommand()
    }

    /**
     * Types a command supplied by the launcher (for example an ssh host from the
     * SSH manager) into the freshly attached session. The trailing newline is
     * separate so the user can read the command before it runs.
     */
    private fun runPendingCommand() {
        val command = pendingCommand ?: return
        pendingCommand = null
        terminalView.postDelayed({
            val session = sessions.getOrNull(currentIndex) ?: return@postDelayed
            TerminalBackend.pasteToSession(session, command)
        }, POST_COMMAND_DELAY_MS)
    }

    private fun switchToSession(index: Int) {
        if (index !in sessions.indices || index == currentIndex) return
        sessionModel.switchToSession(index)
        terminalView.attachSession(sessions[index])
        terminalView.onScreenUpdated()
        supportActionBar?.title = sessions[index].mSessionName.ifEmpty {
            distroName.replaceFirstChar { it.uppercase() }
        }
        updateDrawer()
    }

    private fun handleSessionFinished(finishedSession: TerminalSession) {
        val idx = sessions.indexOf(finishedSession)
        if (idx < 0) return
        sessionModel.removeSession(idx)
        if (splitActive) {
            exitSplit()
        }
        if (sessions.isEmpty()) {
            finish()
        } else {
            terminalView.attachSession(sessions[currentIndex])
            terminalView.onScreenUpdated()
            updateDrawer()
        }
        RedTermWidgetProvider.updateAll(this)
    }

    private var splitActive = false
    private var splitBackend: TerminalBackend? = null
    private var splitLeftBackend: TerminalBackend? = null
    private val splitViewSession = mutableMapOf<com.termux.view.TerminalView, TerminalSession>()

    private fun toggleSplit() {
        if (splitActive) {
            exitSplit()
            return
        }
        if (sessions.size < 2) {
            Toast.makeText(this, "Open a second session to use split view", Toast.LENGTH_SHORT).show()
            return
        }
        splitActive = true
        val container = findViewById<LinearLayout>(R.id.split_container)
        val left = findViewById<com.termux.view.TerminalView>(R.id.terminal_view_left)
        val right = findViewById<com.termux.view.TerminalView>(R.id.terminal_view_right)
        val secondaryIdx = (currentIndex + 1) % sessions.size

        terminalView.visibility = View.GONE
        findViewById<View>(R.id.search_highlight_overlay).visibility = View.GONE
        container.visibility = View.VISIBLE

        val lb = TerminalBackend(left, this).also {
            splitLeftBackend = it
            wireBackend(it)
            it.onTap = { splitSelect(left) }
        }
        val rb = TerminalBackend(right, this).also {
            splitBackend = it
            wireBackend(it)
            it.onTap = { splitSelect(right) }
        }
        sessions[currentIndex].updateTerminalSessionClient(lb)
        sessions[secondaryIdx].updateTerminalSessionClient(rb)
        left.setTerminalViewClient(lb)
        right.setTerminalViewClient(rb)
        TerminalBackend.splitViews.clear()
        TerminalBackend.splitViews.add(left)
        TerminalBackend.splitViews.add(right)
        splitViewSession[left] = sessions[currentIndex]
        splitViewSession[right] = sessions[secondaryIdx]

        val bg = tc(R.attr.terminalBg, 0xFF1E1E2E.toInt())
        for (view in listOf(left, right)) {
            view.attachSession(splitViewSession[view])
            view.onScreenUpdated()
            view.setTextSize(currentFontSize)
            view.setBackgroundColor(bg)
            applyFontToView(view, getSharedPreferences("settings", MODE_PRIVATE))
        }
        left.requestFocus()
        updateSplitButton()
    }

    private fun exitSplit() {
        if (!splitActive) return
        splitActive = false
        val container = findViewById<LinearLayout>(R.id.split_container)
        splitViewSession.clear()
        TerminalBackend.splitViews.clear()
        splitBackend = null
        splitLeftBackend = null
        for (s in sessions) {
            terminalBackend?.let { s.updateTerminalSessionClient(it) }
        }
        container.visibility = View.GONE
        terminalView.visibility = View.VISIBLE
        findViewById<View>(R.id.search_highlight_overlay).visibility = View.VISIBLE
        if (sessions.isNotEmpty()) {
            terminalView.attachSession(sessions[currentIndex])
            terminalView.onScreenUpdated()
        }
        terminalView.requestFocus()
        updateSplitButton()
    }

    private fun splitSelect(view: com.termux.view.TerminalView) {
        val s = splitViewSession[view] ?: return
        val idx = sessions.indexOf(s)
        if (idx < 0 || idx == currentIndex) return
        sessionModel.switchToSession(idx)
        supportActionBar?.title = s.mSessionName.ifEmpty {
            distroName.replaceFirstChar { it.uppercase() }
        }
        updateDrawer()
    }

    private fun updateSplitButton() {
        setCardButtonBg(findViewById<TextView>(R.id.panel_split), splitActive)
    }

    private fun focusedSplitView(): com.termux.view.TerminalView? {
        if (!splitActive) return null
        val left = findViewById<com.termux.view.TerminalView>(R.id.terminal_view_left)
        val right = findViewById<com.termux.view.TerminalView>(R.id.terminal_view_right)
        return if (right.hasFocus()) right else left
    }

    private fun focusedBackend(): TerminalBackend? {
        if (!splitActive) return terminalBackend
        val right = findViewById<com.termux.view.TerminalView>(R.id.terminal_view_right)
        return if (right.hasFocus()) splitBackend else splitLeftBackend
    }

    private fun closeSession(index: Int) {
        if (sessions.size <= 1) return
        sessionModel.removeSession(index)
        if (currentIndex >= 0) {
            terminalView.attachSession(sessions[currentIndex])
            terminalView.onScreenUpdated()
        }
        updateDrawer()
    }

    private fun updateDrawer() {
        findViewById<TextView>(R.id.session_count).text = getString(R.string.session_count_format, sessions.size)
        sessionListContainer.removeAllViews()
        if (sessions.isEmpty()) {
            sessionListContainer.addView(TextView(this).apply {
                text = getString(R.string.no_sessions)
                setTextColor(0xFF6C7086.toInt())
                textSize = 13f
                setPadding(16, 20, 16, 20)
            })
            return
        }
        for (i in sessions.indices) {
            val bgColor = if (i == currentIndex)
                tc(R.attr.extraKeysBg, 0xFF181825.toInt())
            else 0
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(4, 4, 4, 4) }
                setCardBackgroundColor(bgColor)
                radius = 10f
                cardElevation = 0f
                setOnClickListener { switchToSession(i); drawerLayout.closeDrawers() }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(12, 10, 8, 10)
                    addView(TextView(context).apply {
                        text = sessions[i].mSessionName.ifEmpty { "session ${i + 1}" }
                        setTextColor(tc(R.attr.terminalText, 0xFFCDD6F4.toInt()))
                        textSize = 13f
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                        setOnLongClickListener {
                            val currentLabel = sessions[i].mSessionName.ifEmpty { "session ${i + 1}" }
                            val input = android.widget.EditText(this@TerminalActivity).apply { setText(currentLabel) }
                            androidx.appcompat.app.AlertDialog.Builder(this@TerminalActivity)
                                .setTitle("Rename session")
                                .setView(input)
                                .setPositiveButton("Rename") { _, _ ->
                                    val newName = input.text.toString().trim()
                                    if (newName.isNotEmpty()) {
                                        sessions[i].mSessionName = newName
                                        updateDrawer()
                                    }
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                            true
                        }
                    })
                    val dotSize = dp(12)
                    addView(android.view.View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                            gravity = Gravity.CENTER
                            setMargins(0, 0, dp(12), 0)
                        }
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            if (i == currentIndex) {
                                setColor(0xFFA6E3A1.toInt())
                            } else {
                                setColor(0x00000000)
                                setStroke(dp(2), 0xFF6C7086.toInt())
                            }
                        }
                    })
                    addView(ImageView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply { gravity = Gravity.CENTER }
                        setImageDrawable(
                            androidx.appcompat.content.res.AppCompatResources.getDrawable(
                                context, android.R.drawable.ic_menu_close_clear_cancel
                            )
                        )
                        imageTintList = android.content.res.ColorStateList.valueOf(0xFF6C7086.toInt())
                        setOnClickListener { closeSession(i) }
                        setPadding(0, 0, 0, 0)
                    })
                })
            }
            sessionListContainer.addView(card)
        }
    }

    private fun exportCurrentOutput() {
        val s = session ?: return
        drawerLayout.closeDrawers()
        Thread {
            try {
                val text = s.emulator.getScreen().getTranscriptText()
                var dir = File(
                    android.os.Environment.getExternalStorageDirectory(), "RedTerm/exports"
                )
                dir.mkdirs()
                if (!dir.exists()) dir = File(filesDir, "exports").apply { mkdirs() }
                val f = File(dir, "${distroName}-${System.currentTimeMillis()}.txt")
                f.writeText(text)
                runOnUiThread {
                    Toast.makeText(this, "Exported: ${f.absolutePath}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun copySelectedText() {
        if (terminalView.isSelectingText) {
            val text = terminalView.getSelectedText()
            if (!text.isNullOrEmpty()) {
                val clip = getSystemService(android.content.ClipboardManager::class.java)
                clip.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
                terminalView.stopTextSelectionMode()
                Toast.makeText(this, "Copied ${text.length} chars", Toast.LENGTH_SHORT).show()
                return
            }
        }
        session?.let {
            val text = it.emulator.getScreen().getTranscriptText()
            val clip = getSystemService(android.content.ClipboardManager::class.java)
            clip.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
            Toast.makeText(this, "Copied entire output (${text.length} chars)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pasteClipboard() {
        val clip = getSystemService(android.content.ClipboardManager::class.java)
        val text = clip.primaryClip?.getItemAt(0)?.text?.toString() ?: return
        TerminalBackend.pasteToSession(session, text)
    }

    private fun showError(msg: String) {
        val errorFile = File(cacheDir, "opencode_error.txt")
        errorFile.writeText(msg)

        terminalView.setTextSize(14)
        terminalView.setBackgroundColor(tc(R.attr.terminalBg, 0xFF1E1E2E.toInt()))
        val backend = TerminalBackend(terminalView, this)
        terminalView.setTerminalViewClient(backend)
        val s = TerminalSession(
            "/system/bin/toybox", filesDir.absolutePath,
            arrayOf("cat", errorFile.absolutePath), emptyArray(),
            TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS, backend
        )
        s.mSessionName = "Error"
        terminalView.attachSession(s)
        terminalView.onScreenUpdated()
        terminalView.post { terminalView.requestFocus() }
    }

    private fun startForegroundService() {
        if (sessions.isEmpty()) return
        try {
            ContextCompat.startForegroundService(this, Intent(this, TerminalService::class.java))
        } catch (e: Exception) {
            android.util.Log.e("TerminalActivity", "Foreground service failed", e)
        }
    }

    override fun onResume() {
        super.onResume()
        startForegroundService()
        if (!com.redtermapp.util.StoragePermission.isAccessible(this)) {
            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            val lastAsk = prefs.getLong("storage_ask_time", 0L)
            if (System.currentTimeMillis() - lastAsk > 8000) {
                prefs.edit { putLong("storage_ask_time", System.currentTimeMillis()) }
                com.redtermapp.util.StoragePermission.requestAccess(this)
            }
        }
        terminalView.requestFocus()
        terminalView.onScreenUpdated()
        titleHandler.postDelayed(titleRunnable, 1000)
        RedTermWidgetProvider.updateAll(this)
    }

    override fun onPause() {
        titleHandler.removeCallbacks(titleRunnable)
        super.onPause()
    }

    private fun updateCwdTitle() {
        val s = session ?: return
        val name = s.mSessionName.ifEmpty { distroName }
        val title = name.replaceFirstChar { it.uppercase() }
        if (supportActionBar?.title != title) {
            supportActionBar?.title = title
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringArrayExtra(EXTRA_SSH_ARGS)?.let { args ->
            pendingSshArgs = args
            pendingSshTitle = intent.getStringExtra(EXTRA_SSH_TITLE)
            createSshSession(args, pendingSshTitle ?: getString(R.string.ssh_session))
            return
        }
        val newDistro = intent.getStringExtra(EXTRA_DISTRO) ?: return
        val newDir = intent.getStringExtra(EXTRA_START_DIR)

        // An explicit directory (for example "open terminal here" from the file
        // browser) always gets its own session so the user lands where they
        // asked. Otherwise reuse that distro's existing session if there is one.
        if (newDir == null) {
            val existing = sessions.indexOfFirst {
                it.mSessionName.equals(newDistro, ignoreCase = true)
            }
            if (existing >= 0) {
                sessionModel.switchToSession(existing)
                terminalView.attachSession(sessions[existing])
                supportActionBar?.title = sessions[existing].mSessionName
                    .ifEmpty { newDistro.replaceFirstChar { it.uppercase() } }
                terminalView.onScreenUpdated()
                showImeWhenTerminalTapped(terminalView)
                showImeWhenTerminalTapped(terminalView)
            terminalView.requestFocus()
                return
            }
        }
        pendingCommand = intent.getStringExtra(EXTRA_COMMAND)?.trim()
            ?.takeIf { it.isNotEmpty() }
        createNewSession(newDistro, newDir)
        terminalView.requestFocus()
    }

    override fun onDestroy() {
        RedTermWidgetProvider.updateAll(this)
        unregisterReceiver(nightReceiver)
        if (sessions.isEmpty()) {
            stopService(Intent(this, TerminalService::class.java))
        }
        terminalBackend?.onSessionFinished = null
        terminalBackend = null
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.action == android.view.MotionEvent.ACTION_DOWN && ev.y < 100 && ev.rawY < 400) {
            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            if (prefs.getBoolean("autohide_keys", false)) {
                updateExtraKeysVisibility()
            }
            toggleQuickPanel()
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun quote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    /**
     * Routes key events to the focused terminal view.
     *
     * Delegating whole events (rather than calling onKeyDown/onKeyUp per action)
     * is what TerminalView expects, and it keeps the deprecated
     * KeyEvent.ACTION_MULTIPLE path out of here: the platform now delivers bulk
     * text through the IME, so that branch was only ever a fallback. Using the
     * focused view also means split-screen panes receive keys correctly.
     */
    /**
     * Tapping anywhere in the terminal opens the soft keyboard, and the back key closes
     * it again, which is how real Termux behaves and how this app behaved in v1.0.4.
     *
     * It has to be driven by touch, not by focus. Tapping a view that already holds
     * focus produces no focus change, so a focus-based version silently did nothing -
     * which is exactly what happened. The listener returns false, so the terminal's own
     * touch handling is untouched, and it must not call performClick: that fired a
     * synthetic click on every release and stopped text being entered at all.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun showImeWhenTerminalTapped(view: android.view.View?) {
        view?.setOnTouchListener { v, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                v.isFocusableInTouchMode = true
                v.requestFocus()
                imeShownByTap = true
                (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
            }
            false
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Back belongs to the input method whenever the keyboard is on screen. This has
        // to be handled here, above the forwarding below: the terminal view is focusable
        // and consumes key events, so `view.dispatchKeyEvent(event) ||` short-circuits and
        // the platform never gets a chance to close the IME. Without this the keyboard
        // could only be dismissed by backgrounding the app, and it reappeared on return.
        // The up event is swallowed too, otherwise it re-shows the keyboard. This matches
        // what Termux does and behaves the same during first-time distro setup.
        // Read the window's live IME state rather than a cached flag. The cached
        // `imeVisible` was only updated by the inset listener on the drawer, so if that
        // listener did not run the flag stayed false and BACK fell straight through to
        // the terminal view, which is exactly the reported symptom. Querying the insets
        // at the moment of the press cannot go stale.
        // isAcceptingText() asks the input method directly whether it is up and
        // consuming input. Reading the window insets instead was unreliable here and
        // reported the keyboard as hidden, so BACK fell through to the terminal view
        // and nothing happened.
        // Whether the keyboard is up is taken from our own record, not from asking the
        // input method. isAcceptingText and isActive both reported false while Gboard was
        // plainly visible, so the press fell through, the activity finished, the terminal
        // session was torn down and a new one started - which is what re-ran .startup and
        // printed the first-time setup again. We are the ones who asked for the keyboard
        // on tap, so we know when it is up.
        val imeShowing = imeShownByTap || imeVisible
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        if (imeShowing && event.keyCode == KeyEvent.KEYCODE_BACK) {
            imeShownByTap = false
            if (event.action == KeyEvent.ACTION_DOWN) {
                imm?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
            }
            return true
        }
        if (currentIndex !in sessions.indices) return super.dispatchKeyEvent(event)
        if ((event.keyCode == KeyEvent.KEYCODE_DEL || event.keyCode == KeyEvent.KEYCODE_FORWARD_DEL) &&
            isSearchPanelVisible()
        ) {
            return findViewById<android.widget.EditText>(R.id.search_input)
                .dispatchKeyEvent(event)
        }
        val view = focusedTerminalView()
        return view.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, 1, 0, "Sessions")
        menu?.add(0, 2, 0, "New Session")
        menu?.add(0, 3, 0, "Font +")
        menu?.add(0, 4, 0, "Font -")
        menu?.add(0, 5, 0, "Reset")
        val fontSub = menu?.addSubMenu(0, 7, 0, "Fonts")
        fontSub?.add(0, 71, 0, "JetBrains Mono")
        fontSub?.add(0, 72, 0, "Fira Code")
        fontSub?.add(0, 73, 0, "Source Code Pro")
        fontSub?.add(0, 74, 0, "Ubuntu Mono")
        fontSub?.add(0, 75, 0, "monospace")
        fontSub?.add(0, 76, 0, "Droid Sans Mono")
        fontSub?.add(0, 77, 0, "Noto Sans Mono")
        fontSub?.add(0, 78, 0, "Cascadia Code")
        rebuildCustomFontMenuItems(menu)

        val themeSub = menu?.addSubMenu(0, 6, 0, "Theme")
        themeSub?.add(0, 61, 0, "Catppuccin Dark")
        themeSub?.add(0, 62, 0, "Green Terminal")
        themeSub?.add(0, 63, 0, "Light")
        themeSub?.add(0, 69, 0, "Red Terminal")
        themeSub?.add(0, 68, 0, "AMOLED Black")
        themeSub?.add(0, 64, 0, "Dracula")
        themeSub?.add(0, 65, 0, "Nord")
        themeSub?.add(0, 66, 0, "Tokyo Night")
        themeSub?.add(0, 67, 0, "Gruvbox Dark")
        themeSub?.add(0, 70, 0, "Custom")
        themeSub?.add(0, 79, 0, "Dynamic")
        menu?.add(0, 8, 0, "Find")
        menu?.add(0, 9, 0, "Snippets")
        menu?.add(0, 10, 0, "Quick settings")
        menu?.add(0, 11, 0, "Split view")
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        rebuildCustomFontMenuItems(menu)
        return super.onPrepareOptionsMenu(menu)
    }

    private fun rebuildCustomFontMenuItems(menu: Menu?) {
        val fontSub = menu?.findItem(7)?.subMenu ?: return
        for (i in 0 until 10) {
            fontSub.removeItem(100 + i)
        }
        customFontFiles().forEachIndexed { i, f ->
            fontSub.add(0, 100 + i, 0, "${f.name.removeSuffix(".ttf").removeSuffix(".TTF").removeSuffix(".otf").removeSuffix(".OTF")} (custom)")
        }
    }

    private fun applyTerminalTheme(themeName: String, notifyOthers: Boolean = true) {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        prefs.edit { putString("theme", themeName) }
        if (notifyOthers) {
            NightModeReceiver.notifyChanged(this, prefs)
        }
        if (isFinishing || isDestroyed) return
        updateTerminalBg()
        val themeRes = when (themeName) {
            "red" -> R.style.Theme_RedTermApp_Red
            "amoled" -> R.style.Theme_RedTermApp_AMOLED
            "green" -> R.style.Theme_RedTermApp_Green
            "light" -> R.style.Theme_RedTermApp_Light
            "dracula" -> R.style.Theme_RedTermApp_Dracula
            "nord" -> R.style.Theme_RedTermApp_Nord
            "tokyo" -> R.style.Theme_RedTermApp_Tokyo
            "gruvbox" -> R.style.Theme_RedTermApp_Gruvbox
            "custom" -> R.style.Theme_RedTermApp_Custom
            else -> R.style.Theme_RedTermApp
        }
        val wrapped = ContextThemeWrapper(this, themeRes)
        fun tca(attr: Int, default: Int): Int {
            val ta = wrapped.obtainStyledAttributes(intArrayOf(attr))
            val c = ta.getColor(0, default); ta.recycle(); return c
        }
        val dynamic = if (themeName == "dynamic") dynamicTerminalColors() else null
        val bg = when {
            themeName == "custom" -> prefs.getInt("custom_bg", 0xFF1E1E2E.toInt())
            dynamic != null -> dynamic.first
            else -> tca(R.attr.terminalBg, 0xFF1E1E2E.toInt())
        }
        val extraBg = when {
            themeName == "custom" -> prefs.getInt("custom_bg", 0xFF0A0A0A.toInt())
            dynamic != null -> dynamic.second
            else -> tca(R.attr.extraKeysBg, 0xFF181825.toInt())
        }
        val textColor = when {
            themeName == "custom" -> prefs.getInt("custom_text", 0xFFCDD6F4.toInt())
            dynamic != null -> dynamic.third
            else -> tca(R.attr.terminalText, 0xFFCDD6F4.toInt())
        }

        val opacity = prefs.getInt("terminal_opacity", 10).coerceIn(0, 10)
        val alpha = (opacity * 25.5).toInt().coerceIn(0, 255)
        val bgWithAlpha = (bg and 0x00FFFFFF) or (alpha shl 24)
        val extraBgWithAlpha = (extraBg and 0x00FFFFFF) or (alpha shl 24)
        terminalView.setBackgroundColor(bgWithAlpha)
        drawerLayout.setBackgroundColor(bg)
        val row1 = findViewById<LinearLayout>(R.id.extra_keys_container).apply { setBackgroundColor(extraBgWithAlpha) }
        val row2 = findViewById<LinearLayout>(R.id.extra_keys_container_row2).apply { setBackgroundColor(extraBgWithAlpha) }
        for (i in 0 until row1.childCount) (row1.getChildAt(i) as? android.widget.TextView)?.setTextColor(textColor)
        for (i in 0 until row2.childCount) (row2.getChildAt(i) as? android.widget.TextView)?.setTextColor(textColor)
    }

    private fun updateTerminalBg() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val theme = prefs.getString("theme", "amoled")
        val bg = when {
            theme == "custom" -> {
                prefs.getInt("custom_bg", 0xFF1E1E2E.toInt())
            }
            theme == "dynamic" -> {
                dynamicTerminalColors()?.first ?: 0xFF1E1E2E.toInt()
            }
            else -> {
                val themeRes = when (theme) {
                    "red" -> R.style.Theme_RedTermApp_Red
                    "amoled" -> R.style.Theme_RedTermApp_AMOLED
                    "green" -> R.style.Theme_RedTermApp_Green
                    "light" -> R.style.Theme_RedTermApp_Light
                    "dracula" -> R.style.Theme_RedTermApp_Dracula
                    "nord" -> R.style.Theme_RedTermApp_Nord
                    "tokyo" -> R.style.Theme_RedTermApp_Tokyo
                    "gruvbox" -> R.style.Theme_RedTermApp_Gruvbox
                    else -> R.style.Theme_RedTermApp
                }
                val wrapped = ContextThemeWrapper(this, themeRes)
                val ta = wrapped.obtainStyledAttributes(intArrayOf(R.attr.terminalBg))
                val c = ta.getColor(0, 0xFF1E1E2E.toInt())
                ta.recycle()
                c
            }
        }
        val opacity = prefs.getInt("terminal_opacity", 10).coerceIn(0, 10)
        val alpha = (opacity * 25.5).toInt().coerceIn(0, 255)
        val bgWithAlpha = (bg and 0x00FFFFFF) or (alpha shl 24)
        terminalView.setBackgroundColor(bgWithAlpha)
    }

    private fun dynamicTerminalColors(): Triple<Int, Int, Int>? {
        if (android.os.Build.VERSION.SDK_INT < 31) return null
        return try {
            Triple(
                getColor(android.R.color.system_neutral1_1000),
                getColor(android.R.color.system_neutral1_900),
                getColor(android.R.color.system_neutral1_100)
            )
        } catch (_: Exception) {
            null
        }
    }

    private var panelVisible = false

    private fun setupSearchPanel() {
        val input = findViewById<android.widget.EditText>(R.id.search_input)
        val prev = findViewById<android.widget.TextView>(R.id.search_prev)
        val next = findViewById<android.widget.TextView>(R.id.search_next)
        val close = findViewById<android.widget.TextView>(R.id.search_close)

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                performSearch(input.text.toString())
                true
            } else false
        }

        prev.setOnClickListener { navigateSearch(-1) }
        next.setOnClickListener { navigateSearch(1) }
        close.setOnClickListener { closeSearchPanel() }
    }

    private fun isSearchPanelVisible(): Boolean =
        findViewById<android.widget.LinearLayout>(R.id.search_panel).isVisible

    private fun searchInputKey(keyCode: Int) {
        findViewById<android.widget.EditText>(R.id.search_input)
            .dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
    }

    private fun closeSearchPanel() {
        findViewById<android.widget.LinearLayout>(R.id.search_panel).visibility = android.view.View.GONE
        searchHighlight.clear()
        val input = findViewById<android.widget.EditText>(R.id.search_input)
        input.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
        terminalView.requestFocus()
    }

    private fun setupQuickPanel(prefs: android.content.SharedPreferences) {
        val panel = findViewById<LinearLayout>(R.id.quick_panel)
        findViewById<TextView>(R.id.panel_close).setOnClickListener { toggleQuickPanel() }

        findViewById<TextView>(R.id.panel_wakelock).apply {
            setOnClickListener {
                val svc = Intent(this@TerminalActivity, com.redtermapp.service.TerminalService::class.java)
                if (prefs.getBoolean("wakelock", false)) {
                    prefs.edit { putBoolean("wakelock", false) }
                    stopService(svc)
                    setCardButtonBg(this, false)
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    prefs.edit { putBoolean("wakelock", true) }
                    ContextCompat.startForegroundService(this@TerminalActivity, svc)
                    setCardButtonBg(this, true)
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            setCardButtonBg(this, prefs.getBoolean("wakelock", false))
        }
        if (prefs.getBoolean("wakelock", false)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        findViewById<TextView>(R.id.panel_split).setOnClickListener { toggleSplit() }
        updateSplitButton()
        findViewById<TextView>(R.id.panel_font_up).setOnClickListener {
            currentFontSize = (currentFontSize + 2).coerceAtMost(36)
            terminalView.setTextSize(currentFontSize)
            prefs.edit { putInt("font_size", currentFontSize) }
        }
        findViewById<TextView>(R.id.panel_font_down).setOnClickListener {
            currentFontSize = (currentFontSize - 2).coerceAtLeast(8)
            terminalView.setTextSize(currentFontSize)
            prefs.edit { putInt("font_size", currentFontSize) }
        }
        findViewById<TextView>(R.id.panel_reset).setOnClickListener {
            session?.reset()
            prefs.edit { putString("font", "monospace") }
            applyFontFromPrefs(prefs)
            currentFontSize = 20
            prefs.edit { putInt("font_size", 20) }
            terminalView.setTextSize(20)
            applyTerminalTheme("amoled")
            toggleQuickPanel()
        }
    }

    private fun toggleQuickPanel() {
        panelVisible = !panelVisible
        findViewById<LinearLayout>(R.id.quick_panel).visibility = if (panelVisible) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun setCardButtonBg(tv: TextView, active: Boolean) {
        tv.setBackgroundColor(if (active) 0xFF45475A.toInt() else 0x33000000)
        tv.setTextColor(if (active) 0xFF89B4FA.toInt() else tc(R.attr.terminalText, 0xFFCDD6F4.toInt()))
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val prefs = getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            1 -> { drawerLayout.openDrawer(GravityCompat.START); true }
            2 -> { createNewSession(); true }
            3 -> { currentFontSize = (currentFontSize + 2).coerceAtMost(36); terminalView.setTextSize(currentFontSize); true }
            4 -> { currentFontSize = (currentFontSize - 2).coerceAtLeast(8); terminalView.setTextSize(currentFontSize); true }
             5 -> {
                session?.reset()
                prefs.edit { putString("font", "monospace") }
                applyFontFromPrefs(prefs)
                currentFontSize = 20
                prefs.edit { putInt("font_size", 20) }
                terminalView.setTextSize(20)
                applyTerminalTheme("amoled")
                true
            }
              61 -> { applyTerminalTheme("default"); true }
              62 -> { applyTerminalTheme("green"); true }
              63 -> { applyTerminalTheme("light"); true }
              69 -> { applyTerminalTheme("red"); true }
              68 -> { applyTerminalTheme("amoled"); true }
              64 -> { applyTerminalTheme("dracula"); true }
              65 -> { applyTerminalTheme("nord"); true }
              66 -> { applyTerminalTheme("tokyo"); true }
              67 -> { applyTerminalTheme("gruvbox"); true }
               70 -> { applyTerminalTheme("custom"); true }
               79 -> { applyTerminalTheme("dynamic"); true }
               71 -> { prefs.edit { putString("font", "JetBrains Mono") }; applyFontFromPrefs(prefs); true }
              72 -> { prefs.edit { putString("font", "Fira Code") }; applyFontFromPrefs(prefs); true }
              73 -> { prefs.edit { putString("font", "Source Code Pro") }; applyFontFromPrefs(prefs); true }
              74 -> { prefs.edit { putString("font", "Ubuntu Mono") }; applyFontFromPrefs(prefs); true }
              75 -> { prefs.edit { putString("font", "monospace") }; applyFontFromPrefs(prefs); true }
              76 -> { prefs.edit { putString("font", "Droid Sans Mono") }; applyFontFromPrefs(prefs); true }
              77 -> { prefs.edit { putString("font", "Noto Sans Mono") }; applyFontFromPrefs(prefs); true }
               78 -> { prefs.edit { putString("font", "Cascadia Code") }; applyFontFromPrefs(prefs); true }
               100 -> { prefs.edit { putString("font", "custom:${customFontFiles().getOrNull(0)?.name ?: ""}") }; applyFontFromPrefs(prefs); true }
               101 -> { prefs.edit { putString("font", "custom:${customFontFiles().getOrNull(1)?.name ?: ""}") }; applyFontFromPrefs(prefs); true }
               102 -> { prefs.edit { putString("font", "custom:${customFontFiles().getOrNull(2)?.name ?: ""}") }; applyFontFromPrefs(prefs); true }
               103 -> { prefs.edit { putString("font", "custom:${customFontFiles().getOrNull(3)?.name ?: ""}") }; applyFontFromPrefs(prefs); true }
               104 -> { prefs.edit { putString("font", "custom:${customFontFiles().getOrNull(4)?.name ?: ""}") }; applyFontFromPrefs(prefs); true }
               105 -> { prefs.edit { putString("font", "custom:${customFontFiles().getOrNull(5)?.name ?: ""}") }; applyFontFromPrefs(prefs); true }
               106 -> { prefs.edit { putString("font", "custom:${customFontFiles().getOrNull(6)?.name ?: ""}") }; applyFontFromPrefs(prefs); true }
               107 -> { prefs.edit { putString("font", "custom:${customFontFiles().getOrNull(7)?.name ?: ""}") }; applyFontFromPrefs(prefs); true }
               108 -> { prefs.edit { putString("font", "custom:${customFontFiles().getOrNull(8)?.name ?: ""}") }; applyFontFromPrefs(prefs); true }
               109 -> { prefs.edit { putString("font", "custom:${customFontFiles().getOrNull(9)?.name ?: ""}") }; applyFontFromPrefs(prefs); true }
                8 -> { toggleSearch(); true }
                9 -> { showSnippetsDialog(); true }
                10 -> { toggleQuickPanel(); true }
                11 -> { toggleSplit(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private val fontCache = HashMap<String, android.graphics.Typeface?>()

    private fun customFontFiles(): List<File> =
        File(filesDir, "fonts").listFiles { f ->
            f.isFile && (f.extension.equals("ttf", true) || f.extension.equals("otf", true))
        }?.sortedBy { it.name.lowercase() } ?: emptyList()

    private fun loadFont(assetPath: String): android.graphics.Typeface? =
        fontCache.getOrPut(assetPath) {
            try {
                android.graphics.Typeface.createFromAsset(assets, assetPath)
            } catch (_: Exception) {
                null
            }
        }

    private fun applyFontFromPrefs(prefs: android.content.SharedPreferences) {
        val tf = fontFromPrefs(prefs)
        terminalView.setTypeface(tf)
    }

    private fun fontFromPrefs(prefs: android.content.SharedPreferences): android.graphics.Typeface {
        val fontName = prefs.getString("font", "monospace")
        val tf = when {
            fontName != null && fontName.startsWith("custom:") ->
                try {
                    android.graphics.Typeface.createFromFile(
                        File(filesDir, "fonts/${fontName.removePrefix("custom:")}")
                    )
                } catch (_: Exception) {
                    null
                }
            else -> when (fontName) {
                "JetBrains Mono" -> loadFont("fonts/JetBrainsMono.ttf")
                "Fira Code" -> loadFont("fonts/FiraCode.ttf")
                "Source Code Pro" -> loadFont("fonts/SourceCodePro.ttf")
                "Ubuntu Mono" -> loadFont("fonts/UbuntuMono.ttf")
                "Droid Sans Mono" -> loadFont("fonts/DroidSansMono.ttf")
                "Noto Sans Mono" -> loadFont("fonts/NotoSansMono.ttf")
                "Cascadia Code" -> loadFont("fonts/CascadiaCode.ttf")
                else -> android.graphics.Typeface.MONOSPACE
            }
        }
        return tf ?: android.graphics.Typeface.MONOSPACE
    }

    private fun applyFontToView(view: com.termux.view.TerminalView, prefs: android.content.SharedPreferences) {
        view.setTypeface(fontFromPrefs(prefs))
    }

    private fun applyTheme() {
        val prefs = getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        val theme = NightModeReceiver.effectiveTheme(prefs)
        if (theme == "dynamic") {
            setTheme(R.style.Theme_RedTermApp)
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                try {
                    com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this)
                } catch (_: Exception) {}
            }
            return
        }
        when (theme) {
            "red" -> setTheme(R.style.Theme_RedTermApp_Red)
            "amoled" -> setTheme(R.style.Theme_RedTermApp_AMOLED)
            "green" -> setTheme(R.style.Theme_RedTermApp_Green)
            "light" -> setTheme(R.style.Theme_RedTermApp_Light)
            "dracula" -> setTheme(R.style.Theme_RedTermApp_Dracula)
            "nord" -> setTheme(R.style.Theme_RedTermApp_Nord)
            "tokyo" -> setTheme(R.style.Theme_RedTermApp_Tokyo)
            "gruvbox" -> setTheme(R.style.Theme_RedTermApp_Gruvbox)
            "custom" -> {
                setTheme(R.style.Theme_RedTermApp_Custom)
                val bg = prefs.getInt("custom_bg", 0xFF1E1E2E.toInt())
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.dark(bg),
                    navigationBarStyle = SystemBarStyle.dark(bg)
                )
            }
            else -> setTheme(R.style.Theme_RedTermApp)
        }
    }

    /**
     * Keeps both extra-keys rows clear of the soft keyboard.
     *
     * With the keyboard open in landscape the two fixed 40dp key rows sit underneath
     * the IME, so the lower one could not be reached. The bottom inset is applied as
     * padding on the content column, which lifts both rows above the keyboard.
     *
     * The listener has to sit on the drawer, not on terminal_content: that view sets
     * fitsSystemWindows="true", which consumes the insets, so a listener on it always
     * read an IME height of zero and did nothing. When the window does resize for
     * adjustResize the inset is reported as zero anyway, so the two paths cannot
     * double up.
     */
    /**
     * True while the soft keyboard is on screen. Tracked from the same inset pass that
     * pads the terminal, so it costs nothing extra.
     */
    private var imeVisible = false

    /** Set when a terminal tap asks for the keyboard, cleared when back closes it. */
    private var imeShownByTap = false

    /**
     * Back must dismiss the keyboard, not the activity or the terminal.
     *
     * The terminal view is focusable and holds an InputConnection, so the platform
     * delivers BACK to it and the keyboard stays open; the only way out was to
     * background the app and return, at which point the keyboard reappeared. Android's
     * own rule is that BACK belongs to the input method whenever it is visible, so that
     * is what is implemented here: the first press hides the keyboard and only a second
     * press leaves the screen. This is the same contract Termux follows, and it behaves
     * identically during first-time distro setup, which shows the keyboard too.
     */
    /**
     * The terminal gets taller as the keyboard opens, not shorter.
     *
     * This used to add the IME inset as bottom padding to the content, on top of
     * `adjustResize` already shrinking the window. The two fought each other: on a
     * landscape screen the padding consumed the whole remaining height, so the terminal
     * collapsed to nothing under the top bar and the second extra-keys row was left
     * behind the keyboard. The platform already resizes the window correctly, so the
     * padding is gone and only the visibility flag is kept, for the back-button handling.
     */
    private fun applyKeyboardInsets() {
        val root = findViewById<android.view.ViewGroup>(R.id.drawer_layout) ?: return
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            imeVisible = insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
            insets
        }
    }

    private fun toggleSearch() {
        val panel = findViewById<android.widget.LinearLayout>(R.id.search_panel)
        if (panel.isVisible) {
            closeSearchPanel()
            return
        }
        panel.visibility = android.view.View.VISIBLE
        val input = findViewById<android.widget.EditText>(R.id.search_input)
        input.requestFocus()
        input.setText("")
        searchMatches.clear()
        searchIndex = -1
        findViewById<android.widget.TextView>(R.id.search_count).text = "0/0"
    }

    private var searchRunId = 0

    private fun performSearch(query: String) {
        searchMatches.clear()
        searchIndex = -1

        val countView = findViewById<android.widget.TextView>(R.id.search_count)

        if (query.isEmpty()) {
            countView.text = "0/0"
            searchHighlight.clear()
            return
        }

        val emulator = terminalView.mEmulator ?: return
        val runId = ++searchRunId

        Thread {
            val matches = try {
                scanTranscript(query, emulator)
            } catch (t: Throwable) {
                emptyList<SearchMatch>()
            }
            runOnUiThread {
                if (runId != searchRunId) return@runOnUiThread
                if (matches.isEmpty()) {
                    countView.text = "0/0"
                    searchHighlight.clear()
                } else {
                    searchMatches.addAll(matches)
                    searchIndex = 0
                    countView.text = getString(R.string.search_match_count_format, 1, searchMatches.size)
                    scrollToMatch(searchMatches[0])
                }
            }
        }.start()
    }

    private fun scanTranscript(query: String, emulator: TerminalEmulator): List<SearchMatch> {
        val buffer = emulator.getScreen()
        val lastRow = emulator.mRows - 1
        val maxCol = emulator.mColumns - 1
        val results = ArrayList<SearchMatch>()
        for (row in -buffer.getActiveTranscriptRows()..lastRow) {
            val internal = try {
                buffer.externalToInternalRow(row)
            } catch (e: IllegalArgumentException) {
                continue
            }
            val terminalRow = buffer.allocateFullLineIfNecessary(internal)
            val line = String(terminalRow.mText, 0, terminalRow.getSpaceUsed())
            var col = line.indexOf(query, ignoreCase = true)
            while (col >= 0) {
                results.add(SearchMatch(row, col, (col + query.length - 1).coerceAtMost(maxCol)))
                col = line.indexOf(query, col + 1, ignoreCase = true)
            }
        }
        return results
    }

    private fun scrollToMatch(match: SearchMatch) {
        val emulator = terminalView.mEmulator ?: return
        val screenRows = emulator.mRows
        val minTopRow = -emulator.getScreen().getActiveTranscriptRows()
        val topRow = (match.row - screenRows + 1).coerceAtLeast(minTopRow)
        terminalView.setTopRow(topRow)
        terminalView.invalidate()
        searchHighlight.setMatches(searchMatches, searchIndex)
    }

    private fun navigateSearch(direction: Int) {
        if (searchMatches.isEmpty()) return
        searchIndex = ((searchIndex + direction) % searchMatches.size + searchMatches.size) % searchMatches.size
        val match = searchMatches[searchIndex]
        findViewById<android.widget.TextView>(R.id.search_count).text = getString(R.string.search_match_count_format, searchIndex + 1, searchMatches.size)
        scrollToMatch(match)
    }

    private fun showSnippetsDialog() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val json = prefs.getString("snippets", "[]") ?: "[]"
        val arr = org.json.JSONArray(json)
        val names = mutableListOf<String>()
        val contents = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            names.add(obj.getString("name"))
            contents.add(obj.getString("content"))
        }

        val items = if (names.isEmpty()) arrayOf("(no snippets — tap + to add)") else names.toTypedArray()

        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Snippets")
        builder.setItems(items) { _, which ->
            if (names.isNotEmpty() && which < contents.size) {
                val content = contents[which]
                val session = terminalView.mTermSession ?: return@setItems
                session.write(content.toByteArray(), 0, content.length)
            }
        }
        builder.setPositiveButton("+ Add") { _, _ -> showAddSnippetDialog() }
        builder.setNegativeButton("Edit") { _, _ -> showEditSnippetsDialog() }
        builder.show()
    }

    private fun showAddSnippetDialog() {
        val input = android.widget.EditText(this)
        input.hint = "command or text"
        input.setTextColor(0xFFCDD6F4.toInt())
        input.setHintTextColor(0x66CDD6F4)

        val nameInput = android.widget.EditText(this)
        nameInput.hint = "snippet name"
        nameInput.setTextColor(0xFFCDD6F4.toInt())
        nameInput.setHintTextColor(0x66CDD6F4)

        val layout = android.widget.LinearLayout(this)
        layout.orientation = android.widget.LinearLayout.VERTICAL
        layout.setPadding(48, 16, 48, 16)
        layout.addView(nameInput)
        layout.addView(input)

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Add Snippet")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim()
                val content = input.text.toString()
                if (name.isNotEmpty() && content.isNotEmpty()) {
                    saveSnippet(name, content)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveSnippet(name: String, content: String) {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val json = prefs.getString("snippets", "[]") ?: "[]"
        val arr = org.json.JSONArray(json)
        val obj = org.json.JSONObject()
        obj.put("name", name)
        obj.put("content", content)
        arr.put(obj)
        prefs.edit { putString("snippets", arr.toString()) }
    }

    private fun showEditSnippetsDialog() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val json = prefs.getString("snippets", "[]") ?: "[]"
        val arr = org.json.JSONArray(json)
        val names = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            names.add(arr.getJSONObject(i).getString("name"))
        }

        if (names.isEmpty()) {
            android.widget.Toast.makeText(this, "No snippets to edit", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Edit / Delete Snippets")
        builder.setItems(names.toTypedArray()) { _, which ->
            if (which < names.size) {
                android.app.AlertDialog.Builder(this)
                    .setTitle(names[which])
                    .setMessage("What to do with this snippet?")
                    .setPositiveButton("Delete") { _, _ ->
                        arr.remove(which)
                        prefs.edit { putString("snippets", arr.toString()) }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        builder.show()
    }
}
