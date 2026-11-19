package com.redtermapp.ui

/**
 * How a device-shell session is spawned: the device's own `/system/bin/sh`,
 * no rootfs, no proot, no install — the same plain native spawn Termux's
 * terminal uses for its own shell.
 *
 * The two parts that could quietly go wrong are the start directory (a blank
 * extra must not point the child at a nonexistent path) and the environment
 * (an empty envp is a dead shell: no PATH, no TERM, nothing the user can
 * type resolves). Both are pure functions here, tested on the JVM, and the
 * activity passes the process environment in, so nothing android.jar stubbed
 * is executed in the tests.
 */
object DeviceShellLauncher {

    const val SHELL = "/system/bin/sh"
    const val TERM = "xterm-256color"

    private const val FALLBACK_PATH = "/system/bin:/system/xbin:/vendor/bin:/sbin"

    /** A blank or absent start directory means the root of the device tree. */
    fun cwdFor(startDir: String?): String = startDir?.trim()?.takeIf { it.isNotEmpty() } ?: "/"

    /**
     * The envp for the child, in `KEY=VALUE` form.
     *
     * The process environment goes first — it carries the PATH the platform
     * gave this app — and the extras override it. TERM and a last-resort
     * PATH are guaranteed, because a child with neither is a terminal the
     * user can open but not use.
     */
    fun envFor(base: Map<String, String>, extras: Map<String, String>): Array<String> {
        val merged = LinkedHashMap(base)
        merged.putAll(extras)
        val entries = merged.map { (key, value) -> "$key=$value" }.toMutableList()
        if (entries.none { it.startsWith("TERM=") }) entries.add("TERM=$TERM")
        if (entries.none { it.startsWith("PATH=") }) entries.add("PATH=$FALLBACK_PATH")
        return entries.toTypedArray()
    }
}
