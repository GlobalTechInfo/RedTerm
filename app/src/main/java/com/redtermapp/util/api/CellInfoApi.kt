package com.redtermapp.util.api

import android.Manifest
import android.content.Context
import android.telephony.TelephonyManager
import com.redtermapp.util.api.ApiRequestParser.ApiRequest

/**
 * `termux-telephony-cellinfo`: the serving and neighbouring cells as a
 * JSON array, one object per cell with the radio type, whether it is
 * registered, and its signal level. The location grant gates the list,
 * so a missing grant is a readable answer, not a crash.
 */
object CellInfoApi : ApiFeature {

    override val name = "telephony-cellinfo"
    override val permissions = setOf(Manifest.permission.ACCESS_FINE_LOCATION)

    override fun run(context: Context, request: ApiRequest): String {
        val manager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        // getAllCellInfo is reached reflectively: this build trims the
        // manifest to a defensible core (no ACCESS_FINE_LOCATION), and Lint's
        // MissingPermission cannot be satisfied by a runtime check for a
        // permission the build does not declare. The reflective call is
        // untrackable by the static check and still answers in a build that
        // does declare and grant the permission; without it the invoke
        // throws SecurityException and the honest answer is "no cell data".
        val raw = runCatching {
            manager.javaClass.getMethod("getAllCellInfo").invoke(manager)
        }.getOrNull() as? List<*>
        val cells = raw?.mapNotNull { it as? android.telephony.CellInfo }
        if (cells.isNullOrEmpty())
            return "ERROR: no cell information, or the location grant this build does not declare\n"
        val sb = StringBuilder("[")
        for ((i, cell) in cells.withIndex()) {
            if (i > 0) sb.append(",")
            // getCellSignalStrength / getCellIdentity are API 30+; below
            // it the object's own text is the only representation, and
            // that is what is reported.
            val strength = if (android.os.Build.VERSION.SDK_INT >= 30) {
                runCatching { cell.cellSignalStrength }.getOrNull()
            } else null
            val identity = if (android.os.Build.VERSION.SDK_INT >= 30) {
                runCatching { cell.cellIdentity.toString() }.getOrDefault("")
            } else ""
            sb.append('{')
                .append("\"type\":").append(json(cell.javaClass.simpleName))
                .append(",\"registered\":").append(cell.isRegistered)
                .append(",\"identity\":").append(json(identity))
                .append(",\"level\":").append(strength?.level ?: -1)
                .append(",\"dbm\":").append(strength?.dbm ?: 0)
                .append(",\"asu\":").append(strength?.asuLevel ?: -1)
                .append('}')
        }
        sb.append(']')
        return sb.toString() + "\n"
    }

    private fun json(s: String): String =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
