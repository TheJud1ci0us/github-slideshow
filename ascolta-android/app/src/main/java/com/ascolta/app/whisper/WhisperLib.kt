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
    @JvmStatic external fun nativeLoadBackends(libDir: String)
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
    ): Array<WhisperSegment>?
    /** Returns [start0, end0, start1, end1, …] in ms of the parts that contain speech. */
    /** [fine]: shorter pieces (0.25 s pauses, at most 10 s) for models trained on short utterances. */
    @JvmStatic external fun nativeDetectSpeech(vadModelPath: String, samples: FloatArray, threads: Int, fine: Boolean = false): LongArray?
    /** Restricts the calling thread (and threads it starts later) to these CPU cores. */
    @JvmStatic external fun nativeSetThreadAffinity(cpus: IntArray): Boolean

    @Volatile private var backendsLoaded = false

    fun ensureBackends(nativeLibDir: String) {
        if (!backendsLoaded) {
            nativeLoadBackends(nativeLibDir)
            backendsLoaded = true
        }
    }
}
