package com.ascolta.app.whisper

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.Closeable
import java.io.File

/** Settings that affect how fast recognition runs. */
data class EngineOptions(
    val threads: Int,
    val flashAttention: Boolean,
    /** Silero model used to skip music and silence; null = process everything. */
    val vadModelPath: String?,
)

/** A loaded speech recognition model. Not thread-safe: one transcription at a time. */
interface SpeechEngine : Closeable {
    /**
     * Transcribes 16 kHz mono audio. Times in the result are relative to [samples].
     * Returns null if [abort] was called.
     */
    fun transcribe(samples: FloatArray, language: String, prompt: String?, onProgress: (Int) -> Unit): List<WhisperSegment>?

    /** Can be called from any thread to stop the running transcription early. */
    fun abort()

    companion object {
        fun load(model: SpeechModel, models: ModelManager, options: EngineOptions): SpeechEngine = when (model.engine) {
            Engine.WHISPER -> WhisperEngine(models.primaryPath(model).path, options)
            Engine.PARAKEET -> ParakeetEngine(models.primaryPath(model), options)
        }
    }
}

/** whisper.cpp. With a VAD model, music and silence are skipped inside whisper.cpp. */
class WhisperEngine(modelPath: String, private val options: EngineOptions) : SpeechEngine {
    private var handle = WhisperLib.nativeInit(modelPath, options.flashAttention)

    init {
        require(handle != 0L) { "Could not load speech model (file may be corrupt): $modelPath" }
    }

    override fun transcribe(
        samples: FloatArray,
        language: String,
        prompt: String?,
        onProgress: (Int) -> Unit,
    ): List<WhisperSegment>? {
        check(handle != 0L) { "Model already released" }
        val segments = WhisperLib.nativeTranscribe(
            handle, samples, language, options.threads, prompt,
            ProgressListener { onProgress(it) }, options.vadModelPath,
        )?.toList() ?: return null
        val vad = options.vadModelPath ?: return segments
        // whisper.cpp maps times back from the speech-only audio only approximately; a
        // sentence after a music break can appear to start during the music. Snap each
        // sentence to the stretch of speech it belongs to.
        val speech = WhisperLib.nativeDetectSpeech(vad, samples, options.threads) ?: return segments
        return SpeechAlignment.alignToSpeech(segments, SpeechAlignment.pairs(speech))
    }

    override fun abort() {
        if (handle != 0L) WhisperLib.nativeAbort(handle)
    }

    override fun close() {
        if (handle != 0L) {
            WhisperLib.nativeFree(handle)
            handle = 0
        }
    }
}

/**
 * NVIDIA Parakeet TDT 0.6B v3 through sherpa-onnx. It works on short utterances, so
 * the audio is first split at pauses with the voice-activity model (which also
 * skips music and silence), then each piece is recognised and split into sentences.
 */
class ParakeetEngine(modelDir: File, private val options: EngineOptions) : SpeechEngine {
    @Volatile private var aborted = false
    private val recognizer: OfflineRecognizer

    init {
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(modelDir, "encoder.int8.onnx").path,
                    decoder = File(modelDir, "decoder.int8.onnx").path,
                    joiner = File(modelDir, "joiner.int8.onnx").path,
                ),
                tokens = File(modelDir, "tokens.txt").path,
                numThreads = options.threads,
                modelType = "nemo_transducer",
            ),
            decodingMethod = "greedy_search",
        )
        recognizer = OfflineRecognizer(null, config)
    }

    override fun transcribe(
        samples: FloatArray,
        language: String,
        prompt: String?,
        onProgress: (Int) -> Unit,
    ): List<WhisperSegment>? {
        aborted = false
        val vad = options.vadModelPath
            ?: error("Parakeet needs the voice-activity model")
        val speech = WhisperLib.nativeDetectSpeech(vad, samples, options.threads)
            ?.let { SpeechAlignment.pairs(it) }
            ?: listOf(0L to samples.size / 16L)
        val pieces = SpeechAlignment.splitLong(speech, MAX_PIECE_MS)
        val out = mutableListOf<WhisperSegment>()
        pieces.forEachIndexed { i, (startMs, endMs) ->
            if (aborted) return null
            onProgress(i * 100 / pieces.size.coerceAtLeast(1))
            val from = (startMs * 16).toInt().coerceIn(0, samples.size)
            val to = (endMs * 16).toInt().coerceIn(from, samples.size)
            if (to - from < 1_600) return@forEachIndexed // < 0.1 s
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples.copyOfRange(from, to), 16_000)
                recognizer.decode(stream)
                val result = recognizer.getResult(stream)
                out += SpeechAlignment.sentencesFromTokens(
                    result.tokens.toList(), result.timestamps.toList(), startMs, endMs, result.text,
                )
            } finally {
                stream.release()
            }
        }
        onProgress(100)
        return if (aborted) null else out
    }

    override fun abort() {
        aborted = true
    }

    override fun close() {
        recognizer.release()
    }

    private companion object {
        const val MAX_PIECE_MS = 25_000L
    }
}

