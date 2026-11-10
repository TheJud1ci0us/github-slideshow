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
 * On-device translation. Google ML Kit (the same models as Google Translate's offline mode,
 * ~30 MB per language) covers about 59 languages; for others (Armenian) the Helsinki-NLP
 * Opus-MT models fill in, through English when needed: Armenian → English (Opus-MT) →
 * French (ML Kit). Everything runs on the phone.
 */
class TranslationService(
    private val opus: OpusModels? = null,
) {
    private val mutex = Mutex()
    private var translator: Translator? = null
    private var pair: Pair<String, String>? = null

    /** One step of a translation: by ML Kit, or by an Opus-MT model. */
    private data class Hop(val source: String, val target: String, val opus: Boolean)

    private fun mlKit(language: String) = TranslateLanguage.fromLanguageTag(language) != null

    /** How to get from [source] to [target], or null if no offline translation exists. */
    private fun route(source: String, target: String): List<Hop>? {
        if (source == target) return null
        if (mlKit(source) && mlKit(target)) return listOf(Hop(source, target, false))
        val models = opus ?: return null
        if (models.has(source, target)) return listOf(Hop(source, target, true))
        // Through English: Opus-MT for the language ML Kit lacks, ML Kit for the other side.
        if (models.has(source, "en") && mlKit(target)) return listOf(Hop(source, "en", true), Hop("en", target, false))
        if (models.has("en", target) && mlKit(source)) return listOf(Hop(source, "en", false), Hop("en", target, true))
        return null
    }

    fun isSupported(source: String, target: String) = route(source, target) != null

    /**
     * What translating [source] → [target] still needs to download: (name, bytes, exact?).
     * Helsinki models have exact sizes; Google's are about 30 MB per language.
     */
    suspend fun missingDownloads(source: String, target: String): List<Triple<String, Long, Boolean>> {
        val hops = route(source, target) ?: return emptyList()
        val out = mutableListOf<Triple<String, Long, Boolean>>()
        val downloaded = runCatching {
            RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java).await().map { it.language }.toSet()
        }.getOrDefault(emptySet())
        for (h in hops) {
            if (h.opus) {
                val bytes = opus!!.missingBytes(h.source, h.target)
                if (bytes > 0) out += Triple("${h.source} → ${h.target}", bytes, true)
            } else {
                for (lang in listOf(h.source, h.target).distinct()) {
                    val tag = TranslateLanguage.fromLanguageTag(lang) ?: continue
                    if (tag != TranslateLanguage.ENGLISH && tag !in downloaded && out.none { it.first == lang }) {
                        out += Triple(lang, 30_000_000L, false)
                    }
                }
            }
        }
        return out
    }

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
        val hops = route(source, target) ?: return false
        return hops.all { h -> if (h.opus) opus!!.isDownloaded(h.source, h.target) else isMlKitDownloaded(h.source, h.target) }
    }

    private suspend fun isMlKitDownloaded(source: String, target: String): Boolean {
        if (source == target) return true
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
        val hops = route(source, target) ?: throw IllegalArgumentException("No offline translation for $source → $target")
        for (h in hops) {
            if (h.opus) opus!!.download(h.source, h.target, requireWifi)
            else if (h.source != h.target) ensureMlKit(h.source, h.target, requireWifi)
        }
    }

    private suspend fun ensureMlKit(source: String, target: String, requireWifi: Boolean) {
        val conditions = DownloadConditions.Builder().apply { if (requireWifi) requireWifi() }.build()
        translatorFor(source, target).downloadModelIfNeeded(conditions).await()
    }

    suspend fun translate(text: String, source: String, target: String): String {
        if (text.isBlank()) return text
        val hops = route(source, target) ?: throw IllegalArgumentException("No offline translation for $source → $target")
        var current = text
        for (h in hops) {
            current = if (h.opus) opus!!.translate(current, h.source, h.target)
            else translatorFor(h.source, h.target).translate(current).await()
        }
        return current
    }
}
