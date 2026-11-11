package com.redtermapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledJobTest {

    private fun job(
        interval: Int = 0,
        fireAt: Long = 0L,
        lastRun: Long = 0L,
        enabled: Boolean = true
    ) = ScheduledJob("j", "j", "arch", "echo hi", interval, fireAt, enabled, lastRun)

    @Test
    fun `repeating job is due after the interval elapsed`() {
        val j = job(interval = 5, lastRun = 1_000L)
        assertFalse(JobSchedule.dueNow(j, 1_000L + 4 * 60_000 + 1))
        assertTrue(JobSchedule.dueNow(j, 1_000L + 5 * 60_000))
    }

    @Test
    fun `one-shot fires only once between now and its fire time`() {
        val j = job(fireAt = 5_000L, lastRun = 0L)
        assertFalse(JobSchedule.dueNow(j, 4_999L))
        assertTrue(JobSchedule.dueNow(j, 5_000L))
        // after it has run, it is satisfied and no longer due
        val ran = j.copy(lastRunMs = 5_000L)
        assertFalse(JobSchedule.dueNow(ran, 6_000L))
        assertTrue(JobSchedule.isSatisfied(ran))
    }

    @Test
    fun `disabled jobs never fire`() {
        assertFalse(JobSchedule.dueNow(job(interval = 1, enabled = false), 9_999L))
        assertEquals(0L, JobSchedule.nextRun(job(interval = 1, enabled = false), 9_999L))
    }

    @Test
    fun `nextRun of a repeating job is lastRun plus interval`() {
        val j = job(interval = 10, lastRun = 100L)
        assertEquals(100L + 10 * 60_000L, JobSchedule.nextRun(j, 0L))
        // and it is clamped to not be earlier than now
        assertEquals(100L + 10 * 60_000L, JobSchedule.nextRun(j, 50L))
    }

    @Test
    fun `json round trip preserves the job`() {
        val j = job(interval = 5, fireAt = 42L, lastRun = 7L, enabled = false)
        val restored = ScheduledJob.fromJson(ScheduledJob.toJson(j))!!
        assertEquals(j, restored)
        val all = ScheduledJob.decodeAll(ScheduledJob.encodeAll(listOf(j)))
        assertEquals(listOf(j), all)
    }

    @Test
    fun `retry delay doubles then caps`() {
        assertEquals(0, JobSchedule.retryDelayMinutes(0))
        assertEquals(1, JobSchedule.retryDelayMinutes(1))
        assertEquals(2, JobSchedule.retryDelayMinutes(2))
        assertEquals(4, JobSchedule.retryDelayMinutes(3))
        assertEquals(8, JobSchedule.retryDelayMinutes(4))
        assertEquals(15, JobSchedule.retryDelayMinutes(5))
        assertEquals(15, JobSchedule.retryDelayMinutes(6))
        // past the cap it stays capped: a dead job never becomes a storm
        assertEquals(15, JobSchedule.retryDelayMinutes(40))
    }

    @Test
    fun `retries stop after the sixth consecutive failure`() {
        assertFalse(JobSchedule.shouldRetry(0))
        for (f in 1..JobSchedule.MAX_CONSECUTIVE_FAILURES) {
            assertTrue("failure $f must retry", JobSchedule.shouldRetry(f))
        }
        assertFalse(JobSchedule.shouldRetry(JobSchedule.MAX_CONSECUTIVE_FAILURES + 1))
    }

    @Test
    fun `json round trip preserves the failure streak`() {
        val j = ScheduledJob("j", "j", "arch", "echo hi", 5, 0L, true, 0L, 3)
        assertEquals(3, ScheduledJob.fromJson(ScheduledJob.toJson(j))!!.consecutiveFails)
    }

    @Test
    fun `a record written before the streak existed loads as zero failures`() {
        val legacy = """{"id":"j","name":"j","distro":"arch","command":"echo hi","interval":5,"fireAt":0,"enabled":true,"lastRun":0}"""
        assertEquals(0, ScheduledJob.fromJson(legacy)!!.consecutiveFails)
    }
}
