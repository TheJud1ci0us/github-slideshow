package com.ascolta.app.whisper

import android.content.Context
import com.ascolta.app.data.AppLog
import com.ascolta.app.data.Word
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.Closeable
import java.io.File

/** Settings that affect how fast recognition runs. */
data class EngineOptions(
    val threads: Int,
    val flashAttention: Boolean,
    /** Language of the audio, when the engine is set up for one language (SenseVoice). */
    val language: String? = null,
    /** Run Whisper on the graphics chip when possible (experimental). */
    val gpu: Boolean = false,
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

    /**
     * Names to expect in this episode (guests, places…), for engines that take word hints
     * (Parakeet). Whisper gets them in its prompt instead.
     */
    fun setNames(names: List<String>) {}

    /** Where the speech detector found speech in the next block (the rest are gaps checked anyway). */
    fun setDetectedSpeech(regions: List<Pair<Long, Long>>?) {}

    /** Whether this engine needs speech regions (it can't process a long block in one go). */
    val needsSpeechRegions: Boolean get() = false

    companion object {
        fun load(context: Context, model: SpeechModel, models: ModelManager, options: EngineOptions): SpeechEngine =
            when (model.engine) {
                Engine.WHISPER -> WhisperEngine(models.primaryPath(model).path, options)
                Engine.PARAKEET, Engine.SENSEVOICE, Engine.REAZONSPEECH, Engine.OMNILINGUAL -> SherpaEngine(model, models.primaryPath(model), options)
            }

    }
}

/**
 * whisper.cpp. To skip music and silence, the speech parts are joined (with short pauses
 * between them) and every timestamp is mapped back to the original audio exactly.
 */
class WhisperEngine(modelPath: String, private val options: EngineOptions) : SpeechEngine {
    // On the GPU, flash attention isn't supported by the OpenCL backend (it would run on the CPU).
    private var handle = WhisperLib.nativeInit(modelPath, options.flashAttention && !options.gpu, options.gpu).also {
        if (options.gpu) com.ascolta.app.data.AppLog.i("Whisper", "GPU requested; available: ${runCatching { WhisperLib.nativeGpuInfo() }.getOrDefault("?").ifEmpty { "none (CPU only)" }}")
    }

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
                // Timed tokens → real words (dictionary-based for Japanese, Chinese, Thai…).
                val pieces = words.indices.map { i -> Word(map?.toOriginal(wordStartsMs[i]) ?: wordStartsMs[i], words[i]) }
                val sentence = text.trim()
                val timed = com.ascolta.app.data.WordSplitter.timedWords(sentence, pieces, start, maxOf(start, end), language)
                onSegment(WhisperSegment(start, maxOf(start, end), sentence, timed))
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
/**
 * Speech models run by sherpa-onnx: NVIDIA Parakeet (transducer), SenseVoice (Japanese,
 * Chinese, Korean, English; non-autoregressive, very light) and ReazonSpeech (Japanese
 * Zipformer transducer). They recognise each speech region in one pass and give a time for
 * every token, from which sentences and words are built.
 */
class SherpaEngine(model: SpeechModel, modelDir: File, private val options: EngineOptions) : SpeechEngine {
    @Volatile private var aborted = false
    private val recognizer: OfflineRecognizer
    private val language = options.language
    private val engine = model.engine
    /**
     * Names as hint words need beam search, which in sherpa-onnx ignores Parakeet's timing
     * predictions (TDT) and left stretches untranscribed (1.1.0). Parakeet stays on greedy
     * decoding; names are corrected after recognition instead (Names.correct).
     */
    private val hints = false
    /** Where the detector found speech in the current block (the rest are gaps checked anyway). */
    @Volatile private var detected: List<Pair<Long, Long>>? = null
    @Volatile private var names = ""

    override val needsSpeechRegions: Boolean get() = true

    override fun setNames(names: List<String>) {
        this.names = com.ascolta.app.data.Names.hotwords(names)
    }

    override fun setDetectedSpeech(regions: List<Pair<Long, Long>>?) {
        detected = regions
    }


