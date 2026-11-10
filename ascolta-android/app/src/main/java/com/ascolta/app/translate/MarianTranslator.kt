package com.ascolta.app.translate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * A Helsinki-NLP Opus-MT model (e.g. Armenian → English) running on the phone with ONNX
 * Runtime: the encoder reads the sentence once, then the decoder writes the translation a
 * word piece at a time (greedy), reusing what it computed for the earlier pieces.
 */
class MarianTranslator(dir: File, threads: Int = 2) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val config = JSONObject(File(dir, "config.json").readText())
    private val eos = config.getInt("eos_token_id")
    private val pad = config.getInt("pad_token_id")
    private val start = config.optInt("decoder_start_token_id", pad)
    private val heads = config.getInt("decoder_attention_heads")
    private val headDim = config.getInt("d_model") / heads
    private val tokenizer = MarianTokenizer.load(File(dir, "tokenizer.json"), eos, pad)
    private val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) }
    private val encoder = env.createSession(File(dir, "encoder_model_quantized.onnx").path, options)
    private val decoder = env.createSession(File(dir, "decoder_model_merged_quantized.onnx").path, options)
    private val pastNames = decoder.inputNames.filter { it.startsWith("past_key_values") }

    @Synchronized
    fun translate(text: String): String {
        if (text.isBlank()) return text
        val ids = tokenizer.encode(text).take(MAX_INPUT).let { if (it.last() != eos) it + eos else it }
        val n = ids.size.toLong()
        val inputIds = OnnxTensor.createTensor(env, LongBuffer.wrap(ids.map { it.toLong() }.toLongArray()), longArrayOf(1, n))
        val mask = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { 1 }), longArrayOf(1, n))
        val opened = mutableListOf<AutoCloseable>(inputIds, mask)
        // The last step's results (its caches feed the next step); closed when replaced.
        var previous: OrtSession.Result? = null
        try {
            val encoded = encoder.run(mapOf("input_ids" to inputIds, "attention_mask" to mask)).also { opened += it }
            val hidden = encoded[0] as OnnxTensor
            // No earlier pieces yet: empty caches.
            val past = HashMap<String, OnnxTensor>()
            for (name in pastNames) {
                past[name] = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, heads.toLong(), 0, headDim.toLong()))
                    .also { opened += it }
            }
            val out = ArrayList<Int>()
            var last = start
            var useCache = false
            repeat(ids.size * 2 + 10) {
                val step = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(last.toLong())), longArrayOf(1, 1))
                val branch = OnnxTensor.createTensor(env, booleanArrayOf(useCache))
                val feeds = HashMap<String, OnnxTensor>(past)
                feeds["input_ids"] = step
                feeds["encoder_attention_mask"] = mask
                feeds["encoder_hidden_states"] = hidden
                feeds["use_cache_branch"] = branch
                val result = decoder.run(feeds)
                step.close()
                branch.close()
                val logits = (result[0] as OnnxTensor).floatBuffer
                var best = -1
                var bestScore = Float.NEGATIVE_INFINITY
                for (i in 0 until logits.remaining()) {
                    if (i == pad) continue
                    val v = logits.get(i)
                    if (v > bestScore) { bestScore = v; best = i }
                }
                // The new caches; the encoder's part is computed only on the first step and kept.
                for ((name, value) in result) {
                    if (!name.startsWith("present")) continue
                    val key = name.replace("present", "past_key_values")
                    if (useCache && ".encoder." in key) continue
                    past[key] = value as OnnxTensor
                }
                if (!useCache) opened += result else previous?.close()
                previous = if (useCache) result else null
                useCache = true
                if (best == eos || best < 0) return tokenizer.decode(out.toIntArray())
                out += best
                last = best
            }
            return tokenizer.decode(out.toIntArray())
        } finally {
            runCatching { previous?.close() }
            opened.asReversed().forEach { runCatching { it.close() } }
        }
    }

    override fun close() {
        runCatching { encoder.close() }
        runCatching { decoder.close() }
    }

    private companion object {
        const val MAX_INPUT = 400
    }
}
