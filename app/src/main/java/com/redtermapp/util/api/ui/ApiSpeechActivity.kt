package com.redtermapp.util.api.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * The transparent host for a blocking speech-to-text request: it starts
 * the recognizer, waits for the transcript, and posts it back to the
 * request thread. The microphone grant is checked by the feature before
 * the activity is launched.
 */
class ApiSpeechActivity : Activity(), RecognitionListener {

    private var recognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val language = intent.getStringExtra("redterm.api.speechLanguage") ?: "en-US"
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        this.recognizer = recognizer
        recognizer.setRecognitionListener(this)
        val listen = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        recognizer.startListening(listen)
    }

    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
        answer("\"$text\"")
    }

    override fun onError(error: Int) {
        val name = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH -> "no match"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "timeout"
            SpeechRecognizer.ERROR_AUDIO -> "audio error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission is not granted"
            else -> "code $error"
        }
        answer("ERROR: $name\n")
    }

    private fun answer(result: String) {
        recognizer?.destroy()
        recognizer = null
        ApiUiBridge.postResult(this, result)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer?.destroy()
        recognizer = null
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onPartialResults(partialResults: Bundle?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit
}
