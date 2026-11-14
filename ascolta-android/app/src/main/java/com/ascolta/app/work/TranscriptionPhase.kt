package com.ascolta.app.work

/** Ephemeral worker progress: no transcript or database schema changes. */
enum class TranscriptionPhase {
    WAITING, DOWNLOADING, LOADING, PROCESSING, READY_AHEAD, PAUSED, TRANSLATING, FAILED;
    companion object {
        const val KEY_PHASE = "transcription_phase"
        const val KEY_BUFFER_MS = "transcription_buffer_ms"
        fun from(value: String?): TranscriptionPhase = entries.firstOrNull { it.name == value } ?: WAITING
    }
}
