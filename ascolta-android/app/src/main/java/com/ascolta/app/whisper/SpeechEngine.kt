package com.ascolta.app.whisper

import android.content.Context
import com.ascolta.app.data.AppLog
import com.ascolta.app.data.Word
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
)

/** A loaded speech recognition model. Not thread-safe: one transcription at a time. */
interface SpeechEngine : Closeable {
    /**
     * Transcribes 16 kHz mono audio. [speech] lists the parts that contain speech (from the
     * voice-activity detector, in ms); everything else (music, silence) is skipped. With
     * null, all of the audio is processed. Each sentence is passed to [onSegment] as soon
     * as it is recognised (times relative to [samples]). Returns false if [abort] was called.
     */
    fun transcribe(
        samples: FloatArray,
        speech: List<Pair<Long, Long>>?,
        language: String,
        prompt: String?,
        onProgress: (Int) -> Unit,
        onSegment: (WhisperSegment) -> Unit,
    ): Boolean

    /** Can be called from any thread to stop the running transcription early. */
    fun abort()

    /** Whether this engine needs speech regions (it can't process a long block in one go). */
    val needsSpeechRegions: Boolean get() = false

    companion object {
        fun load(context: Context, model: SpeechModel, models: ModelManager, options: EngineOptions): SpeechEngine =
            when (model.engine) {
                Engine.WHISPER -> WhisperEngine(models.primaryPath(model).path, options)
                Engine.PARAKEET -> ParakeetEngine(models.primaryPath(model), options)
                Engine.PARAKEET_NPU -> loadNpu(context, model, models, options)
            }

        /**
         * The NPU engine. Tries the whole encoder on the NPU, then the split layout (first
         * layers on the CPU, the rest on the NPU); remembers what worked. If the NPU refuses
         * both, falls back to Parakeet on the CPU when downloaded, and disables the NPU option
         * with the reason (until the next app update).
         */
        private fun loadNpu(context: Context, model: SpeechModel, models: ModelManager, options: EngineOptions): SpeechEngine {
            NpuSupport.unavailableReason(context)?.let { throw IllegalStateException("NPU unavailable: $it") }
            ParakeetNpuEngine.prepareProcess(context)
            val dir = models.primaryPath(model)
            installSplitGraphs(context, dir)
            val layouts = listOf("full", "split").let { all ->
                NpuSupport.preferredLayout(context)?.let { pref -> listOf(pref) + (all - pref) } ?: all
            }
            val errors = mutableListOf<String>()
            for (layout in layouts) {
                try {
                    val engine = ParakeetNpuEngine(
                        dir, models.parakeetTokens(), options, npu = true, split = layout == "split", cacheDir = dir,
                    )
                    NpuSupport.rememberLayout(context, layout)
                    AppLog.i("Npu", "NPU engine ready ($layout encoder) on ${NpuSupport.chipName()}")
                    return engine
                } catch (e: Throwable) {
                    AppLog.w("Npu", "NPU engine ($layout encoder) failed to start", e)
                    errors += "$layout: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            val reason = errors.joinToString(" | ")
            NpuSupport.markFailed(context, reason)
            if (models.isDownloaded(SpeechModel.PARAKEET)) {
                AppLog.i("Npu", "Falling back to Parakeet on the CPU")
                return ParakeetEngine(models.primaryPath(SpeechModel.PARAKEET), options)
            }
            throw IllegalStateException("The NPU couldn't run this model ($reason). Choose NVIDIA Parakeet v3 (CPU) in Settings.")
        }

        /** Copies the bundled split-encoder graphs next to encoder.data (they reference its weights). */
        private fun installSplitGraphs(context: Context, dir: File) {
            val target = File(dir, "encoder_onnx").apply { mkdirs() }
            for (name in listOf("encoder_pre.onnx", "encoder_post.onnx")) {
                val out = File(target, name)
                val bytes = context.assets.open("parakeet_npu/$name").use { it.readBytes() }
                if (!out.exists() || out.length() != bytes.size.toLong()) out.writeBytes(bytes)
            }
        }
    }
}

/**
 * whisper.cpp. To skip music and silence, the speech parts are joined (with short pauses
 * between them) and every timestamp is mapped back to the original audio exactly.
 */
class WhisperEngine(modelPath: String, private val options: EngineOptions) : SpeechEngine {
    private var handle = WhisperLib.nativeInit(modelPath, options.flashAttention)

    init {
        require(handle != 0L) { "Could not load speech model (file may be corrupt): $modelPath" }
    }

    override fun transcribe(
        samples: FloatArray,
        speech: List<Pair<Long, Long>>?,
        language: String,
        prompt: String?,
        onProgress: (Int) -> Unit,
        onSegment: (WhisperSegment) -> Unit,
    ): Boolean {
        check(handle != 0L) { "Model already released" }
        val map = speech?.let { SpeechMap.build(it, samples.size / 16L) }
        if (map != null && map.isEmpty) {
            onProgress(100)
            return true // no speech in this block
        }
        val audio = map?.let { SpeechMap.joinSpeech(samples, it) } ?: samples
        val listener = object : TranscribeListener {
            override fun onProgress(percent: Int) = onProgress(percent)
            override fun onSegment(startMs: Long, endMs: Long, text: String, words: Array<String>, wordStartsMs: LongArray) {
                val start = map?.toOriginal(startMs) ?: startMs
                val end = map?.toOriginal(endMs, isEnd = true) ?: endMs
                val timed = words.indices.map { i ->
                    Word(map?.toOriginal(wordStartsMs[i]) ?: wordStartsMs[i], words[i].trim())
                }.filter { it.text.isNotEmpty() }
                onSegment(WhisperSegment(start, maxOf(start, end), text, timed.ifEmpty { null }))
            }
        }
        return WhisperLib.nativeTranscribe(handle, audio, language, options.threads, prompt, listener, null) != null
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
 * NVIDIA Parakeet TDT 0.6B v3 through sherpa-onnx (on the CPU). It works on short
 * utterances, so each stretch of speech is recognised separately and split into sentences.
 */
class ParakeetEngine(modelDir: File, private val options: EngineOptions) : SpeechEngine {
    @Volatile private var aborted = false
    private val recognizer: OfflineRecognizer

    override val needsSpeechRegions: Boolean get() = true

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
        speech: List<Pair<Long, Long>>?,
        language: String,
        prompt: String?,
        onProgress: (Int) -> Unit,
        onSegment: (WhisperSegment) -> Unit,
    ): Boolean {
        aborted = false
        val regions = speech ?: listOf(0L to samples.size / 16L)
        val pieces = SpeechAlignment.splitLong(regions, MAX_PIECE_MS)
        pieces.forEachIndexed { i, (startMs, endMs) ->
            if (aborted) return false
            onProgress(i * 100 / pieces.size.coerceAtLeast(1))
            val from = (startMs * 16).toInt().coerceIn(0, samples.size)
            val to = (endMs * 16).toInt().coerceIn(from, samples.size)
            if (to - from < 1_600) return@forEachIndexed // < 0.1 s
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples.copyOfRange(from, to), 16_000)
                recognizer.decode(stream)
                val result = recognizer.getResult(stream)
                SpeechAlignment.sentencesFromTokens(
                    result.tokens.toList(), result.timestamps.toList(), startMs, endMs, result.text,
                ).forEach(onSegment)
            } finally {
                stream.release()
            }
        }
        onProgress(100)
        return !aborted
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

/**
 * Maps between the original audio and the "speech only" audio given to whisper
 * (speech regions joined with a short pause between them).
 */
class SpeechMap private constructor(
    /** (start in joined audio, start in original, length), all in ms. */
    val parts: List<Triple<Long, Long, Long>>,
    val gapMs: Long,
) {
    val isEmpty get() = parts.isEmpty()
    val joinedLengthMs: Long get() = parts.lastOrNull()?.let { it.first + it.third } ?: 0

    /** Converts a time in the joined audio to the original audio. */
    fun toOriginal(joinedMs: Long, isEnd: Boolean = false): Long {
        if (parts.isEmpty()) return joinedMs
        var idx = parts.indexOfLast { it.first <= joinedMs }
        if (idx < 0) idx = 0
        val (joinedStart, origStart, len) = parts[idx]
        val offset = joinedMs - joinedStart
        if (offset <= len) return origStart + offset.coerceAtLeast(0)
        // Inside the pause after this part: an end belongs to this part, a start to the next.
        return if (isEnd || idx + 1 >= parts.size) origStart + len else parts[idx + 1].second
    }

    companion object {
        /** Merges regions closer than [gapMs] and builds the map. */
        fun build(regions: List<Pair<Long, Long>>, audioMs: Long, gapMs: Long = 300): SpeechMap {
            val merged = mutableListOf<Pair<Long, Long>>()
            for ((s0, e0) in regions.sortedBy { it.first }) {
                val s = s0.coerceIn(0, audioMs)
                val e = e0.coerceIn(s, audioMs)
                if (e <= s) continue
                val last = merged.lastOrNull()
                if (last != null && s - last.second <= gapMs) merged[merged.lastIndex] = last.first to maxOf(last.second, e)
                else merged += s to e
            }
            val parts = mutableListOf<Triple<Long, Long, Long>>()
            var joined = 0L
            for ((s, e) in merged) {
                parts += Triple(joined, s, e - s)
                joined += (e - s) + gapMs
            }
            return SpeechMap(parts, gapMs)
        }

        /** The speech parts of [samples] joined together, with [SpeechMap.gapMs] of silence between them. */
        fun joinSpeech(samples: FloatArray, map: SpeechMap): FloatArray {
            val gap = (map.gapMs * 16).toInt()
            val total = map.parts.sumOf { (it.third * 16).toInt() } + gap * (map.parts.size - 1).coerceAtLeast(0)
            val out = FloatArray(total.coerceAtLeast(0))
            var pos = 0
            map.parts.forEachIndexed { i, (_, origStart, len) ->
                val from = (origStart * 16).toInt().coerceIn(0, samples.size)
                val n = (len * 16).toInt().coerceAtMost(samples.size - from).coerceAtMost(out.size - pos)
                System.arraycopy(samples, from, out, pos, n)
                pos += (len * 16).toInt()
                if (i < map.parts.size - 1) pos += gap
            }
            return out
        }
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
        val blocks = speechBlocks(speech, maxGapMs)
        return segments.map { alignSegment(it, blocks) }
    }

    /** Merges speech regions separated by pauses shorter than [maxGapMs]. */
    fun speechBlocks(speech: List<Pair<Long, Long>>, maxGapMs: Long = 1_500): List<Pair<Long, Long>> {
        val blocks = mutableListOf<Pair<Long, Long>>()
        for ((s, e) in speech.sortedBy { it.first }) {
            val last = blocks.lastOrNull()
            if (last != null && s - last.second <= maxGapMs) blocks[blocks.lastIndex] = last.first to maxOf(last.second, e)
            else blocks += s to e
        }
        return blocks
    }

    /** Moves a segment into the speech block it overlaps most. */
    fun alignSegment(seg: WhisperSegment, blocks: List<Pair<Long, Long>>): WhisperSegment {
        val best = blocks.maxByOrNull { (s, e) -> minOf(e, seg.endMs) - maxOf(s, seg.startMs) } ?: return seg
        val overlap = minOf(best.second, seg.endMs) - maxOf(best.first, seg.startMs)
        return if (overlap <= 0) seg
        else seg.copy(startMs = maxOf(seg.startMs, best.first), endMs = minOf(seg.endMs, best.second))
    }

    /**
     * Groups consecutive speech regions into windows whose joined length (regions plus
     * [gapMs] between them) stays within [maxMs]; regions longer than that are split first.
     */
    fun packWindows(regions: List<Pair<Long, Long>>, maxMs: Long, gapMs: Long): List<List<Pair<Long, Long>>> {
        val windows = mutableListOf<MutableList<Pair<Long, Long>>>()
        var used = 0L
        for ((s, e) in splitLong(regions.sortedBy { it.first }, maxMs)) {
            val len = e - s
            val current = windows.lastOrNull()
            if (current != null && used + gapMs + len <= maxMs) {
                current += s to e
                used += gapMs + len
            } else {
                windows += mutableListOf(s to e)
                used = len
            }
        }
        return windows
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
     * Builds sentence segments (with word timings) from recogniser tokens (text pieces; a
     * leading space or "▁" starts a new word) and their start times in seconds within the piece.
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
        val words = mutableListOf<Word>()
        var start = -1L
        for (i in tokens.indices) {
            val t = pieceStartMs + (timestamps[i] * 1000).toLong()
            if (start < 0) start = t
            val piece = tokens[i].replace('▁', ' ')
            if (words.isEmpty() || piece.startsWith(" ")) words += Word(t, piece.trim())
            else words[words.lastIndex] = words.last().copy(text = words.last().text + piece)
            sb.append(piece)
            val text = sb.toString().trim()
            if (SENTENCE_END.containsMatchIn(text) && text.length > 1) {
                val end = if (i + 1 < tokens.size) pieceStartMs + (timestamps[i + 1] * 1000).toLong() else pieceEndMs
                out += WhisperSegment(start, end, text, words.filter { it.text.isNotEmpty() }.toList())
                sb.clear()
                words.clear()
                start = -1
            }
        }
        val rest = sb.toString().trim()
        if (rest.isNotEmpty()) out += WhisperSegment(start, pieceEndMs, rest, words.filter { it.text.isNotEmpty() }.toList())
        return out
    }
}
