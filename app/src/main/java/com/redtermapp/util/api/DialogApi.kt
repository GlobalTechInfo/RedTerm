package com.redtermapp.util.api

import android.content.Context
import com.redtermapp.util.api.ApiRequestParser.ApiRequest
import com.redtermapp.util.api.ui.ApiUiBridge

/**
 * `termux-dialog`: a real dialog the user answers, blocking until the
 * answer (or timeout) and returning it as a JSON value. The widgets the
 * reference offers are all here: `text`, `confirm`, `spinner`, `radio`,
 * `checkbox`, `sheet`, `counter`, `date`, `time`; `speech` needs a
 * microphone capture loop and answers as unsupported rather than a
 * blank dialog.
 */
object DialogApi : ApiFeature {

    override val name = "dialog"
    override val permissions = emptySet<String>()

    override fun run(context: Context, request: ApiRequest): String {
        val kind = request.str("input_method") ?: "text"
        if (kind == "speech") {
            return "ERROR: the speech dialog needs a live microphone capture this bridge cannot block on; use redterm-microphone-record\n"
        }
        val values = request.str("input_values")?.split(',')?.map { it.trim() } ?: emptyList()
        val range = request.str("input_range") ?: ""
        val spec = ApiUiBridge.ApiUiSpec(
            kind = kind,
            timeoutSec = request.int("timeout") ?: 60,
            extras = mapOf(
                "redterm.api.title" to (request.str("input_title") ?: ""),
                "redterm.api.hint" to (request.str("input_hint") ?: ""),
                "redterm.api.values" to values.joinToString(","),
                "redterm.api.range" to range,
            )
        )
        return ApiUiBridge.show(context, com.redtermapp.util.api.ui.ApiDialogActivity::class.java, spec)
    }
}
