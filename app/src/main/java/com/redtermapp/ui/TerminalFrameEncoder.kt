package com.redtermapp.ui

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalRow
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth

/**
 * Encodes one snapshot of the emulator's visible screen as plain ANSI.
 *
 * The terminal library exposes no hook on what a session *writes* — its byte-level
 * entry point only feeds the process stdin — so a recording is a sequence of screen
 * dumps: every drawn frame is re-encoded from the same `TerminalRow` data the
 * renderer reads, and the player reconstructs it by feeding the ANSI back into an
 * emulator of its own. Colour indexes and attributes travel as SGR, so a file made
 * here replays through whatever palette the playing emulator holds; in this app that
 * is the same theme machinery, so a themed recording stays coherent.
 *
 * The decode side is `TextStyle.decodeForeColor/decodeBackColor/decodeEffect` — the
 * ground truth, checked against its bytecode: a palette index ≤ 255, the default
 * fg/bg sentinels 256/257, and eight attribute bits sharing one effects word with
 * the two truecolour markers. The cell walk mirrors `TerminalRenderer.render`:
 * cells are read through a running `mText` offset (combining marks travel with
 * their base character), a double-width character consumes two columns, and the
 * `0` character in the second half of a wide cell is geometry rather than text.
 *
 * Two stream-shaping rules decide the rest:
 * - **State is tracked, not assumed.** Codes are the delta from the state the last
 *   run left the emulator in. A default run following a coloured one must print an
 *   explicit reset, or the spaces it travels with render in the earlier colour.
 * - **A default space and an unwritten cell paint identically.** Trailing
 *   default-styled spaces are not written (so a whole blank row contributes
 *   nothing), and a blank-default run in the middle of a row is written as bare
 *   spaces — the cursor advance is what those spaces are *for*.
 */
object TerminalFrameEncoder {

    private const val ESC = 27.toChar()
    private const val SPACE = ' '

    // Attribute bits, as `decodeEffect` returns them: the attributes share the
    // effects word with the two truecolour markers, which is why everything needed
    // sits inside one 11-bit mask (2047) rather than in separate fields.
    private const val ATTR_BOLD = TextStyle.CHARACTER_ATTRIBUTE_BOLD
    private const val ATTR_ITALIC = TextStyle.CHARACTER_ATTRIBUTE_ITALIC
    private const val ATTR_UNDERLINE = TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE
    private const val ATTR_BLINK = TextStyle.CHARACTER_ATTRIBUTE_BLINK
    private const val ATTR_INVERSE = TextStyle.CHARACTER_ATTRIBUTE_INVERSE
    private const val ATTR_INVISIBLE = TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE
    private const val ATTR_STRIKETHROUGH = TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH
    private const val ATTR_DIM = 1 shl 8
    private const val EFFECT_TRUECOLOR_FG = 1 shl 9
    private const val EFFECT_TRUECOLOR_BG = 1 shl 10

    /** SGR word for each attribute bit, in canonical enable order. */
    private val attributeWords = arrayOf(
        intArrayOf(ATTR_BOLD, 1),
        intArrayOf(ATTR_DIM, 2),
        intArrayOf(ATTR_ITALIC, 3),
        intArrayOf(ATTR_UNDERLINE, 4),
        intArrayOf(ATTR_BLINK, 5),
        intArrayOf(ATTR_INVERSE, 7),
        intArrayOf(ATTR_INVISIBLE, 8),
        intArrayOf(ATTR_STRIKETHROUGH, 9)
    )

    private const val FG_DEFAULT = "39"
    private const val BG_DEFAULT = "49"

    /** Default-foreground sentinel index inside a style word. */
    private const val COLOR_INDEX_FOREGROUND = 256
    /** Default-background sentinel index inside a style word. */
    private const val COLOR_INDEX_BACKGROUND = 257

    /** What one run asks the emulator to be. */
    private data class Shape(val fg: String, val bg: String, val attrs: Int)

