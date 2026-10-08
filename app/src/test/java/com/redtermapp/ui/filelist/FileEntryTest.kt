package com.redtermapp.ui.filelist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the path arithmetic the browser relies on, which is the part that has to
 * work for a remote path as well as a local one.
 */
class FileEntryTest {

    @Test
    fun `the parent of a nested path is the directory above it`() {
        assertEquals("/srv/www", FileEntry("/srv/www/index.html", "index.html", false).parent)
    }

    @Test
    fun `the parent of a top level path is the root`() {
        assertEquals("/", FileEntry("/srv", "srv", true).parent)
    }

    @Test
    fun `a relative path has no parent to go up to`() {
        assertNull(FileEntry("srv", "srv", true).parent)
    }

    @Test
    fun `a hidden file is recognised by its name`() {
        assertTrue(FileEntry("/root/.bashrc", ".bashrc", false).isHidden)
        assertFalse(FileEntry("/root/bashrc", "bashrc", false).isHidden)
    }

    /**
     * A remote listing gives bare names, so the source rebuilds the full path
     * itself. Getting the joining wrong is how a browser ends up offering to
     * create a folder called `/srv/tmp/new folder` at the root of the server.
     */
    @Test
    fun `joining a directory and a name never produces a double slash`() {
        fun join(dir: String, name: String) =
            if (dir.endsWith("/")) "$dir$name" else "$dir/$name"
        assertEquals("/srv/app/file", join("/srv/app", "file"))
        assertEquals("/file", join("/", "file"))
    }

    @Test
    fun `a default entry is a plain file`() {
        val entry = FileEntry("/x", "x", false)
        assertEquals(0L, entry.size)
        assertEquals("", entry.modified)
        assertFalse(entry.isSymlink)
    }
}