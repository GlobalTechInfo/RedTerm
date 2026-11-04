package com.redtermapp.util.api

import android.content.Context
import android.speech.tts.TextToSpeech
import com.redtermapp.util.api.ApiRequestParser.ApiRequest
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `termux-tts-speak` / `termux-tts-engines`: the on-device
 * text-to-speech engines. One engine instance is kept per app process so
 * consecutive calls do not pay for init again; the engine can be chosen
 * by name from the engines list, and `stop` shuts it down.
 */
object TtsApi : ApiFeature {

    override val name = "tts"
    override val permissions = emptySet<String>()

    @Volatile private var live: Pair<String, TextToSpeech>? = null

    override fun run(context: Context, request: ApiRequest): String {
        val op = request.str("op") ?: "speak"
        return when (op) {
            "engines" -> engines(context)
            "stop" -> {
                live?.second?.stop()
                live?.second?.shutdown()
                live = null
                "TTS stopped\n"
            }
            else -> speak(context, request)
        }
    }

    private fun speak(context: Context, request: ApiRequest): String {
        val text = request.str("text")
            ?: request.str("file")?.let { runCatching { java.io.File(it).readText() }.getOrNull() }
            ?: return "ERROR: tts speak needs text\n"
        val wanted = request.str("engine") ?: ""
        val tts = live?.takeIf { it.first == wanted }?.second ?: run {
            live?.second?.shutdown()
            val ready = CountDownLatch(1)
            var status = TextToSpeech.ERROR
            val created = runCatching {
                TextToSpeech(context, object : TextToSpeech.OnInitListener {
                    override fun onInit(initStatus: Int) {
                        status = initStatus
                        ready.countDown()
                    }
                }, wanted.ifEmpty { null })
            }.getOrElse {
                live = null
                return "ERROR: TTS unavailable: ${it.message}\n"
            }
            if (!ready.await(5, TimeUnit.SECONDS)) {
                created.shutdown()
                live = null
                return "ERROR: TTS did not initialise\n"
            }
            if (status != TextToSpeech.SUCCESS) {
                created.shutdown()
                live = null
                return "ERROR: TTS engine failed to start\n"
            }
            live = wanted to created
            created
        }
        val lang = request.str("language")
        if (lang != null) {
            // The three-argument Locale constructor is deprecated in the
            // compile SDK in favour of Locale.of (API 33+), but it is not
            // removed on any level we ship, and it is the one that keeps
            // language / region / variant as three exact parts — forLanguageTag
            // re-normalises them through BCP-47 and cannot represent an
            // empty region with a present variant. It is therefore reached
            // by name: the deprecation warning is about a source reference
            // to the withdrawn member, not about the platform answering a
            // reflective one.
            val locale = Locale::class.java
                .getConstructor(String::class.java, String::class.java, String::class.java)
                .newInstance(lang, request.str("region") ?: "", request.str("variant") ?: "") as Locale
            tts.setLanguage(locale)
        }
        request.float("rate")?.let { tts.setSpeechRate(it) }
        request.float("pitch")?.let { tts.setPitch(it) }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "redterm")
        return "SPEAKING ${text.length} chars\n"
    }

    private fun engines(context: Context): String {
        val ready = CountDownLatch(1)
        val tts = runCatching {
            TextToSpeech(context) { _ -> ready.countDown() }
        }.getOrElse { return "ERROR: TTS unavailable: ${it.message}\n" }
        try {
            ready.await(5, TimeUnit.SECONDS)
            val sb = StringBuilder("[")
            for ((i, info) in tts.engines.withIndex()) {
                if (i > 0) sb.append(",")
                sb.append("{\"name\":\"").append(info.name.replace("\"", "\\\""))
                    .append("\",\"label\":\"").append((info.label ?: "").replace("\\", "\\\\").replace("\"", "\\\""))
                    .append("\"}")
            }
            sb.append("]")
            return sb.toString() + "\n"
        } finally {
            tts.shutdown()
        }
    }
}