/** Pure helpers for timestamps (unit tested). */
object SpeechAlignment {
    fun pairs(flat: LongArray): List<Pair<Long, Long>> =
        (0 until flat.size / 2).map { flat[2 * it] to flat[2 * it + 1] }

    /**
     * Groups speech regions separated by short pauses into blocks, then moves each
     * segment into the block it overlaps most (so no sentence starts during music).
     */
    fun alignToSpeech(
        segments: List<WhisperSegment>,
        speech: List<Pair<Long, Long>>,
        maxGapMs: Long = 1_500,
    ): List<WhisperSegment> {
        if (speech.isEmpty()) return segments
        val blocks = mutableListOf<Pair<Long, Long>>()
        for ((s, e) in speech.sortedBy { it.first }) {
            val last = blocks.lastOrNull()
            if (last != null && s - last.second <= maxGapMs) blocks[blocks.lastIndex] = last.first to maxOf(last.second, e)
            else blocks += s to e
        }
        return segments.map { seg ->
            val best = blocks.maxByOrNull { (s, e) -> minOf(e, seg.endMs) - maxOf(s, seg.startMs) } ?: return@map seg
            val overlap = minOf(best.second, seg.endMs) - maxOf(best.first, seg.startMs)
            if (overlap <= 0) seg
            else seg.copy(startMs = maxOf(seg.startMs, best.first), endMs = minOf(seg.endMs, best.second))
        }
    }

    /** Splits regions longer than [maxMs] into roughly equal parts. */
    fun splitLong(regions: List<Pair<Long, Long>>, maxMs: Long): List<Pair<Long, Long>> =
        regions.flatMap { (s, e) ->
            val len = e - s
            if (len <= maxMs) listOf(s to e)
            else {
                val parts = ((len + maxMs - 1) / maxMs).toInt()
                val step = len / parts
                (0 until parts).map { i -> (s + i * step) to (if (i == parts - 1) e else s + (i + 1) * step) }
            }
        }

    private val SENTENCE_END = Regex("""[.!?…]["»”’)]*$""")

    /**
     * Builds sentence segments from recogniser tokens (text pieces; a leading space or
     * "▁" starts a new word) and their start times in seconds within the piece.
     */
    fun sentencesFromTokens(
        tokens: List<String>,
        timestamps: List<Float>,
        pieceStartMs: Long,
        pieceEndMs: Long,
        fallbackText: String,
    ): List<WhisperSegment> {
        if (tokens.isEmpty() || timestamps.size != tokens.size) {
            val text = fallbackText.trim()
            return if (text.isEmpty()) emptyList() else listOf(WhisperSegment(pieceStartMs, pieceEndMs, text))
        }
        val out = mutableListOf<WhisperSegment>()
        val sb = StringBuilder()
        var start = -1L
        for (i in tokens.indices) {
            val t = pieceStartMs + (timestamps[i] * 1000).toLong()
            if (start < 0) start = t
            sb.append(tokens[i].replace('▁', ' '))
            val text = sb.toString().trim()
            if (SENTENCE_END.containsMatchIn(text) && text.length > 1) {
                val end = if (i + 1 < tokens.size) pieceStartMs + (timestamps[i + 1] * 1000).toLong() else pieceEndMs
                out += WhisperSegment(start, end, text)
                sb.clear()
                start = -1
            }
        }
        val rest = sb.toString().trim()
        if (rest.isNotEmpty()) out += WhisperSegment(start, pieceEndMs, rest)
        return out
    }
}
