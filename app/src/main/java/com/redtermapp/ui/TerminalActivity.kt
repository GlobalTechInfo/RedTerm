package com.redtermapp.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
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
    private val sessions = mutableListOf<TerminalSession>()
    private var currentIndex = -1
    private var currentFontSize = 14

    companion object {
        private const val EXTRA_DISTRO = "distro"
        private const val REQUEST_NOTIFICATIONS = 1001

        fun launch(context: Context, distroName: String) {
            context.startActivity(
                Intent(context, TerminalActivity::class.java).apply {
                    putExtra(EXTRA_DISTRO, distroName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
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
        createNewSession()
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
            textSize = 11f
            setBackgroundResource(0)
            setPadding(8, 2, 8, 2)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(2, 0, 2, 0) }
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
            "ESC" to { session?.writeCodePoint(false, 27); Unit },
            "TAB" to { session?.writeCodePoint(false, 9); Unit },
            "CTRL" to { toggleCtrl() },
            "ALT" to { toggleAlt() },
            "/" to { session?.writeCodePoint(false, '/'.code); Unit },
            "-" to { session?.writeCodePoint(false, '-'.code); Unit },
            "|" to { session?.writeCodePoint(false, '|'.code); Unit },
            "~" to { session?.writeCodePoint(false, '~'.code); Unit },
        )
        for ((label, action) in keys) {
            container.addView(createKeyButton(label, action))
        }
    }

    private fun setupExtraKeysRow2() {
        val container = findViewById<LinearLayout>(R.id.extra_keys_container_row2)
        val keys = listOf(
            "\u25B2" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_DPAD_UP, 0); Unit },
            "\u25BC" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_DPAD_DOWN, 0); Unit },
            "\u25C0" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_DPAD_LEFT, 0); Unit },
            "\u25B6" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT, 0); Unit },
            "PG\u2191" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_PAGE_UP, 0); Unit },
            "PG\u2193" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_PAGE_DOWN, 0); Unit },
            "HOME" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_MOVE_HOME, 0); Unit },
            "END" to { terminalView.handleKeyCode(KeyEvent.KEYCODE_MOVE_END, 0); Unit },
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

        val cmd = ProotRunner.buildCommand(this, rootfsDir.absolutePath, distroName)
        val backend = terminalBackend ?: TerminalBackend(terminalView, this).also {
            terminalBackend = it
            terminalView.setTerminalViewClient(it)
        }

        val s = TerminalSession(
            cmd.prootBin, filesDir.absolutePath,
            cmd.args, cmd.env,
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
        currentFontSize = 14
        terminalView.setTextSize(currentFontSize)
        terminalView.setBackgroundColor(tc(R.attr.terminalBg, 0xFF1E1E2E.toInt()))

        terminalView.post {
            terminalView.keepScreenOn = true
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
        sessionListContainer.removeAllViews()
        if (sessions.isEmpty()) {
            sessionListContainer.addView(TextView(this).apply {
                text = "No sessions"
                setTextColor(0xFF6C7086.toInt())
                textSize = 14f
                setPadding(16, 14, 16, 14)
            })
            return
        }
        for (i in sessions.indices) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                if (i == currentIndex) setBackgroundColor(tc(R.attr.terminalBg, 0xFF313244.toInt()))
                setOnClickListener { switchToSession(i); drawerLayout.closeDrawers() }
            }
            val label = "session ${i + 1}"
            row.addView(TextView(this).apply {
                text = label
                setTextColor(tc(R.attr.terminalText, 0xFFCDD6F4.toInt()))
                textSize = 14f
                setPadding(16, 14, 0, 14)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            val closeBtn = ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                setBackgroundColor(0)
                setPadding(8, 8, 8, 8)
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                    gravity = Gravity.CENTER_VERTICAL
                }
                setOnClickListener { closeSession(i) }
            }
            row.addView(closeBtn)
            sessionListContainer.addView(row)
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

    override fun onDestroy() {
        super.onDestroy()
        for (s in sessions) s.finishIfRunning()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (currentIndex !in sessions.indices) return super.dispatchKeyEvent(event)
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> terminalView.onKeyDown(event.keyCode, event) || super.dispatchKeyEvent(event)
            KeyEvent.ACTION_UP -> terminalView.onKeyUp(event.keyCode, event) || super.dispatchKeyEvent(event)
            KeyEvent.ACTION_MULTIPLE -> {
                if (event.keyCode == KeyEvent.KEYCODE_UNKNOWN) {
                    session?.write(event.characters ?: ""); true
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val prefs = getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            1 -> { drawerLayout.openDrawer(Gravity.START); true }
            2 -> { createNewSession(); true }
            3 -> { currentFontSize = (currentFontSize + 2).coerceAtMost(36); terminalView.setTextSize(currentFontSize); true }
            4 -> { currentFontSize = (currentFontSize - 2).coerceAtLeast(8); terminalView.setTextSize(currentFontSize); true }
            5 -> { session?.reset(); true }
            61 -> { prefs.edit().putString("theme", "default").apply(); recreate(); true }
            62 -> { prefs.edit().putString("theme", "green").apply(); recreate(); true }
            63 -> { prefs.edit().putString("theme", "light").apply(); recreate(); true }
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

    private fun applyTheme() {
        val theme = getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            .getString("theme", "default")
        when (theme) {
            "green" -> setTheme(R.style.Theme_RedTermApp_Green)
            "light" -> setTheme(R.style.Theme_RedTermApp_Light)
            else -> setTheme(R.style.Theme_RedTermApp)
        }
    }
}
