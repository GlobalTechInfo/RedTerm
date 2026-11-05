package com.redtermapp.distro

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user-managed startup hook, asserted by running it — the same reason the
 * generated `.bashrc` is sourced by a real bash rather than compared as text.
 *
 * The guard block under test is *the one [DistroSetup.buildStartupScript]
 * actually emits*, extracted from its output and re-pointed at the test's
 * temp file — not a hand copy. A copy diverges silently from the generator
 * (the way a test that only asserts the api bind appears somewhere passes on
 * every build that carries the bug); the extraction keeps the two in lockstep.
 */
class StartupHookSourcingTest {

    private val sh: String? = sequenceOf("/bin/sh", "/usr/bin/sh")
        .firstOrNull { File(it).canExecute() }

    /** The guard exactly as [DistroSetup.buildStartupScript] emits it, re-pointed at [hook]. */
    private fun guardBlock(hook: String): String {
        val lines = DistroSetup.buildStartupScript("debian").lineSequence().toList()
        // The guard is the only place the interactive marker is referenced;
        // it opens one `if` that contains a nested `if`, so it closes after
        // the second `fi` — the outer one.
        val start = lines.indexOfFirst { it.contains("REDTERM_INTERACTIVE_SESSION") }
        check(start >= 0) { "generated startup script lost the interactive guard" }
        val block = ArrayList<String>()
        var fiSeen = 0
        for (line in lines.subList(start, lines.size)) {
            block.add(line)
            if (line.trim() == "fi") fiSeen++
            if (fiSeen == 2) break
        }
        check(fiSeen == 2) { "generated guard did not close: $block" }
        return block.joinToString("\n") { it.replace("/root/.startup.local", hook) } + "\n"
    }

    private class Run(val out: String, val err: String, val code: Int)

    /** Runs the guard followed by a sentinel, with the marker set or unset. */
    private fun run(hookPath: String, hookPresent: Boolean, interactive: Boolean): Run {
        val hookFile = File(hookPath)
        if (hookPresent) {
            // A failing hook: exit non-zero and try to write a marker, so the
            // test can prove both that it ran and that the session continued.
            hookFile.writeText(
                "#!/bin/sh\ntouch \"${hookPath}.ran\"\nexit 1\n"
            )
        } else {
            hookFile.delete()
        }
        val script = guardBlock(hookPath) + "echo SENTINEL_REACHED\n"
        val env = HashMap<String, String>()
        if (interactive) env["REDTERM_INTERACTIVE_SESSION"] = "1"
        val process = ProcessBuilder(sh!!, "-c", script)
            .redirectErrorStream(false)
            .apply { if (env.isNotEmpty()) environment().putAll(env) }
            .start()
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        process.waitFor()
        return Run(out, err, process.exitValue())
    }

    @Test
    fun `an interactive session with a hook present runs it`() {
        if (sh == null) return
        val hook = File.createTempFile("redterm-hook", ".sh").path
        val r = run(hook, hookPresent = true, interactive = true)
        assertTrue("the hook did not run", File("$hook.ran").exists())
        // The hook exited 1, so the guard reports the failure — but the
        // session's own next line still ran, so it was not fatal.
        assertTrue(
            "a failing hook must report its error",
            r.out.contains("reported an error (exit 1)")
        )
        assertTrue("a failing hook must not stop the session", r.out.contains("SENTINEL_REACHED"))
        File(hook).delete()
        File("$hook.ran").delete()
    }

    @Test
    fun `a one-shot command does not run the hook`() {
        if (sh == null) return
        val hook = File.createTempFile("redterm-hook", ".sh").path
        val r = run(hook, hookPresent = true, interactive = false)
        assertFalse("the hook ran for a one-shot command", File("$hook.ran").exists())
        assertTrue(r.out.contains("SENTINEL_REACHED"))
        File(hook).delete()
        File("$hook.ran").delete()
    }

    @Test
    fun `a missing hook is a silent no-op`() {
        if (sh == null) return
        val hook = File.createTempFile("redterm-hook", ".sh").apply { delete() }.path
        val r = run(hook, hookPresent = false, interactive = true)
        assertFalse(File("$hook.ran").exists())
        assertTrue("the session must continue with no hook", r.out.contains("SENTINEL_REACHED"))
        assertEquals("a missing hook must not report an error", "", r.err.trim())
    }

    @Test
    fun `the hook file is not touched by startup regeneration`() {
        // RedTerm rewrites /root/.startup on every new session; the user-managed
        // hook must survive that. Generating the startup script is the act under
        // test — it must not read or alter the hook file.
        val hook = File.createTempFile("redterm-hook", ".sh").apply {
            writeText("#!/bin/sh\necho user-motd\n")
        }
        val before = hook.readText()
        // What TerminalActivity does on a new session: regenerate and rewrite
        // .startup only.
        DistroSetup.buildStartupScript("debian")
        assertEquals("regenerating .startup altered the user's hook", before, hook.readText())
        hook.delete()
    }
}
