package com.redtermapp.distro

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `.bashrc` the app writes into a distribution that has none of its own.
 *
 * A distribution that ships its own `.bashrc` is left alone — Debian, Alpine and the
 * rest know how to prompt on their own, and overwriting one is how a user loses their
 * customisation.
 */
class ShellConfigTest {

    /**
     * No colour escape in the prompt, ever.
     *
     * `\e[` followed by digits ending in `m` is an SGR sequence. Any parameter at or
     * above 30 selects a palette slot, and a fixed slot is legible in one theme and gone
     * in the next — measured across the ten themes a bold blue ran from 1.06:1 to
     * 12.19:1, and a bold green did the opposite, dropping to 1.22:1 on the light one. A
     * prompt that chooses its own colour is invisible in most themes; one that inherits
     * the terminal's foreground follows whichever theme is selected, including a theme
     * changed while the session is open.
     */
    @Test
    fun `the prompt contains no colour escapes`() {
        val sgr = Regex("""\\e\[([0-9;]*)m""")
        for (match in sgr.findAll(ShellConfig.PS1)) {
            for (parameter in match.groupValues[1].split(';').filter { it.isNotEmpty() }) {
                val value = parameter.toIntOrNull() ?: 0
                assertTrue(
                    "the prompt sets colour $value in: ${ShellConfig.PS1}",
                    value < 30
                )
            }
        }
    }

    /** Bold is fine — it resolves to the bright slot, which is the same foreground. */
    @Test
    fun `the prompt may still use bold`() {
        val boldOnly = Regex("""\\e\[1m""")
        assertTrue(boldOnly.containsMatchIn(ShellConfig.PS1))
    }

    /** Nothing about the prompt may depend on the terminal being able to do colour. */
    @Test
    fun `the prompt still shows the user, the host and the path without colour`() {
        assertTrue(ShellConfig.PS1.contains("\\u"))
        assertTrue(ShellConfig.PS1.contains("\\h"))
        assertTrue(ShellConfig.PS1.contains("\\w"))
    }

    @Test
    fun `the generated file carries the marker that identifies it as ours`() {
        assertTrue(ShellConfig.MARKER in ShellConfig.bashrc())
    }

    @Test
    fun `the generated file sets the prompt`() {
        assertTrue(ShellConfig.bashrc().contains("PS1="))
    }

    // ------------------------------------------------------- what may be replaced

    @Test
    fun `nothing there means there is nothing to protect`() {
        assertTrue(ShellConfig.shouldWrite(null))
    }

    /** Ours, so it may be refreshed — otherwise a fixed prompt never reaches an install. */
    @Test
    fun `our own file is refreshed`() {
        assertTrue(ShellConfig.shouldWrite(ShellConfig.bashrc()))
    }

    /** An older version of ours, written before a fix, also carries the marker. */
    @Test
    fun `an older file of ours is refreshed`() {
        val older = "# ~/.bashrc\n# managed by RedTerm\nPS1='hardcoded colours'\n"
        assertTrue(ShellConfig.shouldWrite(older))
    }

    /**
     * The one that must never change. A hand-written `.bashrc` belongs to the user, and
     * replacing it with ours is how a person's terminal gets silently rewritten.
     */
    @Test
    fun `a file the user wrote is never touched`() {
        assertFalse(ShellConfig.shouldWrite("export PS1='mine'\nalias l='ls -la'\n"))
    }

    /** A distribution's own file has no marker and must survive. */
    @Test
    fun `a distro's own bashrc is never touched`() {
        val debian = "# ~/.bashrc\n" +
            "export PATH=\$PATH:/usr/local/bin\n" +
            "if [ -f /etc/bash_completion.d/* ]; then . /etc/bash_completion.d/*; fi\n"
        assertFalse(ShellConfig.shouldWrite(debian))
    }

    @Test
    fun `an empty file is left alone, because it is not absent`() {
        // An empty file carries no marker, so it is treated as somebody's and left
        // alone: absence is what means "nothing to protect".
        assertFalse(ShellConfig.shouldWrite(""))
    }
}
