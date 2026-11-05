package com.redtermapp.util.api

import android.content.Context
import android.media.AudioManager
import android.media.AudioManager.ADJUST_LOWER
import android.media.AudioManager.ADJUST_RAISE
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.redtermapp.util.api.ApiRequestParser.ApiRequest

/**
 * `termux-volume`. Volume has no permission, but OEMs that route the
 * audio stream through a vendor service can still refuse; the answer the
 * stream reports is the truth, not the call that requested it.
 */
object VolumeApi : ApiFeature {

    override val name = "volume"
    override val permissions = emptySet<String>()

    /** Stream names the guest can spell; the int fallback is the raw id. */
    fun streamId(name: String): Int = when (name) {
        "music" -> AudioManager.STREAM_MUSIC
        "alarm" -> AudioManager.STREAM_ALARM
        "ring", "ringtone" -> AudioManager.STREAM_RING
        "notification" -> AudioManager.STREAM_NOTIFICATION
        "system" -> AudioManager.STREAM_SYSTEM
        "voice", "call" -> AudioManager.STREAM_VOICE_CALL
        "dtmf" -> AudioManager.STREAM_DTMF
        else -> name.toIntOrNull() ?: AudioManager.STREAM_MUSIC
    }

    override fun run(context: Context, request: ApiRequest): String {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val extras = request.extras
        val stream = streamId(extras.str("stream") ?: "music")
        val op = extras.str("op") ?: "print"
        val value = extras.int("value") ?: -1
        val max = audio.getStreamMaxVolume(stream)
        when (op) {
            "set" -> {
                val level = request.int("level")
                if (level != null) {
                    audio.setStreamVolume(stream, level.coerceIn(0, max), 0)
                } else if (value >= 0) {
                    audio.setStreamVolume(stream, (value.coerceIn(0, 100) * max / 100), 0)
                } else {
                    return "ERROR: volume set needs a value\n"
                }
            }
            "raise" -> audio.adjustStreamVolume(stream, ADJUST_RAISE, 0)
            "lower" -> audio.adjustStreamVolume(stream, ADJUST_LOWER, 0)
            "mute" -> audio.setStreamVolume(stream, 0, 0)
            "unmute" -> audio.setStreamVolume(stream, max, 0)
            else -> Unit
        }
        return build(stream, audio.getStreamVolume(stream), max)
    }

    fun build(stream: Int, volume: Int, maxVolume: Int): String =
        "{\"volume\":$volume,\"maxVolume\":$maxVolume,\"stream\":$stream}"
}

/**
 * `termux-vibrate`. VibrationEffect and VibratorManager both need API 26+;
 * below that the only vibrate overloads are withdrawn from the platform,
 * and this app does not call withdrawn APIs, so the honest answer is a
 * version gate, not a suppress.
 */
object VibrateApi : ApiFeature {

    override val name = "vibrate"
    override val permissions = emptySet<String>()

    override fun run(context: Context, request: ApiRequest): String {
        if (Build.VERSION.SDK_INT < 26) {
            return "ERROR: vibrate needs Android 8.0 or newer\n"
        }
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            // The VIBRATOR_SERVICE constant is deprecated on 31+, and the
            // string form of getSystemService is not; "vibrator" is the
            // service name it names.
            context.getSystemService("vibrator") as Vibrator
        }
        val patternCsv = request.extras.str("pattern")
        val effect = if (!patternCsv.isNullOrEmpty()) {
            val times = ArrayList<Long>(patternCsv.split(',').size)
            for (piece in patternCsv.split(',')) {
                val ms = piece.trim().toLongOrNull()
                    ?: return "ERROR: vibrate pattern item '$piece' is not a millisecond count\n"
                times.add(ms)
            }
            VibrationEffect.createWaveform(times.toLongArray(), -1)
        } else {
            val duration = request.extras.ints["duration"] ?: 500
            VibrationEffect.createOneShot(duration.toLong(), VibrationEffect.DEFAULT_AMPLITUDE)
        }
        vibrator.vibrate(effect)
        return ""
    }
}

/** `termux-toast`. A toast is only ever shown on the main thread. */
object ToastApi : ApiFeature {

    override val name = "toast"
    override val permissions = emptySet<String>()

    override fun run(context: Context, request: ApiRequest): String {
        val message = request.extras.str("message")
            ?: return "ERROR: toast needs a message\n"
        val long = request.bool("long") == true
        // Create and show on the main thread: the request thread is a
        // bridge worker, and a Toast constructed off the main looper is
        // the configuration some OEM firmwares reject.
        Handler(Looper.getMainLooper()).post {
            val toast = android.widget.Toast.makeText(context, message, if (long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT)
            toast.show()
        }
        return ""
    }
}
