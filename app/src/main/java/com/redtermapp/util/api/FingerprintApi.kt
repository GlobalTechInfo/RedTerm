package com.redtermapp.util.api

import android.content.Context
import android.hardware.biometrics.BiometricPrompt
import com.redtermapp.util.api.ApiRequestParser.ApiRequest
import com.redtermapp.util.api.ui.ApiBiometricActivity
import com.redtermapp.util.api.ui.ApiUiBridge

/**
 * `termux-fingerprint`: check enrollment / trigger a scan. `check` is
 * instant and headless; `scan` shows the system biometric prompt and
 * blocks until it succeeds, is cancelled, or times out, returning the
 * verdict.
 */
object FingerprintApi : ApiFeature {

    override val name = "fingerprint"
    override val permissions = emptySet<String>()

    override fun run(context: Context, request: ApiRequest): String {
        val op = request.str("op") ?: "check"
        return when (op) {
            "check" -> check(context)
            "scan" -> {
                if (android.os.Build.VERSION.SDK_INT < 28)
                    return "ERROR: the biometric prompt needs Android 9.0 or newer\n"
                val desc = request.str("description") ?: ""
                ApiUiBridge.show(
                    context,
                    ApiBiometricActivity::class.java,
                    ApiUiBridge.ApiUiSpec(
                        "biometric",
                        request.int("timeout") ?: 30,
                        mapOf("redterm.api.biometricDescription" to desc)
                    )
                )
            }
            else -> "ERROR: unknown fingerprint op '$op'\n"
        }
    }

    private fun check(context: Context): String {
        // BiometricManager.from is not on this build's stub; the class
        // ships on the platform, so reach it reflectively and degrade to
        // a readable "could not check" when it is absent.
        val status = runCatching {
            val clazz = Class.forName("android.hardware.biometrics.BiometricManager")
            val from = clazz.getMethod("from", Context::class.java).invoke(null, context)
            val canAuth = clazz.getMethod("canAuthenticate").invoke(from) as Int
            canAuth
        }.getOrNull()
        if (status == null) return "could not determine biometric state on this platform\n"
        return when (status) {
            0 -> "available\n" // BIOMETRIC_SUCCESS
            1 -> "not available (hardware unavailable)\n" // BIOMETRIC_ERROR_HW_UNAVAILABLE
            2 -> "not available (no biometrics enrolled)\n" // BIOMETRIC_ERROR_NONE_ENROLLED
            3 -> "not available (no hardware)\n" // BIOMETRIC_ERROR_NO_HARDWARE
            4 -> "not available (security update required)\n" // BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED
            8 -> "not available (keyguard not secure)\n" // BIOMETRIC_ERROR_KEYGUARD_NOT_SECURE
            else -> "not available (code $status)\n"
        }
    }
}
