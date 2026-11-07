package com.ascolta.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ascolta.app.data.EpisodeEntity
import com.ascolta.app.data.Segment
import com.ascolta.app.data.Transcript
import com.ascolta.app.data.TranscriptState
import com.ascolta.app.data.VocabEntity
import com.ascolta.app.data.indexAt
import com.ascolta.app.data.languageName
import com.ascolta.app.graph
import kotlinx.coroutines.launch

private val SPEEDS = listOf(0.5f, 0.65f, 0.75f, 0.85f, 1f, 1.15f, 1.25f, 1.5f)

private data class WordLookup(val word: String, val segment: Segment)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(episodeId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val settings = graph.settings

    val episode by remember(episodeId) { graph.repo.episode(episodeId) }.collectAsState(initial = null)
    val podcast by produceState<com.ascolta.app.data.PodcastEntity?>(null, episode?.podcastId) {
        value = episode?.podcastId?.let { graph.repo.getPodcast(it) }
    }
    val player by graph.player.state.collectAsState()
    val showOriginal by settings.showOriginal.flow.collectAsState()
    val showTranslation by settings.showTranslation.flow.collectAsState()
    val autoPause by settings.autoPause.flow.collectAsState()
    val textScale by settings.transcriptTextScale.flow.collectAsState()
    val nativeLanguage by settings.nativeLanguage.flow.collectAsState()

    val isCurrent = player.episodeId == episodeId
    val ep = episode

    // Reload the transcript as transcription progresses (it is saved after every chunk).
    var transcript by remember { mutableStateOf<Transcript?>(null) }
    val progressStep = ((ep?.transcriptProgress ?: 0f) * 50).toInt()
    LaunchedEffect(episodeId, ep?.transcriptState, progressStep) {
        transcript = graph.repo.loadTranscript(episodeId)
    }
    val segments = transcript?.segments.orEmpty()

    // Fill in missing translations (e.g. translation model wasn't available during transcription).
    var translating by remember { mutableStateOf(false) }
    LaunchedEffect(transcript?.complete, nativeLanguage) {
        val t = transcript ?: return@LaunchedEffect
        val needs = t.complete && (t.segments.any { it.translation == null } ||
            (t.translationLanguage != null && t.translationLanguage != nativeLanguage))
        if (needs) {
            translating = true
            runCatching { graph.repo.translateTranscript(episodeId) }
                .onSuccess { if (it != null) transcript = it }
                .onFailure { Toast.makeText(context, "Translation unavailable: ${it.message}", Toast.LENGTH_LONG).show() }
            translating = false
        }
    }

    LaunchedEffect(segments, isCurrent) {
        if (isCurrent) graph.player.sentenceEnds = segments.map { it.endMs }.toLongArray()
    }

    val position = if (isCurrent) player.positionMs else ep?.playbackPositionMs ?: 0
    val duration = if (isCurrent && player.durationMs > 0) player.durationMs else (ep?.durationSec ?: 0) * 1000
    val currentIndex = segments.indexAt(position)

    fun startOrToggle() {
        val e = episode ?: return
        if (isCurrent) graph.player.togglePlay()
        else scope.launch { graph.player.play(e, podcast?.title, e.imageUrl ?: podcast?.imageUrl) }
    }

    fun seek(ms: Long, play: Boolean = true) {
        val e = episode ?: return
        if (isCurrent) graph.player.seekTo(ms, play)
        else scope.launch {
            graph.player.play(e.copy(playbackPositionMs = ms), podcast?.title, e.imageUrl ?: podcast?.imageUrl)
        }
    }

    val listState = rememberLazyListState()
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(dragged) { if (dragged) follow = false }
    LaunchedEffect(currentIndex, follow) {
        if (follow && currentIndex >= 0) listState.animateScrollToItem((currentIndex - 1).coerceAtLeast(0))
    }

    var menu by remember { mutableStateOf(false) }
    var lookup by remember { mutableStateOf<WordLookup?>(null) }

    androidx.compose.material3.Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(ep?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        podcast?.title?.let {
                            Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Options") }
                    val source = languageName(transcript?.language ?: settings.learningLanguage.value)
                    val target = languageName(nativeLanguage)
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        CheckItem("Show $source", showOriginal) { settings.showOriginal.set(it) }
                        CheckItem("Show $target", showTranslation) { settings.showTranslation.set(it) }
                        CheckItem("Pause after each sentence", autoPause) { settings.autoPause.set(it) }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Larger text") }, onClick = {
                            settings.transcriptTextScale.set((textScale + 0.15f).coerceAtMost(2f))
                        })
                        DropdownMenuItem(text = { Text("Smaller text") }, onClick = {
                            settings.transcriptTextScale.set((textScale - 0.15f).coerceAtLeast(0.7f))
                        })
                        if (ep?.transcriptState == TranscriptState.DONE) {
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Re-transcribe with current model") }, onClick = {
                                menu = false
                                scope.launch { graph.repo.retranscribe(episodeId) }
                            })
                        }
                    }
                },
            )
        },
        bottomBar = {
            Controls(
                isPlaying = isCurrent && player.isPlaying,
                buffering = isCurrent && player.isBuffering,
                position = position,
                duration = duration,
                speed = player.speed,
                onPlayPause = ::startOrToggle,
                onSeek = { seek(it, play = isCurrent && player.isPlaying) },
                onPrevious = {
                    if (segments.isEmpty()) seek((position - 10_000).coerceAtLeast(0))
                    else {
                        val cur = segments.getOrNull(currentIndex)
                        val target = if (cur != null && position - cur.startMs > 1500) cur.startMs
                        else segments.getOrNull(currentIndex - 1)?.startMs ?: 0
                        seek(target)
                    }
                },
                onNext = {
                    if (segments.isEmpty()) seek(position + 30_000)
                    else segments.getOrNull(currentIndex + 1)?.let { seek(it.startMs) }
                },
                onRepeat = {
                    segments.getOrNull(currentIndex)?.let { seek(it.startMs) } ?: seek((position - 5_000).coerceAtLeast(0))
                },
                onSpeed = {
                    val next = SPEEDS.firstOrNull { it > player.speed + 0.01f } ?: SPEEDS.first()
                    graph.player.setSpeed(next)
                },
            )
        },
        floatingActionButton = {
            if (!follow && segments.isNotEmpty()) {
                SmallFloatingActionButton(onClick = { follow = true }) { Icon(Icons.Filled.MyLocation, "Follow playback") }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (ep != null) StatusBanner(ep, transcript, translating)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(segments, key = { i, s -> "$i-${s.startMs}" }) { i, segment ->
                    SegmentItem(
                        segment = segment,
                        active = i == currentIndex,
                        showOriginal = showOriginal,
                        showTranslation = showTranslation,
                        textScale = textScale,
                        onSeek = { seek(segment.startMs) },
                        onWord = { word ->
                            graph.player.pause()
                            lookup = WordLookup(word, segment)
                        },
                    )
                }
            }
        }
    }

    lookup?.let { l ->
        WordSheet(
            lookup = l,
            language = transcript?.language ?: settings.learningLanguage.value,
            episode = ep,
            onPlaySentence = { seek(l.segment.startMs) },
            onDismiss = { lookup = null },
        )
    }
}

