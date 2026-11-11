package com.redtermapp.util.sftp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirSearchTest {

    private class F(val name: String, val dir: Boolean = false, val link: Boolean = false)

    /** Build [DirSearch.Entry]s for a dir from a scripted table. */
    private fun tableOf(
        table: Map<String, List<F>>
    ): (String) -> List<DirSearch.Entry<F>>? {
        return { dir ->
            table[dir]?.map { f ->
                DirSearch.Entry(f.name, f.dir, f.link, dir + "/" + f.name, f)
            }
        }
    }

    @Test
    fun `shallow matches are found before deep ones, breadth-first`() {
        val table = mapOf(
            "/r" to listOf(F("top.txt"), F("d", dir = true)),
            "/r/d" to listOf(F("deep.txt"), F("d2", dir = true)),
            "/r/d/d2" to listOf(F("deepest.txt"))
        )
        val result = DirSearch.find<F>(
            "/r", tableOf(table), "txt", limit = 10, maxDirs = 100
        )
        assertEquals(
            listOf("/r/top.txt", "/r/d/deep.txt", "/r/d/d2/deepest.txt"),
            result.matches.map { it.remotePath }
        )
        assertFalse(result.truncated)
    }

    @Test
    fun `matching is case-insensitive on the name only`() {
        val table = mapOf(
            "/r" to listOf(F("Readme.md"), F("notes.md"))
        )
        val result = DirSearch.find<F>("/r", tableOf(table), "readme", limit = 10, maxDirs = 100)
        assertEquals(listOf("/r/Readme.md"), result.matches.map { it.remotePath })
    }

    @Test
    fun `directories named like the query are not matches`() {
        val table = mapOf(
            "/r" to listOf(F("log", dir = true), F("log.txt"))
        )
        val result = DirSearch.find<F>("/r", tableOf(table), "log", limit = 10, maxDirs = 100)
        assertEquals(listOf("/r/log.txt"), result.matches.map { it.remotePath })
    }

    @Test
    fun `the limit stops the walk and reports truncation`() {
        val table = mapOf(
            "/r" to listOf(F("a.txt"), F("b.txt"), F("c.txt"), F("more", dir = true)),
            "/r/more" to listOf(F("d.txt"))
        )
        val result = DirSearch.find<F>("/r", tableOf(table), ".txt", limit = 2, maxDirs = 100)
        assertEquals(2, result.matches.size)
        assertTrue(result.truncated)
    }

    @Test
    fun `a directory symlink is listed but not descended`() {
        val table = mapOf(
            "/r" to listOf(F("link", dir = true, link = true), F("x.txt")),
            "/r/link" to listOf(F("should-not-appear.txt"))
        )
        val result = DirSearch.find<F>("/r", tableOf(table), "txt", limit = 10, maxDirs = 100)
        assertEquals(listOf("/r/x.txt"), result.matches.map { it.remotePath })
        assertFalse(result.truncated)
    }

    @Test
    fun `a cycle of real directories is bounded by the dir cap`() {
        // d1 -> d2 -> d1 would walk forever; the cap must stop it and say so.
        val table = mapOf(
            "/r" to listOf(F("d1", dir = true)),
            "/r/d1" to listOf(F("d2", dir = true)),
            "/r/d1/d2" to listOf(F("back", dir = true))
        )
        // "back" is not a link, so a cycle cannot be made from this table alone;
        // what the cap protects against is a tree that keeps going: emulate it
        // with a childrenOf that invents a fresh subdir until told to stop.
        val result = DirSearch.find<F>(
            "/r",
            { dir ->
                listOf(DirSearch.Entry("next", true, false, "$dir/next", F("next")))
            },
            "zzz", limit = 10, maxDirs = 5
        )
        assertEquals(5, result.dirsListed)
        assertTrue(result.truncated)
    }

    @Test
    fun `an unreadable directory truncates but does not stop the walk`() {
        val children = { dir: String ->
            when (dir) {
                "/r" -> listOf(
                    DirSearch.Entry("ok", false, false, "/r/ok", F("ok")),
                    DirSearch.Entry("bad", true, false, "/r/bad", F("bad"))
                )
                else -> null // /r/bad cannot be listed
            }
        }
        val result = DirSearch.find<F>("/r", children, "o", limit = 10, maxDirs = 100)
        assertEquals(listOf("/r/ok"), result.matches.map { it.remotePath })
        assertEquals(1, result.unreadableDirs)
        assertTrue(result.truncated)
    }
}
