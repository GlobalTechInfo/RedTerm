package com.redtermapp.distro

import java.io.File
import java.io.Serializable

/**
 * Walks a rootfs and decides what a backup should contain.
 *
 * Plain `java.io` throughout, deliberately. `java.nio.file` would be the tidier tool, but
 * `Files.readAttributes`, `Files.newDirectoryStream` and `Files.isReadable` are all API 26
 * and this app supports 24 — enabling core library desugaring to reach them would be a
 * whole-app build change to serve one function, and the lint gate is set to abort on
 * error. `File.listFiles`, `File.length` and `File.canRead` are API 1.
 *
 * Which entries are representable is the same question whatever the API used, and it is
 * not a small one: the archiver on a device is toybox, and toybox has no case for a unix
 * socket. It prints `unknown file type '140000'` — `0140000` is `S_IFSOCK`, masked out of
 * the mode — sets a non-zero exit and abandons the whole archive. One socket in `/run`
 * was enough to fail a backup, and toybox names no file, so the message points at
 * nothing. The contents are therefore decided here, before any archiver runs.
 */
object RootfsArchive {

    /** One thing left out, and why — so the user is told rather than left guessing. */
    data class Skipped(val path: String, val reason: String) : Serializable

    /**
     * @param entries paths relative to the rootfs's parent, parents before children, so an
     *   extraction recreates the tree in an order that works.
     * @param skipped what was left out. Not necessarily a problem: sockets and fifos are
     *   runtime artifacts, recreated by whatever needs them.
     * @param contentBytes total size of the regular files, for the space check.
     */
    data class Plan(
        val entries: List<String>,
        val skipped: List<Skipped>,
        val contentBytes: Long
    ) : Serializable

