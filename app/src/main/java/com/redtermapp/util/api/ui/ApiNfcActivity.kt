package com.redtermapp.util.api.ui

import android.app.Activity
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle

/**
 * The transparent host for a blocking NFC read or write. Reader mode is
 * the mechanism: while it is enabled, every tag that is discovered
 * brings this Activity to the front and its callback fires, so no
 * persistent receiver is needed. The request thread is woken with the
 * tag's answer the moment it is available, and the request's own
 * timeout bounds the wait when no tag ever comes near.
 */
class ApiNfcActivity : Activity() {

    private var adapter: NfcAdapter? = null
    private var kind: String = ""
    @Volatile private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kind = intent.getStringExtra("redterm.api.kind") ?: ""
        enableReaderMode()
    }

    private fun enableReaderMode() {
        val adapter = NfcAdapter.getDefaultAdapter(this)
        if (adapter == null) {
            postOnce("ERROR: this device has no NFC hardware\n")
            finish()
            return
        }
        this.adapter = adapter
        when (kind) {
            "nfc-read" -> adapter.enableReaderMode(this, readCallback, 0, null)
            "nfc-write" -> adapter.enableReaderMode(this, writeCallback, 0, null)
            else -> {
                postOnce("ERROR: unknown NFC kind\n")
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // onPause disables reader mode and Android does not restore it,
        // so a tag tapped right after the pause/resume transition would
        // be missed and the waiting request would run out its timeout.
        // Re-enable it for the stored request kind; an answered session
        // has no reason to read tags again.
        if (!answered) enableReaderMode()
    }

    override fun onPause() {
        super.onPause()
        val adapter = adapter
        if (adapter != null) {
            runCatching { adapter.disableReaderMode(this) }
        }
        this.adapter = null
    }

    override fun onDestroy() {
        super.onDestroy()
        // Back or a swipe destroys the activity without any tag: without
        // this, the request thread is left to time out instead of
        // receiving the dismissal it was dismissed with.
        if (isFinishing) postOnce("ERROR: the NFC dialog was dismissed before a tag\n")
    }

    private fun postOnce(result: String) {
        if (answered) return
        answered = true
        ApiUiBridge.postResult(this, result)
    }

    private val readCallback = object : NfcAdapter.ReaderCallback {
        override fun onTagDiscovered(tag: Tag) {
            val ndef = Ndef.get(tag)
            if (ndef == null) {
                postOnce("ERROR: the tag has no NDEF chip\n")
                finish()
                return
            }
            val message = runCatching {
                ndef.connect()
                try {
                    ndef.cachedNdefMessage ?: ndef.ndefMessage
                } finally {
                    ndef.close()
                }
            }.getOrNull()
            postOnce(dump(message))
            finish()
        }
    }

    private val writeCallback = object : NfcAdapter.ReaderCallback {
        override fun onTagDiscovered(tag: Tag) {
            val value = intent.getStringExtra("redterm.api.nfcValue") ?: ""
            // A well-known text record the reader can hand back to the
            // guest as a string.
            val record = NdefRecord(
                NdefRecord.TNF_WELL_KNOWN,
                NdefRecord.RTD_TEXT,
                ByteArray(0),
                value.toByteArray(Charsets.UTF_8)
            )
            val ndef = Ndef.get(tag)
            if (ndef == null) {
                postOnce("ERROR: the tag has no NDEF chip\n")
                finish()
                return
            }
            try {
                ndef.connect()
                try {
                    if (!ndef.isWritable) {
                        postOnce("ERROR: the tag is read-only\n")
                        return
                    }
                    ndef.writeNdefMessage(NdefMessage(record))
                } finally {
                    ndef.close()
                }
                postOnce("\"$value\"")
            } catch (e: Exception) {
                postOnce("ERROR: write failed: ${e.message}\n")
            }
            finish()
        }
    }

    private fun dump(message: NdefMessage?): String {
        if (message == null) return "ERROR: the tag has no readable NDEF content\n"
        return message.records.joinToString("\n") { r ->
            String(r.payload, Charsets.UTF_8).let { s ->
                s.filter { c -> c.isLetterOrDigit() || c in " \n\r\t:/._-" }.ifEmpty {
                    r.payload.joinToString(",") { b -> "%02x".format(b) }
                }
            }
        } + "\n"
    }
}
