package com.redtermapp.util.api

import android.content.Context
import android.content.pm.PackageManager
import com.redtermapp.util.api.ApiRequestParser.ApiRequest

/**
 * The dispatch table for the API bridge. One [ApiFeature] per feature, and
 * the "action" extra picks the feature the way Termux:API does, so a script
 * that works there works here: `-a com.redtermapp.api.TERMUX_ACTION --es
 * action <feature>`.
 *
 * The gate checks each feature's dangerous permissions before it runs, so a
 * missing grant is a readable answer rather than a dead pipe. Adding a
 * feature is one object and one line in [features].
 */
object ApiActions {

    /** The single action value the guest helper is built around. */
    const val ACTION = "com.redtermapp.api.TERMUX_ACTION"

    private val features: Map<String, ApiFeature> = listOf<ApiFeature>(
        DownloadApi,
        BatteryApi,
        VolumeApi,
        VibrateApi,
        NotificationApi,
        ToastApi,
        BrightnessApi,
        AudioInfoApi,
        ClipboardApi,
        NetworkApi,
        WifiApi,
        ShareApi,
        LocationApi,
        SmsApi,
        CallApi,
        ContactsApi,
        TorchApi,
        MediaScannerApi,
        CameraInfoApi,
        CallLogApi,
        WallpaperApi,
        UsbApi,
        SensorApi,
        TtsApi,
        MediaPlaybackApi,
        MicRecordApi,
        InfraredApi,
        TelephonyInfoApi,
        SsidApi,
        KeystoreApi,
        DialogApi,
        NfcApi,
        FingerprintApi,
        JobScheduleApi,
        CallbackApi,
        StorageGetApi,
        SafApi,
        ApiLifecycleApi,
        NotificationChannelApi,
        CellInfoApi,
        WifiEnableApi,
        SpeechToTextApi,
        CameraPhotoApi,
    ).associateBy { it.name }

    fun run(context: Context, request: ApiRequest): String {
        if (request.action != ACTION) {
            return "ERROR: unknown action '${request.action}'\n"
        }
        val name = request.extras.strings["action"]
            ?: return "ERROR: no feature given (missing --es action)\n"
        val feature = features[name]
            ?: return "ERROR: unknown RedTerm API feature '$name'\n"
        val missing = feature.permissions.filterNot {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            // A missing grant is only actionable if this build actually
            // declares the permission: the Settings card offers a row to
            // request it. A permission the build omits (this one trims the
            // manifest to a defensible core so Play Protect stops reading a
            // trojan profile) cannot be requested at all, and the honest
            // answer is that the command is not available in this build.
            val declared = try {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                info.requestedPermissions?.toSet().orEmpty()
            } catch (e: PackageManager.NameNotFoundException) {
                emptySet()
            }
            val undeclared = missing.filterNot { it in declared }
            return when {
                undeclared.isNotEmpty() ->
                    "ERROR: '$name' needs ${undeclared.joinToString(" ")} but this build does not declare it; the command is not available here\n"
                else ->
                    "ERROR: '$name' needs permission(s) ${missing.joinToString(", ")}; grant them in RedTerm under Settings, then retry\n"
            }
        }
        return feature.run(context, request)
    }
}
