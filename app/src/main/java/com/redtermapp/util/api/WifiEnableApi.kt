package com.redtermapp.util.api

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import com.redtermapp.util.api.ApiRequestParser.ApiRequest

/**
 * `termux-wifi-enable`: ask the system to turn Wi-Fi on or off. From
 * Android 10 on the platform ignores an app's request and returns false,
 * and that refusal is reported as such rather than as a broken toggle.
 */
object WifiEnableApi : ApiFeature {

    override val name = "wifi-enable"
    override val permissions = emptySet<String>()

    override fun run(context: Context, request: ApiRequest): String {
        val manager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // The `enabled` extra must be present *and* a boolean. A missing or
        // int-typed flag must not silently read as "turn it off" — that is
        // how a typo kills the device's connectivity.
        val enabled = request.bool("enabled")
            ?: return "ERROR: wifi-enable needs the enabled flag (--ez enabled true|false)\n"
        // Flipping the radio is a CHANGE_WIFI_STATE grant. This build trims
        // the manifest to a core that does not declare it, so check whether
        // it is held before touching the reflective call: a denial here is
        // a missing declaration, not a missing runtime grant.
        if (context.checkSelfPermission(Manifest.permission.CHANGE_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
            return "ERROR: not available in this build: ${Manifest.permission.CHANGE_WIFI_STATE} is not declared\n"
        }
        val state = manager.wifiState
        if (enabled && state == WifiManager.WIFI_STATE_ENABLED) return "already on\n"
        if (!enabled && state == WifiManager.WIFI_STATE_DISABLED) return "already off\n"
        // setWifiEnabled was withdrawn on API 28 with no public
        // replacement; it is reached by name on the declared type so the
        // build carries no reference to the retired member. The answer is
        // the platform's own — true where an app may still flip it, false
        // where it refuses.
        val accepted = runCatching {
            WifiManager::class.java.getMethod("setWifiEnabled", java.lang.Boolean.TYPE)
                .invoke(manager, enabled) as? Boolean
        }.getOrNull() ?: false
        return if (accepted) {
            if (enabled) "1\n" else "0\n"
        } else {
            "ERROR: this Android version does not let an app change Wi-Fi state; toggle it in Settings\n"
        }
    }
}
