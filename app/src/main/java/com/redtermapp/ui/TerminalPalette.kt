package com.redtermapp.ui

import androidx.annotation.ColorInt
import kotlin.math.roundToInt

/**
 * Builds the 259-entry palette a terminal session draws with.
 *
 * The terminal is the one part of the app that used to ignore the theme. Its
 * background was themed at runtime but its glyphs were not, because the colour the
 * glyphs use lives in the session's palette rather than anywhere the layout can reach
 * — so the light theme put a light background under text that stayed light, and every
 * theme's terminal looked like the default one.
 *
 * Layout, as the emulator expects it:
 * `[0]` foreground, `[1]` background, `[2]` cursor, then `[3 + i]` for each of the 256
 * xterm entries — 16 basic, a 6×6×6 cube, then 24 greys.
 *
 * The 16 basic colours keep their recognisable hues, because output colour is meaning
 * (`ls`, `grep --color`, `git diff`) and a theme must not make red stop meaning red.
 * The two that would otherwise clash with the background — black and white — are taken
 * from the theme instead. The cube and the greys are interpolated between the theme's
 * two foregrounds, so anything a program picks lands on the theme rather than fighting
 * it.
 *
 * Pure, so the arithmetic is tested rather than checked by eye.
 */
object TerminalPalette {

    const val SIZE = 259
    private const val BASIC = 16
    private const val CUBE_BASE = 16
    private const val CUBE_STEPS = 6
    private const val GREY_BASE = 232
    private const val GREY_COUNT = 24

    /** The 16 standard xterm colours, in order. */
    private val BASIC_16 = intArrayOf(
        0xFF000000.toInt(), 0xFFAA0000.toInt(), 0xFF00AA00.toInt(), 0xFFAA5500.toInt(),
        0xFF0000AA.toInt(), 0xFFAA00AA.toInt(), 0xFF00AAAA.toInt(), 0xFFAAAAAA.toInt(),
        0xFF555555.toInt(), 0xFFFF5555.toInt(), 0xFF55FF55.toInt(), 0xFFFFAA00.toInt(),
        0xFF5555FF.toInt(), 0xFFFF55FF.toInt(), 0xFF55FFFF.toInt(), 0xFFFFFFFF.toInt()
    )

    /** The cube's six levels per channel, xterm's own values. */
    private val CUBE_LEVELS = intArrayOf(0, 95, 135, 175, 215, 255)

    fun build(@ColorInt foreground: Int, @ColorInt background: Int): IntArray {
        val palette = IntArray(SIZE)
        palette[0] = foreground
        palette[1] = background
        // The cursor follows the foreground so it is visible on the theme's
        // background, which is the whole reason the text colour was being applied.
        palette[2] = foreground

        for (i in 0 until BASIC) {
            palette[3 + i] = when (i) {
                // Black and white are the two that would otherwise disappear into or
                // glare against the background; the theme decides those instead.
                0 -> foreground
                7 -> background
                8 -> dim(foreground)
                15 -> foreground
                else -> BASIC_16[i]
            }
        }

        for (i in 0 until GREY_COUNT) {
            palette[3 + GREY_BASE + i] =
                mix(background, foreground, (i + 1f) / (GREY_COUNT + 1))
        }

        var entry = 0
        for (r in 0 until CUBE_STEPS) {
            for (g in 0 until CUBE_STEPS) {
                for (b in 0 until CUBE_STEPS) {
                    palette[3 + CUBE_BASE + entry++] = blend(
                        background,
                        CUBE_LEVELS[r], CUBE_LEVELS[g], CUBE_LEVELS[b]
                    )
                }
            }
        }
        return palette
    }

    /** A colour halfway to black, for the "bright black" slot. */
    private fun dim(@ColorInt color: Int): Int = scale(color, 0.6f)

    private fun scale(@ColorInt color: Int, factor: Float): Int =
        argb((red(color) * factor).roundToInt(), (green(color) * factor).roundToInt(),
            (blue(color) * factor).roundToInt())

    private fun mix(@ColorInt from: Int, @ColorInt to: Int, amount: Float): Int = argb(
        lerp(red(from), red(to), amount),
        lerp(green(from), green(to), amount),
        lerp(blue(from), blue(to), amount)
    )

    /**
     * A cube entry: the channel's own colour where the theme has room for it, pulled
     * toward the background so a low level does not glare.
     */
    private fun blend(@ColorInt background: Int, r: Int, g: Int, b: Int): Int =
        argb(mixChannel(background, r), mixChannel(background, g), mixChannel(background, b))

    /**
     * One channel of a cube entry, lifted off the theme's own background.
     *
     * The low end of the cube sits near the background rather than at black, so the
     * darkest colours a program picks do not glow against a dark theme. Using the
     * background's actual channel matters: an earlier version took the background as a
     * parameter and then ignored it, which made every theme produce an identical cube
     * and the theme invisible in all 216 of those entries.
     */
    private fun mixChannel(@ColorInt background: Int, level: Int): Int {
        val base = level / 255f
        val from = channel(background)
        return lerp(from, level, 0.4f + 0.6f * base)
    }

    private fun channel(@ColorInt background: Int): Int =
        (red(background) + green(background) + blue(background)) / 3

    private fun lerp(from: Int, to: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        return (from + (to - from) * t).roundToInt().coerceIn(0, 255)
    }

    /** Always opaque: a palette entry with alpha renders as nothing at all. */
    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun red(@ColorInt color: Int): Int = (color shr 16) and 0xFF
    private fun green(@ColorInt color: Int): Int = (color shr 8) and 0xFF
    private fun blue(@ColorInt color: Int): Int = color and 0xFF
}