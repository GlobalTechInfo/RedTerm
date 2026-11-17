package com.redtermapp.ui

/**
 * The queueing and resuming rules for the SFTP transfer queue, kept pure so
 * they are unit-tested. Only a task whose size is actually smaller than what
 * already exists can resume; a destination that is already complete is skipped
 * rather than re-downloaded, and any task whose total is unknown is always run
 * fresh, because without a size there is nothing to compare an existing file
 * against.
 */
object TransferPlan {

    enum class Kind { UPLOAD, DOWNLOAD }

    enum class Plan { FRESH, RESUME, DONE }

    /**
     * What to do with a transfer whose destination already holds [existing]
     * bytes out of [total] bytes total.
     */
    fun plan(existing: Long, total: Long): Plan = when {
        total <= 0L -> Plan.FRESH // unknown size: nothing to compare against
        existing <= 0L -> Plan.FRESH
        existing >= total -> Plan.DONE
        else -> Plan.RESUME
    }

    /** The byte offset a RESUME transfer should start reading/writing at. */
    fun startOffset(existing: Long, total: Long): Long =
        when (plan(existing, total)) {
            Plan.RESUME -> existing
            else -> 0L
        }
}
