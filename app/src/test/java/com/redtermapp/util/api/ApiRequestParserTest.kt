package com.redtermapp.util.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire grammar is what the guest helper speaks, so it is pinned here
 * against the exact strings the helper produces (measured against the
 * helper on a real socket), not against what the parser wanted to match.
 */
class ApiRequestParserTest {

    private fun parse(cmdline: String): ApiRequestParser.Result = ApiRequestParser.parse(cmdline)

    @Test
    fun `the rootfs-host line the rebuilt helper appends parses`() {
        // Measured from the aarch64 helper against a host listener: the
        // rootfs-host extra travels as a quoted --es value after the action.
        val result = parse(
            "--user 0 -a com.redtermapp.api.TERMUX_ACTION " +
                "--es action \"api-start\" --es redterm-rootfs-host \"/data/user/0/com.redtermapp/files/rootfs/debian\""
        )
        val ok = result as ApiRequestParser.Result.Ok
        assertEquals("api-start", ok.request.extras.strings["action"])
        assertEquals(
            "/data/user/0/com.redtermapp/files/rootfs/debian",
            ok.request.extras.strings["redterm-rootfs-host"]
        )
    }

    @Test
    fun `the battery-status line with rootfs-host parses`() {
        // The exact wire bytes the host-built helper emits for
        // redterm-battery-status in a distro session that exports REDTERM_ROOTFS_HOST.
        val result = parse(
            "--user 0 -a com.redtermapp.api.TERMUX_ACTION " +
                "--es action \"battery-status\" --es redterm-rootfs-host \"/data/user/0/com.redtermapp/files/rootfs/debian\""
        )
        val ok = result as ApiRequestParser.Result.Ok
        assertEquals(ApiActions.ACTION, ok.request.action)
        assertEquals("battery-status", ok.request.extras.strings["action"])
        assertEquals(0, ok.request.extras.ints["user"])
    }

    @Test
    fun `the battery request the helper actually sends parses`() {
        val result = parse(
            "--user 0 -a com.redtermapp.api.TERMUX_ACTION --es action \"battery-status\" --es note \"hello world\""
        )
        val ok = result as ApiRequestParser.Result.Ok
        assertEquals(ApiActions.ACTION, ok.request.action)
        assertEquals(0, ok.request.extras.ints["user"])
        assertEquals("battery-status", ok.request.extras.strings["action"])
        assertEquals("hello world", ok.request.extras.strings["note"])
    }

    @Test
    fun `escaped quotes inside a value come back unescaped`() {
        val result = parse("-a X --es k \"say \\\"hi\\\"\" --ei n 42 --ez b true")
        val ok = result as ApiRequestParser.Result.Ok
        assertEquals("X", ok.request.action)
        assertEquals("say \"hi\"", ok.request.extras.strings["k"])
        assertEquals(42, ok.request.extras.ints["n"])
        assertEquals(true, ok.request.extras.booleans["b"])
    }

    @Test
    fun `string arrays split on unescaped commas`() {
        // The wire form of an escaped comma is a single backslash: "c\,d".
        val result = parse("-a X --esa k \"a,b,c\\,d\"")
        val ok = result as ApiRequestParser.Result.Ok
        assertEquals(listOf("a", "b", "c,d"), ok.request.extras.stringArrays.getValue("k").toList())
    }

    @Test
    fun `the exact media-scan wire line the helper sends parses`() {
        // What the guest script (v4) + helper actually put on the wire for
        // `redterm-media-scan -v /sdcard/DCIM`, measured from the guest
        // side: one array element, not an empty one.
        val result = parse(
            "--user 0 -a com.redtermapp.api.TERMUX_ACTION " +
                "--es action \"media-scan\" --ez verbose true --esa paths \"/sdcard/DCIM\""
        )
        val ok = result as ApiRequestParser.Result.Ok
        assertEquals("media-scan", ok.request.extras.strings.getValue("action"))
        assertEquals(true, ok.request.extras.booleans.getValue("verbose"))
        assertEquals(listOf("/sdcard/DCIM"), ok.request.extras.stringArrays.getValue("paths").toList())
    }

    @Test
    fun `float extras parse`() {
        val result = parse("-a X --ef v -1.5")
        val ok = result as ApiRequestParser.Result.Ok
        assertEquals(-1.5f, ok.request.extras.floats.getValue("v"))
    }

    @Test
    fun `an unknown long option is reported, not silently dropped`() {
        val result = parse("-a X --ex k \"v\"")
        val error = result as ApiRequestParser.Result.Error
        assertTrue(error.message, error.message.contains("--ex k"))
    }

    @Test
    fun `leftover bare words are reported`() {
        val result = parse("-a X trailing junk")
        val error = result as ApiRequestParser.Result.Error
        assertTrue(error.message, error.message.contains("trailing junk"))
    }

    @Test
    fun `no action is an error`() {
        val result = parse("--es a \"1\"")
        assertTrue(result is ApiRequestParser.Result.Error)
    }

    @Test
    fun `a missing value on an option is an error, not a parse of nothing`() {
        val result = parse("-a X --es")
        val error = result as ApiRequestParser.Result.Error
        assertTrue(error.message, error.message.isNotEmpty())
    }

    @Test
    fun `a value ending in a backslash parses`() {
        // The wire form of the value "abc\" is "abc\\" — the closing quote
        // is preceded by an escaped backslash, which the old lookbehind
        // regex could not tell from an escaped quote.
        val result = parse("-a X --es k \"abc\\\\\"")
        val ok = result as ApiRequestParser.Result.Ok
        assertEquals("abc\\", ok.request.extras.strings["k"])
    }

    @Test
    fun `an embedded backslash comes back unescaped`() {
        val result = parse("-a X --es k \"a\\\\b\"")
        val ok = result as ApiRequestParser.Result.Ok
        assertEquals("a\\b", ok.request.extras.strings["k"])
    }

    @Test
    fun `numeric and boolean values travel bare`() {
        val result = parse("-a X --ei level 25 --ez on true --ef gain 1.5")
        val ok = result as ApiRequestParser.Result.Ok
        assertEquals(25, ok.request.extras.ints["level"])
        assertEquals(true, ok.request.extras.booleans["on"])
        assertEquals(1.5f, ok.request.extras.floats["gain"])
    }

    @Test
    fun `option-looking text inside a quoted value does not leak`() {
        val result = parse("-a X --es k \"a --es b \\\"c\\\" d\" --es real \"v\"")
        val ok = result as ApiRequestParser.Result.Ok
        assertEquals("a --es b \"c\" d", ok.request.extras.strings["k"])
        assertEquals("v", ok.request.extras.strings["real"])
    }
}
