package com.redtermapp.ui

import android.content.Context
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

    private var ctrlDown = false
    private var altDown = false
    private var shiftDown = false
    private var fnDown = false
    private var fontSize = 14f
    var onSessionFinished: ((TerminalSession) -> Unit)? = null

    override fun onTextChanged(session: TerminalSession) {
        view.invalidate()
    }

    override fun onTitleChanged(session: TerminalSession) {}

    override fun onSessionFinished(session: TerminalSession) {
        onSessionFinished?.invoke(session)
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        clip?.setText(text)
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val text = clip?.text ?: return
        session?.write(text.toString())
    }

    override fun onBell(session: TerminalSession) {}

    override fun onColorsChanged(session: TerminalSession) {}

    override fun onTerminalCursorStateChange(state: Boolean) {}

    override fun getTerminalCursorStyle(): Int? = null

    override fun onScale(scale: Float): Float {
        fontSize = (fontSize * scale).coerceIn(8f, 36f)
        view.setTextSize(fontSize.toInt())
        return fontSize / (fontSize / scale)
    }

    override fun onSingleTapUp(e: MotionEvent) {
        view.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = true
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = true
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
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