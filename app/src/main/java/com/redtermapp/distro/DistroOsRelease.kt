package com.redtermapp.distro

import java.io.File

/**
 * Reads the version a distro reports about itself, from the /etc/os-release
 * inside its rootfs — the same file `cat /etc/os-release` shows, so the
 * dashboard's "version" matches what the user sees in the shell.
 *
 * The parse is pure (a map of the file's text) and unit-tested; only the file
 * read needs the device. Quoted and bare KEY=VALUE forms are both handled,
 * and comment/blank lines are ignored, which is the shape every distro ships.
 */
object DistroOsRelease {

    fun parse(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            var value = line.substring(eq + 1).trim()
            if (value.length >= 2 && (value.startsWith("\"") || value.startsWith("'")) &&
                value.endsWith(value.first())
            ) {
                value = value.substring(1, value.length - 1)
            }
            out[key] = value
        }
        return out
    }

    /** The version string to show: VERSION_ID, falling back to VERSION, then blank. */
    fun versionOf(fields: Map<String, String>): String =
        fields["VERSION_ID"]?.takeIf { it.isNotEmpty() }
            ?: fields["VERSION"]?.takeIf { it.isNotEmpty() }
            ?: ""

    /** Read and parse a distro's /etc/os-release; empty when the file is missing. */
    fun read(rootfsDir: File): Map<String, String> {
        val f = File(rootfsDir, "etc/os-release")
        return if (f.isFile) runCatching { parse(f.readText()) }.getOrNull() ?: emptyMap()
        else emptyMap()
    }
}
