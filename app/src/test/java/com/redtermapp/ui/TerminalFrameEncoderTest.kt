package com.redtermapp.ui

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives a real [TerminalEmulator] with escape sequences and asserts on the frames
 * `TerminalFrameEncoder` produces, so the encoder is checked against the behaviour
 * the screen actually has rather than against an assumption about style words.
 */
class TerminalFrameEncoderTest {

    private class NoOutput : TerminalOutput() {
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

    /**
     * The library's own constructor order, decoded from its bytecode:
     * `(output, columns, rows, cellWidthPixels, cellHeightPixels, transcriptRows, client)`.
     * The pixel sizes only matter for rendering measurements; the emulator itself
     * works in cells.
     */
    private fun newEmulator(columns: Int = 10, rows: Int = 4): TerminalEmulator =
        TerminalEmulator(NoOutput(), columns, rows, 8, 16, null, client)

    private fun feed(emulator: TerminalEmulator, data: String) {
        val bytes = data.toByteArray(Charsets.UTF_8)
        emulator.append(bytes, bytes.size)
    }

    private fun feedBytes(emulator: TerminalEmulator, data: ByteArray) {
        emulator.append(data, data.size)
    }

    private val esc = "\u001b"

    /** The stream between one row's positioning and the next row's. */
    private fun rowLine(frame: String, row: Int, nextRow: Int): String {
        val start = frame.indexOf(esc + "[" + row + ";1H")
        if (start < 0) return ""
        val end = frame.indexOf(esc + "[" + nextRow + ";1H", start)
        return if (end < 0) frame.substring(start + (esc + "[" + row + ";1H").length)
        else frame.substring(start + (esc + "[" + row + ";1H").length, end)
    }

    private fun allocate(emulator: TerminalEmulator, row: Int): com.termux.terminal.TerminalRow {
        val s = emulator.screen
        return s.allocateFullLineIfNecessary(s.externalToInternalRow(row))
    }

    @Test
    fun `empty screen encodes no frame`() {
        assertNull(TerminalFrameEncoder.encode(newEmulator()))
    }

    @Test
    fun `frame restarts from a known state`() {
        val emulator = newEmulator()
        feed(emulator, "\u001b[1;1H\u001b[31mred")
        val frame = TerminalFrameEncoder.encode(emulator)!!
        assertTrue("frame must start with home+clear+reset", frame.startsWith("" + esc + "[H" + esc + "[2J" + esc + "[m"))
    }

    @Test
    fun `text is written at the cell it was fed to`() {
        val emulator = newEmulator()
        feed(emulator, "\u001b[1;3Hhi")
        val frame = TerminalFrameEncoder.encode(emulator)!!
        // The two untouched cells are written as plain spaces; hi must follow them
        // and nothing more may be on the row.
        assertEquals("  hi", rowLine(frame, 1, 2))
    }

    @Test
    fun `moves are rendered, newlines are not`() {
        val emulator = newEmulator()
        feed(emulator, "\u001b[1;1Htop\u001b[2;3Hmiddle")
        val frame = TerminalFrameEncoder.encode(emulator)!!
        // Column 3 is reached through the two untouched cells written before it.
        assertEquals("  middle", rowLine(frame, 2, 3))
        assertFalse("no literal newline may end up in the frame", frame.contains("\n"))
    }

    @Test
    fun `a coloured run carries its SGR codes`() {
        val emulator = newEmulator()
        feed(emulator, "\u001b[1;1H\u001b[31mred\u001b[32mgreen")
        val frame = TerminalFrameEncoder.encode(emulator)!!
        // Deltas from default state: foreground changes, the background was never
        // left default, so only one colour code names each run.
        assertTrue(frame.contains(esc + "[31mred"))
        assertTrue(frame.contains(esc + "[32mgreen"))
    }

    @Test
    fun `bold and underline travel with their run`() {
        val emulator = newEmulator()
        feed(emulator, "\u001b[1;1H\u001b[1;4monthy")
        val frame = TerminalFrameEncoder.encode(emulator)!!
        assertTrue(frame.contains(esc + "[1;4monthy"))
    }

