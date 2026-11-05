package com.redtermapp.util.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The guest scripts are shell, and shell is read by running it: each one is
 * syntax-checked with `sh -n` and then executed against a stub helper that
 * prints its argv, so a test pins the exact extra sequence the app side
 * receives for a documented flag combination.
 */
class ApiHelperShellTest {

    private fun tempRootfs(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "redterm-apishell-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    @Test
    fun `every guest script is valid sh`() {
        val rootfs = tempRootfs()
        assertTrue(ApiHelper.installGuestScripts(null, rootfs))
        for (f in File(rootfs, "usr/local/bin").listFiles().orEmpty()) {
            val ok = ProcessBuilder("sh", "-n", f.absolutePath).start().waitFor() == 0
            assertTrue("${f.name} is not valid sh", ok)
        }
    }

    /** Runs <rootfs>/usr/local/bin/<name> with [args]; the helper path is swapped for a stub that prints each argv on its own line. */
    private fun forward(rootfs: File, name: String, vararg args: String): List<String> {
        ApiHelper.installGuestScripts(null, rootfs)
        val stubDir = File(rootfs, "stub")
        stubDir.mkdirs()
        val stub = File(stubDir, "redterm-api")
        stub.writeText("#!/bin/sh\nfor a in \"\$@\"; do printf '%s\\n' \"\$a\"; done\n")
        stub.setExecutable(true, false)
        val body = File(rootfs, "usr/local/bin/$name").readText()
            .replace("/usr/local/redterm/redterm-api", stub.absolutePath)
        val script = File(stubDir, name)
        script.writeText(body)
        val process = ProcessBuilder(listOf("sh", script.absolutePath) + args.toList())
            .redirectErrorStream(true)
            .start()
        val exit = process.waitFor()
        val lines = process.inputStream.bufferedReader().readLines()
        assertEquals("sh exited $exit, lines: $lines", 0, exit)
        return lines
    }

    @Test
    fun `volume flags map to the documented extras`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "volume", "--es", "op", "raise",
                "--es", "stream", "ring", "--ei", "value", "-1"),
            forward(rootfs, "termux-volume", "-i", "-s", "ring")
        )
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "volume", "--es", "op", "set",
                "--es", "stream", "", "--ei", "value", "75"),
            forward(rootfs, "termux-volume", "-f", "75")
        )
    }

    @Test
    fun `a toast with spaces in it stays one value`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "toast", "--es", "message", "hello world"),
            forward(rootfs, "termux-toast", "hello world")
        )
    }

    @Test
    fun `a notification message with spaces stays one value`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "notification", "--es", "op", "post",
                "--es", "title", "T", "--es", "message", "a b", "--ei", "id", "-1"),
            forward(rootfs, "termux-notification", "-t", "T", "-m", "a b")
        )
    }

    @Test
    fun `share joins the positionals into one text`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "share", "--es", "text", "hello world",
                "--es", "mime", "text/plain", "--es", "subject", "",
                "--esa", "files", ""),
            forward(rootfs, "termux-share", "-d", "text/plain", "hello", "world")
        )
    }

    @Test
    fun `clipboard set from a file passes the file content`() {
        val rootfs = tempRootfs()
        val file = File(rootfs, "clip.txt")
        file.writeText("abc def")
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "clipboard", "--es", "op", "set",
                "--es", "text", "abc def"),
            forward(rootfs, "termux-clipboard-set", "-f", file.absolutePath)
        )
    }

    @Test
    fun `brightness without an arg sends no level`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "brightness"),
            forward(rootfs, "termux-brightness")
        )
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "brightness", "--ei", "level", "128"),
            forward(rootfs, "termux-brightness", "128")
        )
    }

    @Test
    fun `vibrate with a pattern passes it as a single value`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "vibrate", "--ei", "duration", "500",
                "--es", "pattern", "100,200,100"),
            forward(rootfs, "termux-vibrate", "-P", "100,200,100")
        )
    }

    @Test
    fun `the passthrough script forwards untouched args`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION, "--es", "action", "battery-status"),
            forward(rootfs, "termux-api", "--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "battery-status")
        )
    }

    @Test
    fun `sms send keeps the message as one value`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "sms", "--es", "op", "send",
                "--es", "number", "+15551234", "--es", "text", "hi there"),
            forward(rootfs, "termux-sms-send", "+15551234", "hi there")
        )
    }

    @Test
    fun `the call script forwards the number`() {
        val rootfs = tempRootfs()
        val expected = listOf("--user", "0", "-a", ApiActions.ACTION,
            "--es", "action", "call", "--es", "number", "+15551234")
        assertEquals(expected, forward(rootfs, "termux-telephony-call", "+15551234"))
        assertEquals(expected, forward(rootfs, "termux-call", "+15551234"))
    }

    @Test
    fun `location with a timeout sends it as an int`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "location", "--ei", "timeout", "5000"),
            forward(rootfs, "termux-location", "5000")
        )
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION, "--es", "action", "location"),
            forward(rootfs, "termux-location")
        )
    }

    @Test
    fun `contact list passes an empty filter when none is given`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "contacts", "--es", "name", "ada lovelace"),
            forward(rootfs, "termux-contact-list", "ada lovelace")
        )
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "contacts", "--es", "name", ""),
            forward(rootfs, "termux-contact-list")
        )
    }

    @Test
    fun `download keeps the url and flags as one value each`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "download",
                "--es", "url", "https://example.com/a b",
                "--es", "description", "my file",
                "--es", "title", "T",
                "--es", "path", "/data/local/tmp/a"),
            forward(rootfs, "termux-download", "https://example.com/a b",
                "-d", "my file", "-t", "T", "-p", "/data/local/tmp/a")
        )
    }

    @Test
    fun `torch defaults to the status op`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION, "--es", "action", "torch", "--es", "op", "status"),
            forward(rootfs, "termux-torch")
        )
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION, "--es", "action", "torch", "--es", "op", "on"),
            forward(rootfs, "termux-torch", "on")
        )
    }

    @Test
    fun `sms inbox without a limit sends no limit extra`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION, "--es", "action", "sms", "--es", "op", "inbox"),
            forward(rootfs, "termux-sms-inbox")
        )
    }

    @Test
    fun `camera info and audio info send only the action extra`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION, "--es", "action", "camera-info"),
            forward(rootfs, "termux-camera-info")
        )
    }

    @Test
    fun `call log defaults are 10 limit and 0 offset`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "call-log", "--ei", "offset", "0", "--ei", "limit", "10"),
            forward(rootfs, "termux-call-log")
        )
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "call-log", "--ei", "offset", "5", "--ei", "limit", "25"),
            forward(rootfs, "termux-call-log", "-o", "5", "-l", "25")
        )
    }

    @Test
    fun `media scan sends paths as one escaped array`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "media-scan", "--esa", "paths", "/a,/b"),
            forward(rootfs, "termux-media-scan", "/a", "/b")
        )
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "media-scan", "--ez", "recursive", "true",
                "--esa", "paths", "/x"),
            forward(rootfs, "termux-media-scan", "-r", "/x")
        )
    }

    @Test
    fun `wallpaper set takes a file or url`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "wallpaper", "--es", "op", "set", "--es", "file", "/sd/p.jpg"),
            forward(rootfs, "termux-wallpaper", "-f", "/sd/p.jpg")
        )
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "wallpaper", "--es", "op", "set", "--es", "url", "https://e/x.jpg"),
            forward(rootfs, "termux-wallpaper", "-u", "https://e/x.jpg")
        )
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "wallpaper", "--es", "op", "remove"),
            forward(rootfs, "termux-wallpaper")
        )
    }

    @Test
    fun `usb open carries vendor and product ids`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "usb", "--es", "op", "open",
                "--es", "device", "", "--es", "vendorId", "0x1234", "--es", "productId", "0xabcd"),
            forward(rootfs, "termux-usb", "-o", "0x1234", "0xabcd")
        )
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "usb", "--es", "op", "list",
                "--es", "device", "", "--es", "vendorId", "", "--es", "productId", ""),
            forward(rootfs, "termux-usb", "-l")
        )
    }

    @Test
    fun `tts speak sends pitch and rate only when set`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "tts", "--es", "op", "speak",
                "--es", "text", "hi", "--es", "engine", "",
                "--es", "language", "", "--es", "region", "", "--es", "variant", "",
                "--es", "stream", ""),
            forward(rootfs, "termux-tts-speak", "hi")
        )
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "tts", "--es", "op", "speak",
                "--es", "text", "hi", "--es", "engine", "com.x.tts",
                "--es", "language", "en", "--es", "region", "", "--es", "variant", "",
                "--es", "stream", "", "--ef", "pitch", "1.5", "--ef", "rate", "2"),
            forward(rootfs, "termux-tts-speak", "-e", "com.x.tts", "-l", "en",
                "-p", "1.5", "-r", "2", "hi")
        )
    }

    @Test
    fun `tts engines lists the available engines`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "tts", "--es", "op", "engines", "--es", "engine", ""),
            forward(rootfs, "termux-tts-engines")
        )
    }

    @Test
    fun `download carries the url and optional flags`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "download",
                "--es", "url", "https://x/a",
                "--es", "description", "d", "--es", "title", "t", "--es", "path", "/p"),
            forward(rootfs, "termux-download", "https://x/a", "-d", "d", "-t", "t", "-p", "/p")
        )
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "download",
                "--es", "url", "https://x/a",
                "--es", "description", "", "--es", "title", "", "--es", "path", ""),
            forward(rootfs, "termux-download", "https://x/a")
        )
    }

    @Test
    fun `sensor list sends only the action extra`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "sensor", "--es", "op", "list"),
            forward(rootfs, "termux-sensor")
        )
    }

    @Test
    fun `media player maps subcommands to ops`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "media-player", "--es", "op", "play", "--es", "file", "/a.mp3"),
            forward(rootfs, "termux-media-player", "play", "/a.mp3")
        )
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "media-player", "--es", "op", "pause"),
            forward(rootfs, "termux-media-player", "pause")
        )
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "media-player", "--es", "op", "resume"),
            forward(rootfs, "termux-media-player", "play")
        )
    }

    @Test
    fun `mic record start sends the bare numeric extras only when set`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "mic-record", "--es", "op", "start"),
            forward(rootfs, "termux-microphone-record", "-d")
        )
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "mic-record", "--es", "op", "stop"),
            forward(rootfs, "termux-microphone-record", "-q")
        )
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "mic-record", "--es", "op", "start",
                "--es", "file", "a.m4a", "--ei", "limit", "5000", "--es", "encoder", "aac"),
            forward(rootfs, "termux-microphone-record", "-f", "a.m4a", "-l", "5", "-e", "aac")
        )
    }

    @Test
    fun `infrared transmit defaults to 38000`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "infrared", "--es", "op", "transmit", "--ei", "frequency", "38000"),
            forward(rootfs, "termux-infrared-transmit")
        )
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "infrared", "--es", "op", "transmit", "--ei", "frequency", "40000"),
            forward(rootfs, "termux-infrared-transmit", "40000")
        )
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "infrared", "--es", "op", "list"),
            forward(rootfs, "termux-infrared-frequencies")
        )
    }

    @Test
    fun `telephony deviceinfo sends only the action extra`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION, "--es", "action", "telephony-deviceinfo"),
            forward(rootfs, "termux-telephony-deviceinfo")
        )
    }

    @Test
    fun `sscid and wifi connectioninfo pick their ops`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION, "--es", "action", "sscid", "--es", "op", "ssid"),
            forward(rootfs, "termux-sscid")
        )
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION, "--es", "action", "sscid", "--es", "op", "info"),
            forward(rootfs, "termux-wifi-connectioninfo")
        )
    }

    @Test
    fun `keystore list and create carry the right extras`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "keystore", "--es", "command", "list", "--es", "alias", ""),
            forward(rootfs, "termux-keystore", "list")
        )
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "keystore", "--es", "command", "create", "--es", "alias", "mykey",
                "--es", "algorithm", "ec", "--es", "curve", "P-256"),
            forward(rootfs, "termux-keystore", "create", "mykey", "-a", "ec", "-c", "P-256")
        )
    }

    @Test
    fun `storage get and saf create forward their args`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "storage-get", "--es", "file", "/sdcard/a"),
            forward(rootfs, "termux-storage-get", "/sdcard/a")
        )
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "saf", "--es", "safmethod", "createDocument",
                "--es", "treeuri", "content://x", "--es", "filename", "f.txt"),
            forward(rootfs, "termux-saf-create", "content://x", "f.txt")
        )
    }

    @Test
    fun `api start and stop pick their ops`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION, "--es", "action", "api-start", "--es", "op", "start"),
            forward(rootfs, "termux-api-start")
        )
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION, "--es", "action", "api-start", "--es", "op", "stop"),
            forward(rootfs, "termux-api-stop")
        )
    }

    @Test
    fun `dialog text carries hint and title`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "dialog", "--es", "input_method", "text",
                "--es", "input_hint", "your name", "--es", "input_title", "Who?"),
            forward(rootfs, "termux-dialog", "text", "-i", "your name", "-t", "Who?")
        )
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "dialog", "--es", "input_method", "spinner",
                "--es", "input_title", "Pick", "--es", "input_values", "a,b,c"),
            forward(rootfs, "termux-dialog", "spinner", "-v", "a,b,c", "-t", "Pick")
        )
    }

    @Test
    fun `nfc read write and status pick their ops`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION, "--es", "action", "nfc", "--es", "op", "status"),
            forward(rootfs, "termux-nfc")
        )
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "nfc", "--es", "op", "write", "--es", "value", "hi"),
            forward(rootfs, "termux-nfc", "-w", "hi")
        )
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION, "--es", "action", "nfc", "--es", "op", "read"),
            forward(rootfs, "termux-nfc", "-l")
        )
    }

    @Test
    fun `fingerprint check and scan pick their ops`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "fingerprint", "--es", "op", "check"),
            forward(rootfs, "termux-fingerprint", "-c")
        )
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "fingerprint", "--es", "op", "scan", "--es", "description", "prove"),
            forward(rootfs, "termux-fingerprint", "-s", "-d", "prove")
        )
    }

    @Test
    fun `job scheduler sends bare extras only when set`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "job-scheduler", "--es", "script", "backup", "--ei", "period_ms", "60000"),
            forward(rootfs, "termux-job-scheduler", "--script", "backup", "--period-ms", "60000")
        )
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "job-scheduler", "--ez", "cancel_all", "true"),
            forward(rootfs, "termux-job-scheduler", "--cancel-all")
        )
    }

    @Test
    fun `callback carries url data and method`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "callback", "--es", "url", "http://127.0.0.1:8000",
                "--es", "data", "{}", "--es", "method", "GET", "--ei", "timeout", "10"),
            forward(rootfs, "termux-callback", "http://127.0.0.1:8000", "-d", "{}", "-m", "GET")
        )
    }

    @Test
    fun `notification list and remove pick their ops`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION, "--es", "action", "notification", "--es", "op", "list"),
            forward(rootfs, "termux-notification-list")
        )
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "notification", "--es", "op", "remove", "--ei", "id", "42"),
            forward(rootfs, "termux-notification-remove", "-i", "42")
        )
    }

    @Test
    fun `camera photo carries camera and file`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "camera-photo", "--es", "camera", "1", "--es", "file", "/out.jpg"),
            forward(rootfs, "termux-camera-photo", "-c", "1", "/out.jpg")
        )
    }

    @Test
    fun `notification channel create and delete`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "notification-channel", "--es", "id", "myid", "--es", "name", "My Name"),
            forward(rootfs, "termux-notification-channel", "myid", "My Name")
        )
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "notification-channel", "--es", "id", "myid", "--es", "name", "", "--ez", "delete", "true"),
            forward(rootfs, "termux-notification-channel", "-d", "myid")
        )
    }

    @Test
    fun `saf methods pick their safmethod`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "saf", "--es", "safmethod", "getManagedDocumentTrees"),
            forward(rootfs, "termux-saf-dirs")
        )
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "saf", "--es", "safmethod", "listDirectory", "--es", "treeuri", "content://t"),
            forward(rootfs, "termux-saf-ls", "content://t")
        )
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "saf", "--es", "safmethod", "removeDocument", "--es", "uri", "content://d"),
            forward(rootfs, "termux-saf-rm", "content://d")
        )
    }

    @Test
    fun `sms list carries limit and offset`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "sms", "--es", "op", "list", "--ei", "limit", "5", "--ei", "offset", "2"),
            forward(rootfs, "termux-sms-list", "-l", "5", "-o", "2")
        )
    }

    @Test
    fun `speech to text carries the language`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "speech-to-text", "--es", "language", "es-ES"),
            forward(rootfs, "termux-speech-to-text", "-l", "es-ES")
        )
    }

    @Test
    fun `cellinfo wifi enable and sensor read pick their ops`() {
        val rootfs = tempRootfs()
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION, "--es", "action", "telephony-cellinfo"),
            forward(rootfs, "termux-telephony-cellinfo")
        )
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "wifi-enable", "--ez", "enabled", "true"),
            forward(rootfs, "termux-wifi-enable", "true")
        )
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION,
                "--es", "action", "sensor", "--es", "op", "read", "--es", "sensor", "accel",
                "--ei", "limit", "3", "--ei", "delay", "200"),
            forward(rootfs, "termux-sensor", "-s", "accel", "-n", "3")
        )
        assertEquals(
            listOf("--user", "0", "-a", ApiActions.ACTION, "--es", "action", "sensor", "--es", "op", "list"),
            forward(rootfs, "termux-sensor")
        )
    }
}
