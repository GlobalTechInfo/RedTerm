package com.redtermapp.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
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
     * [buttons] in a two-column grid, every cell the same width, aligned row on row.
     *
     * Natural-width buttons in a wrapping row do not line up: each takes the width its
     * label asks for, so the second row starts wherever the first row happened to end,
     * and a button under another sits off to one side. Equal-width cells make every
     * button the same size and put each directly under the one above it.
     *
     * The only thing that would make a half-width cell ugly is a label wrapping to one
     * word per line — and [actionButton] already forces `maxLines = 1` with an
     * ellipsize, so a long label shortens instead of stacking. An odd count leaves a
     * spacer in the last cell so the lone button stays one column wide, not full width.
     */
    fun buttonGrid(context: Context, buttons: List<MaterialButton>): LinearLayout {
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val gap = (6 * context.resources.displayMetrics.density).toInt()
        val rows = (buttons.size + 1) / 2
        for (r in 0 until rows) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            if (r > 0) {
                row.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = gap }
            }
            val first = buttons[r * 2]
            val second = buttons.getOrNull(r * 2 + 1)
            first.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { rightMargin = if (second != null) gap else 0 }
            row.addView(first)
            if (second != null) {
                second.layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                row.addView(second)
            } else {
                // Keep the lone button one column wide, not stretched across the row.
                row.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
            }
            grid.addView(row)
        }
        return grid
    }

    /**
     * The one thing you came to this card to do.
     *
     * Filled, where [actionButton] is outlined, because a row of identically-styled
     * buttons has no answer to "which one do I press" -- and on a list of saved servers
     * that question is asked every single time. Exactly one per card: two primaries is
     * the same as none.
     *
     * The label is chosen by measured contrast against the accent rather than assumed
     * white, because a pale accent under white text is unreadable and the theme decides
     * the accent, not this file.
     */
    fun primaryButton(
        context: Context,
        labelRes: Int,
        onClick: () -> Unit
    ): MaterialButton = MaterialButton(context).apply {
        setText(labelRes)
        isAllCaps = false
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        val accent = context.accentColor()
        backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
        setTextColor(com.redtermapp.distro.DistroBrand.readableOn(accent))
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

    /**
     * A Spinner whose items are painted in the theme's own text colour.
     *
     * The framework's `simple_spinner_item` and `simple_spinner_dropdown_item` do not
     * reliably follow this app's theme pinning, so on a dark theme the selected name and
     * the drop-down items can render nearly invisible. Painting the text here is what
     * makes the item readable on every theme rather than depending on a widget default.
     */
    fun spinner(context: Context, items: List<String>): Spinner {
        val color = context.cardTextColor()
        val density = context.resources.displayMetrics.density
        val adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, items) {
            override fun getView(
                position: Int, convertView: View?, parent: android.view.ViewGroup
            ): View {
                val tv = (convertView as? TextView) ?: TextView(context)
                tv.text = getItem(position)
                tv.textSize = 14f
                tv.setTextColor(color)
                return tv
            }

            override fun getDropDownView(
                position: Int, convertView: View?, parent: android.view.ViewGroup
            ): View {
                val tv = (convertView as? TextView) ?: TextView(context)
                tv.text = getItem(position)
                tv.textSize = 14f
                tv.setTextColor(color)
                val p = (12 * density).toInt()
                tv.setPadding(p, p, p, p)
                return tv
            }
        }
        val s = Spinner(context)
        s.adapter = adapter
        return s
    }
}