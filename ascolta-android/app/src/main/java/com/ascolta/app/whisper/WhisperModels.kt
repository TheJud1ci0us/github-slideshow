package com.ascolta.app.whisper

import android.content.Context
import android.net.Uri
import com.ascolta.app.net.downloadWithBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

/**
 * Multilingual whisper.cpp models (quantized). They are downloaded once,
 * then all speech recognition happens on the phone.
 */
enum class WhisperModel(
    val id: String,
    val fileName: String,
    val label: String,
    val approxSizeMb: Int,
    val description: String,
) {
    TINY("tiny", "ggml-tiny-q5_1.bin", "Tiny", 32, "Fastest. Lower accuracy; fine for slow, clear speech."),
    BASE("base", "ggml-base-q5_1.bin", "Base", 60, "Fast with decent accuracy. Good default."),
    SMALL("small", "ggml-small-q5_1.bin", "Small", 190, "About 3× slower than Base, noticeably more accurate."),
    TURBO("turbo", "ggml-large-v3-turbo-q5_0.bin", "Large v3 Turbo", 574, "Best accuracy, slow. Needs a recent phone with 8 GB RAM."),
    CUSTOM("custom", "custom.bin", "Imported model", 0, "A ggml whisper model file you imported yourself.");

    val url: String?
        get() = if (this == CUSTOM) null else "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"

    companion object {
        fun byId(id: String): WhisperModel = entries.firstOrNull { it.id == id } ?: BASE
    }
}

class WhisperModelManager(private val context: Context, private val http: OkHttpClient) {
    private val dir = File(context.filesDir, "models")

    fun file(model: WhisperModel) = File(dir, model.fileName)

    fun isDownloaded(model: WhisperModel) = file(model).let { it.exists() && it.length() > 0 }

    /** [onProgress] receives bytes downloaded and total bytes (-1 if unknown). */
    suspend fun download(model: WhisperModel, onProgress: suspend (Long, Long) -> Unit) {
        val url = model.url ?: error("Import a model file in Settings first")
        http.downloadWithBytes(url, file(model), onProgress)
    }

    fun delete(model: WhisperModel) {
        file(model).delete()
        File(file(model).path + ".part").delete()
    }

    /** Copies a user-picked ggml model file into the app as the CUSTOM model. */
    suspend fun import(uri: Uri) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val tmp = File(dir, "custom.bin.tmp")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        val dest = file(WhisperModel.CUSTOM)
        dest.delete()
        tmp.renameTo(dest)
    }
}
