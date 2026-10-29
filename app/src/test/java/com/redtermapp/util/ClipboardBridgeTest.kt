package com.redtermapp.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The guest half of the clipboard bridge is a shell file, so it is run, not
 * read: the SET and GET paths go through a real /bin/sh with the shim's
 * text, and the app half is checked on real files.
 */
class ClipboardBridgeTest {

    private val scratch = ArrayList<File>()

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "redterm-$name-${System.nanoTime()}")
            .apply { mkdirs(); scratch.add(this) }

    @After
    fun clean() {
        scratch.forEach { it.deleteRecursively() }
    }

    private fun runSh(
        args: List<String>,
        env: Map<String, String> = emptyMap(),
        stdin: String? = null
    ): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val builder = ProcessBuilder(listOf("/bin/sh") + args)
        for ((k, v) in env) builder.environment()[k] = v
        var stdinFile: File? = null
        if (stdin != null) {
            stdinFile = tempDir("cb-stdin").let { d -> File(d, "in").apply { writeText(stdin) } }
            builder.redirectInput(ProcessBuilder.Redirect.from(stdinFile))
        }
        builder.redirectOutput(ProcessBuilder.Redirect.PIPE)
        val p = builder.start()
        out.write(p.inputStream.readBytes())
        val exited = p.waitFor(30, TimeUnit.SECONDS)
        if (!exited) {
            p.destroyForcibly()
            throw AssertionError("sh run did not exit: $args")
        }
        return p.exitValue() to out.toString(Charsets.UTF_8)
    }

    @Test
    fun `shim starts with a shebang and carries the managed marker`() {
        val s = ClipboardBridge.shim("/sd/clip")
        assertTrue(s.startsWith("#!/system/bin/sh\n"))
        assertTrue(s.contains("# managed by RedTerm (clipboard shim v${ClipboardBridge.SHIM_VERSION})"))
        assertTrue(s.contains("d=\"${'$'}{REDTERM_CLIPDIR:-/sd/clip}\""))
    }

    @Test
    fun `set then consume then get round-trips through a real shell`() {
        val clip = tempDir("cb-clip")
        val bin = tempDir("cb-bin")
        val xsel = File(bin, "xsel").apply {
            writeText(ClipboardBridge.shim(clip.absolutePath))
            setExecutable(true)
        }

        val (setCode, _) = runSh(
            listOf(xsel.absolutePath, "-i"),
            env = mapOf("REDTERM_CLIPDIR" to clip.absolutePath),
            stdin = "CODE-1234"
        )
        assertEquals("SET must exit 0", 0, setCode)
        assertEquals("CODE-1234", File(clip, ClipboardBridge.PENDING_NAME).readText())
        assertTrue(File(clip, ClipboardBridge.TRIGGER_NAME).exists())

        assertEquals("CODE-1234", ClipboardBridge.consume(clip))
        assertFalse(File(clip, ClipboardBridge.TRIGGER_NAME).exists())
        assertEquals("CODE-1234", File(clip, ClipboardBridge.LAST_NAME).readText())

        val (getCode, getOut) = runSh(
            listOf(xsel.absolutePath, "-o"),
            env = mapOf("REDTERM_CLIPDIR" to clip.absolutePath)
        )
        assertEquals(0, getCode)
        assertEquals("CODE-1234", getOut)
    }

    @Test
    fun `set with empty input still delivers, as an empty clipboard`() {
        val clip = tempDir("cb-clip-empty")
        val xsel = File(tempDir("cb-bin-empty"), "xsel").apply {
            writeText(ClipboardBridge.shim(clip.absolutePath))
            setExecutable(true)
        }
        val (code, _) = runSh(
            listOf(xsel.absolutePath, "-i"),
            env = mapOf("REDTERM_CLIPDIR" to clip.absolutePath),
            stdin = ""
        )
        assertEquals(0, code)
        assertEquals("", ClipboardBridge.consume(clip))
    }

    @Test
    fun `get with nothing ever set prints nothing and succeeds`() {
        val clip = tempDir("cb-clip-none")
        val xsel = File(tempDir("cb-bin-none"), "xsel").apply {
            writeText(ClipboardBridge.shim(clip.absolutePath))
            setExecutable(true)
        }
        val (code, out) = runSh(
            listOf(xsel.absolutePath, "-o"),
            env = mapOf("REDTERM_CLIPDIR" to clip.absolutePath)
        )
        assertEquals(0, code)
        assertEquals("", out)
    }

    @Test
    fun `a stale trigger without a pending file is swept, not an error`() {
        val clip = tempDir("cb-clip-stale")
        File(clip, ClipboardBridge.TRIGGER_NAME).writeText("")
        assertNull(ClipboardBridge.consume(clip))
        assertFalse(File(clip, ClipboardBridge.TRIGGER_NAME).exists())
        assertNull(ClipboardBridge.consume(clip))
    }

    @Test
    fun `install writes both shims and rewrites a drifted file`() {
        val rootfs = tempDir("cb-rootfs")
        val guest = "/sdcard/Android/data/com.redtermapp/files/clipboard"
        ClipboardBridge.installShims(rootfs, guest)
        val expected = ClipboardBridge.shim(guest)
        for (name in listOf("xsel", "xclip")) {
            val f = File(rootfs, "usr/local/bin/$name")
            assertTrue("$name missing", f.isFile)
            assertEquals(name, expected, f.readText())
        }
        File(rootfs, "usr/local/bin/xsel").writeText("#!/bin/sh\necho manual\n")
        ClipboardBridge.installShims(rootfs, guest)
        assertEquals("a hand-written shim is replaced on the next session start",
            expected, File(rootfs, "usr/local/bin/xsel").readText())
    }
}
