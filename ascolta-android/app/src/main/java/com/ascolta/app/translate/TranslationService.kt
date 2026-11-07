package com.ascolta.app.translate

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * On-device translation with Google ML Kit (the same models as Google Translate's
 * offline mode). Each language model is ~30 MB and downloaded once.
 */
class TranslationService {
    private val mutex = Mutex()
    private var translator: Translator? = null
    private var pair: Pair<String, String>? = null

    fun isSupported(source: String, target: String) =
        source != target &&
            TranslateLanguage.fromLanguageTag(source) != null &&
            TranslateLanguage.fromLanguageTag(target) != null

    private suspend fun translatorFor(source: String, target: String): Translator = mutex.withLock {
        val key = source to target
        translator?.takeIf { pair == key }?.let { return it }
        translator?.close()
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.fromLanguageTag(source)!!)
            .setTargetLanguage(TranslateLanguage.fromLanguageTag(target)!!)
            .build()
        return Translation.getClient(options).also {
            translator = it
            pair = key
        }
    }

    suspend fun isModelDownloaded(source: String, target: String): Boolean {
        val manager = RemoteModelManager.getInstance()
        val downloaded = manager.getDownloadedModels(TranslateRemoteModel::class.java).await()
            .map { it.language }.toSet()
        return listOf(source, target)
            .mapNotNull { TranslateLanguage.fromLanguageTag(it) }
            .filter { it != TranslateLanguage.ENGLISH } // English is built in
            .all { it in downloaded }
    }

    /** Downloads the language models if needed. Throws if not possible (e.g. offline). */
    suspend fun ensureModel(source: String, target: String, requireWifi: Boolean) {
        val conditions = DownloadConditions.Builder().apply { if (requireWifi) requireWifi() }.build()
        translatorFor(source, target).downloadModelIfNeeded(conditions).await()
    }

    suspend fun translate(text: String, source: String, target: String): String {
        if (text.isBlank()) return text
        return translatorFor(source, target).translate(text).await()
    }
}
