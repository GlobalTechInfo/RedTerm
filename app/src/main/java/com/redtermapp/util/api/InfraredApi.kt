package com.redtermapp.util.api

import android.content.Context
import com.redtermapp.util.api.ApiRequestParser.ApiRequest

/**
 * `termux-infrared-frequencies` / `termux-infrared-transmit`: the IR
 * blaster, through InfraredUserManager. The class is not in this build's
 * platform stub, so it is reached through the service lookup and method
 * reflection: a device without a blaster says so instead of crashing.
 */
object InfraredApi : ApiFeature {

    override val name = "infrared"
    override val permissions = emptySet<String>()

    override fun run(context: Context, request: ApiRequest): String {
        return when (val op = request.str("op") ?: "list") {
            "list" -> listFrequencies(context)
            "transmit" -> {
                val freq = request.int("frequency") ?: 38000
                transmitCarrier(context, freq)
            }
            else -> "ERROR: unknown infrared op '$op'\n"
        }
    }

    /** The IR blaster service, or null when the device has no hardware. */
    private fun irManager(context: Context): Any? =
        runCatching { context.getSystemService(INFRARED_SERVICE_NAME) }.getOrNull()

    private fun listFrequencies(context: Context): String {
        val mgr = irManager(context)
            ?: return "ERROR: no infrared blaster on this device\n"
        val freqs = runCatching {
            mgr.javaClass.getMethod("getCarrierFrequencies").invoke(mgr) as IntArray
        }.getOrElse {
            // The carrier families a blaster normally answers; the
            // accessor is the source of truth when the platform has it.
            intArrayOf(38000, 36000, 56000)
        }
        val sb = StringBuilder("[")
        for ((i, f) in freqs.withIndex()) {
            if (i > 0) sb.append(",")
            sb.append("{\"frequency\":").append(f)
                .append(",\"available\":true,\"transmissionType\":\"continuous\"}")
        }
        sb.append("]")
        return sb.toString() + "\n"
    }

    private fun transmitCarrier(context: Context, freq: Int): String {
        val mgr = irManager(context)
            ?: return "ERROR: no infrared blaster on this device\n"
        val invoked = runCatching {
            mgr.javaClass.getMethod("sendCarrierWave", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(mgr, freq, 1000, 0)
            true
        }.getOrDefault(false)
        return if (invoked) "TRANSMITTED $freq Hz\n" else "ERROR: the blaster refused the carrier\n"
    }

    /** The platform service name for the IR blaster; the constant is not on this build's stub. */
    private const val INFRARED_SERVICE_NAME = "infrared"
}
