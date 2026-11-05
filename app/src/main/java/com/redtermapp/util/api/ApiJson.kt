package com.redtermapp.util.api

/**
 * The one correct JSON string encoder for the bridge's JSON answers. The
 * ApiFeatures hand-rolled a `replace("\"", "\\\"")` escaper, which produces
 * invalid JSON the moment a value carries a control character — and an
 * SSID is the value most likely to: `\n`, `\t` and raw 0x00-0x1F bytes are
 * all legal in an SSID. Every JSON string the bridge emits goes through
 * this one place so that invariant is in one file, not re-derived (or
 * re-missed) per command.
 */
object ApiJson {

    fun string(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20) {
                        sb.append(java.lang.String.format("\\u%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
