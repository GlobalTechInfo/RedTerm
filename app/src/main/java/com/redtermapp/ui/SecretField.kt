package com.redtermapp.ui

import android.content.Context
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.appcompat.content.res.AppCompatResources
import com.redtermapp.R

/**
 * A text field that can show what is in it.
 *
 * A passphrase typed blind into a masked field is the one place in this feature
 * where a silent typo is invisible until the server refuses the key — and the error
 * then points at the server rather than at the keyboard. Being able to check what
 * was typed is the difference between fixing that in a second and filing a bug.
 */
object SecretField {

    /**
     * Wraps [input] so it carries a trailing reveal toggle.
     *
     * The toggle is an icon button laid over the field's own end, rather than a row
     * of buttons below it, so it does not push the field off the centre of a dialog
     * the way an extra button does.
     *
     * The field must not be `setSingleLine` with a raw text transformation left in
     * place: toggling clears the transformation and the layout can lose its height if
     * it was measured while masked. A visible-but-invisible-frame approach is not
     * used either — the button is always visible, because a toggle that is itself
     * hidden is no use at all.
     */
    fun withRevealToggle(context: Context, input: EditText): View {
        val density = context.resources.displayMetrics.density
        val size = (40 * density).toInt()
        val margin = (4 * density).toInt()

        val reveal = ImageButton(context).apply {
            setImageDrawable(
                AppCompatResources.getDrawable(context, R.drawable.ic_eye)
                    ?: AppCompatResources.getDrawable(context, android.R.drawable.ic_menu_view)
            )
            contentDescription = context.getString(R.string.show_passphrase)
            // A borderless drawable on a light dialog is invisible; the tint is what
            // makes it read as part of the field rather than floating beside it.
            setBackgroundColor(0x00000000)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            isFocusable = true
            isClickable = true
        }

        var shown = false
        reveal.setOnClickListener {
            shown = !shown
            if (shown) {
                input.transformationMethod = null
                // Kept as a password so the field still does not appear in a
                // screenshot-friendly plain-text state in a screen recording.
                input.inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                input.setSelection(input.text.length)
            } else {
                input.transformationMethod = PasswordTransformationMethod.getInstance()
            }
            reveal.contentDescription = context.getString(
                if (shown) R.string.hide_passphrase else R.string.show_passphrase
            )
            reveal.setImageDrawable(
                AppCompatResources.getDrawable(
                    context,
                    if (shown) R.drawable.ic_eye_off else R.drawable.ic_eye
                ) ?: AppCompatResources.getDrawable(context, android.R.drawable.ic_menu_view)
            )
        }

        val frame = FrameLayout(context).apply {
            addView(
                input,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                reveal,
                FrameLayout.LayoutParams(size, size, Gravity.END or Gravity.CENTER_VERTICAL).apply {
                    setMargins(0, margin, margin, margin)
                }
            )
        }
        // Room for the button, so the text never runs underneath it.
        input.setPaddingRelative(
            input.paddingLeft,
            input.paddingTop,
            size + margin * 2,
            input.paddingBottom
        )
        return frame
    }
}