@Composable
private fun CheckItem(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = { androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = null) },
        onClick = { onChange(!checked) },
    )
}

@Composable
private fun StatusBanner(ep: EpisodeEntity, transcript: Transcript?, translating: Boolean) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val partial = transcript?.segments?.isNotEmpty() == true
    when (ep.transcriptState) {
        TranscriptState.RUNNING, TranscriptState.QUEUED -> Banner {
            Text(
                "${ep.transcriptStatus ?: "Preparing transcript"} · ${(ep.transcriptProgress * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(6.dp))
            if (ep.transcriptState == TranscriptState.RUNNING) {
                LinearProgressIndicator(progress = { ep.transcriptProgress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (partial) "Transcribed sentences appear below as they're ready."
                else "Runs on your phone; you can leave the app and keep listening.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TranscriptState.FAILED -> Banner {
            Text("Transcription failed: ${ep.error ?: "unknown error"}", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { scope.launch { graph.repo.transcribe(ep.id) } }) { Text("Try again") }
        }
        TranscriptState.NONE -> if (!partial) Banner {
            Text("No transcript yet", style = MaterialTheme.typography.titleSmall)
            Text(
                "Create one on your phone. The episode is downloaded first if needed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { scope.launch { graph.repo.transcribe(ep.id) } }) { Text("Create transcript") }
        }
        else -> if (translating) Banner {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Translating…", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Banner(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) { content() }
    }
}

@Composable
private fun SegmentItem(
    segment: Segment,
    active: Boolean,
    showOriginal: Boolean,
    showTranslation: Boolean,
    textScale: Float,
    onSeek: () -> Unit,
    onWord: (String) -> Unit,
) {
    val bg = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    Column(
        Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            formatTime(segment.startMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.pointerInput(segment.startMs) { detectTapGestures { onSeek() } }.padding(bottom = 2.dp),
        )
        if (showOriginal) {
            TappableText(
                segment.text,
                MaterialTheme.typography.bodyLarge.copy(
                    fontSize = (18 * textScale).sp,
                    lineHeight = (26 * textScale).sp,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                ),
                onWord,
            )
        }
        if (showTranslation) {
            Text(
                segment.translation ?: "…",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = (15 * textScale).sp,
                    lineHeight = (21 * textScale).sp,
                    fontStyle = FontStyle.Italic,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp).pointerInput(segment.startMs) { detectTapGestures { onSeek() } },
            )
        }
    }
}

/** Text where tapping a word reports that word. */
@Composable
private fun TappableText(text: String, style: TextStyle, onWord: (String) -> Unit) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text,
        style = style,
        onTextLayout = { layout = it },
        modifier = Modifier.pointerInput(text) {
            detectTapGestures { pos ->
                val l = layout ?: return@detectTapGestures
                wordAt(text, l.getOffsetForPosition(pos))?.let(onWord)
            }
        },
    )
}

private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-'

fun wordAt(text: String, offset: Int): String? {
    if (text.isEmpty()) return null
    var i = offset.coerceIn(0, text.length - 1)
    if (!isWordChar(text[i])) {
        if (i > 0 && isWordChar(text[i - 1])) i-- else return null
    }
    var start = i
    while (start > 0 && isWordChar(text[start - 1])) start--
    var end = i
    while (end < text.length && isWordChar(text[end])) end++
    return text.substring(start, end).trim('\'', '’', '-').takeIf { it.isNotEmpty() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WordSheet(
    lookup: WordLookup,
    language: String,
    episode: EpisodeEntity?,
    onPlaySentence: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val translation by produceState<String?>(null, lookup) {
        value = runCatching { graph.repo.translateWord(lookup.word, language) }
            .getOrElse { "(translation unavailable: ${it.message})" }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text(lookup.word, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            if (translation == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            else Text(translation!!, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(lookup.segment.text, style = MaterialTheme.typography.bodyMedium)
            lookup.segment.translation?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = translation != null,
                    onClick = {
                        scope.launch {
                            val added = graph.repo.saveWord(
                                VocabEntity(
                                    word = lookup.word.lowercase(),
                                    translation = translation.orEmpty(),
                                    sentence = lookup.segment.text,
                                    sentenceTranslation = lookup.segment.translation,
                                    language = language,
                                    episodeId = episode?.id,
                                    episodeTitle = episode?.title,
                                    createdAt = System.currentTimeMillis(),
                                ),
                            )
                            Toast.makeText(context, if (added) "Saved to Words" else "Already saved", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    },
                ) {
                    Icon(Icons.Filled.BookmarkAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Save word")
                }
                OutlinedButton(onClick = { onPlaySentence(); onDismiss() }) {
                    Icon(Icons.Filled.Replay, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Play sentence")
                }
            }
        }
    }
}

@Composable
private fun Controls(
    isPlaying: Boolean,
    buffering: Boolean,
    position: Long,
    duration: Long,
    speed: Float,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
    onSpeed: () -> Unit,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Slider(
                value = dragging ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { onSeek((it * duration).toLong()) }
                    dragging = null
                },
                enabled = duration > 0,
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatTime(dragging?.let { (it * duration).toLong() } ?: position), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                Text(if (duration > 0) formatTime(duration) else "--:--", style = MaterialTheme.typography.labelSmall)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onSpeed) {
                    Text(String.format(java.util.Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.') + "×")
                }
                IconButton(onClick = onPrevious) { Icon(Icons.Filled.SkipPrevious, "Previous sentence") }
                FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(64.dp)) {
                    if (buffering) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    else Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause", Modifier.size(36.dp))
                }
                IconButton(onClick = onNext) { Icon(Icons.Filled.SkipNext, "Next sentence") }
                IconButton(onClick = onRepeat) { Icon(Icons.Filled.Replay, "Repeat sentence") }
            }
        }
    }
}