    private class Run(val shape: Shape, val text: StringBuilder) {
        /** Everything default and nothing but spaces: pure cursor travel. */
        fun isBlankDefault(): Boolean {
            if (shape.fg != FG_DEFAULT || shape.bg != BG_DEFAULT || shape.attrs != 0) return false
            for (i in 0 until text.length) if (text[i] != SPACE) return false
            return true
        }
    }

    /** The frame's pending SGR state, and the stream it is being written into. */
    private class Writer {
        val stream = StringBuilder()
        var fg = FG_DEFAULT
        var bg = BG_DEFAULT
        var attrs = 0  // attribute bit mask actually in effect

        private fun isDefault(): Boolean =
            fg == FG_DEFAULT && bg == BG_DEFAULT && attrs == 0

        /** State after emitting [codes] for [want]: `want` exactly. */
        fun settle(want: Shape) { fg = want.fg; bg = want.bg; attrs = want.attrs }

        fun travel(count: Int) {
            if (!isDefault()) {
                // Blank-default travel landed in the middle of a coloured run's
                // aftermath: without the reset the spaces render in that colour.
                stream.append(ESC).append("[0m")
                fg = FG_DEFAULT
                bg = BG_DEFAULT
                attrs = 0
            }
            repeat(count) { stream.append(SPACE) }
        }
    }

    /**
     * One frame, or null when the screen has no content at all.
     *
     * Rows are positioned (`ESC n;1H`) rather than walked line by line, so the
     * output is independent of any viewer's wrap handling, and the frame opens
     * with home + clear + reset so replay begins from known default state.
     */
    fun encode(emulator: TerminalEmulator): String? {
        val screen = emulator.getScreen()
        val writer = Writer()
        var lastContentRow = -1
        for (row in 0 until emulator.mRows) {
            val runs = buildRuns(screen, screen.externalToInternalRow(row), emulator.mColumns) ?: continue
            writer.stream.append(ESC).append('[').append(row + 1).append(";1H")
            for (run in runs) {
                if (run.isBlankDefault()) {
                    writer.travel(run.text.length)
                    continue
                }
                val codes = codesBetween(writer, run.shape)
                if (codes.isNotEmpty()) {
                    writer.stream.append(ESC).append('[')
                    codes.forEachIndexed { index, code ->
                        if (index > 0) writer.stream.append(';')
                        writer.stream.append(code)
                    }
                    writer.stream.append('m')
                }
                writer.settle(run.shape)
                writer.stream.append(run.text)
            }
            lastContentRow = row
        }
        if (lastContentRow < 0) return null
        val frame = StringBuilder(writer.stream.length + 12)
        frame.append(ESC).append("[H").append(ESC).append("[2J").append(ESC).append("[m")
        frame.append(writer.stream)
        return frame.toString()
    }

    /** The codes that move the writer's current state into [want]. */
    private fun codesBetween(writer: Writer, want: Shape): List<String> {
        if (writer.fg == want.fg && writer.bg == want.bg && writer.attrs == want.attrs) {
            return emptyList()
        }
        val codes = ArrayList<String>(4)
        val removed = writer.attrs and want.attrs.inv()
        if (removed != 0) {
            // Attributes cannot be switched off singly in SGR: reset everything,
            // then list exactly what this run does want.
            codes.add("0")
            if (want.fg != FG_DEFAULT) codes.add(want.fg)
            if (want.bg != BG_DEFAULT) codes.add(want.bg)
            for (word in attributeWords) {
                if (want.attrs and word[0] != 0) codes.add(word[1].toString())
            }
        } else {
            if (want.fg != writer.fg) codes.add(want.fg)
            if (want.bg != writer.bg) codes.add(want.bg)
            val added = want.attrs and writer.attrs.inv()
            for (word in attributeWords) {
                if (added and word[0] != 0) codes.add(word[1].toString())
            }
        }
        return codes
    }

