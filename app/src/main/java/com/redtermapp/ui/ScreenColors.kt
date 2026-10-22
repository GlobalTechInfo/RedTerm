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
 * UI chrome takes its text from `extraKeysText` rather than from the terminal's own
 * foreground. The terminal colours are for terminal characters — a green terminal wants a
 * green prompt — and reusing them for labels and headings is what turns a whole screen
 * the colour of the shell.
 *
 * **Read the attribute the themes actually declare.** These two functions used to read
 * `android:attr/textColorPrimary` and `android:attr/textColorSecondary`, and not one of
 * the ten themes declares either: the framework default was taken every time, and the
 * hardcoded fallback for it was the app's *background* colour rather than its text. So
 * every label on every card was drawn near-black on a near-black card on all nine dark
 * themes, and a screen could be invisible on exactly the themes it looked correct on.
 * `extraKeysText` is declared by all ten, and it is the colour meant for text sitting on
 * a card, so it is the one to ask.
 */
fun Context.themeColor(@AttrRes attr: Int, @ColorInt fallback: Int): Int {
    val typed = theme.obtainStyledAttributes(intArrayOf(attr))
    try {
        return typed.getColor(0, fallback)
    } finally {
        typed.recycle()
    }
}

/**
 * The colour the app's own cards use, per theme.
 *
 * The fallback is this app's *default* theme's value, so a custom theme that omits the
 * attribute degrades to looking like the default rather than to something unrelated.
 * The same rule is what [cardTextColor] and [mutedTextColor] follow below.
 */
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
    themeColor(R.attr.extraKeysText, 0xFFCDD6F4.toInt())

/** Secondary text on a card, for facts under a label. */
@ColorInt
fun Context.mutedTextColor(): Int =
    withAlpha(themeColor(R.attr.extraKeysText, 0xFFCDD6F4.toInt()), 0.8f)

/**
 * Emphasis, for a status that must be noticed without being read.
 *
 * Our own `accentColor` attribute, which all ten themes set explicitly to the value they
 * give `colorPrimary`.
 *
 * This used to read `android:attr/colorAccent`, on the reasoning that a library attribute
 * is not reachable from an app module. That is true of the app's *own* `R.attr` and false
 * of a dependency's -- but Material's `R` turns out not to be exposed here either, and
 * `colorAccent` is a framework attribute these Material3 themes do not usefully define.
 * Whatever it resolved to was not the accent any theme chose, and the cost was chips drawn
 * in a colour nobody could read: `mosh` on a server card and "protected by a passphrase" on
 * a key card, while every other colour on the same cards was fine.
 *
 * Declaring the attribute and setting it in every theme is the version of this that can be
 * checked: `ThemeContrastTest` asserts each theme declares it, and that it has contrast
 * against that theme's own card colour.
 */
@ColorInt
fun Context.accentColor(): Int = themeColor(R.attr.accentColor, 0xFF89B4FA.toInt())

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