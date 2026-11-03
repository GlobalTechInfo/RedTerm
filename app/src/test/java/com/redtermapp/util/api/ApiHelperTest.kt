package com.redtermapp.util.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The guest scripts are managed files: the marker says whose they are, and
 * content-based rewriting is what gets a corrected pair into a rootfs that
 * already carries the older one.
 */
class ApiHelperTest {

    /** The preamble every guest script carries: shebang, marker, and the missing-helper guard. */
    private val shebangAndGuard =
        "#!/bin/sh\n" +
            "# managed by RedTerm (redterm-api bridge v${ApiHelper.GUEST_SCRIPT_VERSION}); do not edit\n" +
            "if [ ! -e /usr/local/redterm/redterm-api ]; then\n" +
            "  echo \"/usr/local/redterm/redterm-api: the RedTerm API helper is not installed in this session\" 1>&2\n" +
            "  echo \"restart the RedTerm session; if it still fails, open RedTerm Diagnostics\" 1>&2\n" +
            "  exit 127\n" +
            "fi\n"

    private fun tempRootfs(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "redterm-apiget-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    @Test
    fun `both scripts land in the guest bin, executable, with the marker`() {
        val rootfs = tempRootfs()
        assertTrue(ApiHelper.installGuestScripts(null, rootfs))
        for (name in listOf("termux-api", "termux-battery-status")) {
            val f = File(rootfs, "usr/local/bin/$name")
            assertTrue("$name missing", f.isFile)
            assertTrue("$name not executable", f.canExecute())
            val text = f.readText()
            assertTrue("$name lost its shebang", text.startsWith("#!/bin/sh\n"))
            assertTrue("$name lost its marker",
                text.contains("# managed by RedTerm (redterm-api bridge v${ApiHelper.GUEST_SCRIPT_VERSION})"))
        }
        assertEquals(
            shebangAndGuard +
                "exec /usr/local/redterm/redterm-api \"\$@\"\n",
            File(rootfs, "usr/local/bin/termux-api").readText()
        )
        assertEquals(
            shebangAndGuard +
                "exec /usr/local/redterm/redterm-api --user 0 -a ${ApiActions.ACTION} --es action battery-status\n",
            File(rootfs, "usr/local/bin/termux-battery-status").readText()
        )
    }

    @Test
    fun `every command is installed under both its redterm and termux name`() {
        val rootfs = tempRootfs()
        assertTrue(ApiHelper.installGuestScripts(null, rootfs))
        val bin = File(rootfs, "usr/local/bin")
        for (base in listOf("api", "battery-status", "download", "sms-send")) {
            assertTrue("redterm-$base missing", File(bin, "redterm-$base").isFile)
            assertTrue("termux-$base missing", File(bin, "termux-$base").isFile)
            assertEquals(
                "the two names must be the same script",
                File(bin, "redterm-$base").readText().removePrefix("#!/bin/sh\n"),
                File(bin, "termux-$base").readText().removePrefix("#!/bin/sh\n"),
            )
        }
        // The RedTerm name is the primary one: same body, so the helper path
        // and quotes are identical.
        assertEquals(
            shebangAndGuard +
                "exec /usr/local/redterm/redterm-api --user 0 -a ${ApiActions.ACTION} --es action battery-status\n",
            File(bin, "redterm-battery-status").readText()
        )
    }

    @Test
    fun `a hand-edited script is replaced on the next install`() {
        val rootfs = tempRootfs()
        ApiHelper.installGuestScripts(null, rootfs)
        val f = File(rootfs, "usr/local/bin/termux-battery-status")
        f.writeText("#!/bin/sh\necho something the distro changed\n")
        ApiHelper.installGuestScripts(null, rootfs)
        assertTrue(
            "the corrected script never arrives",
            f.readText().contains("managed by RedTerm")
        )
    }

    @Test
    fun `an unchanged install does not rewrite the files`() {
        val rootfs = tempRootfs()
        ApiHelper.installGuestScripts(null, rootfs)
        val f = File(rootfs, "usr/local/bin/termux-api")
        val first = f.readText()
        val mtimeBefore = f.lastModified()
        ApiHelper.installGuestScripts(null, rootfs)
        assertEquals(first, f.readText())
        assertEquals("an unchanged install must be a no-op", mtimeBefore, f.lastModified())
    }

    @Test
    fun `every managed script carries the marker and no unsubstituted placeholder`() {
        val rootfs = tempRootfs()
        assertTrue(ApiHelper.installGuestScripts(null, rootfs))
        val bin = File(rootfs, "usr/local/bin")
        val scripts = bin.listFiles().orEmpty()
        assertTrue("the full script set must arrive, got ${scripts.size}", scripts.size >= 15)
        val bridge = ApiHelper.guestScripts.keys
        for (f in scripts) {
            val text = f.readText()
            assertTrue("${f.name} lost its shebang", text.startsWith("#!/bin/sh\n"))
            assertTrue("${f.name} leaked a placeholder",
                !text.contains("__EXEC__") && !text.contains("__A__"))
            // The bridge marker is only on bridge scripts; the pure guest-side
            // tools carry their own header and no helper guard.
            if (f.name in bridge) {
                assertTrue("${f.name} lost its marker",
                    text.contains("# managed by RedTerm (redterm-api bridge v${ApiHelper.GUEST_SCRIPT_VERSION})"))
            } else {
                assertTrue("${f.name} is not a bridge script but carries the bridge marker",
                    !text.contains("# managed by RedTerm (redterm-api bridge"))
            }
        }
    }

    @Test
    fun `redterm-startup-local is a pure guest tool with no helper guard`() {
        val rootfs = tempRootfs()
        assertTrue(ApiHelper.installGuestScripts(null, rootfs))
        val bin = File(rootfs, "usr/local/bin")
        val f = File(bin, "redterm-startup-local")
        assertTrue("redterm-startup-local missing", f.isFile && f.canExecute())
        val text = f.readText()
        // Pure: no helper-existence guard, no bridge marker.
        assertTrue("must not carry the helper guard",
            !text.contains("the RedTerm API helper is not installed"))
        assertTrue("must not carry the bridge marker",
            !text.contains("# managed by RedTerm (redterm-api bridge"))
        // It is RedTerm-only: no termux- alias is installed for it.
        assertTrue("no termux- alias for a RedTerm-only tool",
            !File(bin, "termux-startup-local").exists())
        // The printed body is the safe, distro-agnostic starter: the
        // Debian-family dynamic-MOTD line is double-guarded so it is a no-op
        // on the other 19 distros.
        assertTrue("missing the guarded MOTD block",
            text.contains("command -v run-parts >/dev/null 2>&1 && [ -d /etc/update-motd.d ]"))
        assertTrue("missing the here-doc that carries the body",
            text.contains("cat <<'STARTER'"))
    }
}