    @Test
    fun `a bright colour is the 90-series code`() {
        val emulator = newEmulator()
        feed(emulator, "\u001b[1;1H\u001b[92mbright")
        val frame = TerminalFrameEncoder.encode(emulator)!!
        assertTrue(frame.contains(esc + "[92mbright"))
    }

    @Test
    fun `a 256-colour index is named directly`() {
        val emulator = newEmulator()
        feed(emulator, "\u001b[1;1H\u001b[38;5;202morange")
        val frame = TerminalFrameEncoder.encode(emulator)!!
        assertTrue(frame.contains(esc + "[38;5;202morange"))
    }

    @Test
    fun `true colour is written as rgb`() {
        val emulator = newEmulator()
        feed(emulator, "\u001b[1;1H\u001b[38;2;12;34;56mrgb")
        val frame = TerminalFrameEncoder.encode(emulator)!!
        assertTrue(frame.contains(esc + "[38;2;12;34;56mrgb"))
    }

    @Test
    fun `a highlighted blank keeps the bar it draws`() {
        val emulator = newEmulator()
        // A reversed search match is often blank: spaces with a highlighted
        // background. Styleless trailing spaces, on the other hand, are geometry.
        feed(emulator, "\u001b[1;1H\u001b[7m   \u001b[mtext   ")
        val frame = TerminalFrameEncoder.encode(emulator)!!
        assertTrue("the inverse run must not be trimmed", frame.contains(esc + "[7m   "))
        assertFalse("default trailing spaces must be dropped", frame.contains("text   "))
    }

    @Test
    fun `a wide character swallows its continuation cell`() {
        val emulator = newEmulator()
        feedBytes(emulator, "\u001b[1;1H中a".toByteArray(Charsets.UTF_8))
        val frame = TerminalFrameEncoder.encode(emulator)!!
        // The runs merge straight through the wide cell: 中 then a with no blank
        // written between them, and no NUL anywhere.
        assertTrue(frame.contains("中"))
        assertFalse(frame.contains("\u0000"))
        assertTrue(frame.contains("中a"))
    }

    @Test
    fun `rows below the last content are skipped`() {
        val emulator = newEmulator(rows = 6)
        feed(emulator, "\u001b[1;1Htop")
        val frame = TerminalFrameEncoder.encode(emulator)!!
        assertFalse(frame.contains(esc + "[2;1H"))
        assertFalse(frame.contains(esc + "[4;1H"))
        assertFalse(frame.contains(esc + "[5;1H"))
        assertFalse(frame.contains(esc + "[6;1H"))
    }

    @Test
    fun `a frame replays into a screen with the same content`() {
        val source = newEmulator(columns = 20, rows = 4)
        feed(
            source,
            "\u001b[1;1H\u001b[1;4mhead\u001b[m line\u001b[2;1H\u001b[31mred\u001b[m tail\u001b[3;1Hthird\u001b[4;18Hend"
        )
        val frame = TerminalFrameEncoder.encode(source)!!
        val replay = newEmulator(columns = 20, rows = 4)
        feedBytes(replay, frame.toByteArray(Charsets.UTF_8))
        assertEquals(
            "replayed text must equal the original",
            source.screen.getTranscriptText(),
            replay.screen.getTranscriptText()
        )
        // And the colours must not settle as defaults: a run's style word records
        // exactly what the cell was drawn with.
        val sourceRow = source.screen.allocateFullLineIfNecessary(source.screen.externalToInternalRow(1))
        val replayRow = replay.screen.allocateFullLineIfNecessary(replay.screen.externalToInternalRow(1))
        assertEquals("red run style must survive the round trip", sourceRow.getStyle(0), replayRow.getStyle(0))
        assertEquals(sourceRow.getStyle(6), replayRow.getStyle(6))
    }

    @Test
    fun `a second frame carries every row the screen has`() {
        val emulator = newEmulator()
        feed(emulator, "\u001b[1;1Hfirst")
        assertNotNull(TerminalFrameEncoder.encode(emulator))
        feed(emulator, "\u001b[2;1Hsecond")
        val frame = TerminalFrameEncoder.encode(emulator)!!
        assertTrue(frame.contains("first"))
        assertTrue(frame.contains(esc + "[2;1Hsecond"))
    }
}
