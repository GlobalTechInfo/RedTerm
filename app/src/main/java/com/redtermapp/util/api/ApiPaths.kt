package com.redtermapp.util.api

import android.content.Context
import com.redtermapp.distro.ProotLaunch
import com.redtermapp.util.AppLog
import com.redtermapp.util.api.ApiRequestParser.ApiRequest
import java.io.File

/**
 * Where a guest-named path actually lives for the app process.
 *
 * The bridge runs in the app's process, but the paths its features open
 * are named on the *guest* side: a guest `/root/photo.jpg` is a file in
 * this session's rootfs, at the host path the launcher exports as
 * `REDTERM_ROOTFS_HOST`, not at the app's own `/root`. A path under one of
 * proot's bind directories (`/sdcard`, `/system`, …) is the same string on
 * both sides. This object is the one place that knows that, so a feature
 * that opens a guest path goes through it rather than reopening the raw
 * string.
 *
 * Both the origin and the containment check are untrusted from the guest:
 * the origin is an extra the guest's helper sets, and a path may carry `..`
 * or a symlink, so each is normalised (and the origin pinned to the app's
 * own storage) before it is trusted.
 */
object ApiPaths {

    /**
     * The extra the guest helper attaches to every request when the
     * launcher exported [REDTERM_ROOTFS_HOST]: the host path of the
     * session's rootfs. Absent for a session started any other way.
     */
    const val EXTRA_ROOTFS_HOST = "redterm-rootfs-host"

    const val REDTERM_ROOTFS_HOST = "REDTERM_ROOTFS_HOST"

    /**
     * The host path the app process opens for [guestPath], or null when it
     * cannot be projected: a relative path needs the guest's own
     * `realpath`, a request without the rootfs extra has no origin, an
     * origin that is not one of the app's own rootfs directories is
     * rejected, and a `..` or symlink that escapes the rootfs is refused.
     */
    fun hostPath(context: Context, request: ApiRequest, guestPath: String): String? {
        val rootfsHost = request.str(EXTRA_ROOTFS_HOST)
        if (rootfsHost.isNullOrEmpty()) return null
        // The origin is guest-supplied, so it is pinned to the app's own
        // storage before it is trusted: only one of this app's rootfs
        // trees (all under its files dir) may be the projection root.
        val origin = File(rootfsHost).absoluteFile.normalize()
        if (!isUnder(origin, canonicalOr(context.filesDir))) {
            AppLog.w(context, "api-paths", "rootfs origin '$rootfsHost' is outside the app's storage; not projecting '$guestPath'")
            return null
        }
        return project(origin.absolutePath, guestPath)
    }

    /**
     * Pure projection: given a trusted [origin], the host path for
     * [guestPath], or null. The bind check and the containment check live
     * here so they are testable without a Context; [hostPath] adds the
     * origin validation on top.
     */
    fun project(origin: String, guestPath: String): String? {
        val path = guestPath.trim()
        if (path.isEmpty() || !path.startsWith("/")) return null
        // Normalise *first*: a bind prefix must match the path after `..`
        // collapse, or `/dev/../data/...` would pass the bind check as-is
        // and the app would stat the host path the `..` resolves to.
        val lexical = normalizeLexically(path)
        for (dir in ProotLaunch.BOUND_DIRS) {
            if (lexical == dir || lexical.startsWith("$dir/")) return lexical
        }
        // Resolve symlinks and `..` at the filesystem level before asking
        // whether the target stayed inside the rootfs; a lexical check is
        // fooled by a symlink whose target lies outside.
        val root = canonicalOr(File(origin))
        val target = canonicalOr(File(root, path.removePrefix("/")))
        return if (target == root || target.path.startsWith(root.path + File.separator)) target.path else null
    }

    /** Resolve symlinks; fall back to the lexical path when that is not possible. */
    private fun canonicalOr(f: File): File =
        runCatching { f.canonicalFile }.getOrDefault(f.absoluteFile)

    /** Lexical `..`/`.` collapse without touching the filesystem. */
    private fun normalizeLexically(path: String): String = File(path).normalize().path

    private fun isUnder(child: File, parent: File): Boolean {
        val c = canonicalOr(child)
        val p = canonicalOr(parent)
        return c == p || c.path.startsWith(p.path + File.separator)
    }

    /** The answer a feature gives when a path cannot be projected. */
    fun unreachable(path: String): String =
        "ERROR: '$path' is not reachable from the app process: guest paths must be absolute " +
            "(a relative one needs the guest's realpath), and the session must carry a rootfs origin " +
            "that is one of the app's own rootfs trees\n"
}
