package com.redtermapp.ui

import android.content.Context
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog

/**
 * Dialogs that stay open until what was entered is usable.
 *
 * A positive button's listener runs and then the dialog dismisses, unconditionally.
 * So the usual shape — validate, show a toast, `return` — closes the dialog anyway and
 * the toast is the only thing left saying anything went wrong, which is easy to miss
 * and looks like the tap did nothing. Validating here keeps the dialog up with the
 * offending field focused, so the mistake can be corrected rather than retyped.
 */
object ValidatedDialog {

    /**
     * Builds the dialog with a positive button that will not dismiss on invalid input.
     *
     * [validate] returns the value to use, or null to keep the dialog open; it may
     * show its own message.
     */
    fun <T> show(
        context: Context,
        title: CharSequence,
        view: View?,
        validate: () -> Any?,
        onAccepted: (T) -> Unit
    ) {
        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .apply { if (view != null) setView(view) }
            .setPositiveButton(com.redtermapp.R.string.ok, null)
            .setNegativeButton(com.redtermapp.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = validate()
                if (value === Reject) return@setOnClickListener
                @Suppress("UNCHECKED_CAST")
                onAccepted(value as T)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /**
     * Returned from a [validate] block when the input cannot be used.
     *
     * A named value rather than a null, so "keep the dialog open" reads as itself
     * instead of looking like a block that forgot to produce a result.
     */
    object Reject

    /** A single-line text field with the dialog's own padding, for a name or label. */
    fun nameField(context: Context, initial: String = ""): EditText = EditText(context).apply {
        setText(initial)
        val inner = (16 * context.resources.displayMetrics.density).toInt()
        setPadding(inner, inner, inner, inner)
        setSingleLine(true)
    }
}