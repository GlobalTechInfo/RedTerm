package com.redtermapp.ui

import java.util.concurrent.locks.ReentrantLock

/**
 * The bookkeeping behind the transfer queue. Pure apart from a lock, so the
 * ordering, the concurrency cap and the resume/done bookkeeping are all
 * unit-tested; the activity runs the pool, this only decides.
 *
 * A task moves PENDING -> RUNNING -> DONE|FAILED|SKIPPED. [nextPending] is
 * the only way work is handed out and it never exceeds [concurrency], so the
 * caller can hand each returned batch to its own pool and be sure of the cap.
 */
class TransferQueue(
    val tasks: List<Task>,
    val concurrency: Int
) {
    enum class State { PENDING, RUNNING, DONE, FAILED, SKIPPED }

    data class Task(
        val id: String,
        val kind: TransferPlan.Kind,
        val label: String,
        val total: Long
    )

    private val lock = ReentrantLock()
    private val states = HashMap<String, State>()
    private var cursor = 0

    init {
        lock.lock()
        try { tasks.forEach { states[it.id] = State.PENDING } } finally { lock.unlock() }
    }

    /**
     * Up to [n] not-yet-started tasks, in stored order, marked RUNNING. Never
     * returns more than the queue's [concurrency] allows: the number of RUNNING
     * tasks it would leave is capped, so the caller's pool can be this size and
     * still not exceed it.
     */
    fun nextPending(n: Int): List<Task> = lock.withLock {
        val slots = (concurrency - states.values.count { it == State.RUNNING }).coerceAtLeast(0)
        val out = mutableListOf<Task>()
        while (cursor < tasks.size && out.size < n && out.size < slots) {
            val t = tasks[cursor++]
            if (states[t.id] == State.PENDING) {
                states[t.id] = State.RUNNING
                out.add(t)
            }
        }
        out
    }

    fun markDone(id: String) = set(id, State.DONE)
    fun markFailed(id: String) = set(id, State.FAILED)
    fun markSkipped(id: String) = set(id, State.SKIPPED)

    private fun set(id: String, s: State) {
        lock.lock()
        try {
            val cur = states[id]
            if (cur == State.RUNNING || cur == State.PENDING) states[id] = s
        } finally {
            lock.unlock()
        }
    }

    fun state(id: String): State = lock.withLock { states[id] ?: State.PENDING }

    /** True when no task is PENDING or RUNNING, i.e. the pool can drain. */
    fun allFinished(): Boolean = lock.withLock {
        states.values.none { it == State.PENDING || it == State.RUNNING }
    }

    val isComplete: Boolean get() = allFinished()

    fun doneCount(): Int = lock.withLock { states.values.count { it == State.DONE } }
    fun failedCount(): Int = lock.withLock { states.values.count { it == State.FAILED } }
    fun skippedCount(): Int = lock.withLock { states.values.count { it == State.SKIPPED } }
}

private inline fun <T> ReentrantLock.withLock(action: () -> T): T {
    lock()
    try {
        return action()
    } finally {
        unlock()
    }
}
