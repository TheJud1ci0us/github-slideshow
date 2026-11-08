package com.ascolta.app.whisper

/** One recognised stretch of speech; times are relative to the audio passed in. */
data class WhisperSegment(val startMs: Long, val endMs: Long, val text: String)

fun interface ProgressListener {
    fun onProgress(percent: Int)
}

/** Raw JNI bindings to whisper.cpp (see app/src/main/cpp/whisper_jni.cpp). */
object WhisperLib {
    init {
        System.loadLibrary("ascolta_whisper")
    }

    /** Loads the CPU code variant best suited to this phone. Call once before anything else. */
    @JvmStatic external fun nativeLoadBackends(libDir: String)
    @JvmStatic external fun nativeCpuVariant(): String
    @JvmStatic external fun nativeInit(modelPath: String, flashAttention: Boolean): Long
    @JvmStatic external fun nativeFree(handle: Long)
    @JvmStatic external fun nativeAbort(handle: Long)
    @JvmStatic external fun nativeSystemInfo(): String
    @JvmStatic external fun nativeTranscribe(
        handle: Long,
        samples: FloatArray,
        language: String,
        threads: Int,
        prompt: String?,
        listener: ProgressListener?,
        vadModelPath: String?,
    ): Array<WhisperSegment>?
    /** Returns [start0, end0, start1, end1, …] in ms of the parts that contain speech. */
    @JvmStatic external fun nativeDetectSpeech(vadModelPath: String, samples: FloatArray, threads: Int): LongArray?

    @Volatile private var backendsLoaded = false

    fun ensureBackends(nativeLibDir: String) {
        if (!backendsLoaded) {
            nativeLoadBackends(nativeLibDir)
            backendsLoaded = true
        }
    }
}
