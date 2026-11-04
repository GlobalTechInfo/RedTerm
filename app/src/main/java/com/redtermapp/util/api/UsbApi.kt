package com.redtermapp.util.api

import android.content.Context
import android.hardware.usb.UsbManager
import com.redtermapp.util.api.ApiRequestParser.ApiRequest

/**
 * `termux-usb`: lists attached USB devices. The reference command also
 * opens a device and hands a file descriptor to the caller, which a
 * one-shot request/response bridge cannot do, so only `list` is offered
 * and the rest say so instead of pretending.
 */
object UsbApi : ApiFeature {

    override val name = "usb"
    override val permissions = emptySet<String>()

    override fun run(context: Context, request: ApiRequest): String {
        val op = request.str("op") ?: "list"
        return when (op) {
            "list" -> list(context)
            "open", "permission" -> "ERROR: redterm-usb -o/-p needs a file descriptor handoff this bridge does not offer; list works\n"
            else -> "ERROR: unknown usb op '$op'\n"
        }
    }

    private fun list(context: Context): String {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val sb = StringBuilder("[")
        val entries = manager.deviceList.entries.toList()
        for ((i, entry) in entries.withIndex()) {
            val (name, device) = entry
            if (i > 0) sb.append(",")
            sb.append('{')
                .append("\"path\":").append(json(name))
                .append(",\"vendor\":").append(device.vendorId)
                .append(",\"product\":").append(device.productId)
                .append(",\"class\":").append(device.deviceClass)
                .append(",\"subClass\":").append(device.deviceSubclass)
                .append(",\"protocol\":").append(device.deviceProtocol)
                .append('}')
        }
        sb.append(']')
        return sb.toString() + "\n"
    }

    private fun json(s: String): String =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
