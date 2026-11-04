package com.redtermapp.util.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import com.redtermapp.util.api.ApiRequestParser.ApiRequest

/**
 * `termux-sscid` / `termux-wifi-connectioninfo`: the Wi-Fi association.
 */
object SsidApi : ApiFeature {

    override val name = "sscid"
    override val permissions = emptySet<String>()

    override fun run(context: Context, request: ApiRequest): String {
        val manager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val connected = onWifi(context)
        if (!connected) {
            return when (request.str("op") ?: "ssid") {
                "ssid" -> ApiJson.string("<not connected>") + "\n"
                "info" -> "{\"connected\":false}\n"
                else -> "ERROR: unknown sscid op\n"
            }
        }
        val scans =
            (runCatching {
                manager.javaClass.getMethod("getScanResults").invoke(manager)
            }.getOrNull() as? List<*>)?.mapNotNull { it as? android.net.wifi.ScanResult }
                ?: emptyList()
        val info = connectionInfoOf(manager)
        val best = scans.maxByOrNull { it.level }
        val infoSsid = info?.let { ssidOfInfo(it) }
        val ssid = when {
            infoSsid != null && !infoSsid.isNullOrEmpty() && infoSsid != "<unknown>" && !infoSsid.startsWith("<") -> infoSsid
            best != null -> ssidOfScan(best)
            infoSsid != null -> infoSsid
            else -> "<connected: no scan entry>"
        }
        val source = when {
            infoSsid != null && !infoSsid.isNullOrEmpty() && infoSsid != "<unknown>" && !infoSsid.startsWith("<") -> "connection"
            best != null -> "scan"
            infoSsid != null -> "connection"
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
                info?.let {
                    val bssid = runCatching {
                        it.javaClass.getMethod("getBSSID").invoke(it) as? String
                    }.getOrNull()
                    if (bssid != null && best == null) {
                        sb.append(",\"bssid\":").append(ApiJson.string(bssid))
                    }
                }
                sb.append('}')
                sb.toString() + "\n"
            }
            else -> "ERROR: unknown sscid op\n"
        }
    }

    private fun onWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun ssidOfScan(scan: android.net.wifi.ScanResult): String =
        if (Build.VERSION.SDK_INT >= 33) scan.wifiSsid.toString()
        else {
            runCatching {
                android.net.wifi.ScanResult::class.java.getField("SSID").get(scan)
            }.getOrNull() as? String ?: "<unknown>"
        }

    private fun connectionInfoOf(manager: WifiManager): Any? =
        try {
            WifiManager::class.java.getMethod("getConnectionInfo").invoke(manager)
        } catch (e: Exception) {
            null
        }

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
                    s.startsWith("<unknown") || (s.startsWith("<") && s.endsWith(">")) ->
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
