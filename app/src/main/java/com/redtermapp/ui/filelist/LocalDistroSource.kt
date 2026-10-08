package com.redtermapp.ui.filelist

import java.io.File
import java.io.IOException

/**
 * The distribution's rootfs, seen as a plain directory.
 *
 * Everything here is a thin wrapper over [File]. It exists so the browser has one
 * interface to talk to rather than branching on where a file lives.
 */
class LocalDistroSource(
    private val rootfsDir: File,
    private val distroName: String
) : FileSource {

    override val root: String = rootfsDir.absolutePath

    /** The distribution's `/root`, which is where a user's files usually are. */
    override val home: String = File(rootfsDir, "root")
        .takeIf { it.isDirectory }?.absolutePath ?: root

    override val label: String = distroName

    override val canOpenLocally: Boolean = true

    override val lastError: String = ""

    override fun cacheCopy(entry: FileEntry): File? = File(entry.path)

    override fun list(path: String): List<FileEntry>? {
        val dir = File(path)
        val children = dir.listFiles() ?: return null
        return children.map { it.toEntry() }
    }

    override fun delete(path: String, recursive: Boolean): OpResult = try {
        val file = File(path)
        val ok = if (recursive) file.deleteRecursively() else file.delete()
        if (ok) OpResult.Ok else OpResult.Failed("could not delete $path")
    } catch (e: IOException) {
        OpResult.Failed(e.message ?: "delete failed")
    }

    override fun mkdir(path: String): OpResult = try {
        if (File(path).mkdirs()) OpResult.Ok else OpResult.Failed("mkdir failed")
    } catch (e: Exception) {
        OpResult.Failed(e.message ?: "mkdir failed")
    }

    override fun rename(from: String, to: String): OpResult = try {
        if (File(from).renameTo(File(to))) OpResult.Ok else OpResult.Failed("rename failed")
    } catch (e: Exception) {
        OpResult.Failed(e.message ?: "rename failed")
    }

    override fun copy(from: String, to: String, move: Boolean): OpResult = try {
        val source = File(from)
        val dest = File(to)
        if (move) {
            if (source.renameTo(dest)) {
                OpResult.Ok
            } else {
                // A rename across filesystems fails, which is the normal case for
                // anything the user copied between two directories of a rootfs
                // that is being rewritten underneath. Copy and delete is slower
                // but always works.
                source.copyRecursively(dest, overwrite = false)
                if (source.deleteRecursively()) OpResult.Ok
                else OpResult.Failed("copied but could not remove the original")
            }
        } else {
            source.copyRecursively(dest, overwrite = false)
            OpResult.Ok
        }
    } catch (e: IOException) {
        OpResult.Failed(e.message ?: "copy failed")
    }

    /** Recursive, because walking a local directory is cheap. */
    override fun summarise(path: String): FolderSummary {
        var files = 0
        var folders = 0
        var bytes = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(File(path))
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            for (child in current.listFiles() ?: continue) {
                if (child.isDirectory) {
                    folders++
                    stack.addLast(child)
                } else {
                    files++
                    bytes += child.length()
                }
            }
        }
        return FolderSummary(files, folders, bytes)
    }

    override fun search(from: String, query: String, limit: Int): Pair<List<FileEntry>, Boolean> {
        val found = ArrayList<FileEntry>()
        var truncated = false
        val stack = ArrayDeque<File>()
        stack.addLast(File(from))
        while (stack.isNotEmpty() && !truncated) {
            val current = stack.removeLast()
            for (child in current.listFiles() ?: continue) {
                if (child.name.contains(query, ignoreCase = true)) {
                    if (found.size >= limit) {
                        truncated = true
                        break
                    }
                    found.add(child.toEntry())
                }
                if (child.isDirectory) stack.addLast(child)
            }
        }
        found.sortBy { it.name.lowercase() }
        return found to truncated
    }

    private fun File.toEntry() = FileEntry(
        path = absolutePath,
        name = name,
        isDirectory = isDirectory,
        size = if (isDirectory) 0L else length(),
        modified = lastModified().toString(),
        isSymlink = false
    )
}