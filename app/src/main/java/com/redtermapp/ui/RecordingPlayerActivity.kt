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

    private lateinit var terminalView: TerminalView
    private var fontSize = 20f

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

        terminalView = TerminalView(this, null).apply {
            id = R.id.replay_terminal
        }
        setContentView(terminalView)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.recording_replay)

        // Font and colours before the first replay byte, for the same reason the
        // session paths set them before attach: TerminalView's renderer is created
        // by setTextSize() and setTypeface() and nothing else, and the first layout
        // calls updateSize(), which reads the renderer's font width guarded only on
        // width, height and session — a view with no renderer crashes on layout,
        // and the recording is only attached with a session, never with a font.
        val prefs = getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        fontSize = prefs.getInt("font_size", 20).toFloat()
        terminalView.setTextSize(fontSize.toInt())
        terminalView.setTypeface(TerminalTheming.fontFromPrefs(this, prefs))
        // A view client too: updateSize() calls the client's onEmulatorSet() with no
        // guard at all, so a view laid out with no client crashes the same way it
        // crashes with no renderer. The player has no keys of its own to handle;
        // every answer is the library's own default behaviour.
        terminalView.setTerminalViewClient(playerViewClient)

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
        // The palette after the attach: the emulator exists then, and a recording's
        // SGR colour indexes replay through the same theme palette the session drew with.
        TerminalTheming.themedColours(this).let { (fg, bg) ->
            TerminalTheming.applyTerminalColours(fg, bg, terminalView)
        }
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

    /**
     * The view's client, asked for at every layout whether or not there is a
     * session to answer about. Movement, key and gesture questions are answered
     * with false so the view keeps its own built-in behaviour; a replay is not
     * interactive.
     */
    private val playerViewClient = object : com.termux.view.TerminalViewClient {
        override fun onScale(scale: Float): Float {
            // The same pinch-to-resize the session terminal answers with: the view
            // multiplies its accumulated scale factor into this and takes the
            // return as what actually applied, so the font is re-set here and the
            // rounding is handed back.
            fontSize = (fontSize * scale).coerceIn(8f, 36f)
            terminalView.setTextSize(fontSize.toInt())
            return fontSize / (fontSize / scale)
        }
        override fun onSingleTapUp(event: android.view.MotionEvent) = Unit
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = false
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = false
        override fun copyModeChanged(copyMode: Boolean) = Unit
        override fun onKeyDown(
            keyCode: Int, keyEvent: android.view.KeyEvent, session: TerminalSession
        ): Boolean = false
        override fun onKeyUp(keyCode: Int, keyEvent: android.view.KeyEvent): Boolean = false
        override fun onLongPress(event: android.view.MotionEvent): Boolean = false
        override fun readControlKey(): Boolean = false
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(
            codePoint: Int, ctrlHeld: Boolean, session: TerminalSession
        ): Boolean = false
        override fun onEmulatorSet() = Unit
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
