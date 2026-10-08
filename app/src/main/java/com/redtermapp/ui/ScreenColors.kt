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
 * **And nothing here names a colour for the user to see.** Every value is read from an
 * attribute the theme itself defines, in the pairing that theme declared. That is the
 * whole discipline: a screen that chooses a colour is a screen that is wrong on
 * whichever theme disagrees with the choice, and there are ten of them.
 *
 * UI chrome takes its text from `textColorPrimary`/`textColorSecondary` rather than
 * from the terminal's own foreground. The terminal colours are for terminal
 * characters — a green terminal wants a green prompt — and reusing them for labels and
 * headings is what turns a whole screen the colour of the shell.
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

/**
 * Primary text on a card.
 *
 * The framework's UI foreground, not the terminal's: a card is chrome, and on a theme
 * whose terminal text is a strong colour, labelling the card in that colour made the
 * button labels and headings disappear into their own background.
 */
@ColorInt
fun Context.cardTextColor(): Int =
    themeColor(android.R.attr.textColorPrimary, 0xFF1E1E2E.toInt())

/** Secondary text on a card, for facts under a label. */
@ColorInt
fun Context.mutedTextColor(): Int =
    withAlpha(themeColor(android.R.attr.textColorSecondary, 0xFF45475A.toInt()), 0.8f)

/**
 * Emphasis, for a status that must be noticed without being read.
 *
 * `android:attr/colorAccent` rather than Material's `colorPrimary`: the app's `R.attr`
 * is non-transitive, so a library attribute is not reachable from here, while every
 * framework attribute is. Material themes derive the accent from the primary anyway,
 * so it follows the theme either way.
 */
@ColorInt
fun Context.accentColor(): Int =
    themeColor(android.R.attr.colorAccent, 0xFF89B4FA.toInt())

/**
 * Destructive actions, from the framework's own error colour.
 *
 * `android:attr/colorError` is exactly the platform's answer to "this action destroys
 * something", and every Material theme already defines it — so there was never a reason
 * for this app to declare a `danger` colour of its own and set it in ten themes.
 */
@ColorInt
fun Context.dangerColor(): Int =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        themeColor(android.R.attr.colorError, DANGER_FALLBACK)
    } else {
        // The platform has no error colour before API 26, so there is nothing to
        // read. One literal, for the two releases where the theme cannot answer.
        DANGER_FALLBACK
    }

private const val DANGER_FALLBACK = 0xFFC01B2E.toInt()

/** [color] at [alpha] opacity, for a tinted fill behind a pill. */
@ColorInt
fun withAlpha(@ColorInt color: Int, alpha: Float): Int =
    (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)