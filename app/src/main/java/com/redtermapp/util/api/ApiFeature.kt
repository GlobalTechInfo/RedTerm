package com.redtermapp.util.api

import android.content.Context
import android.content.pm.PackageManager
import com.redtermapp.util.api.ApiRequestParser.ApiRequest

/**
 * One Termux:API feature. The [name] is the value of the `action` extra the
 * guest helper sends, and [permissions] is the set of *dangerous* runtime
 * permissions the feature needs: the gate checks them before [run] is called
 * and answers a readable "grant this in Settings" instead of letting a
 * `SecurityException` die silently in a pipe.
 *
 * Normal (install-time) manifest permissions are not listed here; they are
 * granted on install and need no runtime check.
 */
interface ApiFeature {
    val name: String
    val permissions: Set<String>
    fun run(context: Context, request: ApiRequest): String
}
