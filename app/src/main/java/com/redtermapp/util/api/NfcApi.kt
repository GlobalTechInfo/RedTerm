package com.redtermapp.util.api

import android.content.Context
import android.nfc.NfcAdapter
import com.redtermapp.util.api.ApiRequestParser.ApiRequest
import com.redtermapp.util.api.ui.ApiUiBridge
import com.redtermapp.util.api.ui.ApiNfcActivity

/**
 * `termux-nfc`: read or write an NDEF tag, or report the adapter's
 * state. Read/write are blocking (the guest waits while the user holds a
 * tag to the reader); state is instant. A device without NFC says so.
 */
object NfcApi : ApiFeature {

    override val name = "nfc"
    override val permissions = emptySet<String>()

    override fun run(context: Context, request: ApiRequest): String {
        val op = request.str("op") ?: "status"
        val adapter = NfcAdapter.getDefaultAdapter(context)
        return when (op) {
            "status" -> {
                if (adapter == null) "ERROR: this device has no NFC hardware\n"
                else "nfc present, ${if (adapter.isEnabled) "enabled" else "disabled"}\n"
            }
            "enable", "disable" -> "ERROR: toggling NFC needs the system settings screen; enable/disable it there, then use -l or -w\n"
            "read" -> {
                if (adapter == null) return "ERROR: this device has no NFC hardware\n"
                ApiUiBridge.show(
                    context,
                    ApiNfcActivity::class.java,
                    ApiUiBridge.ApiUiSpec("nfc-read", request.int("timeout") ?: 30, emptyMap())
                )
            }
            "write" -> {
                if (adapter == null) return "ERROR: this device has no NFC hardware\n"
                val value = request.str("value") ?: return "ERROR: write needs a value\n"
                ApiUiBridge.show(
                    context,
                    ApiNfcActivity::class.java,
                    ApiUiBridge.ApiUiSpec(
                        "nfc-write",
                        request.int("timeout") ?: 30,
                        mapOf("redterm.api.nfcValue" to value)
                    )
                )
            }
            else -> "ERROR: unknown nfc op '$op'\n"
        }
    }
}
