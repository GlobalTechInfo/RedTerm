package com.redtermapp.ui

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import androidx.annotation.ColorInt
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip

/**
 * The small pieces the code-built screens share.
 *
 * A status rendered as another line of body text is not read as a status. The card here
 * used to say "protected by a passphrase" in the same grey 12sp as the creation date,
 * which made a fact about how the key behaves look like trivia about when it was made.
 *
 * Nothing here chooses a colour. Buttons take their fill and their label from the theme
 * and are left alone; chips and cards are styled from theme attributes. The only colour
 * passed in is a *tint* the caller read from the theme itself, never a literal.
 */
object ScreenWidgets {

    /**
     * A compact pill for a status, key type or count.
     *
     * A real `Chip` rather than a `TextView` with a drawn background, so its padding,
     * corner radius, outline and text colour come from the widget's style and follow the
     * theme. The hand-drawn version had to be told a colour for each part, and that is
     * exactly the part that goes wrong on the next theme.
     *
     * [tint] must come from [Context.accentColor] or another theme attribute.
     */
    fun chip(context: Context, text: String, @ColorInt tint: Int): Chip = Chip(context).apply {
        this.text = text
        isCheckable = false
        isClickable = false
        isFocusable = false
        chipMinHeight = 0f
        ensureAccessibleTouchTarget(0)
        setEnsureMinTouchTargetSize(false)
        setChipBackgroundColorResource(android.R.color.transparent)
        chipStrokeWidth = context.resources.displayMetrics.density
        chipStrokeColor = android.content.res.ColorStateList.valueOf(tint)
        setTextColor(tint)
        textSize = 11f
        maxLines = 1
    }

    /** Chips laid out left to right with even spacing, for the top of a card. */
    fun headerRow(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /**
     * A button for a row of actions.
     *
     * No colour is set. `MaterialButton` already resolves its container from
     * `colorPrimary` and its label from `colorOnPrimary`, and this screen's callers were
     * overriding that with a colour picked off the card — which rendered the labels
     * invisible on any theme whose primary matched the card's text.
     *
     * `maxLines = 1` is not cosmetic: a label with room to wrap is a label that will
     * wrap the moment the row is one button wider than it was.
     */
    fun actionButton(
        context: Context,
        labelRes: Int,
        onClick: () -> Unit
    ): MaterialButton = MaterialButton(context).apply {
        setText(labelRes)
        isAllCaps = false
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setOnClickListener { onClick() }
    }

    /**
     * A destructive action.
     *
     * Outlined rather than filled, so it reads differently from the neutral buttons
     * beside it even before the colour is seen — and tinted from the theme's
     * `colorError` rather than a colour this file decided on.
     */
    fun dangerButton(
        context: Context,
        labelRes: Int,
        onClick: () -> Unit
    ): MaterialButton = MaterialButton(context).apply {
        setText(labelRes)
        isAllCaps = false
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        backgroundTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.TRANSPARENT
        )
        val error = context.dangerColor()
        strokeColor = android.content.res.ColorStateList.valueOf(error)
        strokeWidth = (1 * context.resources.displayMetrics.density).toInt()
        setTextColor(error)
        setOnClickListener { onClick() }
    }
}