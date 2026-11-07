package com.ascolta.app.whisper

import java.io.Closeable

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

    @JvmStatic external fun nativeInit(modelPath: String): Long
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
    ): Array<WhisperSegment>?
}

/** A loaded speech model. Not thread-safe: use one transcription at a time. */
class WhisperContext private constructor(private var handle: Long) : Closeable {

    /** Returns null if the transcription was aborted. */
    fun transcribe(
        samples: FloatArray,
        language: String,
        prompt: String?,
        threads: Int = defaultThreads(),
        onProgress: ProgressListener? = null,
    ): List<WhisperSegment>? {
        check(handle != 0L) { "Model already released" }
        return WhisperLib.nativeTranscribe(handle, samples, language, threads, prompt, onProgress)?.toList()
    }

    /** Can be called from any thread to stop the running transcription early. */
    fun abort() {
        if (handle != 0L) WhisperLib.nativeAbort(handle)
    }

    override fun close() {
        if (handle != 0L) {
            WhisperLib.nativeFree(handle)
            handle = 0
        }
    }

    companion object {
        fun load(modelPath: String): WhisperContext {
            val handle = WhisperLib.nativeInit(modelPath)
            require(handle != 0L) { "Could not load speech model (file may be corrupt): $modelPath" }
            return WhisperContext(handle)
        }

        /** Phones have a few fast cores; more threads than that usually slows whisper down. */
        fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    }
}
