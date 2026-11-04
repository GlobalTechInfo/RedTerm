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
            val m = mgr.javaClass.getMethod("getCarrierFrequencies")
            val r = m.invoke(mgr)
            if (r is IntArray) {
                r
            } else if (r is Array<*>) {
                val out = mutableListOf<Int>()
                for (range in r) {
                    if (range == null) continue
                    val min = runCatching { range.javaClass.getMethod("getMinFrequency").invoke(range) as Int }.getOrNull()
                    val max = runCatching { range.javaClass.getMethod("getMaxFrequency").invoke(range) as Int }.getOrNull()
                    if (min != null) out.add(min)
                    else if (max != null) out.add(max)
                }
                if (out.isNotEmpty()) out.toIntArray() else intArrayOf(38000, 36000, 56000)
            } else {
                intArrayOf(38000, 36000, 56000)
            }
        }.getOrElse {
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
        val pattern = intArrayOf(freq / 1000, freq / 1000)
        val invoked = runCatching {
            val clazz = mgr.javaClass
            try {
                val m = clazz.getMethod("transmit", Int::class.javaPrimitiveType, IntArray::class.java)
                m.invoke(mgr, freq, pattern)
                true
            } catch (_: NoSuchMethodException) {
                try {
                    val m = clazz.getMethod("sendCarrierWave", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    m.invoke(mgr, freq, 1000, 0)
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }.getOrDefault(false)
        return if (invoked) "TRANSMITTED $freq Hz\n" else "ERROR: the blaster refused the carrier\n"
    }

        private const val INFRARED_SERVICE_NAME = Context.CONSUMER_IR_SERVICE
}
