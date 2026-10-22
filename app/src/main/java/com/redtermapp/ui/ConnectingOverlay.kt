package com.redtermapp.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.cardview.widget.CardView
import com.redtermapp.R

/**
 * A connecting overlay: a dim scrim with a spinner, a line saying what is
 * connecting, and a way out.
 *
 * A session is not ready when its process starts. A mosh bootstrap opens an SSH
 * connection, runs `mosh-server new` and reads back a port and a key — several
 * seconds on a real network — and a plain SSH session or an SFTP browser's first
 * listing each dial their own connection in the same span. Saying so belongs here
 * rather than in the terminal, because the terminal is where the user's own output
 * goes — a status line printed there competes with a command they just typed, and
 * any attempt to rewrite it in place is orphaned the moment the far end scrolls.
 *
 * Shared by the terminal and the SFTP browser so both connections are described by
 * the same words and the same look. Callers on the main thread [show] and [hide];
 * showing while already shown is a no-op, and hiding when hidden is too.
 */
class ConnectingOverlay(
    private val activity: Activity,
    /** The view the scrim is added to, painted over it completely. */
    private val parent: ViewGroup
) {
    private var view: View? = null

    val isShowing: Boolean get() = view != null

    /**
     * Shows the overlay for [message], fading in.
     *
     * [onCancel] is invoked after the overlay is dismissed by the cancel button.
     * Without a way out, a connection that stalls leaves the user looking at an
     * opaque scrim with nothing to press. The connecting work is a blocking ssh
     * call and cannot be interrupted usefully, so cancelling is about the
     * *result*, not the attempt — the caller decides what a discarded result means.
     * Omitting it is for connects where stopping to wait still ends in a result
     * the activity wants, such as a browser whose listing lands either way.
     */
    fun show(message: String, onCancel: (() -> Unit)? = null) {
        if (view != null) return
        val density = activity.resources.displayMetrics.density
        val pad = (24 * density).toInt()
        val gap = (12 * density).toInt()

        val scrim = FrameLayout(activity).apply {
            setBackgroundColor(0xB0000000.toInt())
            // Swallow taps: a terminal or a file list must not take focus mid-connect.
            isClickable = true
            isFocusable = true
            elevation = 8 * density
            alpha = 0f
        }
        val card = CardView(activity).apply {
            radius = 14 * density
            setCardBackgroundColor(activity.cardColor())
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            // Without a floor the card is exactly as wide as its text, so a short
            // message gives a thin strip and a long one fills the screen. A minimum
            // reads as deliberate at either end.
            minimumWidth = (220 * density).toInt()
        }
        column.addView(
            ProgressBar(activity).apply {
                // The framework default is the platform accent, which is not this
                // app's accent on every theme — a spinner is the one thing on screen
                // during a connection, so it is worth matching.
                indeterminateTintList = ColorStateList.valueOf(activity.accentColor())
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        val label = TextView(activity).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(activity.cardTextColor())
            setPadding(0, gap, 0, 0)
            // Android 16 deprecated announceForAccessibility as disruptive; the
            // sanctioned replacement for a status message is a live region that
            // announces its own text when the text changes while it is on screen.
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        column.addView(
            label,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        if (onCancel != null) {
            column.addView(
                ScreenWidgets.actionButton(activity, R.string.cancel) {
                    hide()
                    onCancel()
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = gap }
            )
        }
        card.addView(column)
        scrim.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        parent.addView(
            scrim,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        view = scrim
        scrim.animate().alpha(1f).setDuration(160).start()
        // The text is set only now, once the live region is on screen: a region
        // announces a change, and text set before the view was attached would
        // have changed nothing after it became visible.
        label.text = message
    }

    /** Fades out and removes the overlay; a no-op when it is not shown. */
    fun hide() {
        val fading = view ?: return
        view = null
        // Faded out rather than removed outright: the content that replaces this
        // arrives in the same frame, and a hard cut reads as a flicker.
        fading.animate().alpha(0f).setDuration(140).withEndAction {
            (fading.parent as? ViewGroup)?.removeView(fading)
        }.start()
    }
}
