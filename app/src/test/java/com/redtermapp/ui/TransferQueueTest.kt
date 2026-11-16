package com.redtermapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferQueueTest {

    private val q: TransferQueue
    private val done = mutableListOf<String>()
    private val failed = mutableListOf<String>()
    private val skipped = mutableListOf<String>()

    private fun run() {
        while (!q.allFinished()) {
            for (t in q.nextPending(2)) {
                when (t.id) {
                    "fail" -> failed += t.id
                    "skip" -> skipped += t.id
                    else -> done += t.id
                }
                when (t.id) {
                    "fail" -> q.markFailed(t.id)
                    "skip" -> q.markSkipped(t.id)
                    else -> q.markDone(t.id)
                }
            }
        }
    }

    init {
        q = TransferQueue(
            listOf(
                TransferQueue.Task("a", TransferPlan.Kind.DOWNLOAD, "a", 10),
                TransferQueue.Task("fail", TransferPlan.Kind.UPLOAD, "f", 10),
                TransferQueue.Task("b", TransferPlan.Kind.DOWNLOAD, "b", 10),
                TransferQueue.Task("skip", TransferPlan.Kind.DOWNLOAD, "s", 10),
                TransferQueue.Task("c", TransferPlan.Kind.DOWNLOAD, "c", 10)
            ),
            concurrency = 2
        )
    }

    @Test
    fun `work is issued in order in capped batches`() {
        val first = q.nextPending(2)
        assertEquals(listOf("a", "fail"), first.map { it.id })
    }

    @Test
    fun `the queue drains to completion with correct tallies`() {
        run()
        assertTrue(q.allFinished())
        assertEquals(3, q.doneCount())
        assertEquals(1, q.failedCount())
        assertEquals(1, q.skippedCount())
        assertEquals(listOf("a", "b", "c"), done)
    }

    @Test
    fun `cap is never exceeded in a single batch`() {
        val big = TransferQueue(
            (1..10).map { TransferQueue.Task("t$it", TransferPlan.Kind.DOWNLOAD, "t$it", 1L) },
            concurrency = 3
        )
        assertTrue(big.nextPending(9).size == 3)
        // finishing the first three lets the next batch proceed
        (1..3).forEach { big.markDone("t$it") }
        assertEquals(listOf("t4", "t5", "t6"), big.nextPending(3).map { it.id })
    }

    @Test
    fun `empty queue is finished`() {
        assertTrue(TransferQueue(emptyList(), 2).allFinished())
    }
}

class TransferPlanTest {

    @Test
    fun `unknown total is always fresh`() {
        assertEquals(TransferPlan.Plan.FRESH, TransferPlan.plan(5, 0))
        assertEquals(TransferPlan.Plan.FRESH, TransferPlan.plan(5, -1))
    }

    @Test
    fun `no existing file is fresh`() {
        assertEquals(TransferPlan.Plan.FRESH, TransferPlan.plan(0, 100))
        assertEquals(0L, TransferPlan.startOffset(0, 100))
    }

    @Test
    fun `partial file resumes at the existing byte`() {
        assertEquals(TransferPlan.Plan.RESUME, TransferPlan.plan(40, 100))
        assertEquals(40L, TransferPlan.startOffset(40, 100))
    }

    @Test
    fun `complete file is done`() {
        assertEquals(TransferPlan.Plan.DONE, TransferPlan.plan(100, 100))
        assertEquals(TransferPlan.Plan.DONE, TransferPlan.plan(200, 100))
        assertEquals(0L, TransferPlan.startOffset(100, 100))
    }
}
