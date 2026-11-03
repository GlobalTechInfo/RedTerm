package com.redtermapp.util.api

import android.Manifest
import android.content.Context
import android.media.MediaRecorder
import com.redtermapp.util.api.ApiRequestParser.ApiRequest
import java.io.File

/**
 * `termux-microphone-record`: a process-wide [MediaRecorder]. `-d`
 * starts with sensible defaults into the app's own storage, `-f -e -b -r
 * -c -l` the full form, `-i` reports, `-q` stops and hands back the file.
 * Needs the microphone grant; a device without a mic says so.
 */
object MicRecordApi : ApiFeature {

    override val name = "mic-record"
    override val permissions = setOf(Manifest.permission.RECORD_AUDIO)

    @Volatile private var recorder: MediaRecorder? = null
    @Volatile private var recordedPath: String? = null
    @Volatile private var recording = false

    override fun run(context: Context, request: ApiRequest): String {
        val op = request.str("op") ?: "info"
        return when (op) {
            "start" -> start(context, request)
            "stop" -> stop()
            "info" -> {
                if (!recording) return "NOT_RECORDING\n"
                "RECORDING ${recordedPath ?: ""}\n"
            }
            else -> "ERROR: unknown mic-record op '$op'\n"
        }
    }

    private fun start(context: Context, request: ApiRequest): String {
        if (recording) return "ALREADY_RECORDING\n"
        val enc = encoderName(request.str("encoder"))
        val file = request.str("file")
            ?: File(context.filesDir, "api/recording-${System.currentTimeMillis()}").absolutePath
        val limitMs = (request.int("limit") ?: 0).toLong()
        val r = MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(formatFor(enc))
            r.setAudioEncoder(encoderFor(enc))
            (request.int("bitrate")?.coerceAtLeast(0))?.takeIf { it > 0 }?.let { r.setAudioEncodingBitRate(it) }
            (request.int("srate")?.coerceAtLeast(0))?.takeIf { it > 0 }?.let { r.setAudioSamplingRate(it) }
            (request.int("channels")?.coerceAtLeast(0))?.takeIf { it > 0 }?.let { r.setAudioChannels(it) }
            if (limitMs > 0) r.setMaxDuration(limitMs.toInt())
            r.setOutputFile(file)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            runCatching { r.release() }
            return "ERROR: could not start recording: ${e.message}\n"
        }
        recorder = r
        recordedPath = file
        recording = true
        return "RECORDING $file\n"
    }

    private fun stop(): String {
        val r = recorder ?: return "NOT_RECORDING\n"
        recording = false
        recorder = null
        var secs = -1
        runCatching {
            r.stop()
            secs = 0
        }
        runCatching { r.release() }
        recordedPath?.let {
            val size = runCatching { File(it).length() }.getOrDefault(-1L)
            return "STOPPED $it ${size} bytes\n"
        }
        return "STOPPED\n"
    }

    private fun encoderName(raw: String?): String = when (raw?.lowercase()) {
        "aac" -> "aac"
        "amrnb" -> "amrnb"
        "amrwb" -> "amrwb"
        "opus" -> "opus"
        "vorbis" -> "vorbis"
        else -> "aac"
    }

    private fun formatFor(enc: String) = when (enc) {
        "amrnb", "amrwb" -> MediaRecorder.OutputFormat.AMR_NB
        "aac", "opus", "vorbis" -> MediaRecorder.OutputFormat.MPEG_4
        else -> MediaRecorder.OutputFormat.MPEG_4
    }

    private fun encoderFor(enc: String) = when (enc) {
        "amrnb" -> MediaRecorder.AudioEncoder.AMR_NB
        "amrwb" -> MediaRecorder.AudioEncoder.AMR_WB
        // OPUS only exists from API 29; below it fall back to AAC so a
        // recording request still succeeds rather than throwing.
        "opus" -> if (android.os.Build.VERSION.SDK_INT >= 29) MediaRecorder.AudioEncoder.OPUS
                  else MediaRecorder.AudioEncoder.AAC
        "vorbis" -> MediaRecorder.AudioEncoder.VORBIS
        else -> MediaRecorder.AudioEncoder.AAC
    }
}
