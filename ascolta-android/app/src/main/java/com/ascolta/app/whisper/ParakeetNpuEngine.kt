package com.ascolta.app.whisper

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import com.ascolta.app.data.AppLog
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * The phone's Qualcomm NPU (Hexagon), used through ONNX Runtime's QNN execution provider.
 * Only Snapdragon phones have it; elsewhere the NPU model is shown but can't be selected.
 */
object NpuSupport {
    private const val PREFS = "npu"

    /** Why the NPU can't be used on this phone, or null if it looks usable. */
    fun unavailableReason(context: Context): String? {
        val failure = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("failure", null)
        if (failure != null) return failure
        if (!Build.SUPPORTED_ABIS.contains("arm64-v8a")) return "not an ARM64 phone"
        val manufacturer = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else Build.HARDWARE
        val qualcomm = manufacturer.contains("QTI", ignoreCase = true) ||
            manufacturer.contains("Qualcomm", ignoreCase = true) ||
            Build.HARDWARE.contains("qcom", ignoreCase = true)
        if (!qualcomm) return "no Qualcomm Snapdragon NPU"
        val fastRpc = listOf("/vendor/lib64/libcdsprpc.so", "/system/vendor/lib64/libcdsprpc.so").any { File(it).exists() }
        if (!fastRpc) return "the NPU driver isn't accessible to apps"
        return null
    }

    fun chipName(): String =
        if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE

    /** Remembers that the NPU failed, so the option is disabled with the reason. */
    fun markFailed(context: Context, reason: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("failure", reason.take(200)).apply()
    }

    fun clearFailure(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("failure").apply()
    }
}

/**
 * Greedy decoding for Parakeet's TDT (token-and-duration transducer), ported from
 * Qualcomm AI Hub's reference app. The small decoder (LSTM) and joint networks run step
 * by step; [Network] lets them run on any backend (and be tested on a computer).
 */
object TdtDecoder {
    const val VOCAB_SIZE = 8193 // 8192 word pieces + blank
    const val BLANK = 8192
    const val HIDDEN = 640
    const val LAYERS = 2
    private const val MAX_SYMBOLS_PER_STEP = 10

    interface Network {
        /** Returns decoder output [HIDDEN] and the new h/c states [LAYERS*HIDDEN]. */
        fun decoder(token: Int, h: FloatArray, c: FloatArray): Triple<FloatArray, FloatArray, FloatArray>
        /** Returns VOCAB_SIZE token logits followed by duration logits. */
        fun joint(encFrame: FloatArray, decOut: FloatArray): FloatArray
    }

    /** Returns (token id, encoder frame) pairs, blank excluded. */
    fun decode(encoder: Array<FloatArray>, validFrames: Int, net: Network): List<Pair<Int, Int>> {
        val tokens = mutableListOf<Pair<Int, Int>>()
        var h = FloatArray(LAYERS * HIDDEN)
        var c = FloatArray(LAYERS * HIDDEN)
        // Token 0 as start symbol, as in Qualcomm's reference (more complete than blank).
        var (decOut, h1, c1) = net.decoder(0, h, c)
        h = h1; c = c1
        var t = 0
        var symbolsAtFrame = 0
        val total = minOf(validFrames, encoder.size)
        while (t < total) {
            val logits = net.joint(encoder[t], decOut)
            val token = argmax(logits, 0, VOCAB_SIZE)
            val duration = argmax(logits, VOCAB_SIZE, logits.size) - VOCAB_SIZE
            if (token == BLANK) {
                t += maxOf(1, duration)
                symbolsAtFrame = 0
            } else {
                tokens += token to t
                val (d, nh, nc) = net.decoder(token, h, c)
                decOut = d; h = nh; c = nc
                symbolsAtFrame++
                if (duration > 0 || symbolsAtFrame >= MAX_SYMBOLS_PER_STEP) {
                    t += maxOf(1, duration)
                    symbolsAtFrame = 0
                }
            }
        }
        return tokens
    }

    private fun argmax(a: FloatArray, from: Int, to: Int): Int {
        var best = from
        for (i in from + 1 until to) if (a[i] > a[best]) best = i
        return best
    }
}

/** Reads sherpa-onnx's tokens.txt ("piece id" per line) for Parakeet v3. */
class ParakeetVocabulary(file: File) {
    private val pieces: Array<String>

