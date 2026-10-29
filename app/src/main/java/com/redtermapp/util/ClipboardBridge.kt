package com.redtermapp.util

import android.content.Context
import java.io.File

/**
 * Bridges the Android clipboard into a proot distro session.
 *
 * Guest tools that need a clipboard (`gh auth login` prints its one-time
 * code and tries to copy it with xsel/xclip/wl-clipboard/Termux:API) have
 * none inside a distro: there is no X, no Wayland, and Termux:API talks to
 * the Termux app, which this is not. So the rootfs gets `xsel`/`xclip`
 * shims that write the content into this app's own external storage, where
 * the guest reaches it through the `/sdcard` bind, and set a trigger
 * marker. The app consumes the trigger and hands the content to the system
 * clipboard, which is what a browser's paste box then reads.
 *
 * The protocol is files, not a socket: no port to collide with, nothing
 * that dies with a process. A pending set the app died before consuming is
 * picked up on the next start, because the trigger file outlives it.
 */
object ClipboardBridge {

    /** Bumped when the shim's text changes; the marker line carries it. */
    const val SHIM_VERSION = 1

    const val TRIGGER_NAME = "trigger"
    const val PENDING_NAME = "pending"
    const val LAST_NAME = "last"

    /**
     * The protocol directory, on the host side. The guest sees the same
     * path, because it lives under /sdcard, which proot binds 1:1; and the
     * app's own external files directory is writable by the guest's
     * processes without any all-files grant, because they run as this
     * app's uid.
     *
     * Null when there is no external storage at all; then the bridge is
     * off, which is the same as today rather than a new failure.
     */
    fun hostDir(context: Context): File? {
        val root = context.getExternalFilesDir(null) ?: return null
        val dir = File(root, "clipboard")
        dir.mkdirs()
        return dir
    }

    /**
     * The path the launcher exports to the guest. It is the host path
     * verbatim: /sdcard binds onto /sdcard, so there is no translation.
     */
    fun guestPath(hostPath: String): String = hostPath

    /**
     * The shim, installed as both `xsel` and `xclip` in the rootfs'
     * /usr/local/bin, which the session's PATH puts ahead of anything the
     * distro might ship.
     *
     * GET (`-o`) prints the last thing the app put on the clipboard; SET
     * (stdin, with or without `-i`) writes it for the app to pick up.
     * Unknown flags fall through to SET, which is the only mode that ever
     * matters here. The fallback directory covers a session whose
     * launcher predates the environment export.
     */
    fun shim(fallbackGuestDir: String): String =
        listOf(
            "#!/system/bin/sh",
            "# managed by RedTerm (clipboard shim v$SHIM_VERSION); do not edit",
            "d=\"${'$'}{REDTERM_CLIPDIR:-$fallbackGuestDir}\"",
            "case \"${'$'}{1:-}\" in",
            "    -o|--output)",
            "        cat \"${'$'}d/$LAST_NAME\" 2>/dev/null",
            "        exit 0",
            "        ;;",
            "    *)",
            "        if [ -t 0 ]; then",
            "            echo \"${'$'}0: nothing to copy: no input (get the clipboard with '${'$'}0 -o')\" 1>&2",
            "            exit 1",
            "        fi",
            "        cat > \"${'$'}d/$PENDING_NAME\" || exit 1",
            "        : > \"${'$'}d/$TRIGGER_NAME\"",
            "        exit 0",
            "        ;;",
            "esac",
        ).joinToString("\n") + "\n"

    /**
     * Writes the shims into the rootfs' /usr/local/bin, executable, and
     * only when the content differs from what is there. That is the rule
     * for a managed file: an older RedTerm shim is rewritten on the next
     * session start, which is how a corrected shim reaches an install that
     * already has one; the marker in the first lines says which hand wrote
     * the file.
     */
    fun installShims(rootfsDir: File, fallbackGuestDir: String) {
        val bin = File(rootfsDir, "usr/local/bin")
        bin.mkdirs()
        val script = shim(fallbackGuestDir)
        for (name in listOf("xsel", "xclip")) {
            val file = File(bin, name)
            val current = file.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
            if (current != script) {
                file.writeText(script)
            }
            file.setExecutable(true, false)
        }
    }

    /**
     * One step of the consumption loop: if the guest set something, move it
     * to [LAST_NAME] for the next GET and return it, otherwise null. A
     * trigger with no pending file (a kill mid-write) is swept, not an
     * error: there is nothing to deliver and the marker must not pile up.
     */
    fun consume(dir: File): String? {
        val trigger = File(dir, TRIGGER_NAME)
        if (!trigger.exists()) return null
        val pending = File(dir, PENDING_NAME)
        val data = if (pending.isFile) runCatching { pending.readText() }.getOrNull() else null
        if (data != null) {
            pending.copyTo(File(dir, LAST_NAME), overwrite = true)
        }
        trigger.delete()
        return data
    }
}
