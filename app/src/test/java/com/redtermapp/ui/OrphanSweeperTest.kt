package com.redtermapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrphanSweeperTest {

    private val rootfs = "/data/user/0/com.redtermapp/files/ssh-rootfs"

    private fun tunnelCmdline() =
        "proot -0 -L -r $rootfs -w /root --link2symlink --sysvipc --kill-on-exit " +
            "/bin/ssh -N -L9000:127.0.0.1:5432 -p 2222 alice@example.com"

    @Test
    fun `a standalone ssh -N under our rootfs is an orphan`() {
        assertTrue(OrphanSweeper.isOrphan(tunnelCmdline(), rootfs))
    }

    @Test
    fun `an interactive session carries no -N and is not ours`() {
        val session = "proot -0 -L -r $rootfs -w /root /bin/ssh -p 22 alice@example.com"
        assertFalse(OrphanSweeper.isOrphan(session, rootfs))
    }

    @Test
    fun `sftp speaks -s sftp and is not a tunnel`() {
        val sftp = "proot -0 -L -r $rootfs -w /root /bin/ssh -p 22 alice@example.com -s sftp"
        assertFalse(OrphanSweeper.isOrphan(sftp, rootfs))
    }

    @Test
    fun `a distro shell under our rootfs is not ours`() {
        val shell = "proot -0 -L -r $rootfs -w /root /bin/bash"
        assertFalse(OrphanSweeper.isOrphan(shell, rootfs))
    }

    @Test
    fun `an ssh -N under somebody else's rootfs is not ours`() {
        val other = tunnelCmdline().replace(rootfs, "/data/user/0/other/files/ssh-rootfs")
        assertFalse(OrphanSweeper.isOrphan(other, rootfs))
    }

    @Test
    fun `scan picks only the orphans and never our own pid`() {
        val cmdlines = mapOf(
            101 to tunnelCmdline(),
            102 to "proot -0 -L -r $rootfs -w /root /bin/bash",
            103 to tunnelCmdline(),
            104 to "some/other binary"
        )
        assertEquals(listOf(101, 103), OrphanSweeper.scan(cmdlines, rootfs, selfPid = 0))
        // Our own pid must be excluded even when it looks exactly like a tunnel.
        assertEquals(listOf(103), OrphanSweeper.scan(cmdlines, rootfs, selfPid = 101))
    }

    @Test
    fun `the kill command names every pid, and nothing when there are none`() {
        assertEquals("kill -9 101 103", OrphanSweeper.killCommand(listOf(101, 103)))
        assertEquals("", OrphanSweeper.killCommand(emptyList()))
    }
}
