package com.redtermapp.util

import com.redtermapp.mosh.MoshClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The pure parts of the device-shell toolkit: which ABI ships what, and
 * what the launcher the session reads actually says.
 */
class DeviceToolkitTest {

    @Test
    fun `assetArch agrees with the mosh one for every shipped abi`() {
        // Raw comparison, not Assert.assertEquals with a message: in a
        // full-suite run the JUnit form failed with a ComparisonFailure
        // naming a value that neither side held, while the raw
        // comparison was true for every pair (reproduced with a clean
        // build cache). The raw form also says what the two sides were
        // when one of them is wrong.
        for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86", "aarch64", "armv7a", "i686")) {
            val m: String? = MoshClient.assetArch(abi)
            val t: String? = DeviceToolkit.assetArch(abi)
            if (m != t) {
                throw java.lang.AssertionError("raw mismatch for $abi: mosh=[$m] toolkit=[$t]")
            }
        }
    }

    @Test
    fun `assetArch refuses unknown abis`() {
        assertEquals(null, DeviceToolkit.assetArch("riscv64"))
        assertEquals(null, DeviceToolkit.assetArch(null))
    }

    @Test
    fun `the launcher runs proot to exec the toolkit bash`() {
        val script = DeviceToolkit.launcherScript(
            "/data/app/lib",
            File("/data/data/com.redtermapp/files/device-toolkit/aarch64"),
            "/sdcard",
            File("/data/data/com.redtermapp/files/device-toolkit-tmp")
        )
        val prootLine = script.lineSequence().first { it.contains("libproot.so") }
        // One logical line: a continuation would make the shell end the
        // statement at the first uncontinued line, which is how a distro
        // launcher once lost its api bind.
        assertFalse(prootLine.endsWith("\\"))
        assertTrue(prootLine.contains("-r /"))
        assertTrue(prootLine.contains("-w \"/sdcard\""))
        assertTrue(prootLine.contains("--kill-on-exit"))
        assertTrue(prootLine.contains("/device-toolkit/aarch64/bash"))
        // The proot scratch space must be the app's own directory: with
        // root the real filesystem the guest /tmp is /data/local/tmp,
        // which the app's uid cannot write to.
        val tmpLine = script.lineSequence().first { it.startsWith("export PROOT_TMP_DIR=") }
        assertTrue(tmpLine.contains("/device-toolkit-tmp"))
    }

    @Test
    fun `the launcher does not fake root or rewrite the filesystem`() {
        val script = DeviceToolkit.launcherScript(
            "/data/app/lib",
            File("/data/data/com.redtermapp/files/device-toolkit/aarch64"),
            "/",
            File("/data/data/com.redtermapp/files/device-toolkit-tmp")
        )
        // -0 would report ownership the app does not have; --link2symlink
        // would turn the device's real hard links into copies.
        val prootLine = script.lineSequence().first { it.contains("libproot.so") }
        assertFalse(prootLine.contains(" -0 "))
        assertFalse(prootLine.contains("--link2symlink"))
    }

    @Test
    fun `the toolkit directory is on the child path`() {
        val script = DeviceToolkit.launcherScript(
            "/data/app/lib",
            File("/data/data/com.redtermapp/files/device-toolkit/aarch64"),
            "/",
            File("/data/data/com.redtermapp/files/device-toolkit-tmp")
        )
        val pathLine = script.lineSequence().first { it.startsWith("export PATH=") }
        assertTrue(pathLine.contains("/device-toolkit/aarch64"))
    }

    @Test
    fun `the loader export precedes the proot call`() {
        val script = DeviceToolkit.launcherScript("/lib", File("/toolkit"), "/", File("/tmp-proot"))
        val loaderIndex = script.indexOf("PROOT_LOADER")
        val prootIndex = script.indexOf("libproot.so")
        assertTrue(loaderIndex in 0 until prootIndex)
    }

    @Test
    fun `on a proot failure the launcher falls back to the system shell`() {
        val script = DeviceToolkit.launcherScript(
            "/data/app/lib",
            File("/data/data/com.redtermapp/files/device-toolkit/aarch64"),
            "/",
            File("/data/data/com.redtermapp/files/device-toolkit-tmp")
        )
        // A dead session is the failure nobody can fix: the foreground proot
        // call must, when it fails, hand the user a working system shell
        // rather than leaving the pty with nothing on it.
        val fallback = script.lineSequence().first { it.trimStart().startsWith("exec ") }
        assertTrue(fallback.contains("/system/bin/sh"))
        // Interactive, so the user keeps a usable prompt (the system shell's
        // own default) instead of a process that has already exited.
        assertTrue(fallback.contains("-i"))
    }

    @Test
    fun `the launcher does not override the prompt`() {
        val script = DeviceToolkit.launcherScript(
            "/data/app/lib",
            File("/data/data/com.redtermapp/files/device-toolkit/aarch64"),
            "/data/data/com.redtermapp/files",
            File("/data/data/com.redtermapp/files/device-toolkit-tmp")
        )
        // No custom PS1: the bundled bash shows its own default prompt
        // (e.g. `bash-5.2$ `), which is more transparent than a marker and
        // already distinguishes it from the fallback system sh. Overriding it
        // would also shadow a prompt the user sets in their own ~/.bashrc.
        assertFalse("PS1 must not be forced; let bash show its honest default",
            script.contains("export PS1"))
        // The fallback must not leave a marker behind either: there is nothing
        // to unset now, so the system shell keeps its own default prompt.
        assertFalse(script.contains("unset PS1"))
    }

    @Test
    fun `the launcher points HOME at the writable cwd, not the binary dir`() {
        val script = DeviceToolkit.launcherScript(
            "/data/app/lib",
            File("/data/data/com.redtermapp/files/device-toolkit/aarch64"),
            "/data/data/com.redtermapp/files",
            File("/data/data/com.redtermapp/files/device-toolkit-tmp")
        )
        // HOME is where the user's `cd ~` and dotfile lookups land: the
        // app's own writable storage, not the directory that holds the
        // toolkit's binaries.
        val homeLine = script.lineSequence().first { it.startsWith("export HOME=") }
        assertTrue(homeLine.contains("/data/data/com.redtermapp/files"))
        assertFalse(homeLine.contains("/device-toolkit/aarch64"))
    }

    @Test
    fun `the launcher is a self-contained script with a shebang and absolute path`() {
        val script = DeviceToolkit.launcherScript(
            "/data/app/lib",
            File("/data/data/com.redtermapp/files/device-toolkit/aarch64"),
            "/data/data/com.redtermapp/files",
            File("/data/data/com.redtermapp/files/device-toolkit-tmp")
        )
        // It is run as `sh -c <script>` under an empty environment (the distro
        // pattern), so it must carry a shebang and set an *absolute* PATH:
        // a `$PATH:...` append would be empty under that env and the child
        // would lose the device's own commands.
        assertTrue(script.lineSequence().first().startsWith("#!/system/bin/sh"))
        val pathLine = script.lineSequence().first { it.startsWith("export PATH=") }
        assertFalse("PATH must be absolute, not an append to the (empty) inherited one",
            pathLine.contains("\$PATH"))
        assertTrue(pathLine.contains("/system/bin"))
        assertTrue(pathLine.contains("/device-toolkit/aarch64"))
    }
}
