package com.redtermapp.distro

import java.io.File

/**
 * Removing a rootfs that a previous install left behind.
 *
 * `File.deleteRecursively()` cannot unlink anything inside a directory it has no write
 * access to, and a rootfs is full of directories an archive deliberately records
 * read-only. Arch's is:
 *
 *     dr-xr-xr-x  arch-aarch64/etc/ca-certificates/extracted/cadir/
 *
 * The first install writes that mode at the end of extraction, so the *second* install
 * cannot clear it. The return value of `deleteRecursively()` was ignored, so extraction
 * continued into the old tree and wrote into files it could not open:
 *
 *     .../cadir/ACCVRAIZ1.pem: open failed: EACCES (Permission denied)
 *
 * which names a file and an errno, and nothing about a tree that survived from last
 * time. It made a working distribution look uninstallable, and the obvious response --
 * reinstall -- is the thing that triggers it.
 *
 * Directories are made owner-writable deepest-first, because that is what unlinking a
 * child needs. Files do not: permission to unlink comes from the parent, so a read-only
 * *file* is never the obstacle.
 *
 * Whether the removal actually happened is returned rather than assumed, so a caller can
 * refuse instead of extracting into a tree it could not clear.
 */
internal object TreeRemoval {

    /**
     * Replaced in tests, where `android.system.Os` is a stub that throws.
     *
     * Same reason `RootfsInstallExtractor.makeSymlink` is a seam: a chmod the test
     * cannot call is a chmod the test cannot exercise, and this one is the entire
     * point of the class.
     */
    internal var chmod: (path: String, mode: Int) -> Unit =
        { path, mode -> android.system.Os.chmod(path, mode) }

    fun remove(dir: File): Boolean {
        if (!dir.exists()) return true
        if (dir.isDirectory) {
            runCatching {
                // Writable *before* descending, not after. Unlinking a child needs write
                // access to its parent, so recursing first leaves every child of a
                // read-only directory in place -- the directory can then be chmod-ed but
                // never deleted, because it is not empty. The first version did it in
                // that order and only passed because a deleteRecursively() retry swept
                // up afterwards; against the real Arch cadir subtree that retry is the
                // only thing standing between this and the original EACCES.
                chmod(dir.absolutePath, 0x1FF)
                dir.listFiles()?.forEach { remove(it) }
            }
        }
        if (!dir.delete() && dir.isDirectory) {
            runCatching { chmod(dir.absolutePath, 0x1FF) }
            dir.deleteRecursively()
        }
        return !dir.exists()
    }
}