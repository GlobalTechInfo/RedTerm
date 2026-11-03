package com.redtermapp.util.api

import android.content.Context
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
        val enabled = request.bool("enabled") == true
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
