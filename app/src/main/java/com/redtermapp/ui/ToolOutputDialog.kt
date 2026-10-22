package com.redtermapp.ui

import android.content.ClipData
import android.view.ViewGroup
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import com.redtermapp.R

/**
 * A dialog for output that came from a tool, rather than for prose.
 *
 * `AlertDialog.setMessage` builds a plain, non-selectable `TextView`. That is fine for
 * "Archive already exists" and useless for the other thing that ends up in the same
 * dialog: the several hundred characters of `tar` explaining why a restore failed. The
 * user cannot select it, cannot copy it, cannot scroll it on a phone, and once they tap
 * OK it is gone — which is exactly the output needed to work out what went wrong.
 *
 * So any dialog carrying tool output gets this instead: selectable, copyable, scrollable
 * and monospaced, since these are paths and flags rather than sentences.
 */
object ToolOutputDialog {

    /** A scrollable, selectable text view for tool output. */
    fun view(context: Context, text: CharSequence): TextView {
        val pad = (20 * context.resources.displayMetrics.density).toInt()
        val body = TextView(context).apply {
            this.text = text
            // Tool output is never a paragraph, so a fixed pitch line keeps paths and
            // flags lined up instead of wrapping into something unreadable.
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(pad, pad / 2, pad, pad / 2)
            // Selectable LAST, and no movement method of our own. setTextIsSelectable
            // installs the movement method selection needs; overwriting it afterwards
            // with ScrollingMovementMethod is what stopped long-press selection from
            // working, and the NestedScrollView in show() does the scrolling anyway.
            setTextIsSelectable(true)
        }
        return body
    }

    /**
     * Shows [text] in a dialog the user can actually read.
     *
     * Long output is scrolled rather than allowed to grow past the screen: a failure
     * message of two hundred lines in a wrap-around `TextView` is a wall, not a report.
     *
     * A Copy button is on the dialog as well as selectable text: one tap puts the
     * whole output on the clipboard, without depending on long-press selection
     * working on every device.
     */
    fun show(context: Context, title: CharSequence, text: CharSequence) {
        val body = view(context, text)
        val scroll = NestedScrollView(context).apply {
            addView(body)
            // Filling the available height is what makes it scroll; the inner TextView
            // wraps to its own height so the whole output is still reachable.
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.label_copy) { _, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("output", text))
                    android.widget.Toast.makeText(
                        context, R.string.copied, android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .show()
    }
}