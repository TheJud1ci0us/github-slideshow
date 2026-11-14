package com.ascolta.app.whisper

import com.ascolta.app.data.Word

/** One recognised stretch of speech; times are relative to the audio passed in. */
data class WhisperSegment @JvmOverloads constructor(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<Word>? = null,
)

/** Receives progress and each sentence (with word start times) as soon as whisper.cpp has decoded it. */
interface TranscribeListener {
    fun onProgress(percent: Int)
    fun onSegment(startMs: Long, endMs: Long, text: String, words: Array<String>, wordStartsMs: LongArray)
}

/** Raw JNI bindings to whisper.cpp (see app/src/main/cpp/whisper_jni.cpp). */
object WhisperLib {
    init {
        System.loadLibrary("ascolta_whisper")
    }

    /** Loads the CPU code variant best suited to this phone. Call once before anything else. */
    @JvmStatic external fun nativeLoadBackends(libDir: String, cpuVariant: String?)
    @JvmStatic external fun nativeCpuVariant(): String
    @JvmStatic external fun nativeInit(modelPath: String, flashAttention: Boolean, useGpu: Boolean): Long

    /** GPUs available to whisper.cpp (OpenCL), or "" if none could be loaded. */
    @JvmStatic external fun nativeGpuInfo(): String
    @JvmStatic external fun nativeFree(handle: Long)
    @JvmStatic external fun nativeAbort(handle: Long)
    @JvmStatic external fun nativeSystemInfo(): String
    @JvmStatic external fun nativeTranscribe(
        handle: Long,
        samples: FloatArray,
        language: String,
        threads: Int,
        prompt: String?,
        listener: TranscribeListener?,
        vadModelPath: String?,
        fastDecoding: Boolean,
    ): Array<WhisperSegment>?
    /** Returns [start0, end0, start1, end1, …] in ms of the parts that contain speech. */
    /** [fine]: shorter pieces (0.25 s pauses, at most 10 s) for models trained on short utterances. */
    @JvmStatic external fun nativeDetectSpeech(vadModelPath: String, samples: FloatArray, threads: Int, fine: Boolean = false): LongArray?
    /** Restricts the calling thread (and threads it starts later) to these CPU cores. */
    @JvmStatic external fun nativeSetThreadAffinity(cpus: IntArray): Boolean

    @Volatile private var backendsLoaded = false

    @Synchronized
    fun ensureBackends(nativeLibDir: String) {
        if (!backendsLoaded) {
            val variant = X86Variants.choose(nativeLibDir)
            if (variant != null) com.ascolta.app.data.AppLog.i("Whisper", "x86_64 CPU variant: $variant")
            nativeLoadBackends(nativeLibDir, variant)
            backendsLoaded = true
        }
    }
}

/**
 * On x86_64 (Android Studio's emulator, Chromebooks), picks the fastest ggml CPU variant whose
 * instructions the system kernel actually enabled (/proc/cpuinfo "flags"). ggml itself only
 * asks the processor (CPUID), and in an emulator the virtual CPU can report AVX2 / AVX-512
 * that the guest can't run: the first such instruction kills the app (SIGILL). On ARM the
 * kernel's own capability list is already used, so nothing changes there.
 */
object X86Variants {
    /** ggml's x86 variants, best first, with the CPU flags each needs (kernel names). */
    private val variants = listOf(
        "sapphirerapids" to listOf("sse4_2", "avx", "f16c", "avx2", "bmi2", "fma", "avx512f", "avx512cd", "avx512vl", "avx512dq", "avx512bw", "avx512vbmi", "avx512_vnni", "avx512_bf16", "amx_tile", "amx_int8"),
        "icelake" to listOf("sse4_2", "avx", "f16c", "avx2", "bmi2", "fma", "avx512f", "avx512cd", "avx512vl", "avx512dq", "avx512bw", "avx512vbmi", "avx512_vnni"),
        "skylakex" to listOf("sse4_2", "avx", "f16c", "avx2", "bmi2", "fma", "avx512f", "avx512cd", "avx512vl", "avx512dq", "avx512bw"),
        "alderlake" to listOf("sse4_2", "avx", "f16c", "avx2", "bmi2", "fma", "avx_vnni"),
        "haswell" to listOf("sse4_2", "avx", "f16c", "avx2", "bmi2", "fma"),
        "sandybridge" to listOf("sse4_2", "avx"),
        "sse42" to listOf("sse4_2"),
        "x64" to emptyList(),
    )

    /** File name of the variant to load, or null on ARM (ggml chooses correctly there). */
    fun choose(nativeLibDir: String, flags: Set<String>? = null): String? {
        if (flags == null && android.os.Build.SUPPORTED_ABIS.firstOrNull() != "x86_64") return null
        val have = flags ?: cpuFlags()
        return variants.firstOrNull { (name, needs) ->
            have.containsAll(needs) && (flags != null || java.io.File(nativeLibDir, "libggml-cpu-$name.so").exists())
        }?.let { "libggml-cpu-${it.first}.so" }
    }

    private fun cpuFlags(): Set<String> = runCatching {
        java.io.File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("flags") }
            ?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.toSet()
    }.getOrNull() ?: emptySet()
}
