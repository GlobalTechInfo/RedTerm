package com.redtermapp.ui.filelist

/**
 * A filesystem the browser can read and change.
 *
 * Everything here blocks: reading a remote directory costs a network round trip
 * and an authentication handshake. The browser therefore calls all of it from a
 * background thread, which is why the interface does not pretend to be
 * asynchronous.
 */
interface FileSource {

    /** The outermost path this source will show; going above it is refused. */
    val root: String

    /** A sensible starting directory, usually the remote or login user's home. */
    val home: String

    /** Shown in the title bar, e.g. a distro name or a server label. */
    val label: String

    /**
     * Whether a file can be opened with a local viewer.
     *
     * False for a remote file until it has been downloaded; the browser uses this
     * to decide between "Open" and "Download".
     */
    val canOpenLocally: Boolean

    /** Where a locally-opened copy of a remote file is kept. */
    fun cacheCopy(entry: FileEntry): java.io.File?

    /** Lists [path]. Null means it could not be read; [lastError] says why. */
    fun list(path: String): List<FileEntry>?

    /** The reason the last [list], or last operation, failed. */
    val lastError: String

    fun delete(path: String, recursive: Boolean): OpResult

    fun mkdir(path: String): OpResult

    fun rename(from: String, to: String): OpResult

    /**
     * Changes permissions on [path].
     *
     * Default is "not supported" rather than abstract, so a source that genuinely
     * cannot do it — a local one, where the filesystem has no such concept to change
     * through this interface — says so instead of having to throw.
     */
    fun setPermissions(path: String, mode: Long): OpResult =
        OpResult.Failed("not supported here")

    /** Makes a symbolic link at [linkPath] pointing at [target]. */
    fun makeSymlink(target: String, linkPath: String): OpResult =
        OpResult.Failed("not supported here")

    /** Copies (or, with [move], relocates) within this source. */
    fun copy(from: String, to: String, move: Boolean): OpResult

    /** File/folder counts and total size for [path]. */
    fun summarise(path: String): FolderSummary

    /**
     * Searches below [from] for names containing [query].
     *
     * @return the matches and whether the result was cut off at [limit].
     */
    fun search(from: String, query: String, limit: Int): Pair<List<FileEntry>, Boolean>
}

/** Outcome of a change. [Failed.reason] is shown to the user verbatim. */
sealed class OpResult {
    object Ok : OpResult()
    data class Failed(val reason: String) : OpResult()

    val succeeded: Boolean get() = this is Ok
}

data class FolderSummary(val files: Int, val folders: Int, val bytes: Long)