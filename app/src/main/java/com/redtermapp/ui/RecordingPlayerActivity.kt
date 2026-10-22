package com.redtermapp.ui

import android.content.Context
import android.graphics.Canvas
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.redtermapp.R
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalRenderer
import java.io.File

/**
 * Replays a recorded ANSI file by feeding it into the terminal library's parser.
 *
 * The player used to feed the file into a pty whose child was `stty` and then nothing,
 * expecting the pty itself to echo the fed bytes back as terminal output — which made
 * playback depend on whichever stty vocabulary the device ships. On this device that
 * verdict is `stty: -icanon: unknown option`, the child exits with code 1 before a
 * single frame is fed into, and the replay is a blank page that finishes before it
 * starts. The parsing had nothing to do with any of it: [TerminalEmulator] consumes
 * escape sequences from a byte stream on its own, without a session or a process, and
 * the library's [TerminalRenderer] draws it onto any canvas. Playback is chunks of
 * the file into a private emulator on a background thread — the same one-thread-per-
 * terminal shape a session's own reader uses — paced, with invalidation posted to the
 * main thread. No pty, no stty, no distro prerequisite.
 */
class RecordingPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"
        const val CHUNK_SIZE = 4096
        const val PACE_MS = 12L
    }

    private lateinit var playbackView: PlaybackView
    private lateinit var sessionFile: File
    private var fontSize = 20

    private val output = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {}
        override fun titleChanged(title: String?, subtitle: String?) {}
        override fun onCopyTextToClipboard(text: String?) {}
        override fun onPasteTextFromClipboard() {}
        override fun onBell() {}
        override fun onColorsChanged() {}
    }

    private val client = object : TerminalSessionClient {
        override fun onTextChanged(session: com.termux.terminal.TerminalSession) = Unit
        override fun onTitleChanged(session: com.termux.terminal.TerminalSession) = Unit
        override fun onSessionFinished(session: com.termux.terminal.TerminalSession) = Unit
        override fun onCopyTextToClipboard(
            session: com.termux.terminal.TerminalSession,
            text: String?
        ) = Unit

        override fun onPasteTextFromClipboard(session: com.termux.terminal.TerminalSession) = Unit
        override fun onBell(session: com.termux.terminal.TerminalSession) = Unit
        override fun onColorsChanged(session: com.termux.terminal.TerminalSession) = Unit
        override fun onTerminalCursorStateChange(terminalCursorVisible: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
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
        sessionFile = file

        fontSize = getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getInt("font_size", 20)

        playbackView = PlaybackView(this).apply { id = R.id.replay_terminal }
        setContentView(playbackView)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.recording_replay)
    }

    /**
     * Feeds the file into [emulator], [CHUNK_SIZE] bytes per [PACE_MS], on a
     * background thread the way a session's own reader feeds its emulator, with
     * invalidation posted to the main thread.
     */
    private fun startPlayback(emulator: TerminalEmulator) {
        Thread({
            try {
                sessionFile.inputStream().use { input ->
                    val buffer = ByteArray(CHUNK_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        emulator.append(buffer, read)
                        playbackView.post { playbackView.invalidate() }
                        Thread.sleep(PACE_MS)
                    }
                }
            } catch (e: InterruptedException) {
                // The activity went away mid-play; the last frame simply stands.
            } catch (e: Exception) {
                // A read failure replays as terminal text, the only surface this
                // screen has, in default styling and at the cursor.
                emulator.append("replay stopped: ${e.message}".toByteArray(), "replay stopped: ${e.message}".length)
                playbackView.post { playbackView.invalidate() }
            }
        }, "redterm-replay").apply { isDaemon = true }.start()
    }

    /**
     * The playing surface: the library's renderer draws a private emulator, because
     * nothing about a replay asks the view layer to host a session. The emulator
     * comes into being at the first layout, from the view's own column and row
     * counts — positioned frames replay into whatever size the viewing device has,
     * which can clip wide sessions on a narrower screen.
     */
    private inner class PlaybackView(context: Context) : android.view.View(context) {

        private var emulator: TerminalEmulator? = null
        private var renderer: TerminalRenderer? = null
        private var background: Int

        init {
            val (fg, bg) = TerminalTheming.themedColours(this@RecordingPlayerActivity)
            background = bg
        }

        override fun onDraw(canvas: Canvas) {
            val e = emulator ?: return
            canvas.drawColor(background)
            renderer?.render(e, canvas, 0, -1, -1, 0, 0)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            if (w == 0 || h == 0) return
            val r = renderer ?: TerminalRenderer(
                fontSize,
                TerminalTheming.fontFromPrefs(
                    context,
                    context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                )
            ).also { renderer = it }
            val fontWidth = r.getFontWidth().toInt().coerceAtLeast(1)
            val lineHeight = r.getFontLineSpacing().toInt().coerceAtLeast(1)
            val cols = (w / fontWidth).coerceAtLeast(1)
            val rows = (h / lineHeight).coerceAtLeast(1)
            val existing = emulator
            if (existing == null) {
                val e = TerminalEmulator(output, cols, rows, fontWidth, lineHeight, null, client)
                TerminalTheming.applyPalette(
                    TerminalTheming.themedColours(this@RecordingPlayerActivity).first,
                    background,
                    e
                )
                emulator = e
                startPlayback(e)
            } else {
                existing.resize(cols, rows, fontWidth, lineHeight)
                invalidate()
            }
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
