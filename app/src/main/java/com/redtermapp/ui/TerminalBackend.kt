package com.redtermapp.ui

import android.content.ClipData
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

class TerminalBackend(
    private val view: TerminalView,
    private val context: Context
) : TerminalSessionClient, TerminalViewClient {

    companion object {
        val splitViews = mutableSetOf<TerminalView>()
    }

    private var ctrlDown = false
    private var altDown = false
    private var shiftDown = false
    private var fnDown = false
    private var fontSize = 14f
    private var invalidatePending = false
    var onSessionFinished: ((TerminalSession) -> Unit)? = null
    var onTap: (() -> Unit)? = null
    var onBellFired: (() -> Unit)? = null
    var onLinkTap: ((String, Boolean) -> Unit)? = null

    override fun onTextChanged(session: TerminalSession) {
        if (invalidatePending) return
        invalidatePending = true
        android.view.Choreographer.getInstance().postFrameCallback {
            invalidatePending = false
            view.invalidate()
        }
    }

    override fun onTitleChanged(session: TerminalSession) {}

    override fun onSessionFinished(session: TerminalSession) {
        onSessionFinished?.invoke(session)
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        if (clip == null) return
        if (Build.VERSION.SDK_INT >= 33) {
            clip.setPrimaryClip(ClipData.newPlainText("terminal", text))
        } else {
            @Suppress("DEPRECATION") clip.setText(text)
        }
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
        val text = if (Build.VERSION.SDK_INT >= 33) {
            clip.primaryClip?.getItemAt(0)?.text
        } else {
            @Suppress("DEPRECATION") clip.text
        } ?: return
        session?.write(text.toString())
    }

    override fun onBell(session: TerminalSession) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("terminal_bell", true)) return
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            if (vibrator != null) {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(150, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION") vibrator.vibrate(150)
                }
            }
        } catch (_: Exception) {
        }
        onBellFired?.invoke()
    }

    override fun onColorsChanged(session: TerminalSession) {}

    override fun onTerminalCursorStateChange(state: Boolean) {}

    override fun getTerminalCursorStyle(): Int? = null

    override fun onScale(scale: Float): Float {
        fontSize = (fontSize * scale).coerceIn(8f, 36f)
        view.setTextSize(fontSize.toInt())
        return fontSize / (fontSize / scale)
    }

    override fun onSingleTapUp(e: MotionEvent) {
        val session = view.mTermSession
        if (session != null && !view.isSelectingText) {
            val link = detectLinkAt(e)
            if (link != null) {
                onLinkTap?.invoke(link.first, link.second)
                return
            }
        }
        view.requestFocus()
        view.post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            if (imm == null) return@post
            val otherActive = splitViews.any { it !== view && imm.isActive(it) }
            if (!otherActive && !imm.isActive(view)) {
                imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        onTap?.invoke()
    }

    private val urlRegex = Regex("""https?://[^\s"'<>()\[\]{}]+|www\.[^\s"'<>()\[\]{}]+""")
    private val pathRegex = Regex("""(?:\.\.?/|~/|/)[^\s"'<>()\[\]{}]+""")

    private fun detectLinkAt(e: MotionEvent): Pair<String, Boolean>? {
        val emu = view.mEmulator ?: return null
        val colRow = try {
            view.getColumnAndRow(e, true)
        } catch (_: Exception) {
            return null
        } ?: return null
        val col = colRow[0]
        val row = colRow[1]
        val buffer = emu.getScreen()
        val internal = try {
            buffer.externalToInternalRow(row)
        } catch (_: Exception) {
            return null
        }
        val terminalRow = try {
            buffer.allocateFullLineIfNecessary(internal)
        } catch (_: Exception) {
            return null
        }
        val line = String(terminalRow.mText, 0, terminalRow.getSpaceUsed())
        for (m in urlRegex.findAll(line)) {
            if (col in m.range) return m.value to false
        }
        for (m in pathRegex.findAll(line)) {
            if (col in m.range) {
                val raw = m.value.trimEnd(' ', ',', ';', ':', ')', '(', '"', '\'', ']', '}', '!', '?', '.')
                if (raw.length >= 2 && (raw.contains('/') || raw.startsWith("~/"))) return raw to true
            }
        }
        return null
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = true
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = true
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        val fKeySequence = when (keyCode) {
            KeyEvent.KEYCODE_F1 -> "\u001bOP"
            KeyEvent.KEYCODE_F2 -> "\u001bOQ"
            KeyEvent.KEYCODE_F3 -> "\u001bOR"
            KeyEvent.KEYCODE_F4 -> "\u001bOS"
            KeyEvent.KEYCODE_F5 -> "\u001b[15~"
            KeyEvent.KEYCODE_F6 -> "\u001b[17~"
            KeyEvent.KEYCODE_F7 -> "\u001b[18~"
            KeyEvent.KEYCODE_F8 -> "\u001b[19~"
            KeyEvent.KEYCODE_F9 -> "\u001b[20~"
            KeyEvent.KEYCODE_F10 -> "\u001b[21~"
            KeyEvent.KEYCODE_F11 -> "\u001b[23~"
            KeyEvent.KEYCODE_F12 -> "\u001b[24~"
            else -> null
        }
        if (fKeySequence != null) {
            session.write(fKeySequence)
            return true
        }
        return false
    }
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = ctrlDown
    override fun readAltKey(): Boolean = altDown
    override fun readShiftKey(): Boolean = shiftDown
    override fun readFnKey(): Boolean = fnDown

    fun setCtrl(v: Boolean) { ctrlDown = v }
    fun setAlt(v: Boolean) { altDown = v }
    fun setShift(v: Boolean) { shiftDown = v }
    fun setFn(v: Boolean) { fnDown = v }

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

    override fun onEmulatorSet() {}

    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "stacktrace", e) }
}