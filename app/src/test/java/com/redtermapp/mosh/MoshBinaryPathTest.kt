package com.redtermapp.mosh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The client has to be installed and launched at the same path.
 *
 * They were not, and the disagreement cost two hours of device testing: the client was
 * extracted to `rootfs/bin/` beside ssh while the launcher asked for `rootfs/root/bin/`,
 * because `/root` is the ssh client's *home* inside the rootfs and not a place to put
 * programs. Every mosh session exited 127 while the log said `started a mosh session`,
 * which is the worst possible combination — the app was confidently reporting a working
 * bootstrap and the shell was saying it could not find the program.
 */
class MoshBinaryPathTest {

    private val rootfs = File("/data/data/com.redtermapp.app/files/ssh-rootfs")

    @Test
    fun `the client is installed beside the other rootfs programs`() {
        assertEquals(
            "/data/data/com.redtermapp.app/files/ssh-rootfs/bin/redterm-mosh",
            MoshClient.installFile(rootfs).path,
        )
    }

    @Test
    fun `the launcher runs the file that was installed`() {
        val installed = MoshClient.installFile(rootfs)
        val launched = MoshClient.launcherTarget()
        assertTrue(
            "launcher should run $installed, not something else; it ran $launched",
            launched.contains(installed.name) && launched.contains("/bin/"),
        )
    }

    /**
     * The specific mistake, pinned: `/root` is the ssh client's home directory inside the
     * rootfs, so a program placed there is not on the path anything runs.
     */
    @Test
    fun `the client is not installed under the ssh home directory`() {
        assertFalse(MoshClient.PROGRAM_GUEST_PATH.startsWith("/root/"))
        assertEquals("/bin/redterm-mosh", MoshClient.PROGRAM_GUEST_PATH)
    }
}
