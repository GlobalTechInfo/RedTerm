package com.redtermapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a row of buttons breaks across lines.
 *
 * Worth testing on its own because this is the rule that decides whether a label sits
 * on one line or stacks down the face of a button, and that is not checkable by
 * looking at the built view: the same code renders plausibly and wrongly at
 * different screen widths.
 */
class FlowLayoutTest {

    private val gap = 8

    /** Everything fits: one line, which is the case that used to be assumed always. */
    @Test
    fun `buttons that fit share one line`() {
        val lines = FlowLayout.planLines(listOf(100, 100, 100, 100), available = 500)
        assertEquals(1, lines.size)
        assertEquals(listOf(0, 1, 2, 3), lines.first())
    }

    /** The failure in the screenshot: four labels, a phone's width, one line each. */
    @Test
    fun `buttons that do not fit wrap instead of being crushed`() {
        val lines = FlowLayout.planLines(listOf(100, 100, 100, 100), available = 250)
        assertTrue("expected more than one line, got $lines", lines.size > 1)
        assertEquals(listOf(0, 1), lines[0])
        assertEquals(listOf(2, 3), lines[1])
    }

    @Test
    fun `an exact fit is still one line`() {
        // 100 + 8 + 100 + 8 + 100 = 316
        assertEquals(1, FlowLayout.planLines(listOf(100, 100, 100), 316).size)
        assertEquals(2, FlowLayout.planLines(listOf(100, 100, 100), 315).size)
    }

    /** No leading gap: the first button starts at the padding, not 8px in. */
    @Test
    fun `the first child pays no leading gap`() {
        val lines = FlowLayout.planLines(listOf(100), available = 100)
        assertEquals(1, lines.size)
        assertEquals(listOf(0), lines.first())
    }

    @Test
    fun `every child appears exactly once`() {
        val widths = listOf(120, 90, 140, 80, 100, 70, 130)
        val lines = FlowLayout.planLines(widths, available = 250)
        assertEquals(widths.indices.toList(), lines.flatten())
    }

    /**
     * A label wider than the whole row gets a line of its own and is ellipsized by
     * its own maxLines. Dropping it would leave a button with no label at all, which
     * is worse than a truncated one.
     */
    @Test
    fun `an oversized child keeps its own line`() {
        val lines = FlowLayout.planLines(listOf(50, 900, 60), available = 400)
        assertEquals(3, lines.size)
        assertEquals(listOf(1), lines[1])
    }

    @Test
    fun `no buttons means no lines`() {
        assertTrue(FlowLayout.planLines(emptyList(), available = 400).isEmpty())
    }

    @Test
    fun `a single button is one line however narrow`() {
        assertEquals(1, FlowLayout.planLines(listOf(30), available = 10).size)
    }

    /** A very narrow screen still lays out, rather than looping or dropping children. */
    @Test
    fun `nothing is lost on a very narrow row`() {
        val widths = listOf(200, 210, 220, 230)
        val lines = FlowLayout.planLines(widths, available = 50)
        assertEquals(widths.indices.toList(), lines.flatten())
    }

    /**
     * The height a row reports has to be at least as tall as the buttons in it.
     *
     * A single line of 100px buttons must come out 100px, not less. It did come out
     * less: a gap was subtracted from a total that had never had one added, so every
     * row of buttons was exactly one gap shorter than its own contents and the labels
     * were sliced horizontally in half.
     */
    @Test
    fun `one line is exactly as tall as its tallest button`() {
        assertEquals(100, FlowLayout.totalHeight(sumOfTallest = 100, lineCount = 1, gap = 24))
    }

    @Test
    fun `no gap is added above the first or below the last line`() {
        // Two lines of 100 with a 24 gap between them: 224, not 272.
        assertEquals(224, FlowLayout.totalHeight(sumOfTallest = 200, lineCount = 2, gap = 24))
        assertEquals(
            320,
            FlowLayout.totalHeight(sumOfTallest = 272, lineCount = 3, gap = 24)
        )
    }

    @Test
    fun `no lines means no height`() {
        assertEquals(0, FlowLayout.totalHeight(sumOfTallest = 0, lineCount = 0, gap = 24))
    }

    /**
     * The whole point of the row: however the buttons wrapped, the height is never
     * less than the tallest one, so nothing is ever clipped.
     */
    @Test
    fun `wrapped rows are never shorter than a single button`() {
        val buttonHeight = 96
        for (count in 1..8) {
            val widths = List(count) { 120 }
            val lines = FlowLayout.planLines(widths, available = 300)
            val height = FlowLayout.totalHeight(
                sumOfTallest = lines.size * buttonHeight,
                lineCount = lines.size,
                gap = 24
            )
            assertTrue("count=$count height=$height", height >= buttonHeight)
        }
    }

    /** The gap is real, so three 100s need 316 rather than 300. */
    @Test
    fun `the gap between children is counted`() {
        val exactly = 100 + gap + 100 + gap + 100
        assertEquals(1, FlowLayout.planLines(listOf(100, 100, 100), exactly).size)
        assertEquals(2, FlowLayout.planLines(listOf(100, 100, 100), exactly - 1).size)
    }
}