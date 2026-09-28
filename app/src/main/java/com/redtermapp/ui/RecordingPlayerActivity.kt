package com.redtermapp.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.redtermapp.R
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import java.io.File

/**
 * Replays a recorded ANSI file.
 *
 * The terminal view only accepts a live process, so replay feeds the captured
 * bytes into a session running a pty with echo disabled. The pty echoes the
 * escape sequences back as output, which the emulator then renders exactly as
 * it would have rendered the original recording.
 */
class RecordingPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)

        val path = intent?.getStringExtra(EXTRA_PATH)
        val file = path?.let { File(it) }
        if (file == null || !file.isFile) {
            Toast.makeText(this, R.string.open_file_failed, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val terminalView = TerminalView(this, null).apply {
            id = R.id.replay_terminal
        }
        setContentView(terminalView)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.recording_replay)

        val client = object : TerminalSessionClient {
            override fun onTextChanged(session: TerminalSession) = Unit
            override fun onTitleChanged(session: TerminalSession) = Unit
            override fun onSessionFinished(session: TerminalSession) = Unit
            override fun onCopyTextToClipboard(session: TerminalSession, text: String) = Unit
            override fun onPasteTextFromClipboard(session: TerminalSession) = Unit
            override fun onBell(session: TerminalSession) = Unit
            override fun onColorsChanged(session: TerminalSession) = Unit
            override fun onTerminalCursorStateChange(terminalCursorVisible: Boolean) = Unit
            override fun getTerminalCursorStyle(): Int? = null
            override fun logError(tag: String?, message: String?) = Unit
            override fun logWarn(tag: String?, message: String?) = Unit
            override fun logInfo(tag: String?, message: String?) = Unit
            override fun logDebug(tag: String?, message: String?) = Unit
            override fun logVerbose(tag: String?, message: String?) = Unit
            override fun logStackTraceWithMessage(
                tag: String?, message: String?, e: Exception?
            ) = Unit

            override fun logStackTrace(tag: String?, e: Exception?) = Unit
        }

        // echo off: without it the pty would echo the input and the emulator
        // would render every byte twice.
        val session = TerminalSession(
            "/system/bin/sh", filesDir.absolutePath,
            arrayOf("stty", "-echo", "-icanon", "min", "1", "time", "0"),
            emptyArray(), 2000, client
        )
        session.mSessionName = file.name
        terminalView.attachSession(session)
        terminalView.onScreenUpdated()
        terminalView.post {
            Thread({
                try {
                    file.inputStream().use { input ->
                        val buffer = ByteArray(4096)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            session.write(buffer.copyOf(read), 0, read)
                            Thread.sleep(12)
                        }
                    }
                } catch (_: Exception) {
                }
            }, "redterm-replay").apply { isDaemon = true }.start()
        }
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
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
