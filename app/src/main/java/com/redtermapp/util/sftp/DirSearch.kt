package com.redtermapp.util.sftp

/**
 * The bounded breadth-first search behind a "search all subfolders" walk.
 *
 * Recursive remote search is one round trip per directory, so the walk is
 * breadth-first (a shallow hit is found before a deep one), stops the moment
 * [find] has [limit] matches, and is capped at [maxDirs] directories so a
 * huge or cyclic tree cannot search the server until the hour changes.
 *
 * The matching and cutoff rules are pure and unit-tested; only the caller's
 * [childrenOf] touches a connection.
 */
object DirSearch {

    /**
     * One directory entry. [payload] is the caller's full record (an
     * [com.redtermapp.ui.filelist.FileEntry] in the app) so the walk can
     * hand back exactly what it was given, unmodified.
     */
    data class Entry<T>(
        val name: String,
        val isDirectory: Boolean,
        val isSymlink: Boolean,
        val remotePath: String,
        val payload: T
    )

    data class Result<T>(
        val matches: List<Entry<T>>,
        /** True when the walk stopped before it was complete. */
        val truncated: Boolean,
        val dirsListed: Int,
        val unreadableDirs: Int
    )

    /**
     * Breadth-first from [root]. Directory symlinks are listed but never
     * descended into, which is what keeps a cycle from searching forever.
     *
     * @return the file matches (names containing [query], case-insensitive)
     *   and whether the walk was cut short by [limit], [maxDirs], or an
     *   unreadable directory.
     */
    fun <T> find(
        root: String,
        childrenOf: (remoteDir: String) -> List<Entry<T>>?,
        query: String,
        limit: Int,
        maxDirs: Int
    ): Result<T> {
        val matches = ArrayList<Entry<T>>()
        var dirsListed = 0
        var unreadable = 0
        val queue = ArrayDeque<String>()
        queue.add(root)
        dirsListed++
        while (queue.isNotEmpty() && matches.size < limit) {
            val dir = queue.removeFirst()
            val children = childrenOf(dir)
            if (children == null) {
                unreadable++
                continue
            }
            for (c in children) {
                if (matches.size >= limit) break
                if (c.name.isEmpty() || c.name == "." || c.name == "..") continue
                when {
                    c.isDirectory && !c.isSymlink -> {
                        if (dirsListed < maxDirs) {
                            queue.add(c.remotePath)
                            dirsListed++
                        }
                    }
                    !c.isDirectory -> {
                        if (c.name.contains(query, ignoreCase = true)) matches.add(c)
                    }
                }
            }
        }
        // Complete only when the queue ran dry with nothing left that would
        // have been cut off by a cap or an unreadable directory.
        val truncated = queue.isNotEmpty() || matches.size >= limit ||
            dirsListed >= maxDirs || unreadable > 0
        return Result(matches, truncated, dirsListed, unreadable)
    }
}
