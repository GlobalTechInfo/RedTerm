package com.redtermapp.util

import java.io.File

/**
 * How much RAM and how many cores the device has, read from /proc.
 *
 * A proot distro is not a virtual machine: it shares the device's kernel,
 * so the resource that decides whether a session will run at all is the
 * device's free memory and core count, not anything the guest reports.
 * That is what the dashboard shows.
 *
 * The [parseMemInfo]/[cpuCount] functions are pure and unit-tested; the
 * [read*] wrappers are the thin /proc reads that need a device.
 */
object DeviceResources {

    /** /proc/meminfo figures, in kilobytes as the kernel reports them. */
    data class Memory(val totalKb: Long, val availableKb: Long) {
        val usedKb: Long get() = (totalKb - availableKb).coerceAtLeast(0L)
    }

    /**
     * The lines that matter out of /proc/meminfo.
     *
     * Each line is `Key:    12345 kB`. Only MemTotal and MemAvailable are
     * read; everything else, and any malformed line, is skipped rather than
     * fatal, because a kernel that omits a field is not an error to report.
     */
    fun parseMemInfo(text: String): Memory {
        var total = 0L
        var available = 0L
        for (line in text.lineSequence()) {
            val sep = line.indexOf(':')
            if (sep <= 0) continue
            val key = line.substring(0, sep).trim()
            val value = line.substring(sep + 1).trim().substringBefore(' ').toLongOrNull() ?: continue
            when (key) {
                "MemTotal" -> total = value
                "MemAvailable" -> available = value
            }
        }
        return Memory(total, available)
    }

    /**
     * Core count from /proc/cpuinfo.
     *
     * One `processor` line per core is the stable part of the format;
     * the model-name and flags lines differ per architecture and must not
     * be counted.
     */
    fun cpuCount(cpuinfo: String): Int =
        cpuinfo.lineSequence().count { it.startsWith("processor") }

    fun readMemory(): Memory? = runCatching {
        parseMemInfo(File("/proc/meminfo").readText())
    }.getOrNull()

    fun readCpuCount(): Int = runCatching {
        cpuCount(File("/proc/cpuinfo").readText())
    }.getOrNull()?.takeIf { it > 0 } ?: Runtime.getRuntime().availableProcessors()
}
