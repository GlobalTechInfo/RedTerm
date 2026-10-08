package com.redtermapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The passphrase prompt answers long after it is shown, on the main thread, while
 * the connection waits on a worker.
 *
 * Reading the answer before it exists is invisible at the call site: the read
 * returns an empty value, the connection is retried with no passphrase, and the user
 * watches the same refusal they got the first time, having answered a dialog that
 * appears to have been ignored. So the waiting is tested here.
 */
class AnswerLatchTest {

    @Test
    fun `a value given before the wait is returned`() {
        val latch = SshKeyUnlock.AnswerLatch<String?>()
        latch.complete("phrase")
        assertEquals("phrase", latch.await(1))
    }

    /**
     * The case that actually broke: the dialog is shown, and the answer arrives
     * afterwards. A wait that returned at that moment would see nothing.
     */
    @Test
    fun `a value that arrives later is still returned`() {
        val latch = SshKeyUnlock.AnswerLatch<String?>()
        val shown = CountDownLatch(1)
        Thread {
            shown.countDown() // the dialog has been shown
            Thread.sleep(50)
            latch.complete("typed after showing")
        }.start()
        shown.await(2, TimeUnit.SECONDS)
        assertEquals("typed after showing", latch.await(5))
    }

    /** Waiting must actually wait, not return straight away with nothing. */
    @Test
    fun `the wait blocks until the value arrives`() {
        val latch = SshKeyUnlock.AnswerLatch<String?>()
        val started = System.nanoTime()
        Thread { Thread.sleep(300); latch.complete("late") }.start()
        assertEquals("late", latch.await(5))
        val waitedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue("returned after ${waitedMillis}ms without waiting", waitedMillis >= 250)
    }

    /** A cancelled dialog completes with null, which is not the same as a timeout. */
    @Test
    fun `a null answer is reported as no answer`() {
        val latch = SshKeyUnlock.AnswerLatch<String?>()
        latch.complete(null)
        assertNull(latch.await(1))
    }

    /**
     * Bounded on purpose: an activity that finishes mid-prompt leaves nothing to
     * dismiss the dialog, and an unbounded wait would hang the transfer thread for
     * the life of the process.
     */
    @Test
    fun `the wait gives up rather than hanging for ever`() {
        val latch = SshKeyUnlock.AnswerLatch<String?>()
        val started = System.nanoTime()
        assertNull(latch.await(1))
        val waitedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue("gave up after ${waitedMillis}ms", waitedMillis in 900..4_000)
    }

    @Test
    fun `an answer given twice does not block a second reader`() {
        val latch = SshKeyUnlock.AnswerLatch<String?>()
        latch.complete("first")
        latch.complete("second")
        assertEquals("second", latch.await(1))
    }
}