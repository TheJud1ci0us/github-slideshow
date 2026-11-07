package com.ascolta.app.data

import android.content.Context
import android.content.SharedPreferences
import com.ascolta.app.whisper.WhisperModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Language(val code: String, val name: String, val country: String)

/** Languages supported by both whisper and ML Kit on-device translation. */
val LANGUAGES = listOf(
    Language("it", "Italian", "IT"),
    Language("en", "English", "US"),
    Language("es", "Spanish", "ES"),
    Language("fr", "French", "FR"),
    Language("de", "German", "DE"),
    Language("pt", "Portuguese", "BR"),
    Language("ja", "Japanese", "JP"),
    Language("zh", "Chinese", "TW"),
)

fun languageName(code: String?) = LANGUAGES.firstOrNull { it.code == code }?.name ?: code ?: "?"

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

    private fun float(key: String, default: Float) = Pref(
        prefs, key, default,
        { k, d -> getFloat(k, d) },
        { k, v -> putFloat(k, v) },
    )

    /** Language of the podcasts (what you are learning). */
    val learningLanguage = string("learning_language", "it")
    /** Language translations are shown in. */
    val nativeLanguage = string("native_language", "en")
    val modelId = string("whisper_model", WhisperModel.BASE.id)

    val autoDownloadNew = bool("auto_download_new", true)
    val autoTranscribe = bool("auto_transcribe", true)
    val wifiOnly = bool("wifi_only", true)
    val chargingOnly = bool("charging_only", false)

    val showOriginal = bool("show_original", true)
    val showTranslation = bool("show_translation", true)
    val autoPause = bool("auto_pause", false)
    val playbackSpeed = float("playback_speed", 1f)
    val transcriptTextScale = float("transcript_text_scale", 1f)

    val model: WhisperModel get() = WhisperModel.byId(modelId.value)
}
