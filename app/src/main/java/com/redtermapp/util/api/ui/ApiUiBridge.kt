package com.redtermapp.util.api.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The hand-off between a blocking API request and the transparent
 * Activity that shows its UI. A request thread registers a slot, the
 * Activity posts its result into it, and the request thread wakes with
 * the answer. A slot that times out is removed and answered as a
 * timeout, so a dismissed dialog can never hold a request thread
 * forever.
 */
object ApiUiBridge {

    private class Slot(val latch: CountDownLatch, val timeoutMs: Long) {
        @Volatile
        var result: String? = null
    }

    private val slots = ConcurrentHashMap<String, Slot>()

    /** The longest a dialog is held for an answer; longer waits are refused up front. */
    const val MAX_DIALOG_TIMEOUT_SEC = 300

    /**
     * Start [activity] carrying [spec] and block until it answers or
     * [spec] times out. The returned string is the Activity's JSON
     * result, or a readable ERROR line when the slot timed out.
     */
    fun show(context: Context, activity: Class<out Activity>, spec: ApiUiSpec): String {
        // Validate the timeout before it is converted: a non-positive
        // value makes the latch return at once and the opened dialog is
        // answered into the void, and a huge value pins this request
        // thread (and the slot) until the activity answers, which never
        // happens if it is killed. Both are refused, not converted.
        if (spec.timeoutSec <= 0) {
            return "ERROR: the dialog timeout must be at least 1 second\n"
        }
        val timeoutSec = spec.timeoutSec.coerceAtMost(MAX_DIALOG_TIMEOUT_SEC)
        val id = UUID.randomUUID().toString()
        val slot = Slot(CountDownLatch(1), timeoutSec * 1000L)
        slots[id] = slot
        val intent = Intent(context, activity).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ApiUiBridge.EXTRA_SLOT_ID, id)
            spec.toExtras().forEach { (k, v) -> putExtra(k, v) }
        }
        runCatching { context.startActivity(intent) }
            .onFailure { e ->
                slots.remove(id)
                slot.latch.countDown()
                return "ERROR: could not show the UI: ${e.message}\n"
            }
        val timedOut = !slot.latch.await(timeoutSec + 15L, TimeUnit.SECONDS)
        slots.remove(id)
        if (timedOut) return "ERROR: the dialog timed out after ${timeoutSec}s\n"
        return slot.result ?: "ERROR: the dialog ended without a result\n"
    }

    const val EXTRA_SLOT_ID = "redterm.api.slot"

    /** The Activity-side half: post the result and release the waiters. */
    fun postResult(activity: android.app.Activity, result: String) {
        val id = activity.intent?.getStringExtra(EXTRA_SLOT_ID) ?: return
        val slot = slots[id] ?: return
        slot.result = result
        slot.latch.countDown()
        slots.remove(id)
    }

    /** What the transparent Activity renders and what the request thread waits for. */
    data class ApiUiSpec(
        val kind: String,
        val timeoutSec: Int = 60,
        val extras: Map<String, String>
    )

    fun ApiUiSpec.toExtras(): Map<String, String> =
        extras + ("redterm.api.kind" to kind) + ("redterm.api.timeout" to timeoutSec.toString())
}
