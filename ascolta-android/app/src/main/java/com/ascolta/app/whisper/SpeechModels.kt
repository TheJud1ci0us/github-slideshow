package com.ascolta.app.whisper

import android.content.Context
import android.net.Uri
import com.ascolta.app.net.downloadWithBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

enum class Engine { WHISPER, PARAKEET, PARAKEET_NPU }

/** A file to download; with [extractTo], it is a zip unpacked into that folder (its top folder stripped). */
data class ModelFile(val url: String, val name: String, val approxBytes: Long, val extractTo: String? = null)

private const val WHISPER_HF = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"
private const val PARAKEET_HF = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main"
/** Qualcomm AI Hub's export of Parakeet v3 for its NPU (encoder/decoder/joint ONNX, float). */
private const val PARAKEET_NPU_ZIP =
    "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/parakeet_tdt/releases/v0.66.0/parakeet_tdt-onnx-float.zip"

/** Languages NVIDIA Parakeet TDT 0.6B v3 understands (25 European languages). */
val PARAKEET_LANGUAGES = setOf(
    "bg", "hr", "cs", "da", "nl", "en", "et", "fi", "fr", "de", "el", "hu", "it",
    "lv", "lt", "mt", "pl", "pt", "ro", "sk", "sl", "es", "sv", "ru", "uk",
)

/**
 * Speech recognition models. They are downloaded once; all recognition then
 * happens on the phone.
 */
enum class SpeechModel(
    val id: String,
    val engine: Engine,
    val label: String,
    val approxSizeMb: Int,
    val files: List<ModelFile>,
    /** null = all languages. */
    val languages: Set<String>? = null,
) {
    TINY("tiny", Engine.WHISPER, "Whisper Tiny", 32,
        listOf(ModelFile("$WHISPER_HF/ggml-tiny-q5_1.bin", "ggml-tiny-q5_1.bin", 32_000_000))),
    BASE("base", Engine.WHISPER, "Whisper Base", 60,
        listOf(ModelFile("$WHISPER_HF/ggml-base-q5_1.bin", "ggml-base-q5_1.bin", 59_707_625))),
    SMALL("small", Engine.WHISPER, "Whisper Small", 190,
        listOf(ModelFile("$WHISPER_HF/ggml-small-q5_1.bin", "ggml-small-q5_1.bin", 190_000_000))),
    TURBO("turbo", Engine.WHISPER, "Whisper Large v3 Turbo", 574,
        listOf(ModelFile("$WHISPER_HF/ggml-large-v3-turbo-q5_0.bin", "ggml-large-v3-turbo-q5_0.bin", 574_000_000))),
    PARAKEET("parakeet-v3", Engine.PARAKEET, "NVIDIA Parakeet v3", 670,
        listOf(
            ModelFile("$PARAKEET_HF/tokens.txt", "parakeet-v3/tokens.txt", 93_939),
            ModelFile("$PARAKEET_HF/decoder.int8.onnx", "parakeet-v3/decoder.int8.onnx", 11_845_274),
            ModelFile("$PARAKEET_HF/joiner.int8.onnx", "parakeet-v3/joiner.int8.onnx", 6_355_277),
            ModelFile("$PARAKEET_HF/encoder.int8.onnx", "parakeet-v3/encoder.int8.onnx", 652_282_294),
        ),
        languages = PARAKEET_LANGUAGES,
    ),
    PARAKEET_NPU("parakeet-v3-npu", Engine.PARAKEET_NPU, "NVIDIA Parakeet v3 (NPU)", 2270,
        listOf(
            ModelFile("$PARAKEET_HF/tokens.txt", "parakeet-v3-npu/tokens.txt", 93_939),
            ModelFile(PARAKEET_NPU_ZIP, "parakeet-v3-npu.zip", 2_266_000_000, extractTo = "parakeet-v3-npu"),
        ),
        languages = PARAKEET_LANGUAGES,
    ),
    CUSTOM("custom", Engine.WHISPER, "Imported Whisper model", 0, emptyList());

    fun supports(language: String) = languages == null || language in languages

    companion object {
        fun byId(id: String): SpeechModel = entries.firstOrNull { it.id == id } ?: BASE
    }
}

/** Silero voice-activity model used to skip music and silence (≈ 0.9 MB). */
val VAD_MODEL = ModelFile(
    "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v5.1.2.bin",
    "ggml-silero-v5.1.2.bin",
    885_098,
)

class ModelManager(private val context: Context, private val http: OkHttpClient) {
    val dir = File(context.filesDir, "models")

    fun file(name: String) = File(dir, name)

    /** The main model file: whisper's .bin, or Parakeet's folder. */
    fun primaryPath(model: SpeechModel): File = when {
        model == SpeechModel.CUSTOM -> file("custom.bin")
        model.engine == Engine.WHISPER -> file(model.files.first().name)
        else -> file(model.files.first().name).parentFile!!
    }

    /** Parakeet's word-piece list for the NPU engine. */
    fun parakeetTokens(): File = file("parakeet-v3-npu/tokens.txt")

    private fun isPresent(f: ModelFile): Boolean =
        if (f.extractTo != null) File(file(f.extractTo), ".complete").exists()
        else file(f.name).let { it.exists() && it.length() > 0 }

    fun isDownloaded(model: SpeechModel): Boolean = when (model) {
        SpeechModel.CUSTOM -> file("custom.bin").let { it.exists() && it.length() > 0 }
        else -> model.files.all(::isPresent)
    }

    fun vadFile(): File = file(VAD_MODEL.name)
    fun isVadDownloaded() = vadFile().let { it.exists() && it.length() > 0 }

    suspend fun downloadVad() {
        if (!isVadDownloaded()) http.downloadWithBytes(VAD_MODEL.url, vadFile()) { _, _ -> }
    }

    /** Downloads all files of [model]; [onProgress] gets overall bytes done and total. */
    suspend fun download(model: SpeechModel, onProgress: suspend (Long, Long) -> Unit) {
        require(model != SpeechModel.CUSTOM) { "Import a model file in Settings first" }
        val total = model.files.sumOf { it.approxBytes }
        var done = 0L
        for (f in model.files) {
            if (!isPresent(f)) {
                val dest = file(f.name)
                http.downloadWithBytes(f.url, dest) { written, _ -> onProgress(done + written, total) }
                f.extractTo?.let { target ->
                    unzip(dest, file(target))
                    dest.delete()
                }
            }
            done += f.approxBytes
            onProgress(done, total)
        }
    }

    /** Unpacks [zip] into [target], dropping the archive's top-level folder (ZIP64-safe). */
    private suspend fun unzip(zip: File, target: File) = withContext(Dispatchers.IO) {
        ZipExtractor.extract(zip, target, stripTopFolder = true)
        File(target, ".complete").writeText("ok")
    }

    fun delete(model: SpeechModel) {
        if (model == SpeechModel.CUSTOM) {
            file("custom.bin").delete()
            return
        }
        model.files.forEach {
            file(it.name).delete()
            File(file(it.name).path + ".part").delete()
            it.extractTo?.let { dir -> file(dir).deleteRecursively() }
        }
    }

    /** Copies a user-picked ggml whisper model file into the app as the CUSTOM model. */
    suspend fun import(uri: Uri) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val tmp = file("custom.bin.tmp")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        val dest = file("custom.bin")
        dest.delete()
        tmp.renameTo(dest)
    }
}
