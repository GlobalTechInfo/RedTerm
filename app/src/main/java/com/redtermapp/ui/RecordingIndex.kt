package com.redtermapp.ui

import java.io.File

/**
 * Pure ordering and filtering for the recordings list. Kept out of the
 * Activity so the rules are unit-tested: a query is a case-insensitive
 * substring of the file name, and an empty query matches everything, so the
 * box doubles as a "clear the filter" rather than needing a second control.
 */
object RecordingIndex {

    fun matches(fileName: String, query: String): Boolean {
        val q = query.trim()
        return q.isEmpty() || fileName.contains(q, ignoreCase = true)
    }

    /** The list as a user reads it: newest recording on top. */
    fun newestFirst(files: List<File>): List<File> =
        files.sortedByDescending { it.lastModified() }
}
