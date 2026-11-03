package com.redtermapp.util.api.ui

import android.app.Activity
import android.os.Bundle

/**
 * The transparent host for a blocking still capture. This build trims the
 * manifest to a defensible core (no CAMERA), and Lint's MissingPermission
 * cannot be satisfied for a permission the build does not declare — so a
 * capture is not available here. The activity reports that to the waiting
 * request thread rather than open a camera that can only throw.
 */
class ApiCameraActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ApiUiBridge.postResult(
            this,
            "ERROR: a still capture needs CAMERA, which this build does not declare\n"
        )
        finish()
    }
}
