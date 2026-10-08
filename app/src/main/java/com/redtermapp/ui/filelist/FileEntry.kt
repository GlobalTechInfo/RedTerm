package com.redtermapp.ui.filelist

/**
 * One row in a file browser, whatever the file actually lives on.
 *
 * Deliberately not a wrapper around `java.io.File`: a remote file has no
 * `java.io.File`, and pretending otherwise by building one locally would make
 * every remote path look like it exists on the device. A path plus the handful of
 * facts a row needs is enough for both.
 */
data class FileEntry(
    /** Full path in its own namespace: a host path, or a remote absolute path. */
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    /** Pre-formatted by the source, because a remote timestamp is remote text. */
    val modified: String = "",
    val isSymlink: Boolean = false,
    /**
     * The raw POSIX mode, when the source knows one.
     *
     * Null for a local entry: a local filesystem reports permissions through a channel
     * this interface does not model, and inventing a figure there would be worse than
     * admitting it is not known.
     */
    val permissions: Long? = null
) {
    val isHidden: Boolean get() = name.startsWith(".")

    /** The directory containing this entry, or null at the top. */
    val parent: String? get() = parentOf(path)

    companion object {
        /**
         * The directory containing [path].
         *
         * String-based rather than `File.getParentFile()`, which returns null for
         * a relative path and cannot be applied to a remote one at all.
         *
         * "/" has no parent, which matters: returning "/" for it would make going
         * up from the root land back on the root, so the browser would show a
         * parent row at the top forever.
         */
        fun parentOf(path: String): String? {
            val trimmed = path.trimEnd('/')
            if (!trimmed.contains('/')) return null
            val above = trimmed.substringBeforeLast('/')
            return if (above.isEmpty()) "/" else above
        }
    }
}