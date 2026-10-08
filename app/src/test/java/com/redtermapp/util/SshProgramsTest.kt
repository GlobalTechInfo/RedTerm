package com.redtermapp.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which bundled binaries a feature may depend on.
 *
 * File transfer is spoken in-app over `ssh -s host sftp` and ships no `sftp`
 * executable, because `sftp` and `scp` are separate programs that cannot be carved
 * out of `ssh`. Gating a feature on one of those binaries therefore gates it on
 * something that is never present — which silently removes the feature from every
 * device rather than failing loudly.
 */
class SshProgramsTest {

    @Test
    fun `only ssh and keygen are required to install`() {
        assertTrue(SshClient.REQUIRED_PROGRAMS.containsAll(listOf("ssh", "ssh-keygen")))
        assertFalse(
            "a program nothing needs must not be able to fail the install",
            SshClient.REQUIRED_PROGRAMS.contains("sftp")
        )
        assertFalse(SshClient.REQUIRED_PROGRAMS.contains("scp"))
    }

    /**
     * No optional programs, because none are shipped.
     *
     * Listing an absent binary made every install check try to extract it, and it is
     * what let a feature gate on `sftp` — a file this APK does not contain — and so
     * silently disable itself on every device.
     */
    @Test
    fun `nothing optional is expected, since nothing optional is shipped`() {
        assertTrue(SshClient.OPTIONAL_PROGRAMS.isEmpty())
    }

    @Test
    fun `nothing that is not shipped is asked for`() {
        assertTrue(SshClient.PROGRAMS.containsAll(listOf("ssh", "ssh-keygen")))
        assertFalse(SshClient.PROGRAMS.contains("sftp"))
        assertFalse(SshClient.PROGRAMS.contains("scp"))
    }
}
