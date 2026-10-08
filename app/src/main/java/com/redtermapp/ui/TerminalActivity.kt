package com.redtermapp.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import com.redtermapp.R
import com.redtermapp.ui.filelist.SftpBrowserActivity
import com.redtermapp.util.SshClient
import com.redtermapp.distro.DistroInstaller
import com.redtermapp.distro.DistroRunner
import com.redtermapp.distro.SessionRecorder
import com.redtermapp.distro.ProotLaunch
import com.redtermapp.service.TerminalService
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import java.io.File

class TerminalActivity : AppCompatActivity() {

    private lateinit var distroName: String
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

        /** Settings preference; reopening killed sessions can be turned off. */
        const val KEY_RESTORE_SESSIONS = "restore_sessions"

        private val UNSAFE_FILENAME_CHARS = Regex("[^A-Za-z0-9._-]+")

        const val EXTRA_DISTRO = "distro"
        const val EXTRA_START_DIR = "start_dir"

        const val EXTRA_COMMAND = "command"

        const val EXTRA_SSH_SERVER_ID = "ssh_server_id"
        const val EXTRA_SSH_LABEL = "ssh_label"
        const val EXTRA_SSH_HOST = "ssh_host"
        const val EXTRA_SSH_PORT = "ssh_port"
        const val EXTRA_SSH_USER = "ssh_user"
        const val EXTRA_SSH_KEY_ID = "ssh_key_id"

