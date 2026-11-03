package com.redtermapp.util.api

import java.util.regex.Pattern

/**
 * The request grammar the guest-side [redterm-api] helper speaks: the text of
 * a fake `am` command line, exactly the grammar Termux:API's SocketListener
 * parses, so termux-shaped invocations work unchanged:
 *
 *   -a "ACTION" --es key "value" --esa key "a,b" --ei key n --ez key true --ef key f
 *
 * Values arrive quoted and escaped (\" and \\); keys and flags are bare.
 * This is a pure function, which is what keeps the guest-visible contract
 * testable without a socket.
 */
object ApiRequestParser {

    data class ApiExtras(
        val strings: Map<String, String> = emptyMap(),
        val stringArrays: Map<String, Array<String>> = emptyMap(),
        val booleans: Map<String, Boolean> = emptyMap(),
        val ints: Map<String, Int> = emptyMap(),
        val floats: Map<String, Float> = emptyMap(),
    )

    data class ApiRequest(val action: String, val extras: ApiExtras)

    sealed class Result {
        data class Ok(val request: ApiRequest) : Result()
        data class Error(val message: String) : Result()
    }

    // Value of -a: a bare word.
    private val ACTION = Pattern.compile("-a *([^ ]+)")
    // Head of a string extra: option, bare key, and the opening quote. The
    // quoted value itself is scanned char by char, because a regex cannot
    // see that a closing quote is part of an escaped backslash pair.
    private val EXTRA_HEAD = Pattern.compile("(--esa|-e|--es) +([^ ]+) +\"")
    // Numeric/boolean extras travel bare on the wire: their values cannot
    // hold a space, so the helper quotes nothing for them.
    private val EXTRA_BOOLEAN = Pattern.compile("--ez +([^ ]+) +([^ ]+)")
    private val EXTRA_INT = Pattern.compile("--ei +([^ ]+) +(-?[0-9]+)")
    private val EXTRA_FLOAT = Pattern.compile("--ef +([^ ]+) +(-?[0-9]+(?:\\.[0-9]+))")

    /** Invert the wire escape: the helper wrote `\` before a `\` or a `"`. */
    private val UNESCAPE = Regex("\\\\(.)", RegexOption.DOT_MATCHES_ALL)
    private fun unescape(escaped: String): String =
        UNESCAPE.replace(escaped) { it.groupValues[1] }

    /** The parts of [text] covered by [spans] become spaces; the rest is kept for the next phase. */
    private fun blanked(text: String, spans: List<IntRange>): String {
        if (spans.isEmpty()) return text
        val sb = StringBuilder(text)
        for (span in spans) for (i in span) sb.setCharAt(i, ' ')
        return sb.toString()
    }

    /** Parse a wire request. [Result.Error]'s message is what the guest sees. */
    fun parse(cmdline: String): Result {
        val stringExtras = HashMap<String, String>()
        val stringArrayExtras = HashMap<String, Array<String>>()
        val booleanExtras = HashMap<String, Boolean>()
        val intExtras = HashMap<String, Int>()
        val floatExtras = HashMap<String, Float>()

        // String extras first, so a flag embedded inside a quoted value is not
        // mistaken for a real argument. The head regex stops at the opening
        // quote; the value is then scanned: a backslash always escapes the
        // next character, and the unescaped closing quote ends it. A head
        // that opens inside a value already consumed is a value, not an arg.
        val chars = cmdline.toCharArray()
        val stringSpans = ArrayList<IntRange>()
        val head = EXTRA_HEAD.matcher(cmdline)
        while (head.find()) {
            if (stringSpans.any { head.start() in it }) continue
            val option = head.group(1) ?: continue
            val key = head.group(2) ?: continue
            var close = head.end()
            var found = false
            while (close < chars.size) {
                when (chars[close]) {
                    '\\' -> close++
                    '"' -> { found = true; break }
                }
                close++
            }
            if (!found) continue
            stringSpans += head.start()..close
            val raw = String(chars, head.end(), close - head.end())
            if (option == "--esa") {
                // Split on the escaped form, then unescape: a `\,` is a comma
                // inside one element, and every escape round-trips.
                stringArrayExtras[key] = raw
                    .split(Regex("(?<!\\\\),"))
                    .map { unescape(it) }
                    .toTypedArray()
            } else {
                stringExtras[key] = unescape(raw)
            }
        }
        var rest = blanked(cmdline, stringSpans)

        var m = EXTRA_BOOLEAN.matcher(rest)
        while (m.find()) {
            val key = m.group(1) ?: continue
            val raw = m.group(2)
            val arg = when (raw?.lowercase()) {
                "true", "t" -> true
                "false", "f" -> false
                else -> raw?.toIntOrNull()?.let { it != 0 }
            }
            if (arg == null) return Result.Error("Invalid boolean extra: --ez $key $raw\n")
            booleanExtras[key] = arg
        }
        rest = EXTRA_BOOLEAN.matcher(rest).replaceAll(" ")

        m = EXTRA_INT.matcher(rest)
        while (m.find()) {
            val key = m.group(1) ?: continue
            val value = m.group(2)?.toIntOrNull()
            if (value == null) return Result.Error("Invalid integer extra: --ei $key ${m.group(2)}\n")
            intExtras[key] = value
        }
        rest = EXTRA_INT.matcher(rest).replaceAll(" ")

        m = EXTRA_FLOAT.matcher(rest)
        while (m.find()) {
            val key = m.group(1) ?: continue
            val value = m.group(2)?.toFloatOrNull()
            if (value == null) return Result.Error("Invalid float extra: --ef $key ${m.group(2)}\n")
            floatExtras[key] = value
        }
        rest = EXTRA_FLOAT.matcher(rest).replaceAll(" ")

        val actions = ArrayList<String>()
        m = ACTION.matcher(rest)
        while (m.find()) actions.add(m.group(1) ?: "")
        if (actions.isEmpty()) return Result.Error("No action given\n")
        if (actions.size > 1) return Result.Error("Multiple actions given: ${actions.joinToString(" ")}\n")
        rest = ACTION.matcher(rest).replaceAll(" ")

        // `--user N` is part of the am-style invocation every termux script
        // carries; this bridge answers only for the calling app's own uid,
        // so it is consumed and kept, not acted on.
        val USER = Pattern.compile("--user +(-?[0-9]+)")
        m = USER.matcher(rest)
        while (m.find()) {
            intExtras["user"] = m.group(1)?.toIntOrNull() ?: 0
        }
        rest = USER.matcher(rest).replaceAll(" ")

        // Anything left over is an argument type this bridge does not speak
        // (--eia/--ela lists, bare words, unknown --e* flags). Saying so is
        // the difference between "unsupported" and "silently dropped"; the
        // original spacing is kept so the report is readable.
        val leftover = rest.trim()
        if (leftover.isNotEmpty()) {
            return Result.Error("Unsupported options: $leftover\n")
        }

        return Result.Ok(
            ApiRequest(
                action = actions[0],
                extras = ApiExtras(
                    strings = stringExtras,
                    stringArrays = stringArrayExtras,
                    booleans = booleanExtras,
                    ints = intExtras,
                    floats = floatExtras,
                )
            )
        )
    }
}
