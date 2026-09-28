package com.redtermapp.distro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The interactive terminal depends on this exact script, so a change here can
 * silently break launching a distro. These tests pin the parts that matter and
 * shell-check the generated syntax.
 */
class ProotLaunchTest {

    private val nativeLibDir = "/data/app/com.redtermapp-1/lib/arm64"
    private val rootfsPath = "/data/user/0/com.redtermapp/files/rootfs/debian"

    private fun interactive(startInner: String? = null) = ProotLaunch.buildScript(
        nativeLibDir = nativeLibDir,
        rootfsPath = rootfsPath,
        hasLoader32 = true,
        startInner = startInner,
        command = null
    )

    private fun oneShot(command: String) = ProotLaunch.buildScript(
        nativeLibDir = nativeLibDir,
        rootfsPath = rootfsPath,
        hasLoader32 = true,
        startInner = "/root",
        command = command
    )

    @Test
    fun `interactive launcher keeps every required proot bind`() {
        val script = interactive()
        for (bind in listOf("/dev", "/proc", "/sys", "/system", "/apex", "/sdcard")) {
            assertTrue("missing bind $bind", script.contains("-b $bind"))
        }
        assertTrue(script.contains("--link2symlink"))
        assertTrue(script.contains("--kill-on-exit"))
        assertTrue(script.contains("-0 -L -r \"$rootfsPath\""))
    }

    @Test
    fun `interactive launcher ends in a login shell`() {
        assertTrue(interactive().contains("/system/bin/sh -i 2>&1"))
    }

    @Test
    fun `interactive launcher honours the start directory`() {
        assertTrue(interactive("/etc").contains("-w /etc"))
        assertTrue(interactive(null).contains("-w /root"))
    }

    @Test
    fun `interactive launcher exports the per-distro startup script`() {
        val script = interactive()
        assertTrue(script.contains("export ENV=/root/.startup"))
    }

    @Test
    fun `command launcher sources the startup script then runs the command`() {
        val script = oneShot("apt-get update")
        assertTrue(script.contains(". /root/.startup"))
        assertTrue(script.contains("apt-get update"))
        assertFalse("must not start an interactive shell", script.contains("/system/bin/sh -i"))
    }

    @Test
    fun `32-bit loader export only appears when the loader is bundled`() {
        val withLoader = ProotLaunch.buildScript(nativeLibDir, rootfsPath, hasLoader32 = true)
        val withoutLoader = ProotLaunch.buildScript(nativeLibDir, rootfsPath, hasLoader32 = false)
        assertTrue(withLoader.contains("PROOT_LOADER_32"))
        assertFalse(withoutLoader.contains("PROOT_LOADER_32"))
    }

    @Test
    fun `generated scripts are valid shell`() {
        val outDir = File("build/proot-launch-scripts").apply { mkdirs() }
        val scripts = mapOf(
            "interactive" to interactive("/root"),
            "one-shot" to oneShot("apt-get update -y"),
            "no-loader" to ProotLaunch.buildScript(nativeLibDir, rootfsPath, hasLoader32 = false)
        )
        scripts.forEach { (name, body) ->
            val file = File(outDir, "$name.sh")
            file.writeText(body)
            file.setExecutable(true, true)
            val process = ProcessBuilder("sh", "-n", file.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
            val code = process.waitFor()
            assertTrue("sh -n failed for $name: $output", code == 0)
        }
    }
}

/**
 * The one-shot launcher embeds a command inside `sh -c '...'`. Merely wrapping a
 * command in quotes is not enough: an embedded single quote ends the quoting
 * early, and an empty argument is swallowed by the outer shell entirely. That
 * silently rewrote `ssh-keygen ... -N '' -C comment` into `-N -C comment`, so
 * keygen read "-C" as the passphrase and rejected the command line.
 *
 * Correct POSIX escaping has a checkable shape: the result is wrapped in quotes
 * and every quote inside it is followed by a backslash, because the only way a
 * quote can appear inside single quotes is the `\'\''` idiom. These tests pin
 * that shape, which a raw interpolation could never satisfy.
 */
class ProotShellQuotingTest {

    private fun assertWellFormed(quoted: String) {
        assertTrue("must open with a quote: $quoted", quoted.startsWith("\'"))
        assertTrue("must end with a quote: $quoted", quoted.endsWith("\'"))
        // Strip every `\'\''` idiom. Exactly the two wrapper quotes may be left,
        // which is what a raw interpolation would fail: it leaves interior quotes.
        val bare = quoted.replace("'\\''", "").count { it == '\'' }
        assertEquals("unescaped interior quotes in: $quoted", 2, bare)
    }

    @Test
    fun `plain command is only wrapped`() {
        assertEquals("'ls -l'", ProotLaunch.quoteForShell("ls -l"))
    }

    @Test
    fun `empty value becomes an empty quoted string`() {
        assertEquals("''", ProotLaunch.quoteForShell(""))
    }

    @Test
    fun `single quote is escaped by closing escaping and reopening`() {
        assertEquals("'it'\\''s'", ProotLaunch.quoteForShell("it's"))
        assertWellFormed(ProotLaunch.quoteForShell("it's"))
    }

    @Test
    fun `empty argument does not collapse`() {
        // The regression: '-N \'\'' -C comment' must not come out as '-N -C comment',
        // which made ssh-keygen take "-C" as the passphrase.
        val quoted = ProotLaunch.quoteForShell("ssh-keygen -N '' -C host")
        assertWellFormed(quoted)
        assertFalse("the empty argument was lost: $quoted", quoted.contains("-N '' -C"))
    }

    @Test
    fun `keygen command is well formed`() {
        val command = "/bin/ssh-keygen -t ed25519 -f '/root/.ssh/id_ed25519' " +
            "-N '' -C redterm@Infinix_X670"
        val quoted = ProotLaunch.quoteForShell(command)
        assertWellFormed(quoted)
        // Two wrapper quotes, plus three quote characters for each quote in the
        // input, because the `\'\''` idiom is quote, backslash, quote, quote.
        val inputQuotes = command.count { it == '\'' }
        assertEquals(2 + 3 * inputQuotes, quoted.count { it == '\'' })
    }

    @Test
    fun `generated script quotes the command instead of interpolating it`() {
        val command = "ssh-keygen -t ed25519 -N '' -C redterm@host"
        val script = ProotLaunch.buildScript(
            nativeLibDir = "/data/app/com.redtermapp-1/lib/arm64",
            rootfsPath = "/data/user/0/com.redtermapp/files/rootfs/debian",
            hasLoader32 = true,
            startInner = null,
            command = command
        )
        // The command itself contains "2>&1" (the startup redirect), so the
        // trailing one is what has to be located.
        val line = script.lines().first { it.contains("/system/bin/sh -c ") }
        val quoted = line.substringAfter("/system/bin/sh -c ").substringBeforeLast(" 2>&1")
        assertWellFormed(quoted)
        assertTrue("the startup script must still be sourced: $quoted", quoted.contains(".startup"))
        assertTrue("the command was not quoted: $quoted", quoted.contains("redterm@host"))
    }
}
