package com.ascolta.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ascolta.app.R
import com.ascolta.app.data.ShadowCheck
import com.ascolta.app.graph
import com.ascolta.app.whisper.EngineLock
import com.ascolta.app.whisper.EngineOptions
import com.ascolta.app.whisper.Recorder
import com.ascolta.app.whisper.SpeechEngine
import com.ascolta.app.whisper.Wav
import com.ascolta.app.whisper.WhisperLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Shadowing: hear the speaker say [text] (at normal or slower speed, looped if wanted), record
 * yourself, compare the two, and optionally let the speech model check which words it heard.
 *
 * [loadOriginal] gives the clip of the speaker (a WAV cut from the episode), or null when the
 * episode isn't downloaded. [onKeepRecording], when given, saves your recording with the card.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ShadowSheet(
    text: String,
    translation: String?,
    language: String,
    loadOriginal: suspend () -> String?,
    onKeepRecording: ((String) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    var original by remember { mutableStateOf<String?>(null) }
    var originalMissing by remember { mutableStateOf(false) }
    val mine = remember { File(context.filesDir, "shadow/${System.currentTimeMillis()}.wav") }
    var hasMine by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var loop by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf<String?>(null) } // "original", "mine", "compare"
    var check by remember { mutableStateOf<ShadowCheck.Result?>(null) }
    var checking by remember { mutableStateOf(false) }
    var checkError by remember { mutableStateOf<String?>(null) }
    val recorder = remember { Recorder() }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    LaunchedEffect(Unit) {
        original = runCatching { loadOriginal() }.getOrNull()
        originalMissing = original == null
    }
    val originalPeaks = remember(original) { original?.let { Wav.peaks(Wav.read(it), 60) } }
    var minePeaks by remember { mutableStateOf<FloatArray?>(null) }
    val originalMs = remember(original) { original?.let { Wav.read(it).size / 16L } ?: 4_000L }

    fun stopPlaying() {
        player?.runCatching { stop(); release() }
        player = null
        playing = null
    }

    fun play(path: String, rate: Float, tag: String, then: (() -> Unit)? = null) {
        stopPlaying()
        player = runCatching {
            MediaPlayer().apply {
                setDataSource(path)
                prepare()
                // Slower without lowering the voice.
                if (rate != 1f) playbackParams = PlaybackParams().setSpeed(rate).setPitch(1f)
                setOnCompletionListener {
                    when {
                        then != null -> then()
                        loop && tag == "original" -> scope.launch { delay(600); play(path, rate, tag) }
                        else -> stopPlaying()
                    }
                }
                start()
            }
        }.getOrNull()
        playing = if (player != null) tag else null
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            recorder.start(mine, originalMs + 2_000)
            recording = true
        }
    }

    fun toggleRecord() {
        if (recording) {
            recorder.stop()
            recording = false
            hasMine = mine.exists() && mine.length() > 44
            minePeaks = Wav.peaks(Wav.read(mine.path), 60)
            check = null
            return
        }
        stopPlaying()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            recorder.start(mine, originalMs + 2_000)
            recording = true
        } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    // The recorder stops by itself after the sentence's length + 2 s.
    LaunchedEffect(recording) {
        while (recording) {
            delay(200)
            if (!recorder.isRecording) {
                recording = false
                hasMine = mine.exists() && mine.length() > 44
                minePeaks = Wav.peaks(Wav.read(mine.path), 60)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recorder.stop()
            player?.runCatching { stop(); release() }
        }
    }

    /** Transcribes the recording with this language's speech model and compares the words. */
    fun checkWhatISaid() {
        checking = true
        checkError = null
        scope.launch {
            val heard = withContext(Dispatchers.Default) {
                runCatching {
                    val samples = Wav.read(mine.path)
                    val model = graph.settings.modelFor(language)
                    if (!graph.models.isDownloaded(model)) error(context.getString(R.string.shadow_need_model, model.label))
                    // Waits (up to 30 s) if an episode is being transcribed.
                    withTimeoutOrNull(30_000) {
                        EngineLock.mutex.withLock {
                            WhisperLib.ensureBackends(context.applicationInfo.nativeLibraryDir)
                            val engine = SpeechEngine.load(
                                context, model, graph.models,
                                EngineOptions(threads = graph.settings.effectiveThreads(), flashAttention = graph.settings.flashAttention.value, language = language),
                            )
                            try {
                                val out = StringBuilder()
                                engine.transcribe(samples, listOf(0L to samples.size / 16L), language, null, onProgress = {}, onSegment = { out.append(it.text).append(' ') })
                                out.toString()
                            } finally {
                                engine.close()
                            }
                        }
                    } ?: error(context.getString(R.string.shadow_busy))
                }
            }
            heard.onSuccess { check = ShadowCheck.compare(text, it) }
                .onFailure { checkError = it.message }
            checking = false
        }
    }

    ModalBottomSheet(onDismissRequest = { stopPlaying(); recorder.stop(); onDismiss() }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text(stringResource(R.string.shadow_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            // The sentence; after a check, the words heard in the accent colour, the others in orange.
            val result = check
            if (result == null) Text(text, style = MaterialTheme.typography.titleLarge)
            else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                result.words.forEach { (w, ok) ->
                    Text(w, style = MaterialTheme.typography.titleLarge, color = if (ok) MaterialTheme.colorScheme.primary else Color(0xFFFFA24C))
                }
            }
            translation?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))

            // Waveforms: the speaker above, you below.
            Waveform(originalPeaks, MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Waveform(minePeaks, MaterialTheme.colorScheme.tertiary)
            if (originalMissing) {
                Text(stringResource(R.string.shadow_no_audio), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(12.dp))

            // Speed and loop for the original.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1f, 0.85f, 0.7f).forEach { s ->
                    FilterChip(selected = speed == s, onClick = { speed = s }, label = { Text("${s}×") })
                }
                FilterChip(selected = loop, onClick = { loop = !loop }, label = { Text(stringResource(R.string.shadow_loop)) },
                    leadingIcon = { Icon(Icons.Filled.Repeat, null, Modifier.size(16.dp)) })
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(
                    enabled = original != null && !recording,
                    onClick = { if (playing == "original") stopPlaying() else original?.let { play(it, speed, "original") } },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(if (playing == "original") Icons.Filled.Stop else Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.shadow_original))
                }
                Button(
                    onClick = { toggleRecord() },
                    colors = if (recording) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(if (recording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp)); Text(stringResource(if (recording) R.string.shadow_stop else R.string.shadow_record))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(enabled = hasMine && !recording, onClick = { if (playing == "mine") stopPlaying() else play(mine.path, 1f, "mine") }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.shadow_mine))
                }
                OutlinedButton(
                    enabled = hasMine && original != null && !recording,
                    onClick = { original?.let { o -> play(o, 1f, "compare") { scope.launch { delay(400); play(mine.path, 1f, "compare") } } } },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.AutoMirrored.Filled.CompareArrows, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.shadow_compare))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                androidx.compose.material3.TextButton(enabled = hasMine && !recording && !checking, onClick = { checkWhatISaid() }) {
                    Text(stringResource(R.string.shadow_check))
                }
                Spacer(Modifier.weight(1f))
                if (onKeepRecording != null) {
                    androidx.compose.material3.TextButton(enabled = hasMine && !recording, onClick = {
                        // Keep a copy (the sheet's file is reused for the next attempt).
                        val kept = File(context.filesDir, "shadow/kept-${System.currentTimeMillis()}.wav")
                        mine.copyTo(kept, overwrite = true)
                        onKeepRecording(kept.path)
                        android.widget.Toast.makeText(context, context.getString(R.string.shadow_kept), android.widget.Toast.LENGTH_SHORT).show()
                    }) { Text(stringResource(R.string.shadow_keep)) }
                }
            }
            if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
            check?.let { Text(stringResource(R.string.shadow_score, it.matched, it.words.size), style = MaterialTheme.typography.bodyMedium) }
            checkError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Text(stringResource(R.string.shadow_private), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** Bars of loudness; an empty line when there's nothing yet. */
@Composable
private fun Waveform(peaks: FloatArray?, color: Color) {
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(Modifier.fillMaxWidth().height(36.dp)) {
        val bars = peaks ?: FloatArray(60)
        val w = size.width / bars.size
        bars.forEachIndexed { i, p ->
            val h = maxOf(2.dp.toPx(), p * size.height)
            drawRoundRect(
                color = if (peaks == null) empty else color,
                topLeft = Offset(i * w + w * 0.2f, (size.height - h) / 2),
                size = Size(w * 0.6f, h),
                cornerRadius = CornerRadius(w * 0.3f),
            )
        }
    }
}