    init {
        val modelConfig = when (model.engine) {
            Engine.SENSEVOICE -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = File(modelDir, "model.int8.onnx").path,
                    // A fixed language is more accurate than detecting it for every sentence.
                    language = options.language?.takeIf { it in setOf("zh", "en", "ja", "ko") } ?: "auto",
                    useInverseTextNormalization = true, // punctuation and numbers as written
                ),
                tokens = File(modelDir, "tokens.txt").path,
                numThreads = options.threads,
            )
            Engine.REAZONSPEECH -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(modelDir, "encoder-epoch-99-avg-1.int8.onnx").path,
                    decoder = File(modelDir, "decoder-epoch-99-avg-1.int8.onnx").path,
                    joiner = File(modelDir, "joiner-epoch-99-avg-1.int8.onnx").path,
                ),
                tokens = File(modelDir, "tokens.txt").path,
                numThreads = options.threads,
                modelType = "transducer",
            )
            Engine.OMNILINGUAL -> OfflineModelConfig(
                omnilingual = OfflineOmnilingualAsrCtcModelConfig(model = File(modelDir, "model.int8.onnx").path),
                tokens = File(modelDir, "tokens.txt").path,
                numThreads = options.threads,
            )
            else -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(modelDir, "encoder.int8.onnx").path,
                    decoder = File(modelDir, "decoder.int8.onnx").path,
                    joiner = File(modelDir, "joiner.int8.onnx").path,
                ),
                tokens = File(modelDir, "tokens.txt").path,
                numThreads = options.threads,
                modelType = "nemo_transducer",
            )
        }
        // ReazonSpeech gives no punctuation: its sentences are found with the Japanese dictionary.
        if (engine == Engine.REAZONSPEECH) com.ascolta.app.data.Japanese.loadBlocking()
        recognizer = OfflineRecognizer(
            null,
            OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
                modelConfig = modelConfig,
                // Parakeet searches a few candidates so the names given as hints can win
                // (off for Parakeet: see `hints`).
                decodingMethod = if (hints) "modified_beam_search" else "greedy_search",
                maxActivePaths = 4,
                hotwordsFile = if (hints) hotwordsFile(modelDir).path else "",
                hotwordsScore = 1.5f,
            ),
        )
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
        // ReazonSpeech drops words on long input (it was trained on short utterances).
        val pieces = SpeechAlignment.splitLong(regions, if (engine.cutsAtPauses) REAZON_MAX_PIECE_MS else MAX_PIECE_MS)
        // A piece of detected speech that comes back empty is logged and decoded again in two
        // halves (short input is where these models are most reliable).
        val queue = ArrayDeque(pieces.map { Triple(it.first, it.second, true) })
        var done = 0
        while (queue.isNotEmpty()) {
            val (startMs, endMs, mayRetry) = queue.removeFirst()
            if (aborted) return false
            onProgress(done++ * 100 / (done + queue.size).coerceAtLeast(1))
            val from = (startMs * 16).toInt().coerceIn(0, samples.size)
            val to = (endMs * 16).toInt().coerceIn(from, samples.size)
            if (to - from < 1_600) continue // < 0.1 s
            val stream = if (hints && names.isNotEmpty()) recognizer.createStream(names) else recognizer.createStream()
            try {
                stream.acceptWaveform(samples.copyOfRange(from, to), 16_000)
                recognizer.decode(stream)
                val result = recognizer.getResult(stream)
                // SenseVoice may report tags such as <|ja|><|NEUTRAL|>; they aren't words.
                val keep = result.tokens.indices.filter { !TAG.matches(result.tokens[it]) }
                val tokens = keep.map { result.tokens[it] }
                val times = if (result.timestamps.size == result.tokens.size) keep.map { result.timestamps[it] } else emptyList()
                if (tokens.none { it.isNotBlank() } && endMs - startMs >= 3_000 && isDetectedSpeech(startMs, endMs)) {
                    AppLog.w("Speech", "${engine.name} returned nothing for ${startMs / 1000}-${endMs / 1000}s of speech (block-relative)" +
                        if (mayRetry) "; retrying in two halves" else "")
                    if (mayRetry) {
                        val mid = (startMs + endMs) / 2
                        queue.addFirst(Triple(mid, endMs, false))
                        queue.addFirst(Triple(startMs, mid, false))
                    }
                    continue
                }
                val sentences = if (engine == Engine.REAZONSPEECH && tokens.isNotEmpty() && times.size == tokens.size) {
                    reazonSentences(tokens, times, startMs, endMs)
                } else {
                    SpeechAlignment.sentencesFromTokens(tokens, times, startMs, endMs, TAG.replace(result.text, ""))
                }
                sentences.forEach { seg ->
                    // Real words (also in Japanese / Chinese), timed from the tokens.
                    val words = com.ascolta.app.data.WordSplitter.timedWords(seg.text, seg.words, seg.startMs, seg.endMs, this.language ?: language)
                    onSegment(seg.copy(words = words))
                }
            } finally {
                stream.release()
            }
        }
        onProgress(100)
        return !aborted
    }

    /** Mostly inside what the detector marked as speech (not a gap checked just in case). */
    private fun isDetectedSpeech(startMs: Long, endMs: Long): Boolean {
        val regions = detected ?: return true
        val inside = regions.sumOf { (s, e) -> (minOf(e, endMs) - maxOf(s, startMs)).coerceAtLeast(0) }
        return inside * 2 >= endMs - startMs
    }

    override fun abort() {
        aborted = true
    }

    override fun close() {
        recognizer.release()
    }

    /**
     * ReazonSpeech: its token times run about 0.3 s early (measured against SenseVoice and the
     * audio's onsets) and the first token is always at 0, so they are corrected; sentences are
     * then found from the grammar (JapaneseSentences), with 。 / 、 added.
     */
    private fun reazonSentences(tokens: List<String>, times: List<Float>, startMs: Long, endMs: Long): List<WhisperSegment> {
        val ms = times.map { (startMs + it * 1000).toLong() + REAZON_SHIFT_MS }.toMutableList()
        if (ms.size >= 2) ms[0] = maxOf(ms[0], ms[1] - 150)
        val fixed = ms.map { it.coerceIn(startMs, maxOf(startMs, endMs - 1)) }
        return com.ascolta.app.data.JapaneseSentences.build(tokens, fixed, endMs)
    }

    private companion object {
        const val MAX_PIECE_MS = 25_000L
        const val REAZON_MAX_PIECE_MS = 12_000L
        const val REAZON_SHIFT_MS = 300L
        val TAG = Regex("""<\|[^|]*\|>""")

        /** Parakeet's word pieces in the form sherpa-onnx reads to spell hints (fewest pieces win). */
        fun bpeVocab(dir: File): File = File(dir, "bpe.vocab").also { out ->
            if (out.exists() && out.length() > 0) return@also
            val tmp = File(dir, "bpe.vocab.tmp")
            tmp.bufferedWriter().use { w ->
                File(dir, "tokens.txt").forEachLine { line ->
                    val token = line.substringBeforeLast(' ')
                    if (token.isNotEmpty()) w.append(token).append("\t-1\n")
                }
            }
            tmp.renameTo(out)
        }

        /** sherpa-onnx needs a hints file to enable hints; each episode then gives its own names. */
        fun hotwordsFile(dir: File): File = File(dir, "hotwords.txt").also { if (!it.exists()) it.writeText("LingoPod\n") }
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

    private fun isUnspacedScript(c: Char): Boolean = when (Character.UnicodeScript.of(c.code)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.THAI, Character.UnicodeScript.LAO, Character.UnicodeScript.KHMER, Character.UnicodeScript.MYANMAR -> true
        else -> false
    }

    private val SENTENCE_END = Regex("""[.!?…。！？؟۔।॥։՞]["»”’)」』]*$""")
    /** Engines without punctuation (e.g. ReazonSpeech): a pause this long ends a sentence. */
    private const val PAUSE_SPLIT_MS = 700L

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
            // A new timed piece at a space, or at every token of a script written without
            // spaces (Japanese, Chinese, Thai), so each keeps its own time.
            if (words.isEmpty() || piece.startsWith(" ") || piece.firstOrNull()?.let(::isUnspacedScript) == true) words += Word(t, piece.trim())
            else words[words.lastIndex] = words.last().copy(text = words.last().text + piece)
            sb.append(piece)
            val text = sb.toString().trim()
            val nextGapMs = if (i + 1 < tokens.size) ((timestamps[i + 1] - timestamps[i]) * 1000).toLong() else 0L
            val pauseEnd = nextGapMs >= PAUSE_SPLIT_MS && text.length >= 8
            if ((SENTENCE_END.containsMatchIn(text) || pauseEnd) && text.length > 1) {
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
