package com.redtermapp.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The spawn spec of the device shell: where it starts and with what
 * environment.
 *
 * A blank start directory must not point the child at a path that does not
 * exist, and an empty environment is a terminal the user can open and not
 * use, so the environment gets the two things it cannot do without.
 */
class DeviceShellLauncherTest {

    @Test
    fun `the shell is the system shell`() {
        assertEquals("/system/bin/sh", DeviceShellLauncher.SHELL)
    }

    @Test
    fun `a blank start directory becomes the root of the device tree`() {
        assertEquals("/", DeviceShellLauncher.cwdFor(null))
        assertEquals("/", DeviceShellLauncher.cwdFor(""))
        assertEquals("/", DeviceShellLauncher.cwdFor("   "))
    }

    @Test
    fun `a start directory is used as given`() {
        assertEquals("/sdcard/Download", DeviceShellLauncher.cwdFor("/sdcard/Download"))
    }

    @Test
    fun `the process environment passes through untouched`() {
        val env = DeviceShellLauncher.envFor(
            mapOf("PATH" to "/system/bin", "USER" to "u0_a123"),
            mapOf("TERM" to DeviceShellLauncher.TERM)
        )
        assertArrayEquals(arrayOf("PATH=/system/bin", "USER=u0_a123", "TERM=xterm-256color"), env)
    }

    @Test
    fun `extras override the process environment`() {
        val env = DeviceShellLauncher.envFor(
            mapOf("TERM" to "linux"),
            mapOf("TERM" to DeviceShellLauncher.TERM)
        )
        // The override wins, and a missing PATH is still guaranteed on top.
        assertEquals(
            listOf("TERM=xterm-256color", "PATH=/system/bin:/system/xbin:/vendor/bin:/sbin"),
            env.toList()
        )
    }

    @Test
    fun `an empty environment gets both guarantees`() {
        val env = DeviceShellLauncher.envFor(emptyMap(), emptyMap())
        // TERM is checked before PATH, so that is the order they are appended in.
        assertEquals(
            listOf("TERM=xterm-256color", "PATH=/system/bin:/system/xbin:/vendor/bin:/sbin"),
            env.toList()
        )
    }

    @Test
    fun `an environment with both PATH and TERM is unchanged`() {
        val base = mapOf("PATH" to "/system/bin", "TERM" to "xterm")
        assertArrayEquals(
            arrayOf("PATH=/system/bin", "TERM=xterm"),
            DeviceShellLauncher.envFor(base, emptyMap())
        )
    }
}
