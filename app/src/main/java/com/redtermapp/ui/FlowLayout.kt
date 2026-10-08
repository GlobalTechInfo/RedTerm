package com.redtermapp.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * A row of buttons that wraps onto as many lines as it needs.
 *
 * Buttons in a horizontal row with `weight = 1` each are the obvious way to lay this
 * out and it fails on a phone: the width is divided before anything is measured, so a
 * four-word label gets a quarter of the screen and wraps to one word per line. The
 * result is a column of ovals with text stacked down them, which is what four equal
 * weights on a narrow screen produces every time.
 *
 * So children keep their natural width here and the row breaks instead. The breaking
 * is decided by [planLines], which takes only numbers and is tested directly.
 */
class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    /** Index of each child, grouped by the line it sits on. */
    private var lines: List<List<Int>> = emptyList()

    /**
     * Measured widths, one slot per child, allocated once.
     *
     * Fixed size rather than grown: measure runs on every layout, so anything
     * allocated here is allocated per frame on a screen that re-measures while
     * scrolling. A row of buttons has a handful of children, so a generous fixed
     * buffer is never the limit in practice, and a child past the end is left
     * unmeasured rather than crashing the layout pass.
     */
    private val widths = IntArray(MAX_CHILDREN)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val count = minOf(childCount, MAX_CHILDREN)
        // Children are measured against the row, not a share of it, and with a real
        // height rather than UNSPECIFIED. A MaterialButton measured with an
        // unbounded height reports less than it actually needs — which is how a row
        // of buttons ended up sliced horizontally through the middle of its labels.
        val childHeightSpec = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        } else {
            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(heightMeasureSpec), MeasureSpec.AT_MOST)
        }
        for (i in 0 until count) {
            val child = getChildAt(i)
            child.measure(
                MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
                childHeightSpec
            )
            widths[i] = child.measuredWidth
        }
        lines = planLinesFrom(widths, count, available, horizontalGap())

        // One pass, no lambdas: this runs on every measure, and a collection
        // expression here allocates per line, per pass, on every frame the row is
        // re-measured.
        var widest = 0
        var lineHeights = 0
        var lineCount = 0
        for (line in lines) {
            var lineWidth = 0
            var tallest = 0
            var placed = 0
            for (index in line) {
                if (placed > 0) lineWidth += horizontalGap()
                lineWidth += widths[index]
                val childHeight = getChildAt(index).measuredHeight
                if (childHeight > tallest) tallest = childHeight
                placed++
            }
            if (lineWidth > widest) widest = lineWidth
            lineHeights += tallest
            lineCount++
        }
        // Gaps sit *between* lines, so one line has none. Getting this wrong by
        // subtracting a gap that was never added made a single row of buttons
        // shorter than the buttons in it, and they were sliced in half.
        val desiredHeight = totalHeight(lineHeights, lineCount, verticalGap()) +
            paddingTop + paddingBottom
        // Reported as its own height, clamped to what the parent allows. Handing back
        // the spec size instead made a wrap-content row claim the whole of whatever it
        // was given, so in a scrolling list it appeared immediately after the content
        // rather than at the bottom of the screen.
        val resolvedHeight = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(desiredHeight, MeasureSpec.getSize(heightMeasureSpec))
            else -> desiredHeight
        }
        setMeasuredDimension(
            resolveSize(widest + paddingLeft + paddingRight, widthMeasureSpec),
            resolvedHeight
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var y = paddingTop
        for (line in lines) {
            var x = paddingLeft
            var tallest = 0
            for (index in line) {
                val child = getChildAt(index)
                child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
                x += child.measuredWidth + horizontalGap()
                tallest = maxOf(tallest, child.measuredHeight)
            }
            y += tallest + verticalGap()
        }
    }

    private fun horizontalGap() = spacing * 2
    private fun verticalGap() = spacing * 3

    private val spacing = (8 * context.resources.displayMetrics.density).toInt()

    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams =
        LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: AttributeSet): ViewGroup.LayoutParams =
        LayoutParams(context, attrs)

    override fun generateLayoutParams(p: ViewGroup.LayoutParams?): ViewGroup.LayoutParams =
        LayoutParams(p)

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean =
        p is LinearLayout.LayoutParams

    /**
     * [LinearLayout.LayoutParams], so callers can pass width and margins as usual.
     *
     * Weight is deliberately ignored. Every button in these rows needs its own width
     * for its own label, and equal weights are what squeezed four labels into a
     * quarter of the screen each in the first place.
     */
    class LayoutParams : LinearLayout.LayoutParams {
        constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

        constructor(p: ViewGroup.LayoutParams?) : super(p)

        constructor(width: Int, height: Int) : super(width, height)
    }

    companion object {
        /** Upper bound on buttons in one row. See [widths]. */
        const val MAX_CHILDREN = 32

        /**
         * Height of [lines], given the total of their tallest children.
         *
         * A gap goes between lines and never above the first or below the last. Pure,
         * because this arithmetic is what decides whether a row of buttons is tall
         * enough to show its own labels, and getting it wrong is invisible in review
         * and obvious on a phone.
         */
        fun totalHeight(sumOfTallest: Int, lineCount: Int, gap: Int): Int {
            if (lineCount <= 0) return 0
            return sumOfTallest + gap * (lineCount - 1)
        }

        /**
         * The array form of [planLines], so the layout pass needs no collection.
         *
         * The list form below is the readable one and is what the tests exercise;
         * this exists only so nothing allocates inside measure.
         */
        internal fun planLinesFrom(
            widths: IntArray,
            count: Int,
            available: Int,
            gap: Int = GAP
        ): List<List<Int>> {
            if (count <= 0) return emptyList()
            val lines = mutableListOf<List<Int>>()
            var current = mutableListOf<Int>()
            var used = 0
            for (index in 0 until count) {
                val width = widths[index]
                val withGap = if (current.isEmpty()) width else used + gap + width
                if (current.isNotEmpty() && withGap > available) {
                    lines.add(current)
                    current = mutableListOf(index)
                    used = width
                } else {
                    current.add(index)
                    used = withGap
                }
            }
            if (current.isNotEmpty()) lines.add(current)
            return lines
        }

        /** Gap between two buttons on one line, in the same unit as [planLines]. */
        const val GAP = 8

        /**
         * Which children share a line, given their widths and the space available.
         *
         * Pure, because this is the part that decides whether a label ends up on one
         * line or stacked down a button, and that is not checkable by looking at the
         * view afterwards.
         *
         * A child wider than the whole row still gets a line to itself rather than
         * being dropped: it is then ellipsized, which is legible, where dropping it
         * would leave a button with no label at all.
         */
        fun planLines(widths: List<Int>, available: Int, gap: Int = GAP): List<List<Int>> {
            if (widths.isEmpty()) return emptyList()
            return planLinesFrom(widths.toIntArray(), widths.size, available, gap)
        }
    }
}