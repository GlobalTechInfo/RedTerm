package com.redtermapp.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.redtermapp.R
import com.redtermapp.distro.DistroInstaller
import com.redtermapp.proot.ProotRunner
import com.redtermapp.service.TerminalService
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import java.io.File

class TerminalActivity : AppCompatActivity() {

    private lateinit var distroName: String
    private lateinit var terminalView: TerminalView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var sessionListContainer: LinearLayout

    private var terminalBackend: TerminalBackend? = null
    private var currentFontSize = 14

    companion object {
        private const val EXTRA_DISTRO = "distro"
        private const val REQUEST_NOTIFICATIONS = 1001
        val sessions = mutableListOf<TerminalSession>()
        var currentIndex = -1

        fun launch(context: Context, distroName: String) {
            context.startActivity(
                Intent(context, TerminalActivity::class.java).apply {
                    putExtra(EXTRA_DISTRO, distroName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)

        distroName = intent?.getStringExtra(EXTRA_DISTRO) ?: "alpine"
        terminalView = findViewById(R.id.terminal_view)
        drawerLayout = findViewById(R.id.drawer_layout)
        sessionListContainer = findViewById(R.id.session_list_container)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = distroName.replaceFirstChar { it.uppercase() }

        setupExtraKeysRow1()
        setupExtraKeysRow2()

        findViewById<TextView>(R.id.new_session_button).setOnClickListener {
            createNewSession()
        }

        requestNotificationPermission()
        requestStoragePermissions()
        if (sessions.isEmpty()) {
            createNewSession()
        } else {
            val backend = TerminalBackend(terminalView, this).also {
                terminalBackend = it
                terminalView.setTerminalViewClient(it)
                it.onSessionFinished = { finishedSession ->
                    val idx = sessions.indexOf(finishedSession)
                    if (idx >= 0) {
                        sessions.removeAt(idx)
                        if (sessions.isEmpty()) {
                            finish()
                        } else {
                            if (currentIndex >= sessions.size) currentIndex = sessions.size - 1
                            terminalView.attachSession(sessions[currentIndex])
                            terminalView.onScreenUpdated()
                            updateDrawer()
                        }
                    }
                }
            }
            for (s in sessions) {
                s.updateTerminalSessionClient(backend)
            }
            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            currentFontSize = prefs.getInt("font_size", 14)
            terminalView.setTextSize(currentFontSize)
            applyFontFromPrefs(prefs)
            terminalView.setBackgroundColor(tc(R.attr.terminalBg, 0xFF1E1E2E.toInt()))
            terminalView.attachSession(sessions[currentIndex])
            terminalView.onScreenUpdated()
            terminalView.post {
                terminalView.requestFocus()
                terminalView.isFocusableInTouchMode = true
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

    private fun createKeyButton(label: String, action: () -> Unit): Button {
        val textColor = tc(R.attr.terminalText, 0xFFCDD6F4.toInt())
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
                        false
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        setBackgroundColor(0)
                        false
                    }
                    else -> false
                }
            }
            setOnClickListener { action() }
        }
    }

    private fun setupExtraKeysRow1() {
        val container = findViewById<LinearLayout>(R.id.extra_keys_container)
        val keys = listOf(
            "\u2630" to { drawerLayout.openDrawer(Gravity.START); Unit },
            "ESC" to { session?.writeCodePoint(false, 27); Unit },
            "TAB" to { session?.writeCodePoint(false, 9); Unit },
            "CTRL" to { toggleCtrl() },
            "ALT" to { toggleAlt() },
            "\u25B2" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_DPAD_UP, 0); Unit },
            "HOME" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_MOVE_HOME, 0); Unit },
            "END" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_MOVE_END, 0); Unit },
        )
        for ((label, action) in keys) {
            container.addView(createKeyButton(label, action))
        }
    }

    private fun setupExtraKeysRow2() {
        val container = findViewById<LinearLayout>(R.id.extra_keys_container_row2)
        val keys = listOf(
            "INS" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_INSERT, 0); Unit },
            "DEL" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_FORWARD_DEL, 0); Unit },
            "&&" to { session?.write("&&"); Unit },
            "\u25B6" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT, 0); Unit },
            "\u25BC" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_DPAD_DOWN, 0); Unit },
            "\u25C0" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_DPAD_LEFT, 0); Unit },
            "\u232B" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_DEL, 0); Unit },
        )
        for ((label, action) in keys) {
            container.addView(createKeyButton(label, action))
        }
    }

    private var ctrlActive = false
    private var altActive = false

    private fun toggleCtrl() {
        ctrlActive = !ctrlActive
        terminalBackend?.setCtrl(ctrlActive)
        updateModifierButtons()
    }

    private fun toggleAlt() {
        altActive = !altActive
        terminalBackend?.setAlt(altActive)
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

    private val session: TerminalSession?
        get() = if (currentIndex in sessions.indices) sessions[currentIndex] else null

    private fun createNewSession() {
        val rootfsDir = DistroInstaller(applicationContext).getRootfsDir(distroName)
        if (!rootfsDir.exists()) {
            showError("Distro $distroName not installed.\nRun installer first.")
            return
        }

        val repairLog = DistroInstaller(applicationContext).repairRootfs(rootfsDir)
        if (repairLog.contains("WARN") || repairLog.contains("missing")) {
            android.util.Log.w("TerminalActivity", "Rootfs issues:\n$repairLog")
        }

        // Ensure /tmp and executable binaries in rootfs (proot needs both)
        File(rootfsDir, "tmp").mkdirs()
        val busybox = File(rootfsDir, "bin/busybox")
        if (busybox.exists() && !busybox.canExecute()) {
            busybox.setExecutable(true, false)
        }
        // Also set bin/sh etc.
        for (name in listOf("sh", "ash", "bash")) {
            val f = File(rootfsDir, "bin/$name")
            if (f.exists() && !f.canExecute()) {
                f.setExecutable(true, false)
            }
        }

        val backend = terminalBackend ?: TerminalBackend(terminalView, this).also {
            terminalBackend = it
            terminalView.setTerminalViewClient(it)
        }

        // ---- Distro init & proot launch ----
        val nativeLibDir = applicationInfo.nativeLibraryDir
        val prootBin = "$nativeLibDir/libproot.so"
        val prootLoader = "$nativeLibDir/libproot-loader.so"
        val prootLoader32 = "$nativeLibDir/libproot-loader32.so"
        val ldr32 = if (File(prootLoader32).exists()) "export PROOT_LOADER_32=$prootLoader32\n" else ""
        val rp = rootfsDir.absolutePath

        // Detect distro
        val osRelease = try { File(rootfsDir, "etc/os-release").readText() } catch (_: Exception) { "" }
        val distro = when {
            osRelease.contains("Alpine", ignoreCase = true) -> "alpine"
            osRelease.contains("Ubuntu", ignoreCase = true) -> "ubuntu"
            osRelease.contains("Debian", ignoreCase = true) -> "debian"
            File(rootfsDir, "etc/fedora-release").exists() || osRelease.contains("Fedora", ignoreCase = true) -> "fedora"
            osRelease.contains("Void", ignoreCase = true) -> "void"
            osRelease.contains("openSUSE", ignoreCase = true) -> "opensuse"
            File(rootfsDir, "etc/debian_version").exists() -> "debian"
            else -> "unknown"
        }

        // Full .bashrc template
        val bashrc = """# ~/.bashrc
export TERM=xterm-256color
stty erase ^?
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
shopt -s histappend histreedit histverify checkwinsize cdspell dirspell
HISTSIZE=10000 HISTFILESIZE=20000
HISTCONTROL=ignoreboth:erasedups
HISTTIMEFORMAT="%F %T "
PS1='\[\e[1;32m\]\u@\h\[\e[0m\]:\[\e[1;34m\]\w\[\e[0m\]\$ '
if [ -d /etc/bash_completion.d ]; then
    for f in /etc/bash_completion.d/*; do . "${'$'}f"; done
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

        // Per-distro init package lists
        val (pmUpdate, pmInstall, pmQuiet) = when (distro) {
            "alpine" -> Triple("apk update", "apk add", "-q")
            "debian", "ubuntu" -> Triple("apt-get update -qq", "DEBIAN_FRONTEND=noninteractive apt-get install -y", "-qq")
            "fedora" -> Triple("dnf check-update || true", "dnf install -y", "-q")
            "void" -> Triple("xbps-install -Su", "xbps-install -S", "")
            "opensuse" -> Triple("zypper refresh", "zypper install -y", "-q")
            else -> Triple(":", ":", "")
        }

        // Write distro init script and shell configs directly (avoids heredoc tempfile bug)
        val rootDir = File(rp, "root")
        rootDir.mkdirs()

        // .bashrc
        File(rootDir, ".bashrc").writeText("""$bashrc""")

        // .bash_profile
        File(rootDir, ".bash_profile").writeText("""[ -f /root/.bashrc ] && . /root/.bashrc
""")

        // .startup — sourced by mksh via ENV on interactive start
        File(rootDir, ".startup").writeText("""if [ ! -f /root/.init_done ]; then
    echo '>>> First-time distro setup...'
    $pmUpdate 2>/dev/null
    $pmInstall $pmQuiet nano curl wget git sudo openssl bash 2>/dev/null
    touch /root/.init_done
    echo '>>> Setup complete.'
fi
bash -i
exit
""")

        val launchSh = File(filesDir, "launch.sh")
        launchSh.parentFile?.mkdirs()
        launchSh.writeText("""#!/system/bin/sh
export HOME=/root
export PATH=/system/bin:/system/xbin:/bin:/sbin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin
export ENV=/root/.startup
export PROOT_LOADER=$prootLoader
${ldr32}export PROOT_TMP_DIR=$rp/tmp
mkdir -p "$rp/tmp"
exec $prootBin -0 -L -r "$rp" -w /root --link2symlink --sysvipc --kill-on-exit \
    -b /dev -b /proc -b /sys -b /system -b /apex -b /linkerconfig/ld.config.txt \
    /system/bin/sh -i 2>&1
""")
        launchSh.setExecutable(true, false)

        val args = arrayOf("-c", launchSh.absolutePath)

        val s = TerminalSession(
            "/system/bin/sh", filesDir.absolutePath,
            args, emptyArray(),
            TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
            backend
        )

        backend.onSessionFinished = { finishedSession ->
            val idx = sessions.indexOf(finishedSession)
            if (idx >= 0) {
                sessions.removeAt(idx)
                if (sessions.isEmpty()) {
                    finish()
                } else {
                    if (currentIndex >= sessions.size) currentIndex = sessions.size - 1
                    terminalView.attachSession(sessions[currentIndex])
                    terminalView.onScreenUpdated()
                    updateDrawer()
                }
            }
        }

        sessions.add(s)
        currentIndex = sessions.size - 1
        terminalView.attachSession(s)
        terminalView.onScreenUpdated()
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        currentFontSize = prefs.getInt("font_size", 14)
        terminalView.setTextSize(currentFontSize)
        applyFontFromPrefs(prefs)
        terminalView.setBackgroundColor(tc(R.attr.terminalBg, 0xFF1E1E2E.toInt()))

        terminalView.post {
            terminalView.requestFocus()
            terminalView.isFocusableInTouchMode = true
        }

        updateDrawer()
    }

    private fun switchToSession(index: Int) {
        if (index !in sessions.indices || index == currentIndex) return
        currentIndex = index
        terminalView.attachSession(sessions[index])
        terminalView.onScreenUpdated()
        updateDrawer()
    }

    private fun closeSession(index: Int) {
        if (sessions.size <= 1) return
        sessions[index].finishIfRunning()
        sessions.removeAt(index)
        if (currentIndex >= sessions.size) currentIndex = sessions.size - 1
        if (currentIndex >= 0) {
            terminalView.attachSession(sessions[currentIndex])
            terminalView.onScreenUpdated()
        }
        updateDrawer()
    }

    private fun updateDrawer() {
        findViewById<TextView>(R.id.session_count).text = sessions.size.toString()
        sessionListContainer.removeAllViews()
        if (sessions.isEmpty()) {
            sessionListContainer.addView(TextView(this).apply {
                text = "No sessions"
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
                setOnLongClickListener { closeSession(i); true }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(12, 10, 8, 10)
                    addView(TextView(context).apply {
                        text = "session ${i + 1}"
                        setTextColor(tc(R.attr.terminalText, 0xFFCDD6F4.toInt()))
                        textSize = 13f
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    })
                    addView(TextView(context).apply {
                        text = if (i == currentIndex) "\u25CF" else "\u25CB"
                        setTextColor(if (i == currentIndex) 0xFF89B4FA.toInt() else 0xFF6C7086.toInt())
                        textSize = 12f
                        setPadding(0, 0, 4, 0)
                    })
                })
            }
            sessionListContainer.addView(card)
        }
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
        terminalView.attachSession(s)
        terminalView.onScreenUpdated()
        terminalView.post { terminalView.requestFocus() }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATIONS
                )
            }
        }
    }

    private fun requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    })
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    1002
                )
            }
        }
    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) return
        }
        try {
            ContextCompat.startForegroundService(this, Intent(this, TerminalService::class.java))
        } catch (e: Exception) {
            android.util.Log.e("TerminalActivity", "Foreground service failed", e)
        }
    }

    override fun onResume() {
        super.onResume()
        terminalView.requestFocus()
        terminalView.onScreenUpdated()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (sessions.isNotEmpty()) {
            terminalView.attachSession(sessions[currentIndex])
            terminalView.onScreenUpdated()
            terminalView.requestFocus()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (currentIndex !in sessions.indices) return super.dispatchKeyEvent(event)
        @Suppress("DEPRECATION")
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> terminalView.onKeyDown(event.keyCode, event) || super.dispatchKeyEvent(event)
            KeyEvent.ACTION_UP -> terminalView.onKeyUp(event.keyCode, event) || super.dispatchKeyEvent(event)
            KeyEvent.ACTION_MULTIPLE -> {
                if (event.keyCode == KeyEvent.KEYCODE_UNKNOWN) {
                    @Suppress("DEPRECATION") session?.write(event.characters ?: ""); true
                } else super.dispatchKeyEvent(event)
            }
            else -> super.dispatchKeyEvent(event)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, 1, 0, "Sessions")
        menu?.add(0, 2, 0, "New Session")
        menu?.add(0, 3, 0, "Font +")
        menu?.add(0, 4, 0, "Font -")
        menu?.add(0, 5, 0, "Reset")
        val themeSub = menu?.addSubMenu(0, 6, 0, "Theme")
        themeSub?.add(0, 61, 0, "Default")
        themeSub?.add(0, 62, 0, "Green Terminal")
        themeSub?.add(0, 63, 0, "Light")
        return true
    }

    private fun applyTerminalTheme(themeName: String) {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        prefs.edit().putString("theme", themeName).apply()
        val themeRes = when (themeName) {
            "green" -> R.style.Theme_RedTermApp_Green
            "light" -> R.style.Theme_RedTermApp_Light
            else -> R.style.Theme_RedTermApp
        }
        val wrapped = ContextThemeWrapper(this, themeRes)
        fun tca(attr: Int, default: Int): Int {
            val ta = wrapped.obtainStyledAttributes(intArrayOf(attr))
            val c = ta.getColor(0, default); ta.recycle(); return c
        }
        val bg = tca(R.attr.terminalBg, 0xFF1E1E2E.toInt())
        val extraBg = tca(R.attr.extraKeysBg, 0xFF181825.toInt())
        val textColor = tca(R.attr.terminalText, 0xFFCDD6F4.toInt())

        terminalView.setBackgroundColor(bg)
        drawerLayout.setBackgroundColor(bg)
        val row1 = findViewById<LinearLayout>(R.id.extra_keys_container).apply { setBackgroundColor(extraBg) }
        val row2 = findViewById<LinearLayout>(R.id.extra_keys_container_row2).apply { setBackgroundColor(extraBg) }
        for (i in 0 until row1.childCount) (row1.getChildAt(i) as? android.widget.TextView)?.setTextColor(textColor)
        for (i in 0 until row2.childCount) (row2.getChildAt(i) as? android.widget.TextView)?.setTextColor(textColor)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val prefs = getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            1 -> { drawerLayout.openDrawer(Gravity.START); true }
            2 -> { createNewSession(); true }
            3 -> { currentFontSize = (currentFontSize + 2).coerceAtMost(36); terminalView.setTextSize(currentFontSize); true }
            4 -> { currentFontSize = (currentFontSize - 2).coerceAtLeast(8); terminalView.setTextSize(currentFontSize); true }
            5 -> { session?.reset(); true }
            61 -> { applyTerminalTheme("default"); true }
            62 -> { applyTerminalTheme("green"); true }
            63 -> { applyTerminalTheme("light"); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startForegroundService()
            }
        }
    }

    private fun applyFontFromPrefs(prefs: android.content.SharedPreferences) {
        val fontName = prefs.getString("font", "JetBrains Mono")
        val tf = when (fontName) {
            "Fira Code" -> android.graphics.Typeface.create("Fira Code", android.graphics.Typeface.NORMAL)
            "Source Code Pro" -> android.graphics.Typeface.create("Source Code Pro", android.graphics.Typeface.NORMAL)
            "Ubuntu Mono" -> android.graphics.Typeface.create("Ubuntu Mono", android.graphics.Typeface.NORMAL)
            "monospace" -> android.graphics.Typeface.MONOSPACE
            else -> android.graphics.Typeface.create("JetBrains Mono", android.graphics.Typeface.NORMAL)
        }
        if (tf != null) terminalView.setTypeface(tf)
    }

    private fun applyTheme() {
        val theme = getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            .getString("theme", "default")
        when (theme) {
            "amoled" -> setTheme(R.style.Theme_RedTermApp_AMOLED)
            "green" -> setTheme(R.style.Theme_RedTermApp_Green)
            "light" -> setTheme(R.style.Theme_RedTermApp_Light)
            else -> setTheme(R.style.Theme_RedTermApp)
        }
    }
}
