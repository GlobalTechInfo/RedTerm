package com.redtermapp.util.sftp

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirWalkTest {

    private fun child(name: String, dir: Boolean = false, size: Long = 0L, link: Boolean = false) =
        DirWalk.Child(name, dir, size, link)

    /** A fake server: /a has b/ and c.txt; /a/b has d.txt and a link back to /a. */
    private fun scripted() = mapOf(
        "/a" to listOf(child("b", dir = true), child("c.txt", size = 3)),
        "/a/b" to listOf(
            child("d.txt", size = 5),
            child("loop", dir = true, link = true)
        )
    )

    private fun childrenOf(table: Map<String, List<DirWalk.Child>>) =
        { dir: String -> table[dir] }

    @Test
    fun `files come with mirrored relative paths and directories are shallowest first`() {
        val result = DirWalk.plan("/a", childrenOf(scripted()))
        assertEquals(
            listOf(
                DirWalk.RemoteFile("/a/c.txt", "c.txt", 3L),
                DirWalk.RemoteFile("/a/b/d.txt", "b/d.txt", 5L)
            ),
            result.files
        )
        // Breadth-first: the parent directory is planned before the child's files.
        assertEquals(listOf("b"), result.directories)
        assertFalse(result.truncated)
    }

    @Test
    fun `a symlinked directory is skipped and named, never followed`() {
        val result = DirWalk.plan("/a", childrenOf(scripted()))
        assertEquals(listOf("/a/b/loop"), result.skippedSymlinks)
        assertTrue(result.files.none { it.relativePath.startsWith("b/loop") })
    }

    @Test
    fun `an unreadable directory is reported, and the walk goes on elsewhere`() {
        val table = scripted().toMutableMap()
        // A third dir /a/e that the server refuses to list.
        table["/a"] = listOf(
            child("b", dir = true),
            child("e", dir = true),
            child("c.txt", size = 3)
        )
        val result = DirWalk.plan("/a", childrenOf(table)) // childrenOf("/a/e") -> null
        assertEquals(listOf("/a/e"), result.unreadableDirs)
        assertEquals(2, result.files.size) // c.txt and b/d.txt still planned
    }

    @Test
    fun `a symlink cycle cannot walk the server forever`() {
        val table = mapOf(
            "/x" to listOf(child("y", dir = true)),
            "/x/y" to listOf(child("back", dir = true, link = true), child("leaf", size = 1))
        )
        val result = DirWalk.plan("/x", childrenOf(table))
        assertEquals(listOf("/x/y/leaf"), result.files.map { it.remotePath })
        assertFalse(result.truncated)
    }

    @Test
    fun `the file cap cuts the walk short and says so`() {
        val table = mapOf(
            "/m" to (1..5).map { child("f$it.txt", size = 1L) }
        )
        val result = DirWalk.plan("/m", childrenOf(table), maxFiles = 3)
        assertEquals(3, result.files.size)
        assertTrue(result.truncated)
    }

    @Test
    fun `the depth cap collects files up to the cap and stops descending`() {
        val table = mapOf(
            "r" to listOf(child("d1", dir = true)),
            "r/d1" to listOf(child("d2", dir = true), child("f1.txt", size = 1L)),
            "r/d1/d2" to listOf(child("f2.txt", size = 1L))
        )
        // Depth 2 lists r and r/d1, but does not descend into r/d1/d2.
        val capped = DirWalk.plan("r", childrenOf(table), maxDepth = 2)
        assertEquals(listOf("d1/f1.txt"), capped.files.map { it.relativePath })
        assertEquals(listOf("d1", "d1/d2"), capped.directories)
        assertTrue(capped.truncated)

        // No cap in practice: everything is collected.
        val full = DirWalk.plan("r", childrenOf(table))
        assertEquals(listOf("d1/f1.txt", "d1/d2/f2.txt"), full.files.map { it.relativePath })
        assertFalse(full.truncated)
    }

    @Test
    fun `mirrorPath keeps every result inside the root`() {
        val root = File("/tmp/mirror")
        assertEquals(File("/tmp/mirror/a/b/c.txt"), DirWalk.mirrorPath(root, "a/b/c.txt"))
        assertNull(DirWalk.mirrorPath(root, "../escape"))
        assertNull(DirWalk.mirrorPath(root, ""))
    }

    @Test
    fun `a crafted dot-dot path that escapes the root is refused`() {
        val root = File("/tmp/mirror")
        // "a/../../.." normalises to /tmp, outside the root.
        assertNull(DirWalk.mirrorPath(root, "a/../../.."))
        // "a/../sibling/x" normalises to <root>/sibling/x: still inside, so legal.
        assertEquals(
            File("/tmp/mirror/sibling/x"),
            DirWalk.mirrorPath(root, "a/../sibling/x")?.absoluteFile?.normalize()
        )
        // Two levels up is outside no matter what follows.
        assertNull(DirWalk.mirrorPath(root, "a/../../outside/x"))
    }

    @Test
    fun `joinPath does not double the separator`() {
        assertEquals("/a/b", DirWalk.joinPath("/a", "b"))
        assertEquals("/a/b", DirWalk.joinPath("/a/", "b"))
        assertEquals("/b", DirWalk.joinPath("/", "b"))
        assertFalse(DirWalk.joinPath("/a/", "b").contains("//"))
    }
}
