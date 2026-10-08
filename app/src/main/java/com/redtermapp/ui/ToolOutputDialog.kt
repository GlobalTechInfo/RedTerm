package com.redtermapp.ui

import android.view.ViewGroup
import android.content.Context
import android.graphics.Typeface
import android.text.method.ScrollingMovementMethod
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
            setTextIsSelectable(true)
            // Tool output is never a paragraph, so a fixed pitch line keeps paths and
            // flags lined up instead of wrapping into something unreadable.
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(pad, pad / 2, pad, pad / 2)
            movementMethod = ScrollingMovementMethod()
        }
        return body
    }

    /**
     * Shows [text] in a dialog the user can actually read.
     *
     * Long output is scrolled rather than allowed to grow past the screen: a failure
     * message of two hundred lines in a wrap-around `TextView` is a wall, not a report.
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
            .show()
    }
}