    /**
     * Walks [rootfsDir] once and returns what should be archived.
     *
     * @param excluded absolute paths proot bind-mounts at launch. They exist on device
     *   storage as unreadable stubs, are recreated by proot, and are excluded here rather
     *   than hunted for with `--exclude` patterns — how a given tar matches a pattern is
     *   its own business, and getting it wrong fails silently, either archiving the entry
     *   anyway or dropping a real path.
     */
    fun plan(rootfsDir: File, excluded: Set<String>): Plan {
        val entries = ArrayList<String>()
        val skipped = ArrayList<Skipped>()
        var bytes = 0L
        val parent = rootfsDir.parentFile
            ?: return Plan(emptyList(), listOf(Skipped(rootfsDir.name, "no parent directory")), 0)

        val stack = ArrayDeque<File>()
        stack.addLast(rootfsDir)
        entries.add(rootfsDir.name)

        // Explicit stack, so an unreadable directory is one skipped entry rather than an
        // exception from a recursive walk that unwinds the lot. Depth is bounded by path
        // length and symlinks are never followed, so this cannot loop.
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            // Resolved once here and reused, because canonicalising is the costly half
            // and every entry in this directory shares the answer.
            val canonicalDir = try {
                dir.canonicalFile
            } catch (_: Exception) {
                dir.absoluteFile
            }
            val children = try {
                dir.listFiles() ?: emptyArray()
            } catch (_: SecurityException) {
                skipped.add(Skipped(nameOf(rootfsDir, dir), "unreadable directory"))
                emptyArray()
            }
            // Sorted so the same tree lists in the same order twice: two archives become
            // comparable, and a repeated failure is reproducible.
            for (child in children.sortedBy { it.name }) {
                if (child.absolutePath in excluded) {
                    skipped.add(Skipped(nameOf(rootfsDir, child), "mounted by proot"))
                    continue
                }
                // Whether *this entry* is a link, and not whether its path resolves
                // anywhere else. Comparing canonicalPath to absolutePath answers the
                // second question, so a symlink anywhere above made every child look
                // like one: nothing was walked, and the backup became a tree of links
                // in a fraction of a second. On Android /data/data is a symlink to
                // /data/user/0, so the rootfs path always has one above it.
                //
                // Resolving the parent first and then only the child's own name is what
                // makes this answer the right question. The parent is resolved once per
                // directory rather than per entry, because canonicalising is the
                // expensive half.
                val isLink = try {
                    val inCanonicalDir = File(canonicalDir, child.name)
                    inCanonicalDir.canonicalFile != inCanonicalDir.absoluteFile
                } catch (_: Exception) {
                    false
                }
                when {
                    isLink -> entries.add(nameOf(rootfsDir, child))
                    // A dangling symlink. `getCanonicalPath` cannot resolve one, so it
                    // returns the path unchanged and the link test above says no — the
                    // entry looks like nothing at all: not a file, not a directory, not
                    // readable. It is still a link, and it still has to be archived as
                    // one, because restoring it verbatim is what recreates it. Arch is
                    // full of them: `/var/lock` -> `../run/lock`, and every
                    // `libfoo.so` whose versioned target sits elsewhere. An entry that
                    // came back from a directory listing and cannot be stat is a
                    // dangling link and nothing else.
                    !child.exists() -> entries.add(nameOf(rootfsDir, child))
                    child.isDirectory -> {
                        entries.add(nameOf(rootfsDir, child))
                        stack.addLast(child)
                    }
                    child.isFile -> {
                        // Readability is checked here rather than left to the archiver: a
                        // mode-000 file still lists, so it would be archived as an entry
                        // and then quietly dropped while the backup reported success.
                        if (child.canRead()) {
                            bytes += child.length()
                            entries.add(nameOf(rootfsDir, child))
                        } else {
                            skipped.add(Skipped(nameOf(rootfsDir, child), "not readable"))
                        }
                    }
                    // Sockets, fifos and devices: what toybox refuses.
                    else -> skipped.add(Skipped(nameOf(rootfsDir, child), describe(child)))
                }
            }
        }
        return Plan(entries, skipped, bytes)
    }

    /**
     * The path as tar will see it, which is the whole path below the parent directory.
     *
     * Built from `File.parent` rather than from the name being walked, because the two
     * silently disagree for a tree made of symlinks: `canonicalPath` resolves them, so
     * `$rootfs/etc` can come back as `/real/place/etc` and the archive ends up holding a
     * file tree that is not the one on disk.
     */
    private fun nameOf(rootfsDir: File, file: File): String {
        val base = rootfsDir.parentFile ?: return file.name
        if (file == rootfsDir) return base.name

        // Strip the base prefix from the absolute path rather than asking
        // java.nio.file to relativise: Path.relativize is API 26 and this app supports
        // 24, and the two cases a textual prefix has to get right are the ones that
        // matter here — a path equal to the base, and a sibling directory whose name
        // merely starts with the same characters.
        val basePath = base.absolutePath.trimEnd(File.separatorChar) + File.separatorChar
        val full = file.absolutePath
        return when {
            full.startsWith(basePath) -> full.substring(basePath.length)
            else -> file.path
        }
    }

    /**
     * Names a file type, without `java.nio.file`.
     *
     * `java.io.File` reports a socket as neither a directory nor a file and nothing more,
     * so the description cannot be specific — it is what it can honestly say. The name is
     * cosmetic; the exclusion either side of it is not, and happens regardless.
     *
     * Reported as unreadable rather than as an odd type, because that is also true and
     * says something useful: a fifo or a socket cannot be read for content, and `canRead`
     * answers false for one, so the branch is reached by the same test that protects
     * against archiving an unreadable file.
     */
    private fun describe(file: File): String =
        if (try {
                file.canRead()
            } catch (_: SecurityException) {
                false
            }
        ) {
            "not a regular file, directory or symlink"
        } else {
            "not readable"
        }

    /** What the permission fix-up had to do. */
    data class Repaired(val files: Int, val directories: Int, val stillUnreadable: List<String>)

    /**
     * Makes everything in the tree readable by its owner, and says what it had to do.
     *
     * A distro image ships files only root may read — `/etc/shadow`, `/etc/gshadow`,
     * `/etc/sudoers` — and Fedora ships several hundred of them. The app does not run as
     * root: the shell reaches those files only because proot fakes it. So `access(R_OK)`
     * fails for the backup, `tar` cannot read them either, and they are left out of the
     * archive. A backup that omits `/etc/shadow` still reports success and restores into
     * a distro where login cannot work.
     *
     * Only the owner's bits are touched, and only where they are missing — the second
     * argument to `setReadable` is `ownerOnly`, and passing `false` there grants the
     * world instead. That is both wider than the intent and the thing lint flags, which
     * is a useful reminder that the argument is not an optional extra.
     *
     * That is the
     * minimum needed to read the tree, and it grants nothing a shell inside proot does
     * not already have — the same fix-up proot-distro applies before its own backup.
     */
    fun makeReadable(rootfsDir: File, excluded: Set<String>): Repaired {
        var files = 0
        var directories = 0
        val stillUnreadable = ArrayList<String>()
        val stack = ArrayDeque<File>()
        stack.addLast(rootfsDir)

        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            if (dir.absolutePath in excluded) continue
            val children = try {
                dir.listFiles() ?: emptyArray()
            } catch (_: SecurityException) {
                continue
            }
            for (child in children) {
                if (child.absolutePath in excluded) continue
                // No link following: a link's own permissions are irrelevant, and the
                // tree it points at is either excluded or visited on its own account.
                val isLink = try {
                    val canonicalDir = try {
                        dir.canonicalFile
                    } catch (_: Exception) {
                        dir.absoluteFile
                    }
                    val inCanonicalDir = File(canonicalDir, child.name)
                    inCanonicalDir.canonicalFile != inCanonicalDir.absoluteFile
                } catch (_: Exception) {
                    false
                }
                if (isLink) continue

                val isDirectory = child.isDirectory
                if (isDirectory) {
                    // Both bits, and both are needed: the execute bit to reach into the
                    // directory, and the read bit to list what is in it. Adding only
                    // execute repairs a directory that still cannot be listed, which is
                    // the same invisibility as not repairing it at all.
                    if (!child.canExecute() || !child.canRead()) {
                        child.setExecutable(true, true)
                        child.setReadable(true, true)
                        directories++
                    }
                    stack.addLast(child)
                    continue
                }
                if (!child.isFile) continue
                if (child.canRead()) continue
                if (child.setReadable(true, true) && child.canRead()) {
                    files++
                } else {
                    stillUnreadable.add(child.path)
                }
            }
        }
        return Repaired(files, directories, stillUnreadable)
    }

    /** One line summarising what was left out, or null when nothing was. */
    fun describeSkipped(skipped: List<Skipped>): String? {
        if (skipped.isEmpty()) return null
        val counts = skipped.groupingBy { it.reason }.eachCount()
            .entries
            .sortedByDescending { it.value }
            // No pluralisation: the reasons are phrases, not nouns, and appending an "s"
            // produced "2 not a regular file, directory or symlinks".
            .joinToString(", ") { "${it.value} \u00d7 ${it.key}" }
        val examples = skipped.take(3).joinToString(", ") { it.path }
        return "$counts ($examples)"
    }
}