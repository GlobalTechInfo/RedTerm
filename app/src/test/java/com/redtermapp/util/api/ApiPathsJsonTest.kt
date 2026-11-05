package com.redtermapp.util.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two pure objects the bridge's path- and string-projection depends on:
 * a guest path only becomes a host path when the session carries its rootfs
 * origin (or the path is under a proot bind), and a JSON string is only
 * valid when every control character is escaped. Both are asserted by value
 * so a regression in the projection or the encoder shows up here, not in a
 * half-broken share sheet or an invalid SSID on a real device.
 */
class ApiPathsJsonTest {

    private val rootfs = "/data/user/0/redterm/rootfs"

    // --- ApiPaths.project --------------------------------------------------

    @Test
    fun `a path under a proot bind is the same string on both sides`() {
        for (p in listOf("/sdcard/DCIM/a.jpg", "/system/bin/sh", "/sdcard/Download/b")) {
            assertEquals(p, ApiPaths.project(rootfs, p))
        }
    }

    @Test
    fun `an absolute guest path is projected under the origin`() {
        assertEquals("$rootfs/root/photo.jpg", ApiPaths.project(rootfs, "/root/photo.jpg"))
    }

    @Test
    fun `a relative path cannot be projected`() {
        assertNull(ApiPaths.project(rootfs, "photo.jpg"))
    }

    @Test
    fun `a bind path with a dotdot that leaves the bind is projected, not returned`() {
        // /dev/../data/... collapses to /data/..., which is not a bind dir,
        // so it is projected under the origin, not handed back as-is.
        val out = ApiPaths.project(rootfs, "/dev/../data/user/0/x")
        assertEquals("$rootfs/data/user/0/x", out)
    }

    @Test
    fun `a path that escapes the origin by traversal is rejected`() {
        assertNull(ApiPaths.project(rootfs, "/../../etc/passwd"))
        assertNull(ApiPaths.project(rootfs, "/root/../../etc/shadow"))
    }

    @Test
    fun `unreachable names the path`() {
        assertEquals(
            "ERROR: '/root/x' is not reachable from the app process: guest paths must be absolute " +
                "(a relative one needs the guest's realpath), and the session must carry a rootfs origin " +
                "that is one of the app's own rootfs trees\n",
            ApiPaths.unreachable("/root/x")
        )
    }

    // --- ApiJson.string ----------------------------------------------------

    @Test
    fun `plain text is quoted unchanged`() {
        assertEquals("\"hello\"", ApiJson.string("hello"))
    }

    @Test
    fun `quotes and backslashes are escaped`() {
        assertEquals("\"a\\\"b\"", ApiJson.string("a\"b"))
        assertEquals("\"a\\\\b\"", ApiJson.string("a\\b"))
    }

    @Test
    fun `the common control characters get their short escapes`() {
        assertEquals("\"a\\nb\"", ApiJson.string("a\nb"))
        assertEquals("\"a\\tb\"", ApiJson.string("a\tb"))
        assertEquals("\"a\\rb\"", ApiJson.string("a\rb"))
    }

    @Test
    fun `rare control characters are escaped as unicode`() {
        // 0x01 has no short escape; it must come out as \u0001, not raw.
        assertEquals("\"\\u0001\"", ApiJson.string("\u0001"))
        // 0x1F (unit separator) is likewise in the 0x00-0x1F range.
        assertEquals("\"\\u001f\"", ApiJson.string("\u001f"))
    }

    @Test
    fun `DEL and above are passed through`() {
        // 0x7F is at/above 0x20, so it is kept as the literal character,
        // not escaped. The result is quote + DEL + quote.
        assertEquals("\"\u007f\"", ApiJson.string("\u007f"))
    }

    @Test
    fun `an SSID full of every escape kind stays one valid JSON string`() {
        val ssid = "cafe\n\u0001tab\t\"q\"\\slash"
        val encoded = ApiJson.string(ssid)
        // Round-trip through a real JSON decoder proves the output is valid
        // JSON, not merely that a few characters changed.
        val parsed = org.json.JSONObject("{\"s\": $encoded}").getString("s")
        assertEquals(ssid, parsed)
    }
}
