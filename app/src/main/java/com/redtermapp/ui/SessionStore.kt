package com.redtermapp.ui

import android.content.Context
import androidx.core.content.edit

/**
 * Remembers which sessions were open, so they come back after Android kills the
 * process.
 *
 * The ViewModel holds the live sessions and is a static singleton, so they
 * already survive the activity being recreated for a rotation or a theme change.
 * They do not survive the process being reclaimed in the background, which is
 * exactly what happens on a low-memory device with a long-running SSH session —
 * the case where reconnecting by hand is most annoying, because the remote work
 * in progress is lost.
 *
 * The list is written on every change and cleared as soon as the last session
 * dies, so closing sessions normally can never resurrect them on next launch.
 */
object SessionStore {

    private const val PREFS = "session_store"
    private const val KEY = "open_sessions"
    private const val KEY_CURRENT = "current_session"

    fun save(context: Context, descriptors: List<SessionDescriptor>, currentId: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY, SessionDescriptor.listToJson(descriptors))
            putString(KEY_CURRENT, currentId ?: "")
        }
    }

    fun load(context: Context): List<SessionDescriptor> =
        SessionDescriptor.listFromJson(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        )

    fun currentId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CURRENT, null)?.takeIf { it.isNotBlank() }

    /** Forgets the open sessions. Called when the last one is closed. */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            remove(KEY)
            remove(KEY_CURRENT)
        }
    }
}