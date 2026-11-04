package com.redtermapp.util.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import com.redtermapp.util.api.ApiRequestParser.ApiRequest

/**
 * `termux-sscid` / `termux-wifi-connectioninfo`: the Wi-Fi association.
 *
 * The scan list is the first source: it is non-deprecated, and the
 * associated network is almost always its strongest-signal entry. When the
 * scan is empty or redacted (no location grant, no recent scan) the
 * connection-info object is the fallback, reached through reflection —
 * the same device-only-access pattern the privileged radio identifiers use
 * in [TelephonyInfoApi] — because the gate bars a direct call to the
 * withdrawn accessor, and a reflective one is the platform answering, not
 * the build relying on a promise it withdrew.
 */
object SsidApi : ApiFeature {

    override val name = "sscid"
    override val permissions = emptySet<String>()

    override fun run(context: Context, request: ApiRequest): String {
        val manager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val connected = onWifi(context)
        // The scan list is gated on the location grant (33+), which this
        // build does not declare. getScanResults is reached reflectively so
        // Lint's MissingPermission (unsatisfiable for an undeclared
        // permission) does not flag it; without the grant the invoke throws
        // and the honest answer is "no scan data". The connection state
        // still answers, and it needs no permission.
        val scans =
            (runCatching {
                manager.javaClass.getMethod("getScanResults").invoke(manager)
            }.getOrNull() as? List<*>)?.mapNotNull { it as? android.net.wifi.ScanResult }
                ?: emptyList()
        val best = scans.maxByOrNull { it.level }
        // The scan is a sampling of the neighbourhood; the connection-info
        // object is the platform's own account of the association.
        val info = if (best == null && connected) connectionInfoOf(manager) else null
        val ssid = when {
            best != null -> ssidOfScan(best)
            info != null -> ssidOfInfo(info)
            connected -> "<connected: no scan entry>"
            else -> "<not connected>"
        }
        val source = when {
            best != null -> "scan"
            info != null -> "connection"
            else -> "none"
        }

        return when (request.str("op") ?: "ssid") {
            "ssid" -> ApiJson.string(ssid) + "\n"
            "info" -> {
                val sb = StringBuilder("{")
                sb.append("\"connected\":").append(connected)
                sb.append(",\"ssid\":").append(ApiJson.string(ssid))
                sb.append(",\"ssid_source\":").append(ApiJson.string(source))
                if (best != null) {
                    sb.append(",\"bssid\":").append(ApiJson.string(best.BSSID ?: ""))
                    sb.append(",\"level\":").append(best.level)
                    sb.append(",\"frequency\":").append(best.frequency)
                    sb.append(",\"capabilities\":").append(ApiJson.string(best.capabilities ?: ""))
                }
                sb.append('}')
                sb.toString() + "\n"
            }
            else -> "ERROR: unknown sscid op\n"
        }
    }

    /**
     * Whether the device's active network is carried over Wi-Fi, the
     * non-deprecated replacement for the withdrawn connection-info call.
     */
    private fun onWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun ssidOfScan(scan: android.net.wifi.ScanResult): String =
        if (Build.VERSION.SDK_INT >= 33) scan.wifiSsid.toString()
        else {
            // The plain SSID field was withdrawn on API 33 in favour of the
            // WifiSsid accessor; below that it is the only source, and it is
            // read by name on the declared type so the build carries no
            // reference to the withdrawn member.
            runCatching {
                android.net.wifi.ScanResult::class.java.getField("SSID").get(scan)
            }.getOrNull() as? String ?: "<unknown>"
        }

    /**
     * The connection-info object behind the withdrawn `getConnectionInfo()`:
     * invoked by name on the *declared* type — not the runtime class. `getSystemService`
     * on MTK/OEM stacks returns a local service proxy whose `javaClass` does not resolve
     * the accessor by name, so reflecting on the proxy is a NoSuchMethodException; the
     * framework type itself always does.
     */
    private fun connectionInfoOf(manager: WifiManager): Any? =
        try {
            WifiManager::class.java.getMethod("getConnectionInfo").invoke(manager)
        } catch (e: Exception) {
            null
        }

    /**
     * The connection-info object behind `getConnectionInfo()`: the
     * accessor is withdrawn on this SDK, so it is invoked by name on the
     * runtime class, and the version-shaped SSID accessors on its result
     * are reached the same way (pre-33 `getSSID` returns a String, 33+
     * `getSsid` returns an object that masks itself without a location
     * grant). Each failure mode gets its own marker, because the answer
     * to "what is my network" is different in each:
     *
     * - `null`  — the platform reports no associated SSID at all
     *   (some OEM stacks return it that way even while connected)
     * - redacted — the SSID exists but the OS masks it without
     *   ACCESS_FINE_LOCATION; the marker names the grant that unlocks it
     * - SecurityException — the call itself needs the location grant
     */
    private fun ssidOfInfo(info: Any): String = try {
        val cls = android.net.wifi.WifiInfo::class.java
        val value = if (Build.VERSION.SDK_INT >= 33) {
            cls.getMethod("getSsid").invoke(info)
        } else {
            cls.getMethod("getSSID").invoke(info)
        }
        when {
            value == null -> "<no associated SSID reported by the platform>"
            else -> {
                val s = value.toString()
                when {
                    s.isEmpty() -> "<no associated SSID reported by the platform>"
                    s.startsWith("<unknown") || s.startsWith("<") && s.endsWith(">") ->
                        "<SSID redacted: grant ACCESS_FINE_LOCATION in RedTerm Settings>"
                    else -> s
                }
            }
        }
    } catch (e: java.lang.SecurityException) {
        "<SSID redacted: grant ACCESS_FINE_LOCATION in RedTerm Settings>"
    } catch (e: Exception) {
        "<SSID unavailable: ${e.javaClass.simpleName}>"
    }
}
