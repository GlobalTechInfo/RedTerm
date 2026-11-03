package com.redtermapp.util.api

import android.Manifest
import android.content.Context
import android.provider.CallLog
import com.redtermapp.util.api.ApiRequestParser.ApiRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * `termux-call-log`: the call history as a JSON array, newest first.
 * The limit/offset come from the caller and are applied in memory so the
 * answer is the same on every Android version: the provider only honours
 * the limit/offset query parameters from API 30 on, and before that it
 * would have returned the whole table.
 */
object CallLogApi : ApiFeature {

    override val name = "call-log"
    override val permissions = setOf(Manifest.permission.READ_CALL_LOG)

    override fun run(context: Context, request: ApiRequest): String {
        val limit = (request.int("limit") ?: 10).coerceAtLeast(0)
        val offset = (request.int("offset") ?: 0).coerceAtLeast(0)
        if (limit == 0) return "[]\n"

        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder("[")
        var first = true
        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI, null, null, null,
            "${CallLog.Calls.DATE} DESC"
        )
        cursor?.use { c ->
            val nameIdx = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
            val numberIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
            val dateIdx = c.getColumnIndex(CallLog.Calls.DATE)
            val durationIdx = c.getColumnIndex(CallLog.Calls.DURATION)
            val typeIdx = c.getColumnIndex(CallLog.Calls.TYPE)
            var seen = 0
            while (c.moveToNext()) {
                if (seen++ < offset) continue
                if (seen - offset > limit) break
                if (!first) sb.append(',')
                first = false
                sb.append('{')
                sb.append("\"name\":").append(json(c.getString(nameIdx) ?: ""))
                sb.append(",\"number\":").append(json(c.getString(numberIdx) ?: ""))
                sb.append(",\"date\":").append(json(dateFmt.format(Date(c.getLong(dateIdx)))))
                sb.append(",\"duration\":").append(c.getLong(durationIdx))
                sb.append(",\"type\":").append(json(callType(c.getInt(typeIdx))))
                sb.append('}')
            }
        }
        sb.append(']')
        return sb.toString() + "\n"
    }

    private fun callType(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "answered_externally"
        CallLog.Calls.BLOCKED_TYPE -> "blocked"
        else -> type.toString()
    }

    private fun json(s: String): String =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
