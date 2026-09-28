package com.redtermapp.ui

import android.content.Context
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import com.redtermapp.R

/**
 * Theme-resolved colours for the screens that build their layout in code.
 *
 * A literal such as `0x22FFFFFF` is the same colour on every theme, so a card drawn
 * with one is invisible on a light theme and an off-palette tint on the dark ones.
 * Reading the attribute keeps a screen following whichever theme is active, which is
 * what the screens built from XML get for free.
 *
 * The default is only a fallback for the case where an attribute is genuinely
 * missing; every theme in `values/themes.xml` defines all of them.
 */
fun Context.themeColor(@AttrRes attr: Int, @ColorInt fallback: Int): Int {
    val typed = theme.obtainStyledAttributes(intArrayOf(attr))
    try {
        return typed.getColor(0, fallback)
    } finally {
        typed.recycle()
    }
}

/** The colour the app's own cards use, per theme. */
@ColorInt
fun Context.cardColor(): Int = themeColor(R.attr.extraKeysBg, 0xFF181825.toInt())

/** Foreground colour for text on a [cardColor] background, per theme. */
@ColorInt
fun Context.cardTextColor(): Int = themeColor(R.attr.terminalText, 0xFFCDD6F4.toInt())
