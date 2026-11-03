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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val adapter = NfcAdapter.getDefaultAdapter(this)
        if (adapter == null) {
            ApiUiBridge.postResult(this, "ERROR: this device has no NFC hardware\n")
            finish()
            return
        }
        this.adapter = adapter
        when (intent.getStringExtra("redterm.api.kind")) {
            "nfc-read" -> adapter.enableReaderMode(this, readCallback, 0, null)
            "nfc-write" -> adapter.enableReaderMode(this, writeCallback, 0, null)
            else -> {
                ApiUiBridge.postResult(this, "ERROR: unknown NFC kind\n")
                finish()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        val adapter = adapter
        if (adapter != null) {
            runCatching { adapter.disableReaderMode(this) }
        }
        this.adapter = null
    }

    private val readCallback = object : NfcAdapter.ReaderCallback {
        override fun onTagDiscovered(tag: Tag) {
            val ndef = Ndef.get(tag)
            if (ndef == null) {
                ApiUiBridge.postResult(this@ApiNfcActivity, "ERROR: the tag has no NDEF chip\n")
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
            ApiUiBridge.postResult(this@ApiNfcActivity, dump(message))
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
                ApiUiBridge.postResult(this@ApiNfcActivity, "ERROR: the tag has no NDEF chip\n")
                finish()
                return
            }
            try {
                ndef.connect()
                try {
                    if (!ndef.isWritable) {
                        ApiUiBridge.postResult(this@ApiNfcActivity, "ERROR: the tag is read-only\n")
                        return
                    }
                    ndef.writeNdefMessage(NdefMessage(record))
                } finally {
                    ndef.close()
                }
                ApiUiBridge.postResult(this@ApiNfcActivity, "\"$value\"")
            } catch (e: Exception) {
                ApiUiBridge.postResult(this@ApiNfcActivity, "ERROR: write failed: ${e.message}\n")
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
