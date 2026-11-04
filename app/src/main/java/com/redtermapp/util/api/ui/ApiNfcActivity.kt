package com.redtermapp.util.api.ui

import android.app.Activity
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import java.util.concurrent.atomic.AtomicBoolean

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
    // The check-and-set must be one atomic step: a tag callback on a binder
    // thread and onDestroy on the main thread could otherwise both pass a
    // plain check-then-set and each post a result.
    private val answered = AtomicBoolean()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kind = intent.getStringExtra("redterm.api.nfc.kind") ?: ""
        adapter = NfcAdapter.getDefaultAdapter(this)
        if (adapter == null) {
            postOnce("ERROR: this device has no NFC hardware\n")
            finish()
            return
        }
        if (kind != "read" && kind != "write") {
            postOnce("ERROR: unknown NFC kind\n")
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (answered.get()) {
            finish()
            return
        }
        enableReaderMode()
    }

    override fun onPause() {
        super.onPause()
        runCatching { adapter?.disableReaderMode(this) }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Back or a swipe destroys the activity without any tag: without
        // this, the request thread is left to time out instead of
        // receiving the dismissal it was dismissed with.
        if (isFinishing) postOnce("ERROR: the NFC dialog was dismissed before a tag\n")
    }

    private fun postOnce(result: String) {
        if (!answered.compareAndSet(false, true)) return
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
            if (message == null) {
                postOnce("ERROR: the tag has no NDEF chip\n")
                finish()
                return
            }
            postOnce(dump(message))
            finish()
        }
    }

    private val writeCallback = object : NfcAdapter.ReaderCallback {
        override fun onTagDiscovered(tag: Tag) {
            val ndef = Ndef.get(tag)
            if (ndef == null) {
                postOnce("ERROR: the tag has no NDEF chip\n")
                finish()
                return
            }
            val text = intent.getStringExtra("redterm.api.nfc.text") ?: ""
            val record = NdefRecord.createTextRecord("en", text)
            val message = NdefMessage(arrayOf(record))
            val wrote = runCatching {
                ndef.connect()
                try {
                    if (!ndef.isWritable) throw IllegalStateException("read-only")
                    ndef.writeNdefMessage(message)
                    true
                } finally {
                    ndef.close()
                }
            }.onFailure { e ->
                postOnce("ERROR: write failed: ${e.message}\n")
                finish()
            }.getOrDefault(false)
            if (wrote) {
                postOnce("\"$text\"")
                finish()
            }
        }
    }

    private fun enableReaderMode() {
        if (answered.get()) return
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V
        adapter?.enableReaderMode(this, if (kind == "write") writeCallback else readCallback, flags, null)
    }

    private fun dump(message: NdefMessage): String {
        val sb = StringBuilder("[")
        var first = true
        for (r in message.records) {
            if (!first) sb.append(",")
            first = false
            sb.append('{')
            sb.append("\"type\":\"").append(String(r.type)).append('"')
            sb.append(",\"payload\":\"").append(String(r.payload)).append('"')
            sb.append('}')
        }
        sb.append(']')
        return sb.toString() + "\n"
    }
}
