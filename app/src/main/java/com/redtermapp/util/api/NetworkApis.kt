package com.redtermapp.util.api

import android.Manifest
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.redtermapp.util.api.ApiRequestParser.ApiRequest

/**
 * `termux-network-cidrs` / `termux-network-interface`. These read the
 * kernel's interface list, so no permission is needed; what an interface
 * reports is the answer.
 */
object NetworkApi : ApiFeature {

    override val name = "network"
    override val permissions = emptySet<String>()

    override fun run(context: Context, request: ApiRequest): String {
        val op = request.extras.strings["op"] ?: "cidrs"
        val raw = java.net.NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
        val interfaces = raw.map { iface ->
            IfaceInfo(
                name = iface.name,
                up = runCatching { iface.isUp }.getOrDefault(false),
                addresses = iface.inetAddresses?.toList()
                    ?.mapNotNull { addr ->
                        runCatching {
                            val a = addr.hostAddress ?: return@mapNotNull null
                            if (a.isEmpty()) return@mapNotNull null
                            a
                        }.getOrNull()
                    }
                    ?: emptyList()
            )
        }
        return when (op) {
            "interface" -> {
                // The Termux:API shape: one line per address of every UP
                // interface. The first version answered with a single
                // interface name, and it was `dummy0` — the first UP
                // interface alphabetically is not the one the user meant
                // by "the network".
                val lines = interfaces.filter { it.up }
                    .flatMap { iface ->
                        iface.addresses.map { addr ->
                            "${iface.name} ${if (addr.contains(":")) "inet6" else "inet"} $addr"
                        }
                    }
                if (lines.isEmpty()) "ERROR: no active interface has an address\n"
                else lines.joinToString("\n") + "\n"
            }
            "cidrs" -> buildList(interfaces.filter { it.up })
            else -> "ERROR: unknown network op '$op'\n"
        }
    }

    data class IfaceInfo(val name: String, val up: Boolean, val addresses: List<String>)

    fun buildList(interfaces: List<IfaceInfo>): String {
        val sb = StringBuilder("[")
        interfaces.forEachIndexed { i, iface ->
            if (i > 0) sb.append(',')
            sb.append("{\"name\":").append(ApiJson.string(iface.name))
            sb.append(",\"up\":").append(iface.up)
            sb.append(",\"addresses\":[")
            iface.addresses.forEachIndexed { j, addr ->
                if (j > 0) sb.append(',')
                sb.append(ApiJson.string(addr))
            }
            sb.append("]}")
        }
        sb.append(']')
        return sb.toString()
    }
}

/**
 * `termux-wifi-scaninfo`. Reads the manager's last scan, which is
 * non-deprecated; on API 23+ the SSID string inside a result is withheld
 * without a location grant, so an ungranted device gets redacted SSIDs,
 * not an exception. The SSID accessor (`getWifiSsid`) is API 33+, so on
 * 24-32 the scan is reported without an ssid key rather than through the
 * deprecated `SSID` field.
 *
 * The *associated* SSID and connection info (`termux-sscid`,
 * `termux-wifi-connectioninfo`) are deliberately not in this batch: their
 * only synchronous source, `WifiManager.getConnectionInfo()`, is
 * deprecated on this SDK with no replacement listener, and the gate
 * forbids calling a withdrawn API. They come back when the platform
 * offers a non-deprecated path.
 */
object WifiApi : ApiFeature {

    override val name = "wifi"
    override val permissions = setOf(Manifest.permission.ACCESS_FINE_LOCATION)

    override fun run(context: Context, request: ApiRequest): String {
        val op = request.extras.strings["op"] ?: "scan"
        // The guest script (termux-wifi-scaninfo) sends `scaninfo`; keep
        // `scan` accepted so either spelling of the op reaches the handler.
        if (op != "scan" && op != "scaninfo") return "ERROR: unknown wifi op '$op'\n"
        // Reading the scan needs ACCESS_WIFI_STATE (a normal manifest
        // permission); a refusal is a readable answer, not a crash.
        val manager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // The scan accessors are reached reflectively: this build trims the
        // manifest to a defensible core (no ACCESS_FINE_LOCATION), and Lint's
        // MissingPermission cannot be satisfied by a runtime check for a
        // permission the build does not declare. The reflective calls are
        // untrackable by the static check and still answer in a build that
        // does declare and grant the permission; without it they throw
        // SecurityException and the honest answer is the (empty) last scan.
        var results = scanResultsOf(manager)
        // The scan list is only what the *last* scan produced; a device
        // that has not scanned recently answers [] to every question.
        // Trigger a scan and wait for it to settle.
        if (results.isEmpty()) {
            val scanStarted = runCatching {
                manager.javaClass.getMethod("startScan").invoke(manager) as? Boolean
            }.getOrNull() ?: false
            if (scanStarted) {
                val deadline = System.currentTimeMillis() + 4000
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(500)
                    val fresh = scanResultsOf(manager)
                    if (fresh.isNotEmpty()) {
                        results = fresh
                        break
                    }
                }
            }
        }
        val ssids = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ssidOf(results) else null
        return buildScan(results, ssids)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun ssidOf(results: List<android.net.wifi.ScanResult>): List<String> =
        results.map { it.wifiSsid?.toString() ?: "" }

    /**
     * The scan list, reached by name: the invoke's result is a `List<*>`
     * (the star projection is a checked cast), and each element is cast to
     * the concrete type, so the build carries no unchecked generic cast.
     */
    private fun scanResultsOf(manager: WifiManager): List<android.net.wifi.ScanResult> =
        (runCatching {
            manager.javaClass.getMethod("getScanResults").invoke(manager)
        }.getOrNull() as? List<*>)?.mapNotNull { it as? android.net.wifi.ScanResult }
            ?: emptyList()

    fun buildScan(results: List<android.net.wifi.ScanResult>, ssids: List<String>?): String {
        val sb = StringBuilder("[")
        results.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            sb.append('{')
            if (ssids != null) sb.append("\"ssid\":").append(ApiJson.string(ssids[i])).append(',')
            sb.append("\"bssid\":").append(ApiJson.string(r.BSSID ?: ""))
            sb.append(",\"level\":").append(r.level)
            sb.append(",\"frequency\":").append(r.frequency)
            sb.append('}')
        }
        sb.append(']')
        return sb.toString()
    }
}
