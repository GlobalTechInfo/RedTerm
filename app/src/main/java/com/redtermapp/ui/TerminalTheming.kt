package com.redtermapp.ui

import android.content.Context
import android.graphics.Typeface
import com.redtermapp.R
import com.termux.view.TerminalView
import java.io.File

/**
 * The terminal's look — font and palette — applied from themes and preferences
 * from one place, for every surface that shows a terminal view: sessions and
 * split panes, and the recording player.
 *
 * The font helpers used to live next to TerminalActivity's session plumbing, and
 * the player was left setting neither font nor palette: a view with no renderer
 * crashes on its first layout (TerminalView's renderer is created by
 * `setTextSize` and `setTypeface` and nothing else, and `updateSize` reads the
 * renderer's font width guarded only on width, height and session), and a replay
 * drew in the library's default palette whatever the app was themed to.
 */
object TerminalTheming {

    private val fontCache = HashMap<String, Typeface?>()

    /**
     * The two passengers every headless [com.termux.terminal.TerminalEmulator]
     * needs: an output that echoes nothing, and a session client that receives
     * nothing. Shared by the player and the GIF exporter, so a new terminal
     * surface starts from the shared pair instead of a copy that drifts.
     */
    val headlessOutput = object : com.termux.terminal.TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {}
        override fun titleChanged(title: String?, subtitle: String?) {}
        override fun onCopyTextToClipboard(text: String?) {}
        override fun onPasteTextFromClipboard() {}
        override fun onBell() {}
        override fun onColorsChanged() {}
    }

    val headlessClient = object : com.termux.terminal.TerminalSessionClient {
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

    /** The theme's terminal foreground and background, for the paths that are not a theme switch. */
    fun themedColours(activity: android.app.Activity): Pair<Int, Int> {
        fun color(attr: Int, default: Int): Int {
            val ta = activity.theme.obtainStyledAttributes(intArrayOf(attr))
            val c = ta.getColor(0, default)
            ta.recycle()
            return c
        }
        return Pair(
            color(R.attr.terminalText, 0xFFCDD6F4.toInt()),
            color(R.attr.terminalBg, 0xFF1E1E2E.toInt())
        )
    }

    /**
     * Puts the current theme's palette onto [views].
     *
     * The view's background is not enough on its own: a session draws its text with
     * colours held in its own palette inside the terminal library, and nothing the
     * layout sets reaches them. Applied after every attach rather than once,
     * because a palette belongs to a session: switching to one that was started
     * before the theme changed would otherwise keep the colours it launched with.
     */
    fun applyTerminalColours(
        foreground: Int,
        background: Int,
        vararg views: TerminalView
    ) {
        for (view in views) {
            view.setBackgroundColor(background)
            val emulator = view.mEmulator ?: continue
            applyPalette(foreground, background, emulator)
            view.mTermSession?.onColorsChanged()
            view.onScreenUpdated()
        }
    }

    /** The 259-colour palette the emulator draws text with, built from a theme's two colours. */
    fun applyPalette(
        foreground: Int,
        background: Int,
        emulator: com.termux.terminal.TerminalEmulator
    ) {
        val palette = TerminalPalette.build(foreground, background)
        val current = emulator.mColors.mCurrentColors
        val shared = minOf(current.size, palette.size, TerminalPalette.SIZE)
        for (i in 0 until shared) current[i] = palette[i]
    }

    fun fontFromPrefs(context: Context, prefs: android.content.SharedPreferences): Typeface {
        val fontName = prefs.getString("font", "monospace")
        val tf = when {
            fontName != null && fontName.startsWith("custom:") ->
                try {
                    Typeface.createFromFile(
                        File(context.filesDir, "fonts/${fontName.removePrefix("custom:")}")
                    )
                } catch (_: Exception) {
                    null
                }
            else -> when (fontName) {
                "JetBrains Mono" -> loadFont(context, "fonts/JetBrainsMono.ttf")
                "Fira Code" -> loadFont(context, "fonts/FiraCode.ttf")
                "Source Code Pro" -> loadFont(context, "fonts/SourceCodePro.ttf")
                "Ubuntu Mono" -> loadFont(context, "fonts/UbuntuMono.ttf")
                "Droid Sans Mono" -> loadFont(context, "fonts/DroidSansMono.ttf")
                "Noto Sans Mono" -> loadFont(context, "fonts/NotoSansMono.ttf")
                "Cascadia Code" -> loadFont(context, "fonts/CascadiaCode.ttf")
                else -> Typeface.MONOSPACE
            }
        }
        return tf ?: Typeface.MONOSPACE
    }

    private fun loadFont(context: Context, assetPath: String): Typeface? =
        fontCache.getOrPut(assetPath) {
            try {
                Typeface.createFromAsset(context.assets, assetPath)
            } catch (_: Exception) {
                null
            }
        }
}
