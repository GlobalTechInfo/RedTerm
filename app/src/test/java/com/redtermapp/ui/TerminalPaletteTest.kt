package com.redtermapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The palette the terminal draws with.
 *
 * Worth testing because the bug it fixes is invisible: a palette that is subtly wrong
 * still renders text, just not legibly, and the only symptom is a screen someone
 * reports as unreadable.
 */
class TerminalPaletteTest {

    private fun ratio(a: Int, b: Int): Double {
        fun lum(c: Int): Double {
            fun f(v: Int): Double {
                val s = v / 255.0
                return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * f((c shr 16) and 0xFF) + 0.7152 * f((c shr 8) and 0xFF) +
                0.0722 * f(c and 0xFF)
        }
        val la = lum(a)
        val lb = lum(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun `the palette has the size the emulator expects`() {
        assertEquals(259, TerminalPalette.build(0xFFCDD6F4.toInt(), 0xFF1E1E2E.toInt()).size)
    }

    /**
     * The one that matters. A light theme with light text is white on white, which is
     * why the terminal looked blank.
     */
    @Test
    fun `text is legible against the background it is given`() {
        for (name in listOf("dark on light", "light on dark", "green on black")) {
            val (fg, bg) = when (name) {
                "dark on light" -> 0xFF1E1E2E.toInt() to 0xFFF5F5F5.toInt()
                "light on dark" -> 0xFFCDD6F4.toInt() to 0xFF1E1E2E.toInt()
                else -> 0xFF33FF33.toInt() to 0xFF000000.toInt()
            }
            val palette = TerminalPalette.build(fg, bg)
            val r = ratio(palette[0], palette[1])
            assertTrue("$name contrast was $r", r > 3.0)
        }
    }

    @Test
    fun `the foreground and background are what was asked for`() {
        val palette = TerminalPalette.build(0xFF112233.toInt(), 0xFF445566.toInt())
        assertEquals(0xFF112233.toInt(), palette[0])
        assertEquals(0xFF445566.toInt(), palette[1])
    }

    /** A cursor in the background's colour would be invisible where it matters. */
    @Test
    fun `the cursor takes the foreground colour`() {
        val palette = TerminalPalette.build(0xFFCDD6F4.toInt(), 0xFF1E1E2E.toInt())
        assertEquals(palette[0], palette[2])
    }

    /** Black and white are the two that must follow the theme, or they vanish. */
    @Test
    fun `basic black and white follow the theme rather than being fixed`() {
        val dark = TerminalPalette.build(0xFFCDD6F4.toInt(), 0xFF1E1E2E.toInt())
        assertEquals(0xFFCDD6F4.toInt(), dark[3])   // black slot -> foreground
        assertEquals(0xFF1E1E2E.toInt(), dark[3 + 7]) // white slot -> background

        val light = TerminalPalette.build(0xFF1E1E2E.toInt(), 0xFFF5F5F5.toInt())
        assertEquals(0xFF1E1E2E.toInt(), light[3])
        assertEquals(0xFFF5F5F5.toInt(), light[3 + 7])
    }

    /**
     * Colour means something in a terminal: red is an error, green is success. A theme
     * that turned them into greys would be pretty and useless.
     */
    @Test
    fun `the recognisable basic colours survive`() {
        val palette = TerminalPalette.build(0xFFCDD6F4.toInt(), 0xFF1E1E2E.toInt())
        // Slot 1 is red, slot 2 green, slot 4 blue.
        val red = palette[3 + 1]
        val green = palette[3 + 2]
        val blue = palette[3 + 4]
        assertTrue("red is not red enough", (red shr 16 and 0xFF) > 100)
        assertTrue("green is not green enough", (green shr 8 and 0xFF) > 100)
        assertTrue("blue is not blue enough", (blue and 0xFF) > 100)
    }

    /** Every entry must be opaque; a zero-alpha entry renders as nothing. */
    @Test
    fun `no palette entry is transparent`() {
        val palette = TerminalPalette.build(0xFF33FF33.toInt(), 0xFF000000.toInt())
        for (i in palette.indices) {
            assertEquals("entry $i", 0xFF, (palette[i] ushr 24) and 0xFF)
        }
    }

    @Test
    fun `every channel stays in range`() {
        val palette = TerminalPalette.build(0xFF33FF33.toInt(), 0xFF000000.toInt())
        for (i in palette.indices) {
            val c = palette[i]
            for (shift in listOf(16, 8, 0)) {
                val v = (c shr shift) and 0xFF
                assertTrue("entry $i shift $shift was $v", v in 0..255)
            }
        }
    }

    /** A theme change has to be visible, or the palette is not being applied. */
    @Test
    fun `a different theme produces a different palette`() {
        val dark = TerminalPalette.build(0xFFCDD6F4.toInt(), 0xFF1E1E2E.toInt())
        val light = TerminalPalette.build(0xFF1E1E2E.toInt(), 0xFFF5F5F5.toInt())
        assertNotEquals(dark[0], light[0])
        assertNotEquals(dark[1], light[1])
        // And the cube moves with them.
        assertNotEquals(dark[3 + 100], light[3 + 100])
    }

    /** The greys must run from the background towards the foreground, not back. */
    @Test
    fun `the greys run from the background towards the foreground`() {
        val fg = 0xFFFFFFFF.toInt()
        val bg = 0xFF000000.toInt()
        val palette = TerminalPalette.build(fg, bg)
        val first = palette[3 + 232]
        val last = palette[3 + 255]
        fun brightness(c: Int) = ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3
        assertTrue("grey run went backwards", brightness(last) > brightness(first))
        assertTrue("first grey is not near the background", abs(brightness(first) - brightness(bg)) < 40)
    }
}