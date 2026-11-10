package com.ascolta.app.translate

import android.content.Context
import android.net.ConnectivityManager
import com.ascolta.app.data.AppLog
import com.ascolta.app.net.downloadWithBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

/**
 * Helsinki-NLP Opus-MT translation models (Apache 2.0) for languages Google's offline
 * translator doesn't have, in the ONNX form made for phones and browsers. Downloaded once per
 * language pair when first needed; one pair is kept in memory at a time.
 */
class OpusModels(private val context: Context, private val http: OkHttpClient) {
    private data class File_(val path: String, val bytes: Long)

    /** Pairs available ("hy-en"), with their files and exact sizes (checked on Hugging Face). */
    private val pairs: Map<String, Pair<String, List<File_>>> = mapOf(
        "hy-en" to ("https://huggingface.co/fintutto/opus-mt-hy-en/resolve/main" to listOf(
            File_("config.json", 1_371), File_("tokenizer.json", 7_174_335),
            File_("onnx/encoder_model_quantized.onnx", 51_616_157), File_("onnx/decoder_model_merged_quantized.onnx", 91_230_001),
        )),
        "en-hy" to ("https://huggingface.co/fintutto/opus-mt-en-hy/resolve/main" to listOf(
            File_("config.json", 1_378), File_("tokenizer.json", 838_669),
            File_("onnx/encoder_model_quantized.onnx", 23_589_276), File_("onnx/decoder_model_merged_quantized.onnx", 34_683_571),
        )),
    )

    private val lock = Mutex()
    private var loaded: Pair<String, MarianTranslator>? = null

    fun has(source: String, target: String) = "$source-$target" in pairs

    private fun dir(key: String) = File(context.filesDir, "opus/$key")

    /** Bytes still to download for this pair (exact). */
    fun missingBytes(source: String, target: String): Long {
        val key = "$source-$target"
        val (_, files) = pairs[key] ?: return 0
        return files.filter { f -> File(dir(key), f.path.substringAfterLast('/')).length() != f.bytes }.sumOf { it.bytes }
    }

    fun isDownloaded(source: String, target: String): Boolean {
        val key = "$source-$target"
        val (_, files) = pairs[key] ?: return false
        return files.all { f -> File(dir(key), f.path.substringAfterLast('/')).length() == f.bytes }
    }

    suspend fun download(source: String, target: String, requireWifi: Boolean) = lock.withLock {
        val key = "$source-$target"
        val (base, files) = pairs[key] ?: throw IOException("No Opus-MT model for $key")
        if (isDownloaded(source, target)) return@withLock
        if (requireWifi) {
            val metered = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
            if (metered) throw IOException("Waiting for Wi-Fi to download the $key translation model")
        }
        AppLog.i("Opus", "Downloading translation model $key")
        for (f in files) {
            val dest = File(dir(key), f.path.substringAfterLast('/'))
            if (dest.length() == f.bytes) continue
            dest.delete()
            http.downloadWithBytes("$base/${f.path}", dest) { _, _ -> }
            if (dest.length() != f.bytes) {
                dest.delete()
                throw IOException("${f.path}: incomplete download")
            }
        }
        AppLog.i("Opus", "Translation model $key ready")
    }

    suspend fun translate(text: String, source: String, target: String): String {
        val key = "$source-$target"
        val translator = lock.withLock {
            loaded?.takeIf { it.first == key }?.second ?: run {
                if (!isDownloaded(source, target)) throw IOException("The $key translation model isn't downloaded yet")
                loaded?.second?.close()
                withContext(Dispatchers.IO) { MarianTranslator(dir(key)) }.also { loaded = key to it }
            }
        }
        return withContext(Dispatchers.Default) { translator.translate(text) }
    }
}
