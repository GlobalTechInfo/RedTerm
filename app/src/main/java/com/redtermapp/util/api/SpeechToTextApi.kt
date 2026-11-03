package com.redtermapp.util.api

import android.content.Context
import android.speech.SpeechRecognizer
import com.redtermapp.util.api.ApiRequestParser.ApiRequest
import com.redtermapp.util.api.ui.ApiSpeechActivity
import com.redtermapp.util.api.ui.ApiUiBridge

/**
 * `termux-speech-to-text`: listen once and return the transcript. The
 * recognizer needs a visible host and the microphone grant, so the
 * request blocks on a transparent activity the way the dialog does.
 */
object SpeechToTextApi : ApiFeature {

    override val name = "speech-to-text"
    override val permissions = setOf(android.Manifest.permission.RECORD_AUDIO)

    override fun run(context: Context, request: ApiRequest): String {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return "ERROR: no speech recognition service is installed\n"
        }
        return ApiUiBridge.show(
            context,
            ApiSpeechActivity::class.java,
            ApiUiBridge.ApiUiSpec(
                "speech",
                30,
                mapOf("redterm.api.speechLanguage" to (request.str("language") ?: "en-US"))
            )
        )
    }
}
