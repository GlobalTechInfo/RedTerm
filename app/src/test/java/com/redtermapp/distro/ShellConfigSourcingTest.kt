package com.redtermapp.distro

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generated `.bashrc`, sourced by a real bash.
 *
 * Asserted by running it rather than by reading it, because the failure this covers is
 * invisible to a string comparison: an unquoted token in a shell file looks perfectly
 * reasonable, and the only symptom is a line of output at login. Every text assertion in
 * the other test would have passed with `HISTFMT=%F %T` in the file, unquoted — where
 * bash reads the `%T` as a job specifier, hands it to fg, and fg reports "fg: no job
 * control" on every single login.
 */
class ShellConfigSourcingTest {

    private val bash: String? = sequenceOf("/bin/bash", "/usr/bin/bash")
        .firstOrNull { File(it).canExecute() }

    private fun generated(): File {
        val dir = File.createTempFile("redterm", "").let {
            it.delete()
            it.mkdirs()
            it.deleteOnExit()
            it
        }
        return File(dir, "bashrc").apply {
            writeText(ShellConfig.bashrc())
            deleteOnExit()
        }
    }

    /** Runs the file as a startup file, returning stdout, stderr and the exit status. */
    private fun source(file: File): Triple<String, String, Int> {
        val script = ". '" + file.path + "'\necho REDTERM_SOURCED_OK\n"
        val process = ProcessBuilder(bash!!, "-c", script)
            .redirectErrorStream(false)
            .start()
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        process.waitFor()
        return Triple(out, err, process.exitValue())
    }

    /**
     * The whole point: the file must run without saying anything.
     *
     * Only complaints naming this file count. It sources `/etc/bash_completion.d` when
     * the host has such a directory, and whatever a host's completion scripts say is
     * that host's business, not evidence about the file the app writes.
     */
    @Test
    fun `the generated bashrc runs without a single complaint`() {
        if (bash == null) return
        val file = generated()
        val (out, err, code) = source(file)
        val ours = err.lineSequence().filter { it.startsWith(file.path) }.toList()
        assertEquals("the generated bashrc reported: $ours", emptyList<String>(), ours)
        assertTrue(
            "sourcing did not reach the end:\nout=$out\nerr=$err",
            out.contains("REDTERM_SOURCED_OK")
        )
        assertEquals(0, code)
    }

    /**
     * Every bundled template is a startup file too, and they are full of `%`.
     *
     * The prompt formats legitimately carry `\D{%H:%M}` and friends, so a template is
     * exactly where an unquoted job specifier can hide. Sourced here for the same reason
     * the generated file is: a stray `%` produces output, not a syntax error.
     */
    @Test
    fun `bundled templates do not report a job specifier`() {
        if (bash == null) return
        val dir = File.createTempFile("redterm", "").let {
            it.delete()
            it.mkdirs()
            it.deleteOnExit()
            it
        }
        val offenders = mutableListOf<String>()
        for (name in TEMPLATE_NAMES) {
            // Content is the asset the screen actually reads; a template that cannot be
            // found is skipped, because its absence says nothing about its well-formedness.
            val content = templateContent(name) ?: continue
            val target = File(dir, "t-$name").apply {
                writeText(content)
                deleteOnExit()
            }
            val (_, err, _) = source(target)
            if (err.contains("job control")) offenders += "$name: ${err.trim()}"
        }
        assertEquals(
            "a template asks bash to resume a job: $offenders",
            emptyList<String>(),
            offenders
        )
    }

    /** The history format has to be quoted, and this records why. */
    @Test
    fun `the history timestamp is quoted so it cannot be read as a job specifier`() {
        assertTrue(
            "HISTTIMEFORMAT must be quoted",
            ShellConfig.bashrc().contains("HISTTIMEFORMAT='")
        )
    }

    /** Neither of the two invented names was ever a bash variable. */
    @Test
    fun `no invented history variables remain`() {
        val content = ShellConfig.bashrc()
        assertFalse(content.contains("HISTFMT="))
        assertFalse(content.contains("HISTTIMATIME="))
    }

    private companion object {
        val TEMPLATE_NAMES = listOf(
            "default.bashrc", "devops.bashrc", "hacker.bashrc", "matrix.bashrc",
            "minimal.bashrc", "powerline.bashrc", "redterm.bashrc", "retro.bashrc",
            "starship.bashrc"
        )

        /**
         * Asset contents, read from the test's own classpath.
         *
         * Android assets are not on a JVM test classpath, so this resolves them next to
         * the source tree; a template that cannot be found is skipped rather than failed,
         * because its absence says nothing about whether it is well formed.
         */
        fun templateContent(name: String): String? {
            val candidates = listOf(
                File("src/main/assets/bashrc/$name"),
                File("app/src/main/assets/bashrc/$name")
            )
            return candidates.firstOrNull { it.isFile }?.readText()
        }
    }
}