        /**
         * Opens a new terminal session for a saved server.
         *
         * The connection details travel as individual extras rather than as a
         * ready-made argument vector: the session needs to know which server and
         * key it belongs to, and an argv tells it neither. It also never needs a
         * distro, so this works on a device with none installed.
         */
        fun launchSsh(context: Context, server: SshStore.Server, keyId: String? = null) {
            context.startActivity(
                Intent(context, TerminalActivity::class.java).apply {
                    putExtra(EXTRA_SSH_SERVER_ID, server.id)
                    putExtra(EXTRA_SSH_LABEL, server.label)
                    putExtra(EXTRA_SSH_HOST, server.host)
                    putExtra(EXTRA_SSH_PORT, server.port)
                    putExtra(EXTRA_SSH_USER, server.user)
                    putExtra(EXTRA_SSH_KEY_ID, keyId ?: server.keyId ?: "")
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

    /**
     * What to open a new session for.
     *
     * The two variants are the whole of the local-versus-SSH distinction: there
     * used to be an Intent extra holding a ready-made argument vector instead,
     * which meant the activity had no idea which host or key it had just
     * connected to and could not restore, rename or close the session properly.
     */
    private sealed class SessionRequest {
        /** A command to type into the session once it is running. */
        abstract val command: String?

        data class Local(
            val distro: String,
            val startDir: String? = null,
            override val command: String? = null
        ) : SessionRequest()

        data class Ssh(
            val server: SshStore.Server,
            val keyId: String? = null,
            val startDir: String? = null,
            override val command: String? = null
        ) : SessionRequest()
    }

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
            // A path in an SSH session is on the *server*. Resolving it against
            // the local rootfs — which is what this used to do unconditionally —
            // lands in a directory that exists for entirely unrelated reasons, so
            // "Open in Files" showed the user's own filesystem instead of the
            // remote one they asked about.
            if (sessionModel.currentDescriptor is SessionDescriptor.Ssh) {
                offerRemotePath(link)
                return
            }
            val rootfs = DistroInstaller(applicationContext).getRootfsDir(distroName)
            val hostPath = when {
                link.startsWith("~/") -> File(rootfs, link.removePrefix("~/"))
                link.startsWith("/") -> File(rootfs, link.removePrefix("/"))
                else -> File(rootfs, link)
            }
            val exists = hostPath.exists()
            val options = mutableListOf(getString(R.string.copy_path))
            if (exists) options.add(0, getString(R.string.open_in_files))
            android.app.AlertDialog.Builder(this)
                .setTitle(link)
                .setItems(options.toTypedArray()) { _, which ->
                    when (options[which]) {
                        getString(R.string.open_in_files) -> {
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

    /**
     * Offers what to do with a path that lives on an SSH server.
     *
     * Browsing it needs the transfer UI, which needs a key; with none there is
     * nothing to offer but the path itself, and pretending otherwise would open
     * an empty browser.
     */
    private fun offerRemotePath(link: String) {
        val descriptor = sessionModel.currentDescriptor as? SessionDescriptor.Ssh
            ?: return
        val canBrowse = SshClient.hasTransferTools(this) && descriptor.keyId != null
        val options = mutableListOf(getString(R.string.copy_path))
        if (canBrowse) options.add(0, getString(R.string.open_in_remote_files))
        android.app.AlertDialog.Builder(this)
            .setTitle(link)
            .setItems(options.toTypedArray()) { _, which ->
                if (which == 0 && options.size > 1) {
                    startActivity(
                        Intent(this, SftpBrowserActivity::class.java).apply {
                            putExtra(SftpBrowserActivity.EXTRA_SERVER_ID, descriptor.serverId)
                            // Null for an unbound server, and the browser then tries
                            // every key on the device rather than refusing to open.
                            putExtra(
                                SftpBrowserActivity.EXTRA_KEY_ID,
                                descriptor.keyId ?: ""
                            )
                            putExtra(SftpBrowserActivity.EXTRA_PATH, remotePathOf(link))
                        }
                    )
                } else {
                    copyText(link)
                }
            }
            .show()
    }

    /**
     * Expands a shell-style path against the remote home.
     *
     * The app has no notion of the remote home directory, so `~/src` becomes
     * `/root/src`, which is what the overwhelming majority of servers use for a
     * root login and is at least an honest guess rather than a literal directory
     * called "~".
     */
    private fun remotePathOf(link: String): String = when {
        link.startsWith("~/") -> "/root/${link.removePrefix("~/")}"
        link.startsWith("/") -> link
        else -> link
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
        // Only as a fallback. The permission is asked for once during setup, where
        // there is a screen that can explain it; this catches an install that skipped
        // that, and stays quiet once the user has answered either way.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            val notifPrefs = getSharedPreferences("settings", MODE_PRIVATE)
            if (!notifPrefs.getBoolean("asked_notifications", false)) {
                notifPrefs.edit { putBoolean("asked_notifications", true) }
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }

        distroName = intent?.getStringExtra(EXTRA_DISTRO) ?: "alpine"
        val pendingStartDir = intent?.getStringExtra(EXTRA_START_DIR)
        val pendingCommand = intent?.getStringExtra(EXTRA_COMMAND)?.trim()?.takeIf { it.isNotEmpty() }
        val pendingServer = intent?.sshServerRequest()
        // Only when the request names a distro. An SSH launch has none, and
        // storing the fallback here would point the quick-settings tile and the
        // widget at a distribution that may not be installed.
        intent?.getStringExtra(EXTRA_DISTRO)?.let { requested ->
            getSharedPreferences("settings", MODE_PRIVATE)
                .edit { putString("last_distro", requested) }
        }
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

        // Only meaningful for a distro session. On an SSH launch distroName is the
        // "alpine" fallback, so this used to measure (and then kick off a
        // background walk of) a distribution the user may not even have.
        val rootfsDir = DistroInstaller(applicationContext).getRootfsDir(distroName)
        if (pendingServer == null && rootfsDir.exists()) {
            renderDistroSize(rootfsDir)
        }

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
        if (pendingServer != null) {
            createSession(SessionRequest.Ssh(pendingServer.server, pendingServer.keyId))
        } else if (sessions.isEmpty()) {
            val intentDistro = intent?.getStringExtra(EXTRA_DISTRO)
            if (intentDistro != null) {
                createSession(SessionRequest.Local(intentDistro, pendingStartDir, pendingCommand))
            } else if (!restoreSessions()) {
                // Nothing was running and nothing was remembered, so there is
                // genuinely nothing to show. Alpine is the app's default target
                // and the error screen explains what to do about it.
                createSession(SessionRequest.Local(distroName))
            }
        } else {
            // A resumed session must still get a refreshed .startup, because it used to
            // be written only when a session was created: any session that outlived an app
            // update kept running the script from the day its rootfs was created, so no
            // repository or package-manager fix ever reached it.
            //
            // The file is only written, never acted on. An earlier version restarted the
            // activity when the script had changed, which hung the app on back
            // navigation: if the write did not take effect the next resume saw a "stale"
            // script again and restarted again, forever. A running shell also cannot be
            // re-fitted, so the new script is picked up the next time a session is
            // actually created, which is the normal, predictable behaviour.
            //
            // Only distro sessions have a .startup; an SSH session's home lives in
            // the client rootfs, which has none and must not be given one.
            val resumedDistro = (sessionModel.currentDescriptor as? SessionDescriptor.Local)?.distro
            if (resumedDistro != null) {
                try {
                    writeShellConfigs(DistroInstaller(applicationContext).getRootfsDir(resumedDistro))
                } catch (_: Exception) {
                    // Never block re-attaching an existing terminal on this.
                }
            }
            val backend = TerminalBackend(terminalView, this).also {
                terminalBackend = it
                terminalView.setTerminalViewClient(it)
                wireBackend(it)
            }
            for (s in sessions) {
                s.updateTerminalSessionClient(backend)
            }
            currentFontSize = prefs.getInt("font_size", 20)
            terminalView.setTextSize(currentFontSize)
            applyFontFromPrefs(prefs)
            applyTerminalColours(terminalFg, terminalBg, terminalView)
            terminalView.attachSession(sessions[currentIndex])
            terminalView.onScreenUpdated()
            terminalView.post {
                showImeWhenTerminalTapped(terminalView)
                terminalView.requestFocus()
                terminalView.isFocusableInTouchMode = true
            }
            // Focus the session the request was actually about. The old code
            // compared mSessionName against the distro name, which for an SSH
            // launch was the "alpine" fallback and so matched nothing.
            val requested = intent?.getStringExtra(EXTRA_DISTRO)
            val target = sessions.indexOfFirst { s ->
                val d = sessionModel.descriptorFor(s)
                when {
                    d is SessionDescriptor.Local -> requested != null && d.distro.equals(requested, true)
                    d is SessionDescriptor.Ssh -> requested == null && s === sessionModel.currentSession
                    else -> false
                }
            }
            if (target >= 0 && target != currentIndex) {
                switchToSession(target)
            } else {
                supportActionBar?.title = currentSessionLabel()
                updateDrawer()
            }
        }
        startForegroundService()
    }

    /**
     * Reopens the sessions that were running when the process was last killed.
     *
     * The ViewModel keeps sessions across an activity restart, so this only has
     * anything to do after the process itself was reclaimed in the background.
     * The stored list is cleared as soon as the last session is closed, so a
     * normal exit leaves nothing here to restore.
     *
     * @return whether anything was restored.
     */
    private fun restoreSessions(): Boolean {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_RESTORE_SESSIONS, true)) {
            SessionStore.clear(this)
            return false
        }
        val stored = SessionStore.load(this)
        if (stored.isEmpty()) return false
        val wantedId = SessionStore.currentId(this)

        val backend = terminalBackend ?: TerminalBackend(terminalView, this).also {
            terminalBackend = it
            terminalView.setTerminalViewClient(it)
            wireBackend(it)
        }

        var restored = 0
        var focusIndex = -1
        for (descriptor in stored) {
            // Each session gets a fresh id. The object it described died with the
            // process, so carrying the id over would only suggest it still exists.
            val id = SessionDescriptor.newId()
            val prepared = when (val request = descriptor.asRequest()) {
                is SessionRequest.Local -> prepareLocal(request, id)
                is SessionRequest.Ssh -> prepareSsh(request, id)
            } ?: continue
            // Added straight to the model rather than through createSession, so
            // focus and the view are set up once at the end instead of once per
            // session.
            val session = TerminalSession(
                prepared.shell, prepared.cwd,
                prepared.args, prepared.env, prepared.scrollback, backend
            )
            session.mSessionName = prepared.descriptor.label
            sessionModel.addSession(session, prepared.descriptor)
            if (descriptor.id == wantedId) focusIndex = sessions.size - 1
            restored++
        }
        if (restored == 0) return false

        if (focusIndex >= 0 && focusIndex != currentIndex) sessionModel.switchToSession(focusIndex)
        currentFontSize = prefs.getInt("font_size", 20)
        terminalView.setTextSize(currentFontSize)
        applyFontFromPrefs(prefs)
        applyTerminalColours(terminalFg, terminalBg, terminalView)
        terminalView.attachSession(sessions[currentIndex])
        terminalView.onScreenUpdated()
        terminalView.post {
            showImeWhenTerminalTapped(terminalView)
            terminalView.requestFocus()
            terminalView.isFocusableInTouchMode = true
        }
        supportActionBar?.title = currentSessionLabel()
        updateDrawer()
        return true
    }

    /** Re-requests a session that was recorded, using the values it was stored with. */
    private fun SessionDescriptor.asRequest(): SessionRequest = when (this) {
        is SessionDescriptor.Local -> SessionRequest.Local(distro, startDir)
        is SessionDescriptor.Ssh -> SessionRequest.Ssh(
            SshStore.Server(serverId, label, host, port, user, keyId),
            keyId = keyId,
            startDir = startDir
        )
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /** Reads a saved-server request out of an intent, or null if it is not one. */
    private fun Intent?.sshServerRequest(): SshServerRequest? {
        if (this == null) return null
        val host = getStringExtra(EXTRA_SSH_HOST)?.takeIf { it.isNotBlank() } ?: return null
        return SshServerRequest(
            server = SshStore.Server(
                id = getStringExtra(EXTRA_SSH_SERVER_ID).orEmpty().ifBlank { host },
                label = getStringExtra(EXTRA_SSH_LABEL)?.takeIf { it.isNotBlank() } ?: host,
                host = host,
                port = getIntExtra(EXTRA_SSH_PORT, 22).coerceIn(1, 65535),
                user = getStringExtra(EXTRA_SSH_USER).orEmpty(),
                keyId = getStringExtra(EXTRA_SSH_KEY_ID)?.takeIf { it.isNotBlank() }
            ),
            keyId = getStringExtra(EXTRA_SSH_KEY_ID)?.takeIf { it.isNotBlank() }
        )
    }

    private data class SshServerRequest(val server: SshStore.Server, val keyId: String?)

    /** The saved server behind a session, or null if it has since been deleted. */
    private fun serverOf(descriptor: SessionDescriptor.Ssh): SshStore.Server? =
        SshStore.load(this).firstOrNull { it.id == descriptor.serverId }

    /**
     * What the session drawer and the title show for [session]: the label the user
     * gave it, falling back to what it actually is.
     */
    private fun sessionLabel(session: TerminalSession?): String {
        session?.mSessionName?.takeIf { it.isNotBlank() }?.let { return it }
        return when (val d = sessionModel.descriptorFor(session)) {
            is SessionDescriptor.Local -> d.distro.replaceFirstChar { it.uppercase() }
            is SessionDescriptor.Ssh -> d.label.ifBlank { d.host }
            else -> distroName.replaceFirstChar { it.uppercase() }
        }
    }

    private fun currentSessionLabel(): String = sessionLabel(session)

    /**
     * Filesystem-safe stem for an exported transcript.
     *
     * Session labels are free text, and one containing a slash would otherwise
     * make the export path point somewhere else entirely.
     */
    private fun exportStem(session: TerminalSession): String =
        sessionLabel(session).replace(UNSAFE_FILENAME_CHARS, "_").trim('_').ifEmpty { "session" }

    /**
     * Renames a session.
     *
     * Goes through the descriptor as well as the terminal's own session name, so
     * the new name survives a restart. A label that only lived on the
     * `TerminalSession` was forgotten the moment Android reclaimed the process,
     * which is exactly when the user is most likely to be checking the list.
     */
    private fun renameSession(index: Int) {
        val target = sessions.getOrNull(index) ?: return
        val input = android.widget.EditText(this).apply {
            setText(sessionLabel(target))
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.rename_session)
            .setView(input)
            .setPositiveButton(R.string.ssh_rename_key) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) return@setPositiveButton
                target.mSessionName = newName
                sessionModel.relabel(target, newName)
                if (index == currentIndex) supportActionBar?.title = newName
                updateDrawer()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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

            val bashrc = com.redtermapp.distro.ShellConfig.bashrc()

            val rootDir = File(rootfsDir, "root")
            rootDir.mkdirs()

            // Written when missing, and refreshed when the existing file is one we
            // wrote. Skipping every existing file meant a corrected prompt could never
            // reach an install that had already been set up; overwriting every existing
            // file would destroy a hand-written .bashrc. The marker tells them apart.
            val bashrcFile = File(rootDir, ".bashrc")
            val existingBashrc = try {
                if (bashrcFile.exists()) bashrcFile.readText() else null
            } catch (_: Exception) {
                null
            }
            if (com.redtermapp.distro.ShellConfig.shouldWrite(existingBashrc)) {
                bashrcFile.writeText(bashrc)
                if (existingBashrc != null) {
                    android.util.Log.i(
                        "TerminalActivity",
                        "refreshed our .bashrc for $distro with the current prompt"
                    )
                }
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
     * Everything a new session needs, independent of how it is launched.
     *
     * Produced by [prepareLocal] or [prepareSsh] and consumed by
     * [attachSession], so the part that decides *what* to run and the part that
     * wires it to the view cannot drift apart. It used to be one function with an
     * SSH branch at the top that returned early, so every feature added after it
     * — font, theme, widget refresh, pending command — was reachable for a
     * distro session and silently skipped for an SSH one.
     */
    private data class PreparedSession(
        val shell: String,
        val cwd: String,
        val args: Array<String>,
        val env: Array<String>,
        val scrollback: Int,
        val descriptor: SessionDescriptor
    )

    /** Opens a session for a saved server, always as a new one. */
    private fun prepareSsh(request: SessionRequest.Ssh, id: String): PreparedSession? {
        // The key can be overridden for one connection without changing what the
        // server is saved with, which is what the new-session chooser does.
        val server = request.server.copy(keyId = request.keyId ?: request.server.keyId)
        // Rebuilding the options from the server keeps this the one place that
        // decides how a connection is secured, shared with the transfer tools.
        val args = mutableListOf("/bin/ssh")
        args.addAll(SshLaunchOptions.forServer(server, SshKeyStore.identitiesFor(this, server)))
        // The client lives in app storage, which is mounted noexec on Android
        // 12+, so it is launched through proot's -L loader exactly like the
        // distro binaries.
        val command = args.joinToString(" ") { ProotLaunch.quoteForShell(it) }
        com.redtermapp.util.AppLog.i(this, "ssh", "session requested: $command")
        val launcher = com.redtermapp.util.SshClient.launcher(this, command, launcherName(id))
        if (launcher == null) {
            com.redtermapp.util.AppLog.e(this, "ssh", "no usable ssh rootfs")
            showError(getString(R.string.ssh_client_unavailable))
            return null
        }
        val home = com.redtermapp.util.SshClient.homeDir(applicationContext)
        return PreparedSession(
            shell = "/system/bin/sh",
            cwd = home.absolutePath,
            args = arrayOf("-c", launcher),
            env = arrayOf(
                "HOME=${home.absolutePath}",
                "TERM=xterm-256color",
                "PATH=/system/bin"
            ),
            scrollback = scrollbackRows(getSharedPreferences("settings", MODE_PRIVATE)),
            descriptor = SessionDescriptor.Ssh(
                id = id,
                label = server.label,
                serverId = server.id,
                host = server.host,
                port = server.port,
                user = server.user,
                keyId = server.keyId,
                startDir = request.startDir
            )
        )
    }

    private fun prepareLocal(request: SessionRequest.Local, id: String): PreparedSession? {
        val distro = request.distro
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val startInner = request.startDir
        val rootfsDir = DistroInstaller(applicationContext).getRootfsDir(distro)
        if (!rootfsDir.exists()) {
            showError(getString(R.string.session_distro_not_installed, distro))
            return null
        }

        DistroInstaller(applicationContext).refreshSizeCache(distro)
        DistroInstaller(applicationContext).refreshNetworkConfig(distro)
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

        val startHost = startInner
            ?.let { File(rootfsDir, it.removePrefix("/")) }?.absolutePath
            ?: filesDir.absolutePath
        // A recorded session starts its shell under `script`, which allocates a pty
        // and writes everything through it. There is no way to attach to a shell that
        // is already running — the terminal library exposes no hook on session
        // output — so this can only be chosen when the session is opened. The toggle
        // says so rather than appearing to do nothing to the session in front of you.
        val recording = takeRecordingRequest(distro)
        val recorder = SessionRecorder(applicationContext)
        val recordCommand = if (recording) {
            recorder.sessionCommand(distro, DistroRunner(applicationContext))
        } else {
            null
        }
        if (recording && recordCommand == null) {
            showError(getString(R.string.record_script_missing, distro))
        }

        val launchSh = ProotLaunch.writeLauncher(
            context = this,
            rootfsDir = rootfsDir,
            startInner = startInner,
            command = recordCommand,
            scriptName = launcherName(id)
        )

        return PreparedSession(
            shell = "/system/bin/sh",
            cwd = startHost,
            args = arrayOf("-c", launchSh.absolutePath),
            // The launcher script exports everything the distro needs.
            env = emptyArray(),
            scrollback = scrollbackRows(prefs),
            descriptor = SessionDescriptor.Local(
                id = id,
                label = distro.replaceFirstChar { it.uppercase() },
                distro = distro,
                startDir = startInner,
                recording = recordCommand != null
            )
        )
    }

    /**
     * One launcher script per session.
     *
     * The name used to be fixed (`launch.sh`, `ssh-session.sh`), so opening a
     * second session rewrote the script the first one's `sh -c` was still
     * reading — a race that only shows up once two sessions overlap, and then the
     * older session runs whatever the newer one wanted.
     */
    private fun launcherName(sessionId: String): String = "launch-$sessionId.sh"

    /** The theme's terminal colours, for the paths that are not a theme switch. */
    private val terminalBg: Int
        get() = tc(R.attr.terminalBg, 0xFF1E1E2E.toInt())
    private val terminalFg: Int
        get() = tc(R.attr.terminalText, 0xFFCDD6F4.toInt())

    /**
     * Puts the theme onto the terminal.
     *
     * The view's background is not enough on its own: a session draws its text with
     * colours held in its own palette, inside the terminal library, and nothing the
     * layout sets reaches them. So the light theme set a light background under text
     * that was still light — a blank-looking terminal — and every other theme showed
     * the default palette whatever the app was themed to.
     *
     * Applied after every attach rather than once, because a palette belongs to a
     * session: switching to one that was started before the theme changed would
     * otherwise keep the colours it was launched with.
     */
    /**
     * Puts the current theme's palette onto [views].
     *
     * The view's background is not enough on its own: a session draws its text with
     * colours held in its own palette inside the terminal library, and nothing the
     * layout sets reaches them. So the light theme set a light background under text
     * that was still light — a blank-looking terminal — and every other theme kept the
     * library's default palette whatever the app was themed to.
     *
     * Takes the colours rather than reading them, because the theme-switch path
     * resolves them from a wrapped theme and from the custom-colour preferences, not
     * from this activity's own theme.
     */
    private fun applyTerminalColours(
        @androidx.annotation.ColorInt foreground: Int,
        @androidx.annotation.ColorInt background: Int,
        vararg views: com.termux.view.TerminalView
    ) {
        val palette = TerminalPalette.build(foreground, background)
        for (view in views) {
            view.setBackgroundColor(background)
            val emulator = view.mEmulator ?: continue
            val current = emulator.mColors.mCurrentColors
            val shared = minOf(current.size, palette.size, TerminalPalette.SIZE)
            for (i in 0 until shared) current[i] = palette[i]
            view.mTermSession?.onColorsChanged()
            view.onScreenUpdated()
        }
    }

    /**
     * Consumes a pending "record the next session" request for [distro].
     *
     * Consumed rather than left set, so the request applies to the one session the
     * user asked for instead of silently recording every session afterwards.
     */
    private fun takeRecordingRequest(distro: String): Boolean {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val key = "record_next_$distro"
        if (!prefs.getBoolean(key, false)) return false
        prefs.edit { remove(key) }
        return true
    }

    /**
     * Turns recording on for the *next* session in this distribution.
     *
     * Cannot apply to the session already on screen: there is no hook on what a
     * running session writes, so the shell would have to have been started under
     * `script` from the beginning. The dialog says so rather than looking broken, and
     * the switch is consumed by the next session so it does not silently record
     * everything afterwards.
     */
    private fun toggleRecording() {
        val turningOn = !recordingRequested(distroName)
        setRecordingRequest(distroName, turningOn)
        val button = findViewById<TextView>(R.id.panel_record)
        setCardButtonBg(button, turningOn)
        if (turningOn) {
            AlertDialog.Builder(this)
                .setTitle(R.string.record_session)
                .setMessage(R.string.record_applies_to_next)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    private fun setRecordingRequest(distro: String, on: Boolean) {
        getSharedPreferences("settings", MODE_PRIVATE).edit {
            putBoolean("record_next_$distro", on)
        }
    }

    private fun recordingRequested(distro: String): Boolean =
        getSharedPreferences("settings", MODE_PRIVATE).getBoolean("record_next_$distro", false)

    private fun scrollbackRows(prefs: android.content.SharedPreferences): Int =
        intArrayOf(500, 1000, 2000, 3000, 5000, 7500, 10000, 15000, 20000, 30000)[
            prefs.getInt("scrollback", 4).coerceIn(0, 9)
        ]

    /**
     * Starts a session, whatever kind it is.
     *
     * This is the only path that adds a session, so an SSH session gets the same
     * font, theme, widget refresh and command handling a distro one does.
     */
    private fun createSession(request: SessionRequest) {
        val id = SessionDescriptor.newId()
        // Read before the branch: a smart cast does not survive a `when` that
        // assigns, so `request.command` would not resolve afterwards.
        val command = request.command
        val prepared = when (request) {
            is SessionRequest.Local -> prepareLocal(request, id)
            is SessionRequest.Ssh -> prepareSsh(request, id)
        } ?: return
        attachSession(prepared, command)
    }

    private fun attachSession(prepared: PreparedSession, command: String?) {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val backend = terminalBackend ?: TerminalBackend(terminalView, this).also {
            terminalBackend = it
            terminalView.setTerminalViewClient(it)
            wireBackend(it)
        }
        wireBackend(backend)

        val session = TerminalSession(
            prepared.shell, prepared.cwd,
            prepared.args, prepared.env,
            prepared.scrollback,
            backend
        )
        session.mSessionName = prepared.descriptor.label

        sessionModel.addSession(session, prepared.descriptor)
        terminalView.attachSession(session)
        terminalView.onScreenUpdated()
        supportActionBar?.title = prepared.descriptor.label

        currentFontSize = prefs.getInt("font_size", 20)
        terminalView.setTextSize(currentFontSize)
        applyFontFromPrefs(prefs)
        applyTerminalColours(terminalFg, terminalBg, terminalView)

        terminalView.post {
            showImeWhenTerminalTapped(terminalView)
            terminalView.requestFocus()
            terminalView.isFocusableInTouchMode = true
        }

        updateDrawer()
        RedTermWidgetProvider.updateAll(this)
        if (command != null) runCommand(command)
    }

    /**
     * Offers a new session for a distro *or* a saved server.
     *
     * Picking one that is already open still starts a second, independent
     * session; that is what makes concurrent sessions possible, and it is why the
     * open count is shown next to each entry rather than the entry being hidden.
     */
    private fun promptForNewSession() {
        val installed = DistroInstaller(applicationContext).getInstalledDistros()
        val servers = SshStore.load(this)
        if (installed.isEmpty() && servers.isEmpty()) {
            showError(getString(R.string.no_distros_installed))
            return
        }
        // With nothing to choose between there is no question to ask. Skipping the
        // dialog is only safe when the single option is unambiguous.
        if (installed.isEmpty() && servers.size == 1) {
            startServerSession(servers.first())
            return
        }

        val options = mutableListOf<SessionRequest>()
        val labels = mutableListOf<String>()
        for (name in installed) {
            val open = sessions.count { sessionModel.descriptorFor(it) is SessionDescriptor.Local &&
                (sessionModel.descriptorFor(it) as SessionDescriptor.Local).distro.equals(name, true) }
            labels.add(
                if (open > 0) {
                    resources.getQuantityString(R.plurals.session_option_with_open, open, name, open)
                } else {
                    name.replaceFirstChar { it.uppercase() }
                }
            )
            options.add(SessionRequest.Local(distro = name))
        }
        for (server in servers) {
            val open = sessions.count { sessionModel.descriptorFor(it) is SessionDescriptor.Ssh &&
                (sessionModel.descriptorFor(it) as SessionDescriptor.Ssh).serverId == server.id }
            val key = SshKeyStore.boundKey(this, server)
            val suffix = getString(
                if (key != null) R.string.ssh_server_key else R.string.ssh_server_no_key,
                key?.label ?: ""
            )
            labels.add(
                if (open > 0) {
                    resources.getQuantityString(
                        R.plurals.session_option_with_open_and_key, open, server.label, suffix, open
                    )
                } else {
                    getString(R.string.session_option_with_key, server.label, suffix)
                }
            )
            options.add(SessionRequest.Ssh(server))
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.new_session)
            .setItems(labels.toTypedArray()) { _, which -> createSession(options[which]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Starts a session for [server], asking which key to use when the answer is
     * ambiguous.
     *
     * A server with no key bound still has an obvious choice when exactly one key
     * exists, and none at all when there are none: a password server works fine
     * without a key, so that case connects straight away.
     */
    private fun startServerSession(server: SshStore.Server, keyId: String? = null) {
        val bound = keyId ?: server.keyId
        if (bound != null) {
            createSession(SessionRequest.Ssh(server, keyId = bound))
            return
        }
        val keys = SshKeyStore.load(this)
        if (keys.size != 1) {
            if (keys.isEmpty()) {
                createSession(SessionRequest.Ssh(server, keyId = null))
                return
            }
            val labels = keys.map { it.label } + getString(R.string.ssh_key_none)
            val ids: List<String?> = keys.map { it.id } + null
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.ssh_choose_key_for, server.label))
                .setItems(labels.toTypedArray()) { _, which ->
                    createSession(SessionRequest.Ssh(server, keyId = ids[which]))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        createSession(SessionRequest.Ssh(server, keyId = keys.first().id))
    }

    /**
     * Types a command into the freshly attached session. The trailing newline is
     * left off so the user can read it before it runs.
     */
    private fun runCommand(command: String) {
        val session = sessions.getOrNull(currentIndex) ?: return
        terminalView.postDelayed({
            // The session may have been closed in the meantime.
            if (session in sessions) TerminalBackend.pasteToSession(session, command)
        }, POST_COMMAND_DELAY_MS)
    }

    private fun switchToSession(index: Int) {
        if (index !in sessions.indices || index == currentIndex) return
        sessionModel.switchToSession(index)
        terminalView.attachSession(sessions[index])
        terminalView.onScreenUpdated()
        supportActionBar?.title = sessionLabel(sessions[index])
        updateDrawer()
    }

    private fun handleSessionFinished(finishedSession: TerminalSession) {
        val idx = sessions.indexOf(finishedSession)
        if (idx < 0) return
        // Read before the session is dropped: this is the only place its descriptor is
        // known, and branching on the display name instead is how the warning below
        // ended up unreachable for every saved server.
        val descriptor = sessionModel.descriptorFor(finishedSession)
        val status = try {
            finishedSession.exitStatus
        } catch (_: Exception) {
            -1
        }
        sessionModel.removeSession(idx)
        if (descriptor is SessionDescriptor.Ssh && status != 0) {
            reportSshExit(descriptor, status)
        }
        if (splitActive) {
            exitSplit()
        }
        if (sessions.isEmpty()) {
            finish()
        } else {
            terminalView.attachSession(sessions[currentIndex])
            terminalView.onScreenUpdated()
            supportActionBar?.title = currentSessionLabel()
            updateDrawer()
        }
        RedTermWidgetProvider.updateAll(this)
    }

    /**
     * Says why an ssh session ended, because the terminal it was running in is
     * usually gone by the time it does and "it just closed" tells the user nothing.
     */
    private fun reportSshExit(descriptor: SessionDescriptor.Ssh, status: Int) {
        val server = SshStore.load(this).firstOrNull { it.id == descriptor.serverId }
        val reason = when (status) {
            255 -> getString(R.string.ssh_session_refused)
            else -> getString(R.string.ssh_session_ended, status)
        }
        val where = server?.let { getString(R.string.ssh_session_where, it.label) } ?: ""
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            AlertDialog.Builder(this@TerminalActivity)
                .setTitle(R.string.ssh_session_ended_title)
                .setMessage("$reason\n\n$where")
                .setPositiveButton(R.string.ok, null)
                .show()
        }
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
            // Without this the panes are unreachable by touch: tapping one does
            // nothing, so the keyboard could only ever be raised for the main
            // view and a split session could not be typed into at all.
            showImeWhenTerminalTapped(view)
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
        supportActionBar?.title = sessionLabel(s)
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

    /** Every terminal view on screen, split panes included. */
    private fun allTerminalViews(): List<com.termux.view.TerminalView> {
        val views = mutableListOf(terminalView)
        if (splitActive) {
            views.add(findViewById<com.termux.view.TerminalView>(R.id.terminal_view_left))
            views.add(findViewById<com.termux.view.TerminalView>(R.id.terminal_view_right))
        }
        return views.filter { it != null && it.id != View.NO_ID }
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
            // Two sessions of the same distro or the same server are normal now,
            // so the row says which one is which: a distro name or a host under
            // the user's label.
            val detail = when (val d = sessionModel.descriptorFor(sessions[i])) {
                is SessionDescriptor.Local -> d.distro
                is SessionDescriptor.Ssh -> "${SshLaunchOptions.target(d.host, d.user)}:${d.port}"
                else -> distroName
            }
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
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                        addView(TextView(context).apply {
                            text = sessionLabel(sessions[i]).ifBlank { "session ${i + 1}" }
                            setTextColor(tc(R.attr.terminalText, 0xFFCDD6F4.toInt()))
                            textSize = 13f
                        })
                        addView(TextView(context).apply {
                            text = detail
                            setTextColor(0xFF6C7086.toInt())
                            textSize = 11f
                        })
                        setOnLongClickListener { renameSession(i); true }
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
        // The focused pane, not the current session: with split view open those
        // can be different, and exporting the other one is not what "export" means.
        val s = focusedSession() ?: return
        drawerLayout.closeDrawers()
        Thread {
            try {
                val text = s.emulator.getScreen().getTranscriptText()
                var dir = File(
                    android.os.Environment.getExternalStorageDirectory(), "RedTerm/exports"
                )
                dir.mkdirs()
                if (!dir.exists()) dir = File(filesDir, "exports").apply { mkdirs() }
                // Named after the session, which for an SSH session used to come
                // out as "alpine-<millis>.txt" because distroName was the fallback.
                val f = File(dir, "${exportStem(s)}-${System.currentTimeMillis()}.txt")
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
        TerminalBackend.pasteToSession(focusedSession() ?: session, text)
    }

    private fun showError(msg: String) {
        val errorFile = File(cacheDir, "opencode_error.txt")
        errorFile.writeText(msg)

        terminalView.setTextSize(14)
        applyTerminalColours(terminalFg, terminalBg, terminalView)
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

    /**
     * Starts the service that keeps sessions alive — but only when the user has asked
     * for that. With the switch off the app must behave like an ordinary app, so no
     * foreground service and no notification, and the process is reclaimed whenever
     * Android feels like reclaiming it.
     */
    private fun startForegroundService() {
        if (sessions.isEmpty()) return
        if (!com.redtermapp.service.SessionKeepAwake.shouldRunPersistent(this)) return
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
        val title = sessionLabel(s)
        if (supportActionBar?.title != title) {
            supportActionBar?.title = title
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A saved-server request always starts another session: the point of
        // pressing Connect on a server that is already open is to get a second
        // one, and the first keeps running.
        intent.sshServerRequest()?.let { request ->
            createSession(SessionRequest.Ssh(request.server, keyId = request.keyId))
            terminalView.requestFocus()
            return
        }
        val newDistro = intent.getStringExtra(EXTRA_DISTRO) ?: return
        val newDir = intent.getStringExtra(EXTRA_START_DIR)

        // An explicit directory (for example "open terminal here" from the file
        // browser) always gets its own session so the user lands where they
        // asked. Otherwise reuse that distro's existing session if there is one.
        if (newDir == null) {
            val existing = sessions.indexOfFirst { s ->
                val d = sessionModel.descriptorFor(s)
                d is SessionDescriptor.Local && d.distro.equals(newDistro, ignoreCase = true)
            }
            if (existing >= 0) {
                switchToSession(existing)
                showImeWhenTerminalTapped(terminalView)
                terminalView.requestFocus()
                return
            }
        }
        createSession(
            SessionRequest.Local(
                distro = newDistro,
                startDir = newDir,
                command = intent.getStringExtra(EXTRA_COMMAND)?.trim()?.takeIf { it.isNotEmpty() }
            )
        )
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
        // Every terminal view, not just the main one: the split panes are real
        // TerminalViews with their own colours, so leaving them out meant a
        // theme change left half the screen in the old palette until split view
        // was toggled off and on again.
        applyTerminalColours(textColor, bgWithAlpha, *allTerminalViews().toTypedArray())
        for (view in allTerminalViews()) {
            view.setTextSize(currentFontSize)
            view.invalidate()
        }
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

        val keepAwake = com.redtermapp.service.SessionKeepAwake
        findViewById<TextView>(R.id.panel_wakelock).apply {
            setOnClickListener {
                val enabling = !keepAwake.isEnabled(this@TerminalActivity)
                // Releases the lock; does not stop the service, which is what carries
                // the sessions themselves.
                keepAwake.setEnabled(this@TerminalActivity, enabling)
                setCardButtonBg(this, enabling)
                if (enabling) {
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            setCardButtonBg(this, keepAwake.isEnabled(this@TerminalActivity))
        }
        if (keepAwake.isEnabled(this)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        findViewById<TextView>(R.id.panel_record).apply {
            setOnClickListener { toggleRecording() }
            setCardButtonBg(this, recordingRequested(distroName))
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
        // Re-read on every reveal: the request is consumed by the next session, so the
        // button has to stop showing as armed the moment that happens.
        findViewById<TextView>(R.id.panel_record)?.let { button ->
            setCardButtonBg(button, recordingRequested(distroName))
        }
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
            2 -> { promptForNewSession(); true }
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
                // The pane with focus, not the main view: with split view open
                // those are different sessions, and pasting into the hidden one
                // looks like nothing happened.
                val session = focusedSession() ?: return@setItems
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
