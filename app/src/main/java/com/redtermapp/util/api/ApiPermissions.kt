package com.redtermapp.util.api

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * The runtime permissions the bridge's features ask for, and how a missing
 * grant is read in Settings. A permission the user has not been asked for
 * yet shows as "not granted", which is the state that makes a feature's
 * gate message true: the guest gets the named permission and the place to
 * give it. POST_NOTIFICATIONS only exists from API 33, so that row is
 * hidden below it.
 */
object ApiPermissions {

    data class Entry(
        val labelRes: Int,
        val permission: String,
        val usedBy: String
    )

    val ENTRIES: List<Entry>
        get() = buildList {
            // This build trims its manifest to a defensible core so Play
            // Protect stops reading a trojan profile; the only API-bridge
            // permission it still declares and can request at runtime is
            // POST_NOTIFICATIONS (API 33+). The location / SMS / call /
            // call-log / contacts / camera / microphone / phone-state
            // permissions are deliberately not declared, so their commands
            // answer "not available in this build" (the gate in ApiActions)
            // rather than offering a request that the manifest could not
            // back.
            if (Build.VERSION.SDK_INT >= 33) {
                add(Entry(com.redtermapp.R.string.api_perm_notifications, Manifest.permission.POST_NOTIFICATIONS, "redterm-notification"))
            }
        }

    fun isGranted(context: Context, entry: Entry): Boolean =
        context.checkSelfPermission(entry.permission) == PackageManager.PERMISSION_GRANTED

    fun missing(context: Context): List<Entry> = ENTRIES.filterNot { isGranted(context, it) }

    /** The permission array a single requestPermissions covers. */
    fun requestable(context: Context): Array<String> = missing(context).map { it.permission }.toTypedArray()
}
