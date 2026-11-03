package com.redtermapp.util.api

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import com.redtermapp.util.api.ApiRequestParser.ApiRequest

/**
 * `termux-brightness`. Writing the screen brightness is a system setting,
 * so the value the call returns is not proof it was applied: the
 * "modify system settings" grant is what makes the write stick, and a
 * write without it is reported, not papered over.
 */
object BrightnessApi : ApiFeature {

    override val name = "brightness"
    override val permissions = emptySet<String>()

    override fun run(context: Context, request: ApiRequest): String {
        val resolver = context.contentResolver
        val level = request.extras.ints["level"]
        if (level == null || level < 0) {
            val current = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            return "{\"brightness\":$current,\"min\":0,\"max\":255}"
        }
        if (!Settings.System.canWrite(context)) {
            return "ERROR: RedTerm needs the \"modify system settings\" grant to set brightness: system Settings, Apps, RedTerm, Special access, Modify system settings\n"
        }
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, level.coerceIn(0, 255))
        return "{\"brightness\":${level.coerceIn(0, 255)},\"min\":0,\"max\":255}"
    }
}

/** `termux-audio-info`: where each stream sits right now, and the ringer mode. */
object AudioInfoApi : ApiFeature {

    override val name = "audio-info"
    override val permissions = emptySet<String>()

    override fun run(context: Context, request: ApiRequest): String =
        build(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)

    fun build(audio: AudioManager): String {
        val streams = listOf(
            "music" to AudioManager.STREAM_MUSIC,
            "alarm" to AudioManager.STREAM_ALARM,
            "ring" to AudioManager.STREAM_RING,
            "notification" to AudioManager.STREAM_NOTIFICATION,
            "voice" to AudioManager.STREAM_VOICE_CALL,
            "dtmf" to AudioManager.STREAM_DTMF
        )
        val sb = StringBuilder("{\"streams\":{")
        streams.forEachIndexed { i, (label, id) ->
            if (i > 0) sb.append(',')
            sb.append('"').append(label).append("\":[")
                .append(audio.getStreamVolume(id))
                .append(',').append(audio.getStreamMaxVolume(id))
                .append(']')
        }
        sb.append('}')
        sb.append(",\"ringerMode\":").append(json(ringerName(audio.ringerMode)))
        sb.append('}')
        return sb.toString()
    }

    private fun ringerName(mode: Int): String = when (mode) {
        AudioManager.RINGER_MODE_NORMAL -> "normal"
        AudioManager.RINGER_MODE_SILENT -> "silent"
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        else -> mode.toString()
    }

    private fun json(value: String): String =
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
