package com.redtermapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JobHistoryStoreTest {

    private fun run(
        id: String = "j",
        at: Long = 1_000L,
        exit: Int = 0,
        tail: String = "line one\nline two"
    ) = JobHistoryStore.JobRun(id, "backup", "arch", "rsync /", at, exit, tail)

    @Test
    fun `round trip keeps the order, newest first`() {
        val runs = listOf(run(at = 3_000), run(at = 2_000), run(at = 1_000))
        val restored = JobHistoryStore.decode(JobHistoryStore.encode(runs))
        assertEquals(runs, restored)
        assertEquals(3_000L, restored.first().atMs)
    }

    @Test
    fun `a missing optional field falls back to its default`() {
        val json = """[{"jobId":"j","atMs":1000,"exitCode":2}]"""
        val runs = JobHistoryStore.decode(json)
        assertEquals(1, runs.size)
        val r = runs[0]
        assertEquals("j", r.jobId)
        assertEquals("", r.jobName)
        assertEquals(2, r.exitCode)
        assertEquals("", r.outputTail)
    }

    @Test
    fun `a blank record is no history, not an error`() {
        val blank: List<JobHistoryStore.JobRun> = JobHistoryStore.decode(null)
        assertTrue(blank.isEmpty())
        val empty: List<JobHistoryStore.JobRun> = JobHistoryStore.decode("")
        assertTrue(empty.isEmpty())
    }

    @Test
    fun `corrupt history is dropped, not fatal`() {
        assertTrue(JobHistoryStore.decode("not json at all").isEmpty())
        // one entry's bad jobId does not take the others with it
        val json = """[{"jobId":"ok"},{"jobId":""}]"""
        val runs = JobHistoryStore.decode(json)
        assertEquals(listOf("ok"), runs.map { it.jobId })
    }

    @Test
    fun `the tail is the end of the output, cut at a line`() {
        val short = "a\nb\n"
        assertEquals("a\nb", JobHistoryStore.outputTailOf(short))

        val out = (1..40).joinToString("\n") { "line $it of 40" }
        val tail = JobHistoryStore.outputTailOf(out)
        // it starts at a line, keeps the ending, and fits a notification
        val body = tail.removePrefix("\n")
        assertTrue(body.endsWith("line 40 of 40"))
        assertTrue(body.length <= JobHistoryStore.TAIL_CHARS)
        // cut starts at the line containing character 250, i.e. line 20
        assertTrue(body.startsWith("line 20 of 40"))
    }

    @Test
    fun `output with no line breaks still fits the budget`() {
        val out = "x".repeat(500)
        val tail = JobHistoryStore.outputTailOf(out)
        assertEquals(1 + JobHistoryStore.TAIL_CHARS, tail.length)
        assertTrue(tail.endsWith("x".repeat(JobHistoryStore.TAIL_CHARS)))
    }

    @Test
    fun `the tail of empty output is empty, so a success stays clean`() {
        assertEquals("", JobHistoryStore.outputTailOf(""))
        assertEquals("", JobHistoryStore.outputTailOf("   \n\n  "))
        assertFalse(JobHistoryStore.outputTailOf("").isBlank().not())
    }
}
