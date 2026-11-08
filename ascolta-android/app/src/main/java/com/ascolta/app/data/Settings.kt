package com.ascolta.app.data

import android.content.Context
import android.content.SharedPreferences
import com.ascolta.app.whisper.SpeechModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** [country] picks the podcast directory's store; [flagCountry] the flag shown for the language. */
data class Language(val code: String, val name: String, val country: String, val flagCountry: String = country)

/** Flag emoji for a two-letter country code (regional indicator letters). */
fun flagEmoji(country: String): String =
    country.uppercase().filter { it in 'A'..'Z' }.take(2).map { Character.toChars(0x1F1E6 + (it - 'A')).concatToString() }.joinToString("")

/** Flag representing a language, e.g. "🇮🇹" for Italian. */
fun languageFlag(code: String?): String =
    LANGUAGES.firstOrNull { it.code == code }?.let { flagEmoji(it.flagCountry) } ?: "🏳️"

/**
 * Languages that can be learned and translated: every language both Whisper (99 languages)
 * and ML Kit's on-device translation (59) support. NVIDIA Parakeet v3 knows 25 of them
 * (see PARAKEET_LANGUAGES). The country picks the podcast directory's store.
 */
val LANGUAGES = listOf(
    Language("af", "Afrikaans", "ZA"),
    Language("ar", "Arabic", "SA"),
    Language("be", "Belarusian", "BY"),
    Language("bg", "Bulgarian", "BG"),
    Language("bn", "Bengali", "IN"),
    Language("ca", "Catalan", "ES"),
    Language("cs", "Czech", "CZ"),
    Language("cy", "Welsh", "GB"),
    Language("da", "Danish", "DK"),
    Language("de", "German", "DE"),
    Language("el", "Greek", "GR"),
    Language("en", "English", "US"),
    Language("es", "Spanish", "ES"),
    Language("et", "Estonian", "EE"),
    Language("fa", "Persian", "US", "IR"),
    Language("fi", "Finnish", "FI"),
    Language("fr", "French", "FR"),
    Language("gl", "Galician", "ES"),
    Language("gu", "Gujarati", "IN"),
    Language("he", "Hebrew", "IL"),
    Language("hi", "Hindi", "IN"),
    Language("hr", "Croatian", "HR"),
    Language("ht", "Haitian Creole", "US", "HT"),
    Language("hu", "Hungarian", "HU"),
    Language("id", "Indonesian", "ID"),
    Language("is", "Icelandic", "IS"),
    Language("it", "Italian", "IT"),
    Language("ja", "Japanese", "JP"),
    Language("ka", "Georgian", "GE"),
    Language("kn", "Kannada", "IN"),
    Language("ko", "Korean", "KR"),
    Language("lt", "Lithuanian", "LT"),
    Language("lv", "Latvian", "LV"),
    Language("mk", "Macedonian", "MK"),
    Language("mr", "Marathi", "IN"),
    Language("ms", "Malay", "MY"),
    Language("mt", "Maltese", "MT"),
    Language("nl", "Dutch", "NL"),
    Language("no", "Norwegian", "NO"),
    Language("pl", "Polish", "PL"),
    Language("pt", "Portuguese", "BR"),
    Language("ro", "Romanian", "RO"),
    Language("ru", "Russian", "RU"),
    Language("sk", "Slovak", "SK"),
    Language("sl", "Slovenian", "SI"),
    Language("sq", "Albanian", "AL"),
    Language("sv", "Swedish", "SE"),
    Language("sw", "Swahili", "KE"),
    Language("ta", "Tamil", "IN"),
    Language("te", "Telugu", "IN"),
    Language("th", "Thai", "TH"),
    Language("tl", "Tagalog", "PH"),
    Language("tr", "Turkish", "TR"),
    Language("uk", "Ukrainian", "UA"),
    Language("ur", "Urdu", "PK"),
    Language("vi", "Vietnamese", "VN"),
    Language("zh", "Chinese", "TW", "CN"),
)

/** Language name in the app's current language, e.g. "Italiano" / "Italian" / "Italien". */
fun languageName(code: String?): String {
    if (code == null) return "?"
    val locale = java.util.Locale.getDefault()
    val name = java.util.Locale.forLanguageTag(code).getDisplayLanguage(locale)
    return name.replaceFirstChar { it.titlecase(locale) }.ifBlank { code }
}

