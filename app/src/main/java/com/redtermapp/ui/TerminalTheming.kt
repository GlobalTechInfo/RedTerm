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
