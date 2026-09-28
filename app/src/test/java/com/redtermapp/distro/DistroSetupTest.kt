package com.redtermapp.distro

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DistroSetupTest {

    private val distros = listOf(
        "alpine", "debian", "ubuntu", "kali", "fedora", "rocky", "almalinux",
        "void", "arch", "manjaro", "opensuse", "unknown"
    )

    @Test
    fun writesEveryScriptForInspection() {
        val dir = File("build/distro-scripts").apply { mkdirs() }
        for (d in distros) {
            File(dir, "$d.sh").writeText(DistroSetup.buildStartupScript(d))
        }
        assertTrue(File(dir, "arch.sh").length() > 0)
    }

    @Test
    fun scriptAlwaysReachesAShell() {
        for (d in distros) {
            val s = DistroSetup.buildStartupScript(d)
            assertTrue("$d: no fallback", s.contains("exec /bin/sh -i"))
            assertTrue("$d: no bash", s.contains("exec bash -i"))
            assertTrue("$d: ENV not cleared", s.contains("unset ENV"))
            assertTrue("$d: no marker", s.startsWith(DistroSetup.MARKER))
        }
    }

    @Test
    fun archUsesFailoverMirrorsAndGccLibsRepair() {
        val s = DistroSetup.buildStartupScript("arch")
        // No extra failovers: the Arch Linux ARM frontends are not synchronised with
        // each other, so appending them made pacman mix a database from one with
        // packages from another and every package 404'd. The image's own mirrorlist
        // is left untouched.
        assertFalse(
            "appends an unsynchronised mirror",
            s.contains("mirror.archlinuxarm.org/") && s.contains(">> /etc/pacman.d/mirrorlist")
        )
        listOf("gcc-libs", "pacman -Syu --noconfirm", "-o %o %u").forEach {
            assertTrue("arch script is missing: $it", s.contains(it))
        }
    }

    @Test
    fun dnfDistrosDropTheMetalink() {
        // Each vendor's repositories go in a file named after that vendor, so the
        // repository actually in use is readable from `ls /etc/yum.repos.d` and a
        // distro can never end up on another vendor's servers.
        val expected = mapOf(
            "fedora" to "fedora.repo",
            "rocky" to "rocky.repo",
            "almalinux" to "almalinux.repo"
        )
        for ((d, file) in expected) {
            val s = DistroSetup.buildStartupScript(d)
            assertTrue("$d still uses metalink", !s.contains("metalink="))
            assertTrue("$d does not write $file", s.contains("/etc/yum.repos.d/$file"))
        }
        // The two that were talking to Fedora's servers need the thorough cleanup.
        for (d in listOf("rocky", "almalinux")) {
            val s = DistroSetup.buildStartupScript(d)
            assertTrue(
                "$d still resolves repositories from dnf.conf or the legacy path",
                s.contains("/etc/yum/repos.d") || s.contains("/etc/dnf/dnf.conf")
            )
            assertTrue(
                "$d points at another vendor's server",
                !s.contains("mirrors.kernel.org/fedora")
            )
        }
        // Rocky and AlmaLinux must each use their own servers.
        assertTrue(DistroSetup.buildStartupScript("rocky").contains("dl.rockylinux.org"))
        assertTrue(DistroSetup.buildStartupScript("almalinux").contains("repo.almalinux.org"))
        // Fedora worked before and must keep its original, narrow cleanup.
        val fedora = DistroSetup.buildStartupScript("fedora")
        assertTrue("fedora's cleanup changed", fedora.contains("/etc/yum.repos.d/*.repo"))
        assertTrue("fedora now rewrites dnf.conf", !fedora.contains("> /etc/dnf/dnf.conf"))
    }

    @Test
    fun opensuseDropsOpenh264AndBadFlag() {
        val s = DistroSetup.buildStartupScript("opensuse")
        assertTrue(s.contains("repo-openh264"))
        assertTrue("bad -q flag", !s.contains("install -y -q"))
    }

    /**
     * Repository configuration has to be re-applied on every start, not once.
     *
     * It used to sit inside the `/root/.init_done` guard while the marker was
     * written whenever bash existed. A first run that could not reach its mirrors
     * therefore left pacman permanently broken: the terminal printed "Setup
     * complete" and every later launch skipped the repair.
     */
    @Test
    fun repoConfigurationIsNotOneShot() {
        for (distro in listOf("arch", "manjaro", "debian", "alpine")) {
            val script = DistroSetup.buildStartupScript(distro)
            val guard = script.indexOf("if [ ! -f /root/.init_done ]")
            assertTrue("$distro has no one-shot guard", guard >= 0)
            val prepare = when (distro) {
                "arch" -> script.indexOf("mirrorlist")
                "manjaro" -> script.indexOf("XferCommand")
                else -> -1
            }
            if (prepare >= 0) {
                assertTrue(
                    "$distro applies its repository configuration only once",
                    prepare < guard
                )
            }
        }
    }

    @Test
    fun archCreatesItsMirrorDirectoryAndReportsWhatItConfigured() {
        val script = DistroSetup.buildStartupScript("arch")
        assertTrue("pacman.d may not exist in the image", script.contains("mkdir -p /etc/pacman.d"))
        // The mirrorlist is appended to, not replaced, so the image's own server
        // stays primary and the failovers are only added once.
        assertTrue(
            "the failovers are not appended",
            script.contains(">> /etc/pacman.d/mirrorlist")
        )
        assertTrue(
            "the pacman downloader is misconfigured, so pacman hands curl no URL",
            script.contains("-o %o %u")
        )
    }

    /**
     * xbps stages every download under /var/cache/xbps and reads it back from
     * there. Without that directory the fetch fails with "No such file or
     * directory" and the partial file is then read as a truncated archive, so the
     * repository never opens.
     */
    @Test
    fun voidCreatesItsXbpsDirectoriesOnEveryStart() {
        val script = DistroSetup.buildStartupScript("void")
        assertTrue("xbps cache directory is not created", script.contains("/var/cache/xbps"))
        assertTrue("xbps database directory is not created", script.contains("/var/db/xbps"))
        val guard = script.indexOf("if [ ! -f /root/.init_done ]")
        assertTrue(
            "the xbps directories are only created once, so a failed first run never repairs",
            script.indexOf("/var/cache/xbps") < guard
        )
    }

    /**
     * systemd's postinst calls systemd-machine-id-setup, which queries the system
     * bus. proot has no D-Bus, so it failed with "Protocol driver not attached" and
     * dpkg left systemd and systemd-sysv unconfigured, failing the whole upgrade.
     * Seeding the id short-circuits that, and it has to be re-applied on every start
     * so an already-broken install repairs itself.
     */
    @Test
    fun debianFamilySeedsAMachineIdOnEveryStart() {
        for (distro in listOf("debian", "ubuntu", "kali")) {
            val script = DistroSetup.buildStartupScript(distro)
            assertTrue("$distro does not seed a machine id", script.contains("/etc/machine-id"))
            val guard = script.indexOf("if [ ! -f /root/.init_done ]")
            assertTrue(
                "$distro only seeds a machine id once, so a broken install never repairs",
                script.indexOf("/etc/machine-id") < guard
            )
        }
    }
}
