package com.redtermapp.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExtendedToolkitTest {

    @Test
    fun `the ext bin slots between the device dirs and the lean toolkit`() {
        val devicePrefix = "/system/bin:/bin"
        val ext = "/data/data/x/files/extended-toolkit/aarch64/bin"
        val toolkit = "/data/data/x/files/device-toolkit/aarch64"
        val p = ExtendedToolkit.toolkitPath(devicePrefix, ext, toolkit)
        assertEquals("$devicePrefix:$ext:$toolkit", p)
        // device dirs lead, the ext bin precedes the lean toolkit so a GNU
        // tool beats the busybox applet.
        assertTrue(p.indexOf(devicePrefix) == 0)
        assertTrue(p.indexOf(ext) < p.indexOf(toolkit))
    }

    @Test
    fun `no ext bin leaves the path unchanged`() {
        val toolkit = "/data/data/x/files/device-toolkit/aarch64"
        assertEquals("/bin:$toolkit", ExtendedToolkit.toolkitPath("/bin", null, toolkit))
        assertEquals("/bin:$toolkit", ExtendedToolkit.toolkitPath("/bin", "   ", toolkit))
    }

    @Test
    fun `the launcher puts the ext bin on PATH and the ext lib on LD_LIBRARY_PATH`() {
        val script = DeviceToolkit.launcherScript(
            "/data/app/lib",
            File("/data/data/x/files/device-toolkit/aarch64"),
            "/sdcard",
            File("/data/data/x/files/device-toolkit-tmp"),
            extBin = "/data/data/x/files/extended-toolkit/aarch64/bin",
            extLib = "/data/data/x/files/extended-toolkit/aarch64/lib"
        )
        val pathLine = script.lineSequence().first { it.startsWith("export PATH=") }
        assertTrue(pathLine.contains("extended-toolkit/aarch64/bin"))
        assertTrue(
            "ext bin must precede the lean toolkit",
            pathLine.indexOf("extended-toolkit") < pathLine.indexOf("device-toolkit")
        )
        assertTrue(
            script.lineSequence().any {
                it.startsWith("export LD_LIBRARY_PATH=") && it.contains("extended-toolkit/aarch64/lib")
            }
        )
    }

    @Test
    fun `the launcher omits the lib line and the ext path when there are no ext tools`() {
        val script = DeviceToolkit.launcherScript(
            "/data/app/lib",
            File("/data/data/x/files/device-toolkit/aarch64"),
            "/sdcard",
            File("/data/data/x/files/device-toolkit-tmp")
        )
        val pathLine = script.lineSequence().first { it.startsWith("export PATH=") }
        assertFalse(pathLine.contains("extended-toolkit"))
        assertFalse(script.lineSequence().any { it.startsWith("export LD_LIBRARY_PATH=") })
    }

    @Test
    fun `the fallback system shell line is still the last line`() {
        val script = DeviceToolkit.launcherScript(
            "/data/app/lib",
            File("/data/data/x/files/device-toolkit/aarch64"),
            "/sdcard",
            File("/data/data/x/files/device-toolkit-tmp")
        )
        val lines = script.lines()
        assertEquals("exec /system/bin/sh -i", lines.last())
    }
}
