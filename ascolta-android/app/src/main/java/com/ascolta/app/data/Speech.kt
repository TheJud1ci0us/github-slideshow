package com.ascolta.app.data

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * The phone's text-to-speech, for hearing a word clearly and slowly. Whether a language is
 * available depends on the voices installed on the phone (Settings → Text-to-speech).
 */
object Speech {
    enum class Result { SPOKEN, NO_VOICE, STARTING }

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private var pending: Pair<String, String>? = null

    private fun ensure(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            pending?.let { (text, lang) -> if (ready) say(text, lang) }
            pending = null
        }
    }

    /** Speaks [text] in [language] at a slightly slow rate. */
    fun speak(context: Context, text: String, language: String): Result {
        ensure(context)
        if (!ready) {
            pending = text to language
            return Result.STARTING
        }
        return if (say(text, language)) Result.SPOKEN else Result.NO_VOICE
    }

    /** True if the phone has a voice for [language] (once the engine has started). */
    fun hasVoice(language: String): Boolean? {
        val t = tts ?: return null
        if (!ready) return null
        return t.isLanguageAvailable(Locale.forLanguageTag(language)) >= TextToSpeech.LANG_AVAILABLE
    }

    private fun say(text: String, language: String): Boolean {
        val t = tts ?: return false
        val result = t.setLanguage(Locale.forLanguageTag(language))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) return false
        t.setSpeechRate(0.85f)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lingopod-word")
        return true
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
    }
}