class Pref<T>(
    private val prefs: SharedPreferences,
    private val key: String,
    default: T,
    read: SharedPreferences.(String, T) -> T,
    private val write: SharedPreferences.Editor.(String, T) -> Unit,
) {
    private val state = MutableStateFlow(prefs.read(key, default))
    val flow: StateFlow<T> = state
    val value: T get() = state.value

    fun set(value: T) {
        prefs.edit().apply { write(key, value) }.apply()
        state.value = value
    }
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun string(key: String, default: String) = Pref(
        prefs, key, default,
        { k, d -> getString(k, d) ?: d },
        { k, v -> putString(k, v) },
    )

    private fun bool(key: String, default: Boolean) = Pref(
        prefs, key, default,
        { k, d -> getBoolean(k, d) },
        { k, v -> putBoolean(k, v) },
    )

    private fun int(key: String, default: Int) = Pref(
        prefs, key, default,
        { k, d -> getInt(k, d) },
        { k, v -> putInt(k, v) },
    )

    private fun float(key: String, default: Float) = Pref(
        prefs, key, default,
        { k, d -> getFloat(k, d) },
        { k, v -> putFloat(k, v) },
    )

    /** The language being learned right now (the library, search and words shown are this one's). */
    val learningLanguage = string("learning_language", "it")
    /** Every language being learned, comma-separated: the flags in the quick-switch bar. */
    val learningLanguages = string("learning_languages", "")
    /** The first-launch language choice has been made. */
    val onboarded = bool("onboarded", false)

    fun learningList(): List<String> =
        learningLanguages.value.split(',').filter { it.isNotBlank() }.ifEmpty { listOf(learningLanguage.value) }

    fun setLearningList(codes: List<String>) {
        val list = codes.distinct().ifEmpty { listOf(learningLanguage.value) }
        learningLanguages.set(list.joinToString(","))
        if (learningLanguage.value !in list) learningLanguage.set(list.first())
    }

    fun switchLanguage(code: String) {
        if (code !in learningList()) setLearningList(learningList() + code)
        learningLanguage.set(code)
    }
    /** Language translations are shown in. */
    val nativeLanguage = string("native_language", "en")
    val modelId = string("whisper_model", SpeechModel.BASE.id)

    /** CPU cores used for speech recognition (1–8); 0 = automatic (the phone's fast cores). */
    val threads = int("threads", 0)

    fun effectiveThreads(): Int =
        threads.value.takeIf { it in 1..8 } ?: com.ascolta.app.whisper.CoreDispatchers.defaultThreads()
    /** whisper.cpp flash attention ("faster calculation mode"). */
    val flashAttention = bool("flash_attention", true)
    /** Skip music and silence with voice-activity detection. */
    val skipNonSpeech = bool("skip_non_speech", true)

    val autoDownloadNew = bool("auto_download_new", true)
    /** Off by default: transcription is chosen per episode with the Transcribe button. */
    val autoTranscribe = bool("auto_transcribe_v2", false)
    /** Network mode: true = Wi-Fi only (downloads, streaming, models); false = Wi-Fi and mobile data. */
    val wifiOnly = bool("wifi_only", true)
    val chargingOnly = bool("charging_only", false)
    /** How often feeds are checked for new episodes, in hours; 0 = only by hand. */
    val refreshHours = int("refresh_hours", 6)
    /** Downloaded episodes are deleted this many days after downloading; 0 = keep. */
    val deleteAfterDays = int("delete_after_days", 0)
    /** Notification when a favourite podcast has a new episode. */
    val notifyFavourites = bool("notify_favourites", true)

    /** "system", "light" or "dark". */
    val theme = string("theme", "system")
    /** Colours from the wallpaper (Android 12+). */
    val dynamicColor = bool("dynamic_color", true)

    val showOriginal = bool("show_original", true)
    val showTranslation = bool("show_translation", true)
    /** Translate sentences at all. Off: only the transcript (tapped words are still translated). */
    val translate = bool("translate_sentences", true)
    /** Player shows the episode's picture instead of the transcript (which is kept). */
    val showCover = bool("show_cover", false)
    val autoPause = bool("auto_pause", false)
    /** Highlights the word being spoken inside the current sentence. */
    val wordHighlight = bool("word_highlight", true)
    val playbackSpeed = float("playback_speed", 1f)
    val transcriptTextScale = float("transcript_text_scale", 1f)

    val model: SpeechModel get() = SpeechModel.byId(modelId.value)
}
