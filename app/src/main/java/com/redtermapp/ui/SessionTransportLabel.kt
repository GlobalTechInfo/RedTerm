package com.redtermapp.ui

import android.content.Context
import androidx.annotation.StringRes
import com.redtermapp.R

/**
 * The transport an SSH session is on, for display next to its label.
 *
 * The decision lives in [transportLabelRes] rather than in the descriptor itself, because
 * the text is user-facing and belongs in `strings.xml`, while a `SessionDescriptor` has no
 * `Context` and should not grow one to format a title. Having the model hand back a ready
 * string was how two hardcoded English fragments got into the title and the notification.
 */
@StringRes
internal fun transportLabelRes(descriptor: SessionDescriptor?): Int = when (descriptor) {
    // A distro session is not a choice the user made, so there is nothing to report.
    is SessionDescriptor.Local -> 0
    // The transport in use rather than the one that was asked for, because the
    // interesting case is a server that wanted mosh and did not get it. `useMosh` is
    // written by `prepareMosh` and nowhere else, so it is the truth about what connected.
    is SessionDescriptor.Ssh ->
        if (descriptor.useMosh) R.string.session_transport_mosh else R.string.session_transport_ssh
    null -> 0
}

/** The suffix to append to a session label: empty when there is nothing to say. */
internal fun Context.transportSuffixOf(descriptor: SessionDescriptor?): String {
    val res = transportLabelRes(descriptor)
    return if (res == 0) "" else getString(res)
}