    /**
     * The row's runs with trailing default-styled blanks resolved away, or null
     * when nothing on it is worth writing.
     */
    private fun buildRuns(
        screen: com.termux.terminal.TerminalBuffer,
        internalRow: Int,
        columns: Int
    ): List<Run>? {
        val row: TerminalRow = screen.allocateFullLineIfNecessary(internalRow)
        val text = row.mText
        val runs = ArrayList<Run>(columns / 4 + 1)
        var offset = 0
        var written = 0
        while (written < columns && offset < text.size) {
            val c = text[offset]
            val charLen = if (Character.isHighSurrogate(c)) 2 else 1
            val codePoint = if (charLen == 2 && offset + 1 < text.size) {
                Character.toCodePoint(c, text[offset + 1])
            } else {
                c.code
            }
            val style = row.getStyle(written)
            if (codePoint == 0) {
                // Second half of a wide cell: geometry, no text of its own.
                offset += 1
                written += 1
                continue
            }
            val cell = StringBuilder(4)
            cell.appendCodePoint(codePoint)
            var end = offset + charLen
            // Zero-width characters stored after the base belong to this cell.
            while (end < text.size && WcWidth.width(text[end].code) <= 0) {
                cell.append(text[end])
                end++
            }
            offset = end
            written += maxOf(1, WcWidth.width(codePoint))
            if (TextStyle.decodeEffect(style) and ATTR_INVISIBLE != 0) continue
            val shape = shapeOf(style)
            val last = runs.lastOrNull()
            if (last != null && last.shape == shape) {
                last.text.append(cell)
            } else {
                runs.add(Run(shape, cell))
            }
        }

        // Trailing cells are left as the clear made them: whole trailing blank
        // default runs go, then a trailing default run's unused spaces.
        while (runs.isNotEmpty() && runs.last().isBlankDefault()) runs.removeAt(runs.size - 1)
        runs.lastOrNull()?.let { last ->
            if (isDefaultShape(last.shape)) {
                var len = last.text.length
                while (len > 0 && last.text[len - 1] == SPACE) len--
                last.text.setLength(len)
            }
        }
        return if (runs.isEmpty()) null else runs
    }

    /** Everything a style word says, as the three things one run's SGR would set. */
    private fun shapeOf(style: Long): Shape {
        val effects = TextStyle.decodeEffect(style)
        val attrs = run {
            var mask = 0
            for (word in attributeWords) if (effects and word[0] != 0) mask = mask or word[0]
            mask
        }
        return Shape(foregroundCode(style, effects), backgroundCode(style, effects), attrs)
    }

    private fun isDefaultShape(shape: Shape): Boolean =
        shape.fg == FG_DEFAULT && shape.bg == BG_DEFAULT && shape.attrs == 0

    /** Foreground half of a style word as an SGR code ("31", "38;5;202", ...). */
    private fun foregroundCode(style: Long, effects: Int): String {
        if (effects and EFFECT_TRUECOLOR_FG != 0) {
            val rgb = TextStyle.decodeForeColor(style) and 0xFFFFFF
            return "38;2;${(rgb shr 16) and 0xFF};${(rgb shr 8) and 0xFF};${rgb and 0xFF}"
        }
        val index = TextStyle.decodeForeColor(style)
        // 256 and above name the emulator's own foreground/background/cursor
        // sentinels rather than palette entries; they replay as the default colour.
        if (index >= COLOR_INDEX_FOREGROUND) return FG_DEFAULT
        return if (index < 8) (30 + index).toString()
        else if (index < 16) (82 + index).toString()
        else "38;5;$index"
    }

    private fun backgroundCode(style: Long, effects: Int): String {
        if (effects and EFFECT_TRUECOLOR_BG != 0) {
            val rgb = TextStyle.decodeBackColor(style) and 0xFFFFFF
            return "48;2;${(rgb shr 16) and 0xFF};${(rgb shr 8) and 0xFF};${rgb and 0xFF}"
        }
        val index = TextStyle.decodeBackColor(style)
        if (index >= COLOR_INDEX_BACKGROUND) return BG_DEFAULT
        return if (index < 8) (40 + index).toString()
        else if (index < 16) (92 + index).toString()
        else "48;5;$index"
    }
}
