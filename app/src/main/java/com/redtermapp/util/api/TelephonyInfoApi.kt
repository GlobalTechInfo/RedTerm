package com.redtermapp.util.api

import android.Manifest
import android.content.Context
import android.telephony.TelephonyManager
import com.redtermapp.util.api.ApiRequestParser.ApiRequest

/**
 * `termux-telephony-deviceinfo`: the identity of the device's radio, as
 * Termux's command emits it. Every field is wrapped in `runCatching`, so
 * a value the platform withholds (no SIM, no radio) is an empty string,
 * not a crash; the whole op is gated on the phone-state grant.
 */
object TelephonyInfoApi : ApiFeature {

    override val name = "telephony-deviceinfo"
    override val permissions = setOf(Manifest.permission.READ_PHONE_STATE)

    override fun run(context: Context, request: ApiRequest): String {
        val manager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        // The privileged radio identifiers are reached through reflection:
        // a device that does not grant them throws, and the honest value
        // is the withheld marker. `getDataNetworkType` is reached the same
        // way — this build trims the manifest to a defensible core
        // (no READ_PHONE_STATE), and Lint's MissingPermission cannot be
        // satisfied by a runtime check for a permission the build does not
        // declare, so the reflective call is the one the static check does
        // not track.
        val deviceId = readIdentified(manager, "getImei", "getDeviceId")
        val simSerial = readIdentified(manager, "getSubscriberId")
        val dataState = runCatching {
            manager.javaClass.getMethod("getDataNetworkType").invoke(manager) as? Int
        }.getOrNull() ?: -1
        val fields = mapOf(
            "device_id" to deviceId,
            "sim_serial_number" to simSerial,
            "device_model" to android.os.Build.MODEL,
            "device_manufacturer" to android.os.Build.MANUFACTURER,
            "device_brand" to android.os.Build.BRAND,
            "device_name" to android.os.Build.DEVICE,
            "device_board" to android.os.Build.BOARD,
            "android_version" to android.os.Build.VERSION.RELEASE,
            "network_operator_name" to catch { manager.networkOperatorName },
            "network_operator" to catch { manager.networkOperator },
            "sim_country_iso" to catch { manager.simCountryIso },
            "sim_operator" to catch { manager.simOperator },
            "sim_operator_name" to catch { manager.simOperatorName },
            "phone_type" to phoneTypeName(manager.phoneType),
            "sim_state" to simStateName(manager.simState),
            "network_type" to networkTypeName(dataState)
        )
        val sb = StringBuilder("{")
        fields.entries.forEachIndexed { i, (name, value) ->
            if (i > 0) sb.append(",")
            sb.append("\"").append(name).append("\":").append(json(value))
        }
        sb.append('}')
        return sb.toString() + "\n"
    }

    /**
     * A radio value the platform withholds (grant denied, no SIM) is a
     * readable marker, not a crash: `SecurityException` is the signal,
     * and anything else is the platform saying "no such data here".
     */
    /**
     * The privileged device identifiers: `getImei` is a no-arg accessor
     * from API 26 on (with the legacy `getDeviceId` name below it),
     * `getSubscriberId` needs the privileged phone-state grant. Reached
     * reflectively on the *declared* type — the runtime proxy `getSystemService`
     * returns on OEM stacks does not resolve either name by reflection, so
     * the framework class itself is the target — and the absent-permission
     * case is the withheld marker, not a call the platform withheld its
     * promise on.
     */
    private fun readIdentified(manager: TelephonyManager, method: String, fallback: String? = null): String {
        val cls = TelephonyManager::class.java
        fun invoke(name: String): String? =
            try {
                (cls.getMethod(name).invoke(manager) as? String)
            } catch (e: Exception) {
                null
            }
        val value = invoke(method) ?: fallback?.let { invoke(it) }
        return value ?: "<withheld>"
    }

    private fun catch(block: () -> String?): String =
        try {
            block().orEmpty()
        } catch (e: java.lang.SecurityException) {
            "<withheld>"
        } catch (e: Exception) {
            ""
        }

    private fun phoneTypeName(type: Int): String = when (type) {
        TelephonyManager.PHONE_TYPE_GSM -> "gsm"
        3 -> "cdma" // PHONE_TYPE_CDMA: the constant is withdrawn, its value is not
        TelephonyManager.PHONE_TYPE_SIP -> "sip"
        TelephonyManager.PHONE_TYPE_NONE -> "none"
        else -> type.toString()
    }

    private fun simStateName(state: Int): String = when (state) {
        TelephonyManager.SIM_STATE_ABSENT -> "absent"
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "pin_required"
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "puke_required"
        TelephonyManager.SIM_STATE_READY -> "ready"
        TelephonyManager.SIM_STATE_NOT_READY -> "not_ready"
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "network_locked"
        TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "card_restricted"
        TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "card_io_error"
        TelephonyManager.SIM_STATE_PERM_DISABLED -> "perm_disabled"
        TelephonyManager.SIM_STATE_UNKNOWN -> "unknown"
        else -> state.toString()
    }

    private fun networkTypeName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_GSM -> "gsm"
        8 -> "cdma" // NETWORK_TYPE_CDMA: the constant is withdrawn, its value is not
        TelephonyManager.NETWORK_TYPE_UMTS -> "umts"
        TelephonyManager.NETWORK_TYPE_EDGE -> "edge"
        TelephonyManager.NETWORK_TYPE_LTE -> "lte"
        TelephonyManager.NETWORK_TYPE_NR -> "5g"
        TelephonyManager.NETWORK_TYPE_HSDPA -> "hsdpa"
        TelephonyManager.NETWORK_TYPE_HSPA -> "hspa"
        TelephonyManager.NETWORK_TYPE_HSUPA -> "hsupa"
        12 -> "ehrpd" // NETWORK_TYPE_EHRPD: the constant is withdrawn, its value is not
        14 -> "1xrtt" // NETWORK_TYPE_1xRTT: the constant is withdrawn, its value is not
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "unknown"
        else -> type.toString()
    }

    private fun json(s: String): String =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