    init {
        val map = HashMap<Int, String>()
        file.forEachLine { line ->
            val sep = line.lastIndexOf(' ')
            if (sep > 0) line.substring(sep + 1).toIntOrNull()?.let { map[it] = line.substring(0, sep) }
        }
        pieces = Array((map.keys.maxOrNull() ?: -1) + 1) { map[it] ?: "" }
    }

    /** Text of a token, with "▁" turned into a leading space (start of a word). */
    fun piece(id: Int): String = pieces.getOrElse(id) { "" }.replace('▁', ' ')
}

/**
 * Parakeet v3 split as in Qualcomm AI Hub: the large encoder (≈97% of the work) runs on
 * the NPU in fixed 13-second windows, while the small decoder and joint networks run on
 * the CPU step by step. Each stretch of speech (≤ 13 s) is processed separately.
 */
class ParakeetNpuEngine(
    private val modelDir: File,
    tokensFile: File,
    private val options: EngineOptions,
    private val npu: Boolean = true,
    private val cacheDir: File? = null,
) : SpeechEngine {
    @Volatile private var aborted = false
    private val env = OrtEnvironment.getEnvironment()
    private val vocab = ParakeetVocabulary(tokensFile)
    private val encoder: OrtSession
    private val decoder: OrtSession
    private val joint: OrtSession

    override val needsSpeechRegions: Boolean get() = true

    init {
        encoder = createEncoder()
        val small = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        decoder = env.createSession(File(modelDir, "decoder_onnx/decoder.onnx").path, small)
        joint = env.createSession(File(modelDir, "joint_onnx/joint.onnx").path, small)
    }

    private fun createEncoder(): OrtSession {
        val model = File(modelDir, "encoder_onnx/encoder.onnx")
        val so = OrtSession.SessionOptions()
        if (!npu) {
            so.setIntraOpNumThreads(options.threads)
            return env.createSession(model.path, so)
        }
        // Compiling the encoder for the NPU takes a while; ONNX Runtime saves the result
        // ("context binary") next to the cache so later loads are quick.
        val ctxModel = File(cacheDir ?: modelDir, "encoder_qnn_ctx.onnx")
        val useCached = ctxModel.exists() && ctxModel.length() > 0
        so.addQnn(
            mapOf(
                "backend_path" to "libQnnHtp.so",
                "htp_performance_mode" to "burst",
                "enable_htp_fp16_precision" to "1",
                "htp_graph_finalization_optimization_mode" to "3",
            ),
        )
        so.addConfigEntry("session.disable_cpu_ep_fallback", "0")
        if (!useCached) {
            so.addConfigEntry("ep.context_enable", "1")
            so.addConfigEntry("ep.context_file_path", ctxModel.path)
            so.addConfigEntry("ep.context_embed_mode", "0")
        }
        val started = System.currentTimeMillis()
        val session = env.createSession((if (useCached) ctxModel else model).path, so)
        AppLog.i("Npu", "Encoder ready on NPU in ${System.currentTimeMillis() - started} ms (cached=$useCached)")
        return session
    }

    private val network = object : TdtDecoder.Network {
        override fun decoder(token: Int, h: FloatArray, c: FloatArray): Triple<FloatArray, FloatArray, FloatArray> {
            OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(token)), longArrayOf(1, 1)).use { tok ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(h), longArrayOf(2, 1, 640)).use { hIn ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(c), longArrayOf(2, 1, 640)).use { cIn ->
                        decoder.run(mapOf("token_id" to tok, "h_in" to hIn, "c_in" to cIn)).use { r ->
                            return Triple(r.floats("decoder_out"), r.floats("h_out"), r.floats("c_out"))
                        }
                    }
                }
            }
        }

        override fun joint(encFrame: FloatArray, decOut: FloatArray): FloatArray {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(encFrame), longArrayOf(1, 1, 640)).use { e ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(decOut), longArrayOf(1, 1, 640)).use { d ->
                    joint.run(mapOf("enc_frame" to e, "dec_out" to d)).use { r -> return r.floats("logits") }
                }
            }
        }
    }

    private fun OrtSession.Result.floats(name: String): FloatArray {
        val tensor = get(name).get() as OnnxTensor
        val buf = tensor.floatBuffer
        return FloatArray(buf.remaining()).also { buf.get(it) }
    }

    /** Recognises one piece of ≤ 13 s; returns sentences with times relative to the piece. */
    fun recognizePiece(audio: FloatArray): List<WhisperSegment> {
        val maxSamples = (WINDOW_FRAMES - 1) * ParakeetFeatures.HOP
        val clipped = if (audio.size > maxSamples) audio.copyOf(maxSamples) else audio
        val feats = ParakeetFeatures.compute(clipped)
        val input = FloatArray(WINDOW_FRAMES * ParakeetFeatures.N_MELS)
        for (t in 0 until minOf(feats.features.size, WINDOW_FRAMES)) {
            System.arraycopy(feats.features[t], 0, input, t * ParakeetFeatures.N_MELS, ParakeetFeatures.N_MELS)
        }
        // All frames marked valid: this export's attention turns into NaN when part of the
        // window is masked out (checked against the model). The padding is zero (silence after
        // normalisation) and only the real frames are decoded below.
        val mask = IntArray(WINDOW_FRAMES) { 1 }
        val enc: Array<FloatArray>
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, WINDOW_FRAMES.toLong(), ParakeetFeatures.N_MELS.toLong())).use { f ->
            OnnxTensor.createTensor(env, IntBuffer.wrap(mask), longArrayOf(1, WINDOW_FRAMES.toLong())).use { m ->
                encoder.run(mapOf("input_features" to f, "attention_mask" to m)).use { r ->
                    val flat = r.floats("encoder_out")
                    val frames = flat.size / TdtDecoder.HIDDEN
                    enc = Array(frames) { i -> flat.copyOfRange(i * TdtDecoder.HIDDEN, (i + 1) * TdtDecoder.HIDDEN) }
                }
            }
        }
        val tokens = TdtDecoder.decode(enc, feats.validFrames / SUBSAMPLING, network)
        val pieces = tokens.map { vocab.piece(it.first) }
        val times = tokens.map { it.second * FRAME_SECONDS }
        return SpeechAlignment.sentencesFromTokens(pieces, times, 0, clipped.size / 16L, pieces.joinToString(""))
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
        val audioMs = samples.size / 16L
        val regions = speech ?: listOf(0L to audioMs)
        // The encoder always processes a full 13-second window, so consecutive stretches of
        // speech are packed together (with short pauses between them) to fill each window;
        // times are mapped back exactly afterwards.
        val windows = SpeechAlignment.packWindows(regions, MAX_WINDOW_MS, GAP_MS)
        windows.forEachIndexed { i, window ->
            if (aborted) return false
            onProgress(i * 100 / windows.size.coerceAtLeast(1))
            val map = SpeechMap.build(window, audioMs, GAP_MS)
            if (map.isEmpty || map.joinedLengthMs < 100) return@forEachIndexed
            recognizePiece(SpeechMap.joinSpeech(samples, map)).forEach { seg ->
                val start = map.toOriginal(seg.startMs)
                onSegment(
                    seg.copy(
                        startMs = start,
                        endMs = maxOf(start, map.toOriginal(seg.endMs, isEnd = true)),
                        words = seg.words?.map { it.copy(startMs = map.toOriginal(it.startMs)) },
                    ),
                )
            }
        }
        onProgress(100)
        return !aborted
    }

    override fun abort() {
        aborted = true
    }

    override fun close() {
        runCatching { encoder.close() }
        runCatching { decoder.close() }
        runCatching { joint.close() }
    }

    companion object {
        /** Static encoder window of Qualcomm's export: 1320 feature frames ≈ 13.2 s. */
        const val WINDOW_FRAMES = 1320
        private const val SUBSAMPLING = 8
        /** One encoder frame = 8 feature frames × 10 ms. */
        private const val FRAME_SECONDS = 0.08f
        /** Speech packed per window (≤ 13.19 s fits the encoder's 1320 frames). */
        private const val MAX_WINDOW_MS = 13_000L
        private const val GAP_MS = 300L

        /**
         * Prepares the process for the NPU: ONNX Runtime must use its own (QNN-enabled)
         * native library rather than sherpa-onnx's, and the NPU driver needs to know where
         * the app's Hexagon libraries are.
         */
        fun prepareProcess(context: Context) {
            System.setProperty("onnxruntime.native.onnxruntime.skip", "true")
            val libDir = context.applicationInfo.nativeLibraryDir
            runCatching {
                android.system.Os.setenv(
                    "ADSP_LIBRARY_PATH",
                    "$libDir;/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/vendor/dsp;/system/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/dsp",
                    true,
                )
            }
        }
    }
}
