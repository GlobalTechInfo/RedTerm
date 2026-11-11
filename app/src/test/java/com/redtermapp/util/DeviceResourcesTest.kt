package com.redtermapp.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceResourcesTest {

    private val meminfo = """
        MemTotal:       7834096 kB
        MemFree:        512345 kB
        MemAvailable:   4096000 kB
        Buffers:          65536 kB
        Cached:         1048576 kB
        SwapTotal:       2097148 kB
    """.trimIndent()

    @Test
    fun `MemTotal and MemAvailable are read, the rest ignored`() {
        val m = DeviceResources.parseMemInfo(meminfo)
        assertEquals(7834096L, m.totalKb)
        assertEquals(4096000L, m.availableKb)
        assertEquals(3738096L, m.usedKb)
    }

    @Test
    fun `a kernel that omits MemAvailable still reports total`() {
        val text = "MemTotal:  100000 kB\n"
        val m = DeviceResources.parseMemInfo(text)
        assertEquals(100000L, m.totalKb)
        assertEquals(0L, m.availableKb)
    }

    @Test
    fun `garbage and blank lines are skipped, not fatal`() {
        val text = "\nnot a meminfo line\nMemTotal:   2048 kB\n   : weird\n"
        val m = DeviceResources.parseMemInfo(text)
        assertEquals(2048L, m.totalKb)
    }

    @Test
    fun `used never goes negative when available exceeds total`() {
        val m = DeviceResources.Memory(totalKb = 100, availableKb = 200)
        assertEquals(0L, m.usedKb)
    }

    @Test
    fun `cpuCount counts processor lines, not model or flags`() {
        val cpuinfo = """
            processor	: 0
            BogoMIPS	: 37.00
            processor	: 1
            model name	: ARMv8 Processor
            processor	: 2
            processor	: 3
        """.trimIndent()
        assertEquals(4, DeviceResources.cpuCount(cpuinfo))
        // "model name" and the bogoMIPS line must not count.
        assertEquals(0, DeviceResources.cpuCount("model name : something\n"))
    }
}
