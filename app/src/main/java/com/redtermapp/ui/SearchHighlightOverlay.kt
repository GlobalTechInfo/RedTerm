package com.redtermapp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.termux.view.TerminalView

data class SearchMatch(val row: Int, val startCol: Int, val endCol: Int)

class SearchHighlightOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var terminalView: TerminalView? = null
    private val matches = ArrayList<SearchMatch>()
    private var currentIndex = 0
    private var visible = false

    private val matchPaint = Paint().apply {
        color = 0x66CDD6F4
        style = Paint.Style.FILL
    }
    private val currentPaint = Paint().apply {
        color = 0xCC89B4FA.toInt()
        style = Paint.Style.FILL
    }

    fun attachTerminalView(view: TerminalView) {
        terminalView = view
    }

    fun setMatches(list: List<SearchMatch>, index: Int) {
        matches.clear()
        matches.addAll(list)
        currentIndex = index
        visible = true
        invalidate()
        startTracking()
    }

    fun clear() {
        if (!visible) return
        visible = false
        matches.clear()
        stopTracking()
        invalidate()
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!visible) return
            invalidate()
            postOnAnimation(this)
        }
    }

    private fun startTracking() {
        removeCallbacks(refreshRunnable)
        postOnAnimation(refreshRunnable)
    }

    private fun stopTracking() {
        removeCallbacks(refreshRunnable)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!visible || matches.isEmpty()) return
        val view = terminalView ?: return
        val emulator = view.mEmulator ?: return
        val renderer = view.mRenderer
        val cellW = renderer.getFontWidth()
        val pitch = renderer.getFontLineSpacing()
        val topOffset = lineSpacingAndAscentField.get(renderer) as Int
        val topRow = view.getTopRow()
        for (i in matches.indices) {
            val m = matches[i]
            val y = topOffset + (m.row - topRow) * pitch
            if (y < -pitch || y >= height) continue
            canvas.drawRect(
                m.startCol * cellW, y.toFloat(),
                (m.endCol + 1) * cellW, (y + pitch).toFloat(),
                if (i == currentIndex) currentPaint else matchPaint
            )
        }
    }

    companion object {
        private val lineSpacingAndAscentField by lazy {
            val f = com.termux.view.TerminalRenderer::class.java.getDeclaredField("mFontLineSpacingAndAscent")
            f.isAccessible = true
            f
        }
    }
}
