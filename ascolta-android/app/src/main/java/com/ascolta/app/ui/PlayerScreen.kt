package com.ascolta.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Headphones
import kotlinx.coroutines.delay
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
import androidx.compose.material3.AlertDialog
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
import com.ascolta.app.data.WordSplitter
import com.ascolta.app.work.WorkScheduler
import com.ascolta.app.data.Segment
import com.ascolta.app.data.Transcript
import com.ascolta.app.data.TranscriptState
import com.ascolta.app.data.VocabEntity
import com.ascolta.app.data.indexAt
import com.ascolta.app.data.Word
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.ascolta.app.data.languageName
import com.ascolta.app.R
import com.ascolta.app.graph
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Subtitles
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
    val showTranslationPref by settings.showTranslation.flow.collectAsState()
    val translateOn by settings.translate.flow.collectAsState()
    val coverMode by settings.showCover.flow.collectAsState()
    val showTranslation = showTranslationPref && translateOn
    val autoPause by settings.autoPause.flow.collectAsState()
    val wordHighlight by settings.wordHighlight.flow.collectAsState()
    val textScale by settings.transcriptTextScale.flow.collectAsState()
    val nativeLanguage by settings.nativeLanguage.flow.collectAsState()

    val isCurrent = player.episodeId == episodeId
    val ep = episode

    // Reload the transcript every time new sentences are saved, so text appears in real time.
    var transcript by remember { mutableStateOf<Transcript?>(null) }
    LaunchedEffect(episodeId, ep?.transcriptState) {
        transcript = graph.repo.loadTranscript(episodeId)
    }
    LaunchedEffect(episodeId) {
        graph.transcripts.updates.collect { id ->
            if (id == episodeId) transcript = graph.repo.loadTranscript(episodeId)
        }
    }
    // Always in time order, so finding the sentence being spoken can't pick the wrong one.
    val segments = remember(transcript) { transcript?.segments.orEmpty().sortedBy { it.startMs } }

    // Fill in missing translations (e.g. translation model wasn't available during transcription).
    var translating by remember { mutableStateOf(false) }
    LaunchedEffect(transcript?.complete, nativeLanguage, translateOn) {
        val t = transcript ?: return@LaunchedEffect
        val needs = translateOn && t.complete && (t.segments.any { it.translation == null } ||
            (t.translationLanguage != null && t.translationLanguage != nativeLanguage))
        if (needs) {
            translating = true
            runCatching { graph.repo.translateTranscript(episodeId) }
                .onSuccess { if (it != null) transcript = it }
                .onFailure { Toast.makeText(context, context.getString(R.string.translation_unavailable, it.message ?: ""), Toast.LENGTH_LONG).show() }
            translating = false
        }
    }

    LaunchedEffect(segments, isCurrent) {
        if (isCurrent) graph.player.sentenceEnds = segments.map { it.endMs }.toLongArray()
    }

    val position = if (isCurrent) player.positionMs else ep?.playbackPositionMs ?: 0
    val duration = if (isCurrent && player.durationMs > 0) player.durationMs else (ep?.durationSec ?: 0) * 1000
    // Sentence at (or just before) the playback position; used for navigation and scrolling.
    val navIndex = segments.indexAt(position)
    // Highlighted sentence: none during music or long silences after a sentence has ended.
    val currentIndex = navIndex.takeIf { it >= 0 && position <= segments[it].endMs + HIGHLIGHT_LINGER_MS } ?: -1
    // Word being spoken inside the highlighted sentence (when the engine gave word timings).
    // Real words in every language (Japanese and Chinese have no spaces), each with its start time.
    val textLanguage = transcript?.language ?: settings.learningLanguage.value
    // Japanese: readings for furigana / romaji (dictionary loaded on first use).
    val isJapanese = textLanguage == "ja"
    LaunchedEffect(isJapanese) { if (isJapanese) com.ascolta.app.data.Japanese.load() }
    val jaReady by com.ascolta.app.data.Japanese.ready.collectAsState()
    val jaScriptPref by settings.japaneseScript.flow.collectAsState()
    val jaScript = com.ascolta.app.data.JapaneseScript.of(jaScriptPref)
    val timedWords = remember(segments, textLanguage, jaReady) {
        segments.map { WordSplitter.timedWords(it.text, it.words, it.startMs, it.endMs, textLanguage) }
    }
    val currentWord = if (!wordHighlight) -1
        else timedWords.getOrNull(currentIndex)?.indexOfLast { it.startMs <= position } ?: -1

    // Streaming over mobile data in Wi-Fi only mode needs the listener's OK first.
    var confirmStream by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun startPlayback(e: EpisodeEntity) {
        val start = { scope.launch { graph.player.play(e, podcast?.title, e.imageUrl ?: podcast?.imageUrl) }; Unit }
        val local = e.localPath?.let { java.io.File(it).exists() } == true
        if (!local && WorkScheduler.blockedByNetworkMode(context)) confirmStream = start else start()
    }

    fun startOrToggle() {
        val e = episode ?: return
        if (isCurrent) graph.player.togglePlay() else startPlayback(e)
    }

    fun seek(ms: Long, play: Boolean = true) {
        val e = episode ?: return
        if (isCurrent) graph.player.seekTo(ms, play) else startPlayback(e.copy(playbackPositionMs = ms))
    }

    confirmStream?.let { start ->
        AlertDialog(
            onDismissRequest = { confirmStream = null },
            title = { Text(stringResource(R.string.stream_mobile_title)) },
            text = { Text(stringResource(R.string.stream_mobile_text)) },
            confirmButton = {
                TextButton(onClick = { confirmStream = null; start() }) { Text(stringResource(R.string.stream_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmStream = null
                    episode?.let { scope.launch { graph.repo.download(it.id) } }
                }) { Text(stringResource(R.string.download_on_wifi)) }
            },
        )
    }

    val listState = rememberLazyListState()
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(dragged) {
        if (dragged) follow = false
        else if (!follow) {
            // Follows the audio again a few seconds after the reader stops scrolling.
            delay(6_000)
            follow = true
        }
    }
    val contextPx = with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.roundToPx() }
    LaunchedEffect(navIndex, follow, showTranslation, showOriginal, textScale, segments.size) {
        if (!follow || navIndex < 0) return@LaunchedEffect
        // Only scroll when the highlighted sentence isn't entirely visible; then put it near the
        // top with a little of the previous sentence above it for context.
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == navIndex }
        val top = info.viewportStartOffset
        val bottom = info.viewportEndOffset
        val fullyVisible = item != null && item.offset >= top && item.offset + item.size <= bottom
        if (fullyVisible) return@LaunchedEffect
        listState.animateScrollToItem(navIndex)
        val height = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == navIndex }?.size ?: 0
        val room = (bottom - top) - height
        if (navIndex > 0 && room > 0) listState.animateScrollBy(-minOf(contextPx, room).toFloat())
    }

    // A sentence taller than the screen: scroll within it so the word being spoken stays in
    // view (its position estimated from its place among the sentence's words).
    LaunchedEffect(currentWord, currentIndex) {
        if (!follow || currentIndex < 0 || currentWord < 0 || coverMode) return@LaunchedEffect
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == currentIndex } ?: return@LaunchedEffect
        val viewport = info.viewportEndOffset - info.viewportStartOffset
        if (item.size <= viewport) return@LaunchedEffect
        val count = timedWords.getOrNull(currentIndex)?.size?.takeIf { it > 0 } ?: return@LaunchedEffect
        val wordY = item.offset + (item.size * currentWord.toFloat() / count).toInt()
        if (wordY < viewport * 0.15f || wordY > viewport * 0.7f) listState.animateScrollBy(wordY - viewport * 0.3f)
    }

    var menu by remember { mutableStateOf(false) }
    var sleepDialog by remember { mutableStateOf(false) }
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
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                actions = {
                    // Sleep timer (stops at the end of a sentence).
                    val sleepTimer by graph.player.sleep.collectAsState()
                    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(sleepTimer) { while (sleepTimer != null) { nowTick = System.currentTimeMillis(); delay(1_000) } }
                    TextButton(onClick = { sleepDialog = true }) {
                        Icon(Icons.Filled.Bedtime, stringResource(R.string.sleep_timer))
                        sleepTimer?.let { t ->
                            Spacer(Modifier.width(4.dp))
                            Text(
                                when {
                                    t.endOfEpisode -> stringResource(R.string.sleep_end_short)
                                    t.snapToMs != null -> "…"
                                    else -> formatTime(((t.endsAt ?: 0) - nowTick).coerceAtLeast(0)).let { it }
                                },
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    // Switch between the episode's picture and the transcript (kept either way).
                    if (segments.isNotEmpty()) {
                        IconButton(onClick = { settings.showCover.set(!coverMode) }) {
                            if (coverMode) Icon(Icons.Filled.Subtitles, stringResource(R.string.show_transcript))
                            else Icon(Icons.Filled.Image, stringResource(R.string.show_cover))
                        }
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more)) }
                    val source = languageName(transcript?.language ?: settings.learningLanguage.value)
                    val target = languageName(nativeLanguage)
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        CheckItem(stringResource(R.string.show_language, source), showOriginal) { settings.showOriginal.set(it) }
                        CheckItem(stringResource(R.string.translate_title), translateOn) { settings.translate.set(it) }
                        if (translateOn) CheckItem(stringResource(R.string.show_language, target), showTranslation) { settings.showTranslation.set(it) }
                        CheckItem(stringResource(R.string.pause_each_sentence), autoPause) { settings.autoPause.set(it) }
                        CheckItem(stringResource(R.string.highlight_words), wordHighlight) { settings.wordHighlight.set(it) }
                        if (isJapanese) {
                            HorizontalDivider()
                            com.ascolta.app.data.JapaneseScript.entries.forEach { s ->
                                CheckItem(
                                    stringResource(
                                        when (s) {
                                            com.ascolta.app.data.JapaneseScript.KANJI -> R.string.ja_script_kanji
                                            com.ascolta.app.data.JapaneseScript.FURIGANA -> R.string.ja_script_furigana
                                            com.ascolta.app.data.JapaneseScript.ROMAJI -> R.string.ja_script_romaji
                                        },
                                    ),
                                    jaScript == s,
                                ) { settings.japaneseScript.set(s.key) }
                            }
                        }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(stringResource(R.string.larger_text)) }, onClick = {
                            settings.transcriptTextScale.set((textScale + 0.15f).coerceAtMost(2f))
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.smaller_text)) }, onClick = {
                            settings.transcriptTextScale.set((textScale - 0.15f).coerceAtLeast(0.7f))
                        })
                        if (ep?.transcriptState == TranscriptState.DONE) {
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text(stringResource(R.string.retranscribe_current)) }, onClick = {
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
                        val cur = segments.getOrNull(navIndex)
                        val target = if (cur != null && position - cur.startMs > 1500) cur.startMs
                        else segments.getOrNull(navIndex - 1)?.startMs ?: 0
                        seek(target)
                    }
                },
                onNext = {
                    if (segments.isEmpty()) seek(position + 30_000)
                    else segments.getOrNull(navIndex + 1)?.let { seek(it.startMs) }
                },
                onRepeat = {
                    segments.getOrNull(navIndex)?.let { seek(it.startMs) } ?: seek((position - 5_000).coerceAtLeast(0))
                },
                onSpeed = {
                    val next = SPEEDS.firstOrNull { it > player.speed + 0.01f } ?: SPEEDS.first()
                    graph.player.setSpeed(next)
                },
            )
        },
        floatingActionButton = {
            if (!follow && segments.isNotEmpty() && !coverMode) {
                SmallFloatingActionButton(onClick = { follow = true }) { Icon(Icons.Filled.MyLocation, stringResource(R.string.follow_playback)) }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (ep != null) StatusBanner(ep, transcript, translating)
            // No transcript (not chosen for this episode, or nothing recognised yet), or the
            // listener chose the picture: the cover. The transcript is kept and shown again on demand.
            if (segments.isEmpty() || coverMode) {
                val cover = ep?.imageUrl ?: podcast?.imageUrl
                Box(Modifier.fillMaxWidth().weight(1f).padding(32.dp), contentAlignment = Alignment.Center) {
                    if (cover != null) {
                        coil3.compose.AsyncImage(
                            model = cover,
                            contentDescription = ep?.title,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)),
                        )
                    } else {
                        Icon(Icons.Filled.Headphones, null, Modifier.size(120.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // With a transcript: one line of what is being said, under the picture.
                segments.getOrNull(currentIndex)?.let { seg ->
                    if (isJapanese && jaReady && jaScript == com.ascolta.app.data.JapaneseScript.ROMAJI) {
                        val romaji = remember(seg.text) { com.ascolta.app.data.Japanese.romaji(seg.text).orEmpty() }
                        LiveLine(seg.copy(text = romaji), null, -1)
                    } else {
                        LiveLine(seg, timedWords.getOrNull(currentIndex), if (wordHighlight) currentWord else -1)
                    }
                }
            } else LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(segments, key = { i, s -> "$i-${s.startMs}" }) { i, segment ->
                    SegmentItem(
                        segment = segment,
                        words = timedWords.getOrNull(i),
                        language = textLanguage,
                        japanese = if (isJapanese && jaReady && jaScript != com.ascolta.app.data.JapaneseScript.KANJI) jaScript else null,
                        active = i == currentIndex,
                        activeWord = if (i == currentIndex) currentWord else -1,
                        showOriginal = showOriginal,
                        showTranslation = showTranslation,
                        textScale = textScale,
                        onSeek = { follow = true; seek(segment.startMs) },
                        onWord = { word ->
                            graph.player.pause()
                            lookup = WordLookup(word, segment)
                        },
                    )
                }
            }
        }
    }

    if (sleepDialog) {
        val active = graph.player.sleep.collectAsState().value != null
        AlertDialog(
            onDismissRequest = { sleepDialog = false },
            title = { Text(stringResource(R.string.sleep_timer)) },
            text = {
                Column {
                    Text(stringResource(R.string.sleep_timer_text), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    for (minutes in listOf(5, 10, 15, 30, 45, 60, 0)) {
                        TextButton(onClick = { graph.player.setSleepTimer(minutes); sleepDialog = false }) {
                            Text(if (minutes == 0) stringResource(R.string.sleep_end_of_episode) else stringResource(R.string.sleep_minutes, minutes))
                        }
                    }
                }
            },
            confirmButton = {
                if (active) TextButton(onClick = { graph.player.setSleepTimer(null); sleepDialog = false }) { Text(stringResource(R.string.sleep_off)) }
            },
            dismissButton = { TextButton(onClick = { sleepDialog = false }) { Text(stringResource(R.string.action_close)) } },
        )
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

/**
 * One line of the sentence being spoken (shown under the episode picture). It keeps the
 * current word in view: when the sentence is too long, the words already said scroll off.
 */
@Composable
private fun LiveLine(segment: Segment, words: List<Word>?, activeWord: Int) {
    val ranges = remember(segment, words) { wordRanges(segment.text, words) }
    // Start a few words before the current one, so it stays visible on one line.
    val from = ranges.getOrNull(activeWord - 3)?.first?.takeIf { activeWord > 3 } ?: 0
    val active = ranges.getOrNull(activeWord)
    val primary = MaterialTheme.colorScheme.primary
    val text = androidx.compose.ui.text.buildAnnotatedString {
        if (from > 0) append("… ")
        val shift = length - from
        append(segment.text.substring(from))
        if (active != null && !active.isEmpty() && active.first >= from) {
            addStyle(
                androidx.compose.ui.text.SpanStyle(color = primary, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                active.first + shift, active.last + 1 + shift,
            )
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    )
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
        // One compact line, so the transcript keeps most of the screen.
        TranscriptState.RUNNING, TranscriptState.QUEUED -> Card(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${ep.transcriptStatus ?: stringResource(R.string.banner_preparing)} · ${(ep.transcriptProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    if (ep.transcriptState == TranscriptState.RUNNING) {
                        LinearProgressIndicator(progress = { ep.transcriptProgress }, modifier = Modifier.fillMaxWidth().height(3.dp))
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
                    }
                }
                IconButton(onClick = { scope.launch { graph.repo.stopTranscription(ep.id) } }) {
                    Icon(Icons.Filled.Stop, stringResource(R.string.action_stop), Modifier.size(20.dp))
                }
                IconButton(onClick = { scope.launch { graph.repo.restartTranscription(ep.id) } }) {
                    Icon(Icons.Filled.Refresh, stringResource(R.string.stuck_restart), Modifier.size(20.dp))
                }
            }
        }
        TranscriptState.FAILED -> Banner {
            Text(
                stringResource(R.string.transcription_failed, ep.error ?: stringResource(R.string.unknown_error)),
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = { scope.launch { graph.repo.restartTranscription(ep.id) } }) {
                Text(stringResource(R.string.action_try_again))
            }
        }
        TranscriptState.NONE -> if (partial || ep.transcriptProgress > 0f) Banner {
            // Stopped by the user part-way: what was transcribed is kept.
            Text(
                stringResource(R.string.paused_title, (ep.transcriptProgress * 100).toInt()),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(R.string.paused_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { scope.launch { graph.repo.transcribe(ep.id) } }) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_continue))
            }
        } else Banner {
            Text(stringResource(R.string.no_transcript_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.no_transcript_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { scope.launch { graph.repo.transcribe(ep.id) } }) {
                Text(stringResource(R.string.ep_create_transcript))
            }
        }
        else -> if (translating) Banner {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.translating), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Banner(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { content() }
    }
}

@Composable
private fun SegmentItem(
    segment: Segment,
    words: List<Word>?,
    language: String?,
    japanese: com.ascolta.app.data.JapaneseScript? = null,
    active: Boolean,
    activeWord: Int,
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
        val textStyle = MaterialTheme.typography.bodyLarge.copy(
            fontSize = (18 * textScale).sp,
            lineHeight = (26 * textScale).sp,
            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        )
        val jaTokens = remember(segment.text, japanese) { japanese?.let { com.ascolta.app.data.Japanese.tokens(segment.text) } }
        if (showOriginal && japanese != null && jaTokens != null) {
            JapaneseSentence(jaTokens, japanese, activeWord, textStyle, onWord)
        } else if (showOriginal) {
            val wordRanges = remember(segment, words) { wordRanges(segment.text, words) }
            val highlight = MaterialTheme.colorScheme.primary
            val text = remember(segment.text, activeWord, wordRanges, highlight) {
                buildAnnotatedString {
                    append(segment.text)
                    wordRanges.getOrNull(activeWord)?.let { range ->
                        addStyle(
                            SpanStyle(background = highlight.copy(alpha = 0.22f), fontWeight = FontWeight.SemiBold),
                            range.first, range.last + 1,
                        )
                    }
                }
            }
            TappableText(
                text,
                MaterialTheme.typography.bodyLarge.copy(
                    fontSize = (18 * textScale).sp,
                    lineHeight = (26 * textScale).sp,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                ),
                language,
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
private fun TappableText(text: AnnotatedString, style: TextStyle, language: String?, onWord: (String) -> Unit) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text,
        style = style,
        onTextLayout = { layout = it },
        modifier = Modifier.pointerInput(text.text) {
            detectTapGestures { pos ->
                val l = layout ?: return@detectTapGestures
                // The dictionary word under the finger (not the whole run of letters, which in
                // Japanese or Chinese would be the whole sentence).
                WordSplitter.wordAt(text.text, l.getOffsetForPosition(pos), language)?.let(onWord)
            }
        },
    )
}

/** Keeps a sentence highlighted this long after it ends, then clears it (music, pauses). */
private const val HIGHLIGHT_LINGER_MS = 1_500L

/**
 * Character ranges of each timed word inside the sentence text, found in order so a
 * repeated word maps to the right occurrence. Words that can't be found get an empty range.
 */
fun wordRanges(text: String, words: List<Word>?): List<IntRange> {
    if (words.isNullOrEmpty()) return emptyList()
    val lower = text.lowercase()
    var cursor = 0
    return words.map { w ->
        val needle = w.text.trim().lowercase()
        if (needle.isEmpty()) return@map IntRange.EMPTY
        val at = lower.indexOf(needle, cursor)
        if (at < 0) IntRange.EMPTY
        else {
            cursor = at + needle.length
            at until cursor
        }
    }
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
            .getOrElse { context.getString(R.string.word_translation_unavailable, it.message ?: "") }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text(lookup.word, style = MaterialTheme.typography.headlineMedium)
            if (language == "ja") {
                // Reading and romaji of the Japanese word.
                val reading = remember(lookup.word) { com.ascolta.app.data.Japanese.reading(lookup.word) }
                val romaji = remember(lookup.word) { com.ascolta.app.data.Japanese.romaji(lookup.word) }
                if (reading != null || romaji != null) {
                    Text(listOfNotNull(reading, romaji).joinToString(" · "), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
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
                            // The word keeps its sentence (with translation) and a clip of the original audio.
                            val sentenceTranslation = lookup.segment.translation
                                ?: runCatching { graph.repo.translateWord(lookup.segment.text, language) }.getOrNull()
                            val clip = graph.repo.makeClip(episode, lookup.segment.startMs, lookup.segment.endMs)
                            val added = graph.repo.saveWord(
                                VocabEntity(
                                    word = lookup.word.lowercase(),
                                    translation = translation.orEmpty(),
                                    sentence = lookup.segment.text,
                                    sentenceTranslation = sentenceTranslation,
                                    language = language,
                                    episodeId = episode?.id,
                                    episodeTitle = episode?.title,
                                    createdAt = System.currentTimeMillis(),
                                    audioPath = clip,
                                ),
                            )
                            if (!added) clip?.let { java.io.File(it).delete() }
                            Toast.makeText(context, context.getString(if (added) R.string.saved_to_words else R.string.already_saved), Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    },
                ) {
                    Icon(Icons.Filled.BookmarkAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.save_word))
                }
                // Saves the whole sentence as a card, so the word keeps its context.
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val sentence = lookup.segment.text.trim()
                            val sentenceTranslation = lookup.segment.translation
                                ?: runCatching { graph.repo.translateWord(sentence, language) }.getOrNull()
                            val clip = graph.repo.makeClip(episode, lookup.segment.startMs, lookup.segment.endMs)
                            val added = graph.repo.saveWord(
                                VocabEntity(
                                    word = sentence,
                                    translation = sentenceTranslation.orEmpty(),
                                    sentence = sentence,
                                    sentenceTranslation = sentenceTranslation,
                                    language = language,
                                    episodeId = episode?.id,
                                    episodeTitle = episode?.title,
                                    createdAt = System.currentTimeMillis(),
                                    isSentence = true,
                                    audioPath = clip,
                                ),
                            )
                            if (!added) clip?.let { java.io.File(it).delete() }
                            Toast.makeText(context, context.getString(if (added) R.string.sentence_saved else R.string.already_saved), Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    },
                ) {
                    Icon(Icons.Filled.BookmarkAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.save_sentence))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(onClick = { onPlaySentence(); onDismiss() }) {
                    Icon(Icons.Filled.Replay, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.play_sentence))
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
                IconButton(onClick = onPrevious) { Icon(Icons.Filled.SkipPrevious, stringResource(R.string.previous_sentence)) }
                FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(64.dp)) {
                    if (buffering) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    else Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(R.string.play_pause), Modifier.size(36.dp))
                }
                IconButton(onClick = onNext) { Icon(Icons.Filled.SkipNext, stringResource(R.string.next_sentence)) }
                IconButton(onClick = onRepeat) { Icon(Icons.Filled.Replay, stringResource(R.string.repeat_sentence)) }
            }
        }
    }
}
