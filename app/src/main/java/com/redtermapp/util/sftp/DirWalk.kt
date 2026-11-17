package com.redtermapp.util.sftp

import java.io.File

/**
 * The plan for a recursive folder download, separated from the connection
 * so the walking rules are unit-testable.
 *
 * Walking a remote tree is one round trip per directory, so the planner is
 * breadth-first: siblings are enumerated before descendants, which means
 * [Result.directories] come out shallowest first (a parent must exist before
 * its child is created locally) and a deep tree cannot eat the caps before
 * the shallow files are collected. The caps themselves keep a symlink cycle
 * or a pathologically deep server from walking until the hour changes.
 *
 * [childrenOf] is the only I/O in the picture and it is injected: the real
 * one is a [SftpClient.list] behind a connection, and the tests drive it with
 * a scripted map, the way the SFTP protocol tests drive a fake server.
 */
object DirWalk {

    /** One directory's child. [isSymlink] matters: links are never followed. */
    data class Child(
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val isSymlink: Boolean
    )

    /** A file to download. [relativePath] mirrors it under the local root. */
    data class RemoteFile(val remotePath: String, val relativePath: String, val size: Long)

    data class Result(
        val files: List<RemoteFile>,
        /** Local directories to create, shallowest first. */
        val directories: List<String>,
        /** Directories that could not be listed; their subtrees are missing. */
        val unreadableDirs: List<String>,
        /** Symlinked directories, which are neither followed nor downloadable. */
        val skippedSymlinks: List<String>,
        /** True when [maxFiles] or [maxDepth] cut the walk short. */
        val truncated: Boolean
    )

    const val DEFAULT_MAX_FILES = 10_000
    const val DEFAULT_MAX_DEPTH = 64

    fun plan(
        root: String,
        childrenOf: (remoteDir: String) -> List<Child>?,
        maxFiles: Int = DEFAULT_MAX_FILES,
        maxDepth: Int = DEFAULT_MAX_DEPTH
    ): Result {
        val files = ArrayList<RemoteFile>()
        val directories = ArrayList<String>()
        val unreadable = ArrayList<String>()
        val skippedSymlinks = ArrayList<String>()
        var truncated = false

        data class Pending(val remote: String, val relative: String, val depth: Int)
        val queue = ArrayDeque<Pending>()
        queue.add(Pending(root, "", 0))

        outer@ while (queue.isNotEmpty()) {
            val pending = queue.removeFirst()
            val children = childrenOf(pending.remote)
            if (children == null) {
                // A directory that cannot be listed is reported, not fatal:
                // the rest of the tree still downloads.
                unreadable.add(pending.remote)
                continue
            }
            for (child in children) {
                // Names are untrusted input: a child that is not a bare name
                // would let the walk leave the tree entirely.
                if (child.name.isEmpty() || child.name == "." || child.name == ".." ||
                    child.name.contains('/')
                ) continue
                val relative =
                    if (pending.relative.isEmpty()) child.name else "${pending.relative}/${child.name}"
                when {
                    child.isSymlink && child.isDirectory -> {
                        // Following a link is how a cycle walks the server
                        // forever; downloading it would read the target as a
                        // file. Neither is what a mirror wants, so it is named
                        // and skipped.
                        skippedSymlinks.add(joinPath(pending.remote, child.name))
                    }
                    child.isDirectory -> {
                        directories.add(relative)
                        if (pending.depth + 1 < maxDepth) {
                            queue.add(
                                Pending(
                                    joinPath(pending.remote, child.name),
                                    relative,
                                    pending.depth + 1
                                )
                            )
                        } else {
                            truncated = true
                        }
                    }
                    else -> {
                        if (files.size >= maxFiles) {
                            truncated = true
                            break@outer
                        }
                        files.add(
                            RemoteFile(
                                remotePath = joinPath(pending.remote, child.name),
                                relativePath = relative,
                                size = child.size
                            )
                        )
                    }
                }
            }
        }
        return Result(files, directories, unreadable, skippedSymlinks, truncated)
    }

    /**
     * The local file for [relativePath] under [localRoot].
     *
     * Remote names are untrusted, so the result is refused when it would
     * escape the root: a crafted `..` segment must not turn a folder
     * download into a write anywhere on the device.
     */
    fun mirrorPath(localRoot: File, relativePath: String): File? {
        if (relativePath.isBlank()) return null
        val target = File(localRoot, relativePath)
        val root = localRoot.absoluteFile.normalize()
        val resolved = target.absoluteFile.normalize()
        val rootPath = root.absolutePath
        val inside =
            resolved.absolutePath == rootPath || resolved.absolutePath.startsWith("$rootPath/")
        return if (inside) target else null
    }

    /** [SftpClient.join] without the companion, so the planner stays pure. */
    fun joinPath(dir: String, name: String): String =
        if (dir.endsWith("/")) "$dir$name" else "$dir/$name"
}
