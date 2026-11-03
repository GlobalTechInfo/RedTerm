package com.redtermapp.util.api

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.redtermapp.util.api.ApiRequestParser.ApiRequest

/**
 * `termux-battery-status`: the JSON this prints is what the Termux:API
 * version prints, key for key, so a script written against either one reads
 * the same answer.
 *
 * [build] is pure and takes the flat fields of the battery intent; the
 * intent itself is the only Android-shaped thing in here, which is why it
 * stays out of the builder and out of the unit tests.
 */
object BatteryApi : ApiFeature {

    override val name = "battery-status"
    override val permissions = emptySet<String>()

    data class BatteryInfo(
        val level: Int,
        val scale: Int,
        val health: Int,
        val plugged: Int,
        val temperature: Int,
        val voltage: Int,
        val technology: String?,
        val status: Int,
        val current: Int?,
        val currentAverage: Int?
    )

    /** The live snapshot. A null intent yields the all-unknowns answer, not an exception. */
    fun read(context: Context): BatteryInfo {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return BatteryInfo(-1, -1, -1, -1, -1, -1, null, -1, null, null)
        // The intent carries no current: Termux:API reads it the same way, as
        // a BatteryManager property, and a device that reports 0 is a device
        // that does not support it, not a battery at exactly zero current.
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val current = batteryManager
            ?.let { runCatching { it.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }.getOrNull() }
            ?.takeIf { it != 0 }
        val currentAverage = batteryManager
            ?.let { runCatching { it.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE) }.getOrNull() }
            ?.takeIf { it != 0 }
        return BatteryInfo(
            level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100),
            health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1),
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
            temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0),
            voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0),
            technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),
            status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1),
            current = current,
            currentAverage = currentAverage
        )
    }

    fun current(context: Context): String = build(read(context))

    override fun run(context: Context, request: ApiRequest): String = current(context)

    /** The exact JSON shape Termux:API's battery-status emits. */
    fun build(info: BatteryInfo): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"batteryLevel\":").append(info.level).append(',')
        sb.append("\"batteryScale\":").append(info.scale).append(',')
        sb.append("\"batteryStatus\":").append(json(statusString(info.status))).append(',')
        sb.append("\"batteryHealth\":").append(json(healthString(info.health))).append(',')
        sb.append("\"batteryPlugged\":").append(json(pluggedString(info.plugged))).append(',')
        sb.append("\"batteryTemp\":").append(info.temperature)
        if (info.voltage != 0) {
            sb.append(',').append("\"batteryVoltage\":").append(info.voltage)
        }
        if (info.current != null) {
            sb.append(',').append("\"current\":").append(info.current)
        }
        if (info.currentAverage != null) {
            sb.append(',').append("\"current_average\":").append(info.currentAverage)
        }
        if (!info.technology.isNullOrEmpty()) {
            sb.append(',').append("\"batteryTechnology\":").append(json(info.technology))
        }
        sb.append('}')
        return sb.toString()
    }

    private fun healthString(health: Int): String = when (health) {
        BatteryManager.BATTERY_HEALTH_COLD -> "COLD"
        BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
        BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER_VOLTAGE"
        BatteryManager.BATTERY_HEALTH_UNKNOWN -> "UNKNOWN"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "UNSPECIFIED_FAILURE"
        else -> health.toString()
    }

    private fun pluggedString(plugged: Int): String = when (plugged) {
        0 -> "UNPLUGGED"
        BatteryManager.BATTERY_PLUGGED_AC -> "PLUGGED_AC"
        BatteryManager.BATTERY_PLUGGED_USB -> "PLUGGED_USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "PLUGGED_WIRELESS"
        BatteryManager.BATTERY_PLUGGED_DOCK -> "PLUGGED_DOCK"
        else -> plugged.toString()
    }

    private fun statusString(status: Int): String = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
        BatteryManager.BATTERY_STATUS_FULL -> "FULL"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
        BatteryManager.BATTERY_STATUS_UNKNOWN -> "UNKNOWN"
        else -> status.toString()
    }

    private fun json(value: String): String =
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
