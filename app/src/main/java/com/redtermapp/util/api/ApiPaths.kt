package com.redtermapp.util.api

import com.redtermapp.distro.ProotLaunch
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
     * The host path the app process opens for [guestPath], or null when
     * it cannot be projected: a relative path needs the guest's own
     * `realpath` (the app does not know the guest's working directory),
     * a request without the rootfs context has no origin to project
     * from, and a `..` that escapes the rootfs would point the app at
     * a tree it should not reach.
     */
    fun hostPath(request: ApiRequest, guestPath: String): String? {
        val path = guestPath.trim()
        if (path.isEmpty() || !path.startsWith("/")) return null
        // A bind mount is the same path on both sides of proot.
        for (dir in ProotLaunch.BOUND_DIRS) {
            if (path == dir || path.startsWith("$dir/")) return path
        }
        val rootfsHost = request.str(EXTRA_ROOTFS_HOST)
        if (rootfsHost.isNullOrEmpty()) return null
        val root = File(rootfsHost).absoluteFile.normalize().absolutePath
        val target = File(root, path.removePrefix("/")).normalize().absolutePath
        return if (target == root || target.startsWith("$root/")) target else null
    }

    /** The answer a feature gives when a path cannot be projected. */
    fun unreachable(path: String): String =
        "ERROR: '$path' is not reachable from the app process: guest paths must be absolute " +
            "(a relative one needs the guest's realpath), and the session must carry its rootfs origin\n"
}
