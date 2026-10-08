package com.redtermapp.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Keeps bottom-anchored content clear of the system navigation bar.
 *
 * The screens built from XML set `fitsSystemWindows`, so they were always inset. The
 * ones built in code set their own padding and nothing else, which was invisible
 * until a row of buttons appeared at the bottom: on a phone with gesture navigation
 * the bar sits over that row, so the buttons are drawn underneath it and cannot be
 * pressed. No error, no clipping — just controls that are there and unreachable.
 *
 * Only the bottom inset is applied. The top is left to the toolbar, which already
 * accounts for the status bar, and adding a second one would push every heading down
 * by an extra bar's height on screens that were not broken.
 */
object ScreenInsets {

    fun applyBottom(view: View) {
        val initialBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            // The listener fires more than once — on rotation, on the keyboard, on a
            // theme change — so the initial padding is captured once and re-applied
            // each time. Adding the inset to the current padding instead would
            // compound, and the row would creep down the screen a little on each pass.
            target.setPadding(
                target.paddingLeft,
                target.paddingTop,
                target.paddingRight,
                initialBottom + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }
}