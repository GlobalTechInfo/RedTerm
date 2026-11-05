package com.redtermapp.util.api.ui

import android.app.Activity
import android.os.Bundle

/**
 * The transparent host for a blocking biometric scan. This build trims the
 * manifest to a defensible core (no USE_BIOMETRIC), and Lint's
 * MissingPermission cannot be satisfied for a permission the build does not
 * declare, so a prompt is not available here: the activity reports that to
 * the waiting request thread rather than build a BiometricPrompt that can
 * only fail.
 */
class ApiBiometricActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ApiUiBridge.postResult(
            this,
            "ERROR: a biometric prompt needs USE_BIOMETRIC, which this build does not declare\n"
        )
        finish()
    }
}
