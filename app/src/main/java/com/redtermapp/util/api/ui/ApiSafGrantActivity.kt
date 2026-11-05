package com.redtermapp.util.api.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * The transparent host that turns `redterm-saf-grant` into a working
 * Storage Access Framework tree. It starts the system tree picker, and
 * the moment the user picks a tree it persists the read and write
 * permissions on the spot, so every `redterm-saf-*` document operation
 * keeps working after a reboot. The URI that was granted is the answer
 * the waiting request thread reads; cancelling the picker says so
 * instead of hanging the 180s timeout.
 */
class ApiSafGrantActivity : Activity() {

    private var pickerStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            startPicker()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A second grant request aimed at this still-open instance must
        // not be answered by the first one's slot.
        ApiUiBridge.postResult(this, "ERROR: a grant is already in progress; wait for it\n")
        finish()
    }

    private fun startPicker() {
        pickerStarted = true
        // The tree picker is a bare ACTION_OPEN_DOCUMENT_TREE: the document
        // UI's tree filter declares the default category, not CATEGORY_OPENABLE
        // (that one belongs to file-open intents). Attaching OPENABLE made the
        // intent unresolvable, so the picker failed to start on any device.
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        runCatching { startActivityForResult(intent, 1) }.onFailure { e ->
            ApiUiBridge.postResult(this, "ERROR: the tree picker could not start: ${e.message}\n")
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1) return
        if (resultCode != RESULT_OK || data?.data == null) {
            ApiUiBridge.postResult(
                this,
                "ERROR: no tree was granted (the picker was cancelled)\n"
            )
            finish()
            return
        }
        val uri = data.data!!
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.onFailure { e ->
            ApiUiBridge.postResult(this, "ERROR: the grant was not persisted: ${e.message}\n")
            finish()
            return
        }
        ApiUiBridge.postResult(this, uri.toString() + "\n")
        finish()
    }

    // The picker can be killed by a configuration change or the system;
    // the persisted-permission result, not this Activity, is the source
    // of truth, and the slot's timeout bounds the wait either way.
    override fun onDestroy() {
        if (pickerStarted) {
            ApiUiBridge.postResult(this, "ERROR: the grant dialog was dismissed before a tree was chosen\n")
        }
        super.onDestroy()
    }
}
