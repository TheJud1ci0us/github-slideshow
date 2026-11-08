package com.ascolta.app.whisper

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Log-mel features exactly as NVIDIA Parakeet expects them (a port of Hugging Face's
 * ParakeetFeatureExtractor): pre-emphasis 0.97, 512-point STFT every 10 ms with a 25 ms
 * Hann window, power spectrum, 128 Slaney mel bands, log(x + 2^-24), then per-band
 * normalisation over the valid frames.
 */
object ParakeetFeatures {
    const val SAMPLE_RATE = 16_000
    const val N_FFT = 512
    const val HOP = 160
    const val WIN = 400
    const val N_MELS = 128
    private const val PREEMPHASIS = 0.97f
    private const val LOG_GUARD = 5.9604645e-8f // 2^-24
    private const val EPSILON = 1e-5f
    private const val BINS = N_FFT / 2 + 1

    /** Symmetric Hann window (torch periodic=False), centred in the 512-sample FFT frame. */
    private val window: FloatArray = FloatArray(N_FFT).also { w ->
        val offset = (N_FFT - WIN) / 2
        for (n in 0 until WIN) w[offset + n] = (0.5 - 0.5 * cos(2.0 * PI * n / (WIN - 1))).toFloat()
    }

    /** Slaney-style mel filter bank (librosa.filters.mel with norm="slaney", htk=False). */
    val melFilters: Array<FloatArray> = slaneyMel(N_MELS, N_FFT, SAMPLE_RATE, 0.0, SAMPLE_RATE / 2.0)

    class Result(
        /** [frames][N_MELS] */
        val features: Array<FloatArray>,
        /** Frames that hold real audio (the rest are zero, masked out). */
        val validFrames: Int,
    )

    fun compute(audio: FloatArray): Result {
        val n = audio.size
        // Pre-emphasis.
        val x = FloatArray(n)
        if (n > 0) x[0] = audio[0]
        for (i in 1 until n) x[i] = audio[i] - PREEMPHASIS * audio[i - 1]

        // torch.stft(center=True, pad_mode="constant"): N_FFT/2 zeros on each side.
        val pad = N_FFT / 2
        val frames = 1 + n / HOP
        val valid = n / HOP
        val out = Array(frames) { FloatArray(N_MELS) }
        val re = DoubleArray(N_FFT)
        val im = DoubleArray(N_FFT)
        val power = DoubleArray(BINS)
        for (t in 0 until frames) {
            val start = t * HOP - pad
            for (k in 0 until N_FFT) {
                val idx = start + k
                re[k] = if (idx in 0 until n) (x[idx] * window[k]).toDouble() else 0.0
                im[k] = 0.0
            }
            fft(re, im)
            for (b in 0 until BINS) power[b] = re[b] * re[b] + im[b] * im[b]
            val row = out[t]
            for (m in 0 until N_MELS) {
                val f = melFilters[m]
                var acc = 0.0
                for (b in 0 until BINS) acc += f[b] * power[b]
                row[m] = ln(acc + LOG_GUARD).toFloat()
            }
        }

        // Per-mel-band normalisation over the valid frames (std with n-1, like torch).
        if (valid > 1) {
            for (m in 0 until N_MELS) {
                var sum = 0.0
                for (t in 0 until valid) sum += out[t][m]
                val mean = sum / valid
                var sq = 0.0
                for (t in 0 until valid) {
                    val d = out[t][m] - mean
                    sq += d * d
                }
                val std = sqrt(sq / (valid - 1))
                for (t in 0 until frames) {
                    out[t][m] = if (t < valid) ((out[t][m] - mean) / (std + EPSILON)).toFloat() else 0f
                }
            }
        } else {
            for (row in out) row.fill(0f)
        }
        return Result(out, valid)
    }

    /** In-place iterative radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun hzToMel(f: Double): Double {
        val fSp = 200.0 / 3
        val minLogHz = 1000.0
        val minLogMel = minLogHz / fSp
        val logStep = ln(6.4) / 27.0
        return if (f >= minLogHz) minLogMel + ln(f / minLogHz) / logStep else f / fSp
    }

    private fun melToHz(m: Double): Double {
        val fSp = 200.0 / 3
        val minLogHz = 1000.0
        val minLogMel = minLogHz / fSp
        val logStep = ln(6.4) / 27.0
        return if (m >= minLogMel) minLogHz * exp(logStep * (m - minLogMel)) else fSp * m
    }

    fun slaneyMel(nMels: Int, nFft: Int, sr: Int, fMin: Double, fMax: Double): Array<FloatArray> {
        val bins = nFft / 2 + 1
        val fftFreqs = DoubleArray(bins) { it * (sr / 2.0) / (bins - 1) }
        val mMin = hzToMel(fMin)
        val mMax = hzToMel(fMax)
        val melF = DoubleArray(nMels + 2) { melToHz(mMin + (mMax - mMin) * it / (nMels + 1)) }
        return Array(nMels) { i ->
            val lowerW = melF[i + 1] - melF[i]
            val upperW = melF[i + 2] - melF[i + 1]
            val enorm = 2.0 / (melF[i + 2] - melF[i])
            FloatArray(bins) { b ->
                val lower = (fftFreqs[b] - melF[i]) / lowerW
                val upper = (melF[i + 2] - fftFreqs[b]) / upperW
                (max(0.0, min(lower, upper)) * enorm).toFloat()
            }
        }
    }
}
