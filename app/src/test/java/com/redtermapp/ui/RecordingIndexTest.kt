package com.redtermapp.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingIndexTest {

    @Test
    fun `empty query matches everything`() {
        assertTrue(RecordingIndex.matches("redterm-arch.ansi", ""))
        assertTrue(RecordingIndex.matches("redterm-arch.ansi", "   "))
    }

    @Test
    fun `query is a case-insensitive substring of the name`() {
        assertTrue(RecordingIndex.matches("redterm-arch.ansi", "ARCH"))
        assertTrue(RecordingIndex.matches("redterm-arch.ansi", "arch"))
        assertTrue(RecordingIndex.matches("redterm-arch.ansi", "-Arch."))
        assertFalse(RecordingIndex.matches("redterm-arch.ansi", "ubuntu"))
    }

    @Test
    fun `newest first is by modification time`() {
        val a = File("/tmp/a.ansi").apply { createNewFile(); setLastModified(1_000L) }
        val b = File("/tmp/b.ansi").apply { createNewFile(); setLastModified(9_000L) }
        val ordered = RecordingIndex.newestFirst(listOf(a, b))
        assertTrue(ordered.first() == b)
        assertTrue(ordered.last() == a)
    }

    @Test
    fun `empty list stays empty`() {
        assertTrue(RecordingIndex.newestFirst(emptyList()).isEmpty())
    }
}
