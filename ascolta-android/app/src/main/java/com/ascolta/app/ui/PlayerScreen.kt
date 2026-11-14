package com.ascolta.app.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Translate
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
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
import com.ascolta.app.work.TranscriptionPhase
import com.ascolta.app.work.WorkScheduler
import com.ascolta.app.data.Segment
import com.ascolta.app.data.Transcript
import com.ascolta.app.data.TranscriptState
import com.ascolta.app.data.VocabEntity
import com.ascolta.app.data.indexAt
import com.ascolta.app.data.Word
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
fun PlayerScreen(episodeId: Long, onBack: () -> Unit, openDiagnostics: () -> Unit = {}) {
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
    // Ads in this copy of the episode, found from their wording.
    val ads = remember(segments) {
        runCatching { com.ascolta.app.data.AdDetector.detect(segments) }
            .onFailure { com.ascolta.app.data.AppLog.w("Ads", "Ad detection failed", it) }
            .getOrDefault(emptyList())
            .also { found ->
                if (found.isNotEmpty()) com.ascolta.app.data.AppLog.d("Ads") {
                    found.joinToString("; ") { a ->
                        val first = segments.firstOrNull { it.startMs >= a.startMs }?.text?.take(60).orEmpty()
                        "%d:%02d–%d:%02d \"%s\"".format(a.startMs / 60_000, a.startMs / 1000 % 60, a.endMs / 60_000, a.endMs / 1000 % 60, first)
                    }
                }
            }
    }
    // Known words (optional): new words softly highlighted.
    val highlightNew by settings.highlightNewWords.flow.collectAsState()
    val wordsLanguage = transcript?.language ?: settings.learningLanguage.value
    val knownLevels by remember(wordsLanguage) { graph.knownWords.levels(wordsLanguage) }.collectAsState()
    val commonWords by produceState(emptySet<String>(), wordsLanguage, highlightNew) {
        if (highlightNew) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { graph.knownWords.common(wordsLanguage, graph.wordLists) }
    }
    // Words coloured by how common they are (optional): the word list of the language.
    val frequencyOn by settings.frequencyColors.flow.collectAsState()
    val frequencyRanks by produceState<Map<String, Int>?>(null, wordsLanguage, frequencyOn) {
        value = if (!frequencyOn) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { graph.wordLists.ranks(wordsLanguage) }
    }
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
                .onFailure {
                    // Leaving the screen only stops waiting (the translation carries on): not an error.
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    Toast.makeText(context, context.getString(R.string.translation_unavailable, it.message ?: ""), Toast.LENGTH_LONG).show()
                }
            translating = false
        }
    }

    LaunchedEffect(segments, isCurrent) {
        if (isCurrent) graph.player.sentenceEnds = segments.map { it.endMs }.toLongArray()
    }

    val position = if (isCurrent) player.positionMs else ep?.playbackPositionMs ?: 0
    // The length: player, feed, downloaded file (read once, then saved), or the transcript's end.
    val fileMs by produceState<Long?>(null, ep?.localPath, ep?.durationSec) {
        if (ep == null || ep.durationSec > 0) return@produceState
        // The downloaded file, or else the stream itself (its first bytes give the length).
        val path = ep.localPath?.takeIf { java.io.File(it).exists() } ?: ep.audioUrl ?: return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.ascolta.app.data.AudioLength.of(path) }
        value?.let { ms -> graph.db.episodeDao().fillDuration(ep.id, ms / 1000) }
    }
    val duration = com.ascolta.app.data.AudioLength.best(
        playerMs = if (isCurrent) player.durationMs else 0,
        feedSec = ep?.durationSec ?: 0,
        fileMs = fileMs,
        transcriptEndMs = segments.lastOrNull()?.endMs,
    )
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
        val start = {
            scope.launch {
                graph.player.play(e, podcast?.title, e.imageUrl ?: podcast?.imageUrl)
                // The episode being listened to gets the transcription first.
                runCatching { graph.repo.prioritize(e.id) }
            }
            Unit
        }
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

    var readingSheet by remember { mutableStateOf(false) }
    var advancedSheet by remember { mutableStateOf(false) }
    var searchSheet by remember { mutableStateOf(false) }
    var transcriptQuery by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var sleepDialog by remember { mutableStateOf(false) }
    var lookup by remember { mutableStateOf<WordLookup?>(null) }
    ForgetTourTarget("transcript"); ForgetTourTarget("player_more"); ForgetTourTarget("player_controls")
    // Selecting part of the transcript (to save with its audio, or to shadow).
    var savingSelection by remember { mutableStateOf(false) }
    var selA by remember { mutableStateOf<com.ascolta.app.data.WordPos?>(null) }
    var selB by remember { mutableStateOf<com.ascolta.app.data.WordPos?>(null) }
    var shadowing by remember { mutableStateOf<com.ascolta.app.data.Selected?>(null) }
    // Correcting a sentence by hand (a misheard name…): its index in the transcript.
    var editing by remember { mutableStateOf<Int?>(null) }
    // "Words in this episode": how much is known, and the new words to learn first.
    var showEpisodeWords by remember { mutableStateOf(false) }
    // Words to select: the timed ones, or (models without word times) the sentence's words with
    // estimated times, so words can be selected in every language and with every model.
    val selectWords = remember(timedWords, segments, textLanguage, jaReady) {
        segments.mapIndexed { i, s ->
            timedWords.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: WordSplitter.estimatedWords(s.text, s.startMs, s.endMs, textLanguage).ifEmpty { null }
        }
    }
    val allRanges = remember(segments, selectWords) { segments.mapIndexed { i, s -> wordRanges(s.text, selectWords.getOrNull(i)) } }
    val selection = remember(selA, selB, segments, selectWords, allRanges) {
        val a = selA
        val b = selB
        if (a == null || b == null || segments.isEmpty()) null
        else runCatching { com.ascolta.app.data.Selection.of(segments, selectWords, allRanges, a, b) }.getOrNull()
    }
    fun selectWord(segmentIndex: Int, word: Int) {
        val lastWord = (allRanges.getOrNull(segmentIndex)?.size ?: 1) - 1
        if (word < 0) {
            // A whole sentence (or extend to it).
            if (selA == null) { selA = com.ascolta.app.data.WordPos(segmentIndex, 0); selB = com.ascolta.app.data.WordPos(segmentIndex, lastWord.coerceAtLeast(0)) }
            else selB = com.ascolta.app.data.WordPos(segmentIndex, if (segmentIndex >= selA!!.segment) lastWord.coerceAtLeast(0) else 0)
        } else if (selA == null) {
            selA = com.ascolta.app.data.WordPos(segmentIndex, word); selB = selA
        } else selB = com.ascolta.app.data.WordPos(segmentIndex, word)
        graph.player.pause()
    }
    // Selecting by sliding: long press a word, slide to the last word, release.
    val dragSelect = remember { DragSelect() }
    var dragging by remember { mutableStateOf(false) }
    var dragAt by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var listBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    /** The word under the finger; a sentence without words counts whole (from its start, or to its end). */
    fun wordUnder(window: androidx.compose.ui.geometry.Offset, end: Boolean): com.ascolta.app.data.WordPos? {
        val (i, w) = dragSelect.at(window) ?: return null
        val last = ((allRanges.getOrNull(i)?.size ?: 1) - 1).coerceAtLeast(0)
        return com.ascolta.app.data.WordPos(i, if (w >= 0) w else if (end) last else 0)
    }
    fun dragTo(window: androidx.compose.ui.geometry.Offset) {
        dragAt = window
        val a = selA ?: return
        wordUnder(window, end = true)?.let { p ->
            // Sliding back above the start: the start sentence counts from its end.
            selB = if (p.segment < a.segment && allRanges.getOrNull(p.segment).isNullOrEmpty()) p.copy(word = 0) else p
        }
    }
    // Near the top or bottom of the transcript while sliding: it scrolls.
    LaunchedEffect(dragging) {
        while (dragging) {
            val bounds = listBounds
            val edge = 56 * context.resources.displayMetrics.density
            val dy = when {
                bounds == null -> 0f
                dragAt.y > bounds.bottom - edge -> 18f
                dragAt.y < bounds.top + edge -> -18f
                else -> 0f
            }
            if (dy != 0f && listState.scrollBy(dy) != 0f) dragTo(dragAt)
            delay(16)
        }
    }

    if (readingSheet) ModalBottomSheet(onDismissRequest = { readingSheet = false }) {
        Column(Modifier.fillMaxWidth().androidxVerticalScroll().padding(16.dp)) {
            Text(stringResource(R.string.reading_settings), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(12.dp))
            val source = languageName(transcript?.language ?: settings.learningLanguage.value)
            val target = languageName(nativeLanguage)
            CheckItem(stringResource(R.string.show_language, source), showOriginal) { settings.showOriginal.set(it) }
            CheckItem(stringResource(R.string.translate_title), translateOn) { settings.translate.set(it) }
            if (translateOn) CheckItem(stringResource(R.string.show_language, target), showTranslation) { settings.showTranslation.set(it) }

            CheckItem(stringResource(R.string.highlight_words), wordHighlight) { settings.wordHighlight.set(it) }
            if (!isJapanese) CheckItem(stringResource(R.string.highlight_new_words), highlightNew) { settings.highlightNewWords.set(it) }
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
        }
    }
    if (advancedSheet) ModalBottomSheet(onDismissRequest = { advancedSheet = false }) {
        Column(Modifier.fillMaxWidth().androidxVerticalScroll().padding(16.dp)) {
            Text(stringResource(R.string.practice_actions), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(12.dp))
            CheckItem(stringResource(R.string.pause_each_sentence), autoPause) { settings.autoPause.set(it) }
            if (segments.isNotEmpty() && !isJapanese) DropdownMenuItem(
                text = { Text(stringResource(R.string.episode_words)) },
                onClick = { advancedSheet = false; showEpisodeWords = true },
            )
            segments.getOrNull(navIndex)?.let { cur ->
                HorizontalDivider()
                DropdownMenuItem(text = { Text(stringResource(R.string.shadow_this)) }, onClick = {
                    advancedSheet = false
                    graph.player.pause()
                    selA = null; selB = null
                    if (graph.allowPractice()) shadowing = com.ascolta.app.data.Selection.of(segments, selectWords, allRanges,
                        com.ascolta.app.data.WordPos(navIndex, 0), com.ascolta.app.data.WordPos(navIndex, ((allRanges.getOrNull(navIndex)?.size ?: 1) - 1).coerceAtLeast(0)))
                })
            }
        }
    }
    if (searchSheet) ModalBottomSheet(onDismissRequest = { searchSheet = false }) {
        TranscriptSearchContent(segments, transcriptQuery, { transcriptQuery = it }) { start ->
            searchSheet = false
            follow = true
            seek(start)
        }
    }
    androidx.compose.material3.Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
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
                    IconButton(onClick = { menu = true }, modifier = Modifier.tourTarget("player_more")) { Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.reading_settings)) }, onClick = { menu = false; readingSheet = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.search_transcript)) }, onClick = { menu = false; searchSheet = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.sleep_timer)) }, onClick = { menu = false; sleepDialog = true })
                        DropdownMenuItem(text = { Text(stringResource(if (coverMode) R.string.show_transcript else R.string.show_cover)) }, onClick = { menu = false; settings.showCover.set(!coverMode) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.practice_actions)) }, onClick = { menu = false; advancedSheet = true })
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
            val raw = selection
            val joinPref by settings.joinSelection.flow.collectAsState()
            // A selection across two transcript sentences: with or without the "." or "?" between them.
            val sel = if (raw != null && raw.canJoin && joinPref) raw.joined() else raw
            // The selection translated in the app once the finger is lifted.
            val selLanguage = transcript?.language ?: settings.learningLanguage.value
            val selNative = settings.nativeLanguage.value
            var selTranslation by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(sel?.text, dragging) {
                selTranslation = null
                val text = sel?.text
                if (text != null && !dragging && selLanguage != selNative) {
                    delay(250)
                    selTranslation = runCatching { graph.repo.translateText(text, selLanguage, selNative) }.getOrNull()
                }
            }
            Column {
            if (sel != null && raw != null) SelectionBar(
                text = sel.text,
                translation = selTranslation,
                translating = !dragging && selTranslation == null && selLanguage != selNative,
                onTranslate = { WebLookup.translate(context, sel.text, selLanguage, selNative) },
                canJoin = raw.canJoin,
                joined = joinPref,
                onJoin = { settings.joinSelection.set(it) },
                onListen = {
                    scope.launch {
                        val clip = graph.repo.selectionClip(ep, sel)
                        if (clip != null) com.ascolta.app.whisper.AudioClip.play(clip)
                        else seek(sel.startMs)
                    }
                },
                saving = savingSelection,
                onSave = {
                    if (!savingSelection && graph.allowPractice()) {
                        savingSelection = true
                        scope.launch {
                        try {
                            val added = graph.repo.saveSelection(ep, sel, transcript?.language ?: settings.learningLanguage.value)
                            val message = context.getString(
                                when {
                                    !added -> R.string.already_saved
                                    ep?.localPath?.let { java.io.File(it).exists() } != true -> R.string.saved_without_audio
                                    sel.single -> R.string.saved_to_words
                                    else -> R.string.sentence_saved
                                },
                            )
                            selA = null; selB = null
                            scope.launch { snackbar.showSnackbar(message) }
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { snackbar.showSnackbar(context.getString(R.string.card_save_failed)) }
                        finally { savingSelection = false }
                        }
                    }
                },
                onShadow = { if (graph.allowPractice()) shadowing = sel },
                onCopy = {
                    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("LingoPod", sel.text))
                    selA = null; selB = null
                },
                onEdit = {
                    val a = selA; val b = selB
                    if (a != null && b != null) editing = minOf(a, b).segment
                },
                onClose = { selA = null; selB = null },
            )
            androidx.compose.foundation.layout.Box(Modifier.tourTarget("player_controls")) { Controls(
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
                    // Always forward: the next sentence, or 15 s on when none is transcribed yet (an ad, music).
                    seek(com.ascolta.app.work.TranscriptionPlan.nextTarget(segments.map { it.startMs }, position, duration))
                },
                onRepeat = {
                    segments.getOrNull(navIndex)?.let { seek(it.startMs) } ?: seek((position - 5_000).coerceAtLeast(0))
                },
                onSpeed = {
                    val next = SPEEDS.firstOrNull { it > player.speed + 0.01f } ?: SPEEDS.first()
                    graph.player.setSpeed(next)
                },
            ) }
            }
        },
        floatingActionButton = {
            if (!follow && segments.isNotEmpty() && !coverMode) {
                androidx.compose.material3.ExtendedFloatingActionButton(onClick = { follow = true }, icon = { Icon(Icons.Filled.MyLocation, null) }, text = { Text(stringResource(R.string.follow_playback)) })
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (ep != null) StatusBanner(ep, transcript, translating, openDiagnostics)
            if (frequencyRanks != null && segments.isNotEmpty()) FrequencyLegend()
            // In an ad: a button to skip it. Skipping by itself (Settings) is done by the player
            // service, so it also works with the screen off.
            val currentAd = com.ascolta.app.data.AdDetector.at(ads, position)
            if (currentAd != null && segments.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.ad_now), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    FilledTonalButton(onClick = { seek(currentAd.endMs) }) {
                        Icon(Icons.Filled.SkipNext, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.ad_skip))
                    }
                }
            }
            // Transcripts made on the phone can be wrong: said once, with how to fix a word.
            val noteSeen by settings.accuracyNoteSeen.flow.collectAsState()
            if (transcript?.source == Transcript.SOURCE_FEED && segments.isNotEmpty() && !coverMode) {
                Text(
                    "✓ " + stringResource(R.string.official_transcript),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            } else if (!noteSeen && segments.isNotEmpty() && !coverMode) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.accuracy_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { settings.accuracyNoteSeen.set(true) }) {
                            Icon(Icons.Filled.Close, stringResource(R.string.action_close), Modifier.size(18.dp))
                        }
                    }
                }
            }
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
                modifier = Modifier.fillMaxSize().tourTarget("transcript")
                    .onGloballyPositioned { listBounds = it.boundsInWindow() },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(segments, key = { i, s -> "$i-${s.startMs}" }) { i, segment ->
                    SegmentItem(
                        segment = segment,
                        words = timedWords.getOrNull(i),
                        selectRanges = allRanges.getOrNull(i).orEmpty(),
                        language = textLanguage,
                        japanese = if (isJapanese && jaReady && jaScript != com.ascolta.app.data.JapaneseScript.KANJI) jaScript else null,
                        active = i == currentIndex,
                        activeWord = if (i == currentIndex) currentWord else -1,
                        showOriginal = showOriginal,
                        showTranslation = showTranslation,
                        textScale = textScale,
                        onSeek = { follow = true; seek(segment.startMs) },
                        onWord = { word ->
                            if (dragging) return@SegmentItem
                            graph.player.pause()
                            lookup = WordLookup(word, segment)
                        },
                        selected = run {
                            val a = selA
                            val b = selB
                            if (a == null || b == null) null
                            else {
                                val (lo, hi) = if (a <= b) a to b else b to a
                                if (i < lo.segment || i > hi.segment) null
                                else {
                                    val last = (allRanges.getOrNull(i)?.size ?: 1) - 1
                                    if (allRanges.getOrNull(i).isNullOrEmpty()) -1..-1
                                    else (if (i == lo.segment) lo.word else 0)..(if (i == hi.segment) hi.word else last)
                                }
                            }
                        },
                        selecting = selA != null,
                        onSelect = { w -> if (!dragging) selectWord(i, w) },
                        dragSelect = dragSelect,
                        index = i,
                        onDragStart = { window ->
                            wordUnder(window, end = false)?.let { p ->
                                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                selA = p
                                selB = wordUnder(window, end = true) ?: p
                                dragAt = window
                                dragging = true
                                graph.player.pause()
                            }
                        },
                        onDragMove = { window -> if (dragging) dragTo(window) },
                        onDragEnd = { dragging = false },
                        newWords = if (highlightNew && !isJapanese) remember(segment.text, knownLevels, commonWords) {
                            com.ascolta.app.data.KnownWords.newWordRanges(segment.text, knownLevels, commonWords)
                        } else null,
                        isAd = ads.any { segment.startMs >= it.startMs && segment.startMs < it.endMs },
                        frequency = frequencyRanks?.let { ranks ->
                            remember(segment.text, ranks, jaReady) { com.ascolta.app.data.WordFrequency.of(segment.text, wordsLanguage, ranks) }
                        },
                    )
                }
            }
        }
    }

    editing?.let { index ->
        val segment = segments.getOrNull(index)
        if (segment == null) { editing = null; return@let }
        var text by remember(index, segment.text) { mutableStateOf(segment.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.edit_sentence)) },
            text = {
                Column {
                    Text(stringResource(R.string.edit_sentence_text), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.material3.OutlinedTextField(
                        value = text, onValueChange = { text = it }, minLines = 2,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val ep0 = ep
                    editing = null; selA = null; selB = null
                    scope.launch { graph.repo.editSentence(ep0, segment, text) }
                }) { Text(stringResource(R.string.select_save)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
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

    shadowing?.let { sel ->
        val lang = transcript?.language ?: settings.learningLanguage.value
        ShadowSheet(
            text = sel.text,
            translation = sel.contextTranslation,
            language = lang,
            loadOriginal = { graph.repo.selectionClip(ep, sel) },
            onKeepRecording = { path -> scope.launch { graph.repo.saveSelection(ep, sel, lang, myAudioPath = path) } },
            onDismiss = { shadowing = null; selA = null; selB = null },
        )
    }

    if (showEpisodeWords) {
        EpisodeWordsSheet(
            texts = segments.filterNot { s -> ads.any { s.startMs >= it.startMs && s.startMs < it.endMs } }.map { it.text },
            levels = knownLevels,
            common = commonWords,
            onWord = { word ->
                showEpisodeWords = false
                segments.firstOrNull { s -> com.ascolta.app.data.KnownWords.WORD.findAll(s.text).any { com.ascolta.app.data.KnownWords.key(it.value) == word } }
                    ?.let { lookup = WordLookup(word, it) }
            },
            onDismiss = { showEpisodeWords = false },
        )
    }

    lookup?.let { l ->
        WordSheet(
            lookup = l,
            language = transcript?.language ?: settings.learningLanguage.value,
            episode = ep,
            onPlaySentence = { seek(l.segment.startMs) },
            onPlayWord = { from, to ->
                if (isCurrent) graph.player.playSnippet(from, to)
                else scope.launch {
                    // Not loaded in the player: cut the word from the downloaded audio.
                    val path = ep?.localPath?.takeIf { java.io.File(it).exists() } ?: return@launch
                    val out = java.io.File(context.cacheDir, "word.wav")
                    val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.ascolta.app.whisper.AudioClip.extract(path, from, to, out, padBeforeMs = 60, padAfterMs = 150)
                    }
                    if (ok) com.ascolta.app.whisper.AudioClip.play(out.path)
                }
            },
            canPlayWord = isCurrent || ep?.localPath?.let { java.io.File(it).exists() } == true,
            wordLists = graph.wordLists,
            podcastTitle = podcast?.title,
            onEditSentence = { editing = segments.indexOf(l.segment).takeIf { it >= 0 }; lookup = null },
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
        modifier = Modifier.semantics { role = androidx.compose.ui.semantics.Role.Checkbox; toggleableState = androidx.compose.ui.state.ToggleableState(checked) },
        text = { Text(label) },
        trailingIcon = { androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = null) },
        onClick = { onChange(!checked) },
    )
}


@Composable
private fun StatusBanner(ep: EpisodeEntity, transcript: Transcript?, translating: Boolean, openDiagnostics: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val dataWarning = rememberDataWarning()
    fun transcribe() = dataWarning.run(
        needs = { graph.repo.downloadNeeds(ep.id, transcribe = true) },
        onWifi = { scope.launch { graph.repo.transcribe(ep.id, wifiOnly = true) } },
    ) { scope.launch { graph.repo.transcribe(ep.id) } }
    val partial = transcript?.segments?.isNotEmpty() == true
    when (ep.transcriptState) {
        // One compact line, so the transcript keeps most of the screen.
        TranscriptState.RUNNING, TranscriptState.QUEUED -> {
            val jobs by remember(ep.id) { androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WorkScheduler.transcribeWorkName(ep.id)) }.collectAsState(initial = emptyList())
            val job = jobs.firstOrNull { it.state == androidx.work.WorkInfo.State.RUNNING }
            val phase = TranscriptionPhase.from(job?.progress?.getString(TranscriptionPhase.KEY_PHASE))
            val model = graph.settings.modelFor(ep.transcriptLanguage ?: graph.settings.learningLanguage.value)
            TranscriptionStatusCard(
                phase = phase,
                detail = stringResource(R.string.local_subtitles_detail, model.label) + "\n" + ep.transcriptStatus.orEmpty(),
                bufferedMs = job?.progress?.getLong(TranscriptionPhase.KEY_BUFFER_MS, -1) ?: -1,
                action = stringResource(R.string.pause_transcription),
                onAction = { scope.launch { graph.repo.stopTranscription(ep.id) } },
                onRestart = { scope.launch { graph.repo.restartTranscription(ep.id) } },
                onDiagnostics = openDiagnostics,
            )
        }
        TranscriptState.FAILED -> {
            val missing = !graph.models.isDownloaded(graph.settings.modelFor(ep.transcriptLanguage ?: graph.settings.learningLanguage.value))
            val limitReached = !graph.isPro && graph.limits.transcriptionLeftMs() <= 0
            TranscriptionStatusCard(
                phase = TranscriptionPhase.FAILED,
                detail = stringResource(R.string.section_diagnostics), error = ep.error ?: stringResource(R.string.unknown_error),
                action = stringResource(when { limitReached -> R.string.get_pro; missing -> R.string.download_model_and_transcribe; else -> R.string.action_try_again }),
                onAction = {
                    if (limitReached) graph.billing.requestPaywall("transcription")
                    else if (missing) dataWarning.run(
                        needs = { graph.repo.downloadNeeds(ep.id, transcribe = true) },
                        onWifi = { scope.launch { graph.repo.restartTranscription(ep.id, wifiOnly = true) } },
                    ) { scope.launch { graph.repo.restartTranscription(ep.id) } }
                    else scope.launch { graph.repo.restartTranscription(ep.id) }
                },
                onDiagnostics = openDiagnostics,
            )
        }
        TranscriptState.NONE -> if (partial || ep.transcriptProgress > 0f) {
            TranscriptionStatusCard(
                phase = TranscriptionPhase.PAUSED, detail = stringResource(R.string.paused_text),
                action = stringResource(R.string.action_continue), onAction = { transcribe() },
            )
        } else Banner {
            Text(stringResource(R.string.no_transcript_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.no_transcript_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { transcribe() }) {
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
internal fun SegmentItem(
    segment: Segment,
    words: List<Word>?,
    language: String?,
    japanese: com.ascolta.app.data.JapaneseScript? = null,
    /** The words that can be selected (character ranges), with or without word times. */
    selectRanges: List<IntRange> = emptyList(),
    active: Boolean,
    activeWord: Int,
    showOriginal: Boolean,
    showTranslation: Boolean,
    textScale: Float,
    onSeek: () -> Unit,
    onWord: (String) -> Unit,
    /** Words of this sentence in the learner's selection (indices), or null. */
    selected: IntRange? = null,
    /** A selection is being made: taps extend it instead of opening the word. */
    selecting: Boolean = false,
    /** Long press on the time (-1 = the whole sentence), or a tap on a word (index) while [selecting]. */
    onSelect: (Int) -> Unit = {},
    /** Selecting by sliding: where this sentence's words are, and the finger's moves (window positions). */
    dragSelect: DragSelect? = null,
    index: Int = -1,
    onDragStart: (androidx.compose.ui.geometry.Offset) -> Unit = {},
    onDragMove: (androidx.compose.ui.geometry.Offset) -> Unit = {},
    onDragEnd: () -> Unit = {},
    /** Words not yet Familiar or Known (character ranges), softly highlighted; null when the option is off. */
    newWords: List<IntRange>? = null,
    /** Part of a detected ad: shown dimmed. */
    isAd: Boolean = false,
    /** Words by how common they are (character ranges and band), coloured; null when the option is off. */
    frequency: List<Pair<IntRange, Int>>? = null,
) {
    val bandColors = frequencyColors()
    val bg = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.09f) else MaterialTheme.colorScheme.surface
    val select by androidx.compose.runtime.rememberUpdatedState(onSelect)
    val seek by androidx.compose.runtime.rememberUpdatedState(onSeek)
    // Long press a word, slide to the last word wanted, release: the words in between are selected.
    val placed = remember { SentenceLayout() }
    val ranges by androidx.compose.runtime.rememberUpdatedState(selectRanges)
    val dragStart by androidx.compose.runtime.rememberUpdatedState(onDragStart)
    val dragMove by androidx.compose.runtime.rememberUpdatedState(onDragMove)
    val dragEnd by androidx.compose.runtime.rememberUpdatedState(onDragEnd)
    if (dragSelect != null) androidx.compose.runtime.DisposableEffect(dragSelect, index) {
        val target = DragSelect.Target(card = { placed.card }, wordAt = { p -> placed.charAt(p)?.let { wordIndexAt(ranges, it) } ?: -1 })
        dragSelect.register(index, target)
        onDispose { dragSelect.unregister(index, target) }
    }
    val slide = Modifier
        .onGloballyPositioned { placed.area = it }
        .pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { p -> placed.area?.takeIf { it.isAttached }?.let { dragStart(it.localToWindow(p)) } },
                onDrag = { change, _ ->
                    change.consume()
                    placed.area?.takeIf { it.isAttached }?.let { dragMove(it.localToWindow(change.position)) }
                },
                onDragEnd = { dragEnd() },
                onDragCancel = { dragEnd() },
            )
        }
    Column(
        Modifier
            .onGloballyPositioned { placed.card = it }
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .then(if (isAd && !active) Modifier.alpha(0.45f) else Modifier),
    ) {
        Text(
            if (isAd) formatTime(segment.startMs) + " · " + stringResource(R.string.ad_label) else formatTime(segment.startMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            // Tap: play from here. Long press: select the whole sentence (e.g. to shadow it).
            modifier = Modifier.pointerInput(segment.startMs) { detectTapGestures(onTap = { seek() }, onLongPress = { select(-1) }) }
                .padding(bottom = 2.dp),
        )
        val textStyle = MaterialTheme.typography.bodyLarge.copy(
            fontSize = (18 * textScale).sp,
            lineHeight = (26 * textScale).sp,
            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        )
        val jaTokens = remember(segment.text, japanese) { japanese?.let { com.ascolta.app.data.Japanese.tokens(segment.text) } }
        // The learner's selection in this sentence, as characters.
        val selectedChars: IntRange? = selected?.let { sel ->
            val from = if (sel.first < 0 || selectRanges.isEmpty()) 0 else selectRanges.getOrNull(sel.first)?.takeIf { !it.isEmpty() }?.first ?: 0
            val to = if (sel.first < 0 || selectRanges.isEmpty()) segment.text.length
            else selectRanges.getOrNull(sel.last)?.takeIf { !it.isEmpty() }?.let { it.last + 1 } ?: segment.text.length
            if (to > from) from until to else null
        }
        if (showOriginal && japanese != null && jaTokens != null) {
            JapaneseSentence(
                jaTokens, japanese, activeWord, textStyle,
                selectedChars = selectedChars,
                tokenColor = frequency?.let { f -> { token: com.ascolta.app.data.JaToken -> f.firstOrNull { it.first.first == token.start }?.let { bandColors[it.second] } } },
                onTap = { token -> if (selecting) onSelect(wordIndexAt(selectRanges, token.start)) else onWord(token.surface) },
                onTokenPlaced = { start, coords -> placed.tokens[start] = coords },
                modifier = slide,
            )
        } else if (showOriginal) {
            val wordRanges = remember(segment, words) { wordRanges(segment.text, words) }
            val highlight = MaterialTheme.colorScheme.primary
            val selectColor = MaterialTheme.colorScheme.tertiary
            val newColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)
            val text = remember(segment.text, activeWord, wordRanges, highlight, selectedChars, newWords, newColor, frequency, bandColors) {
                buildAnnotatedString {
                    append(segment.text)
                    // How common each word is: coloured text.
                    frequency?.forEach { (r, band) -> if (r.last < segment.text.length) addStyle(SpanStyle(color = bandColors[band]), r.first, r.last + 1) }
                    // New words: a light tint, the same for all (not distracting).
                    newWords?.forEach { r -> if (r.last < segment.text.length) addStyle(SpanStyle(background = newColor), r.first, r.last + 1) }
                    wordRanges.getOrNull(activeWord)?.let { range ->
                        addStyle(
                            SpanStyle(background = highlight.copy(alpha = 0.22f), fontWeight = FontWeight.SemiBold),
                            range.first, range.last + 1,
                        )
                    }
                    // The learner's selection.
                    selectedChars?.let { r -> addStyle(SpanStyle(background = selectColor.copy(alpha = 0.35f)), r.first, r.last + 1) }
                }
            }
            TappableText(
                text,
                MaterialTheme.typography.bodyLarge.copy(
                    fontSize = (18 * textScale).sp,
                    lineHeight = (26 * textScale).sp,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                ),
                onTap = { offset ->
                    if (selecting) onSelect(wordIndexAt(selectRanges, offset))
                    // The dictionary word under the finger (not the whole run of letters, which in
                    // Japanese or Chinese would be the whole sentence).
                    else WordSplitter.wordAt(segment.text, offset, language)?.let(onWord)
                },
                modifier = slide,
                onLayout = { coords, layout -> placed.text = coords; placed.textLayout = layout },
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

/** Text where a tap or a long press reports the character under the finger. */
@Composable
private fun TappableText(
    text: AnnotatedString,
    style: TextStyle,
    onTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onLayout: (androidx.compose.ui.layout.LayoutCoordinates, TextLayoutResult) -> Unit = { _, _ -> },
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // The gesture detector lives as long as the text: always call the latest handlers (otherwise a
    // tap after a long press still acts as if nothing were selected and opens the dictionary).
    val tap by androidx.compose.runtime.rememberUpdatedState(onTap)
    Text(
        text,
        style = style,
        onTextLayout = { layout = it },
        modifier = modifier
            .onGloballyPositioned { c -> layout?.let { onLayout(c, it) } }
            .pointerInput(text.text) {
                detectTapGestures(onTap = { pos -> layout?.let { tap(it.getOffsetForPosition(pos)) } })
            },
    )
}

/** The timed word at character [offset] (or the nearest one before it); -1 without word times. */
private fun wordIndexAt(ranges: List<IntRange>, offset: Int): Int {
    if (ranges.isEmpty()) return -1
    val hit = ranges.indexOfFirst { !it.isEmpty() && offset in it }
    if (hit >= 0) return hit
    return ranges.indexOfLast { !it.isEmpty() && it.first <= offset }.coerceAtLeast(0)
}

/**
 * Checks the learner's word [suggested] in place of [lookup]'s word: real, close to what was
 * heard, different, and making sense in the whole sentence (translated there and back). A
 * plausible one corrects the sentence and is rewarded (once per word, 20 a day).
 */
private suspend fun checkSuggestion(
    context: android.content.Context,
    lookup: WordLookup,
    suggested: String,
    language: String,
    episode: EpisodeEntity?,
    podcastTitle: String?,
): WrongWordResult {
    val graph = context.graph
    val lists = graph.wordLists
    val sentence = lookup.segment.text
    val at = sentence.indexOf(lookup.word).takeIf { it >= 0 } ?: sentence.lowercase().indexOf(lookup.word.lowercase())
    val languageName = com.ascolta.app.data.languageName(language)
    if (at < 0) return WrongWordResult(com.ascolta.app.data.WordCheck.Verdict.EMPTY, suggested, languageName = languageName)
    val range = at until at + lookup.word.length
    val quick = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        com.ascolta.app.data.WordCheck.quick(lookup.word, suggested, language, lists) { w -> runCatching { graph.dictionary.lookup(w, language) }.getOrNull() != null }
    }
    if (quick != null) return WrongWordResult(quick, suggested.trim(), languageName = languageName)
    val newSentence = com.ascolta.app.data.WordCheck.replace(sentence, range, suggested)
    val native = graph.settings.nativeLanguage.value
    // The whole new sentence translated, then back; and the old one's round trip, to compare.
    val there = if (native == language) null else runCatching { graph.repo.translateText(newSentence, language, native) }.getOrNull()
    val back = there?.let { runCatching { graph.repo.translateText(it, native, language) }.getOrNull() }
    val backOld = if (back == null) null else runCatching {
        graph.repo.translateText(lookup.segment.translation ?: graph.repo.translateText(sentence, language, native), native, language)
    }.getOrNull()
    val meaning = if (there == null) null else com.ascolta.app.data.WordCheck.meaning(suggested, newSentence, sentence, there, back, backOld, native, lists)
    com.ascolta.app.data.AppLog.d("WrongWord") {
        "\"${lookup.word}\" → \"$suggested\": ${meaning ?: "plausible"}; there \"${there?.take(80)}\"; back \"${back?.take(80)}\"; old back \"${backOld?.take(80)}\""
    }
    if (meaning != null) return WrongWordResult(meaning, suggested.trim(), newSentence, there, languageName = languageName)
    val learned = graph.repo.editSentence(episode, lookup.segment, newSentence)
    val key = "${episode?.id}:${lookup.segment.startMs}:${range.first}"
    // Coins only for listening in a language other than the learner's native one.
    val foreign = com.ascolta.app.data.FishGame.isForeign(language, native)
    val rewarded = foreign && graph.universe.rewardCatch(key)
    if (rewarded) graph.progress.recordCatch()
    com.ascolta.app.data.Analytics.track("Transcript.mistakeCaught", mapOf("language" to language, "rewarded" to rewarded.toString()))
    return WrongWordResult(
        com.ascolta.app.data.WordCheck.Verdict.PLAUSIBLE, suggested.trim(), newSentence, there,
        coins = if (rewarded) com.ascolta.app.data.FishGame.CATCH_COINS else 0,
        learned = learned, nativeLanguage = !foreign, podcast = podcastTitle, meaningUnchecked = there == null || back == null, languageName = languageName,
    )
}

/** Colours of the frequency bands (top 1,000 · up to 5,000 · up to 15,000), lighter on a dark theme. */
@Composable
internal fun frequencyColors(): List<Color> {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark) listOf(Color(0xFF6FDC97), Color(0xFF7DB8FF), Color(0xFFD29BFF))
    else listOf(Color(0xFF1E8A4C), Color(0xFF1F63C2), Color(0xFF8A3FC4))
}

/** What the colours mean, above the transcript. */
@Composable
private fun FrequencyLegend() {
    val colors = frequencyColors()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(R.string.frequency_band_1, R.string.frequency_band_2, R.string.frequency_band_3).forEachIndexed { i, label ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(colors[i], androidx.compose.foundation.shape.CircleShape))
                Text(" " + stringResource(label), style = MaterialTheme.typography.labelSmall, color = colors[i])
            }
        }
    }
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

/**
 * Words in this episode: the share of words already Familiar or Known, and the new words that
 * come up most (worth learning before or while listening). Ads aren't counted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpisodeWordsSheet(texts: List<String>, levels: Map<String, Int>, common: Set<String>, onWord: (String) -> Unit, onDismiss: () -> Unit) {
    val (share, newWords) = remember(texts, levels, common) { com.ascolta.app.data.KnownWords.coverage(texts, levels, common) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text(stringResource(R.string.episode_words), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.episode_words_known, (share * 100).toInt(), newWords.size),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(progress = { share }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            Text(stringResource(R.string.episode_words_top), style = MaterialTheme.typography.labelLarge)
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(newWords.take(40).size) { i ->
                    val (word, count) = newWords[i]
                    ListItem(
                        headlineContent = { Text(word) },
                        trailingContent = { Text("×$count", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        modifier = Modifier.clickable { onWord(word) },
                    )
                }
            }
            Text(stringResource(R.string.episode_words_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** New · Seen · Familiar · Known ✓, plus "Ignore" (names, interjections). */
@Composable
private fun FamiliarityRow(level: Int?, onSet: (Int) -> Unit) {
    val K = com.ascolta.app.data.KnownWords
    val labels = listOf(R.string.level_new, R.string.level_seen, R.string.level_familiar, R.string.level_known)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.familiarity_title), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            if (level != null && !K.highlighted(level)) {
                Text(
                    if (level == K.IGNORED) stringResource(R.string.level_ignored) else "✓ " + stringResource(labels[level]),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            K.LEVELS.forEach { l ->
                FilterChip(
                    selected = (level ?: K.NEW) == l,
                    onClick = { onSet(l) },
                    label = { Text(stringResource(labels[l]) + if (l == K.KNOWN) " ✓" else "") },
                )
            }
        }
        TextButton(onClick = { onSet(if (level == K.IGNORED) K.NEW else K.IGNORED) }, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(if (level == K.IGNORED) R.string.level_unignore else R.string.level_ignore), style = MaterialTheme.typography.labelMedium)
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
    onPlayWord: (Long, Long) -> Unit,
    canPlayWord: Boolean,
    onDismiss: () -> Unit,
    /** The long word lists, to tell whether the word was probably misheard; null for other languages. */
    wordLists: com.ascolta.app.data.WordLists? = null,
    podcastTitle: String? = null,
    /** Opens the sentence's edit dialog ("Edit anyway"). */
    onEditSentence: () -> Unit = {},
) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val translation by produceState<String?>(null, lookup) {
        value = runCatching { graph.repo.translateWord(lookup.word, language) }
            .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; context.getString(R.string.word_translation_unavailable, it.message ?: "") }
    }
    // Dictionary meanings (Wiktionary), in the learner's language when it isn't English.
    val entry by produceState<Pair<com.ascolta.app.data.Dictionary.Entry, List<String>>?>(null, lookup) {
        val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { graph.dictionary.lookup(lookup.word, language) }.getOrNull()
                ?: runCatching {
                    // A compound the dictionary doesn't have: its parts ("hundkläder" = hund + kläder).
                    val parts = wordLists?.let { com.ascolta.app.data.DoubtfulWords.parts(lookup.word, language, it) } ?: return@runCatching null
                    val entries = parts.map { graph.dictionary.lookup(it, language) ?: return@runCatching null }
                    com.ascolta.app.data.Dictionary.Entry(
                        word = parts.joinToString(" + "), pos = "",
                        meanings = entries.mapIndexed { i, e -> "${parts[i]}: ${e.meanings.firstOrNull().orEmpty()}" },
                        romanization = null,
                    )
                }.getOrNull()
        } ?: return@produceState
        val native = graph.settings.nativeLanguage.value
        val meanings = if (native == "en") found.meanings else runCatching {
            graph.repo.translateText(found.meanings.joinToString("\n"), "en", native).split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        }.getOrDefault(found.meanings)
        value = found to meanings
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
            // Hear the word: as the speaker said it (from its timing in the sentence), or the phone's voice.
            val timing = remember(lookup) {
                val seg = lookup.segment
                val words = com.ascolta.app.data.WordSplitter.timedWords(seg.text, seg.words, seg.startMs, seg.endMs, language)
                val i = words?.indexOfFirst { it.text.equals(lookup.word, ignoreCase = true) } ?: -1
                if (words == null || i < 0) null
                else words[i].startMs to (words.getOrNull(i + 1)?.startMs ?: seg.endMs).coerceAtMost(words[i].startMs + 2_500)
            }
            var noVoice by remember { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                if (timing != null && canPlayWord) {
                    AssistChip(
                        onClick = { onPlayWord(timing.first, timing.second) },
                        label = { Text(stringResource(R.string.hear_podcast)) },
                        leadingIcon = { Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)) },
                        shape = androidx.compose.foundation.shape.CircleShape,
                    )
                }
                AssistChip(
                    onClick = {
                        noVoice = com.ascolta.app.data.Speech.speak(context, lookup.word, language) == com.ascolta.app.data.Speech.Result.NO_VOICE
                    },
                    label = { Text(stringResource(R.string.hear_voice)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, null, Modifier.size(18.dp)) },
                    shape = androidx.compose.foundation.shape.CircleShape,
                )
            }
            // The word in Google Translate (the app if installed).
            AssistChip(
                onClick = { WebLookup.translate(context, lookup.word, language, graph.settings.nativeLanguage.value) },
                label = { Text(stringResource(R.string.google_translate)) },
                leadingIcon = { Icon(Icons.Filled.Translate, null, Modifier.size(18.dp)) },
                shape = androidx.compose.foundation.shape.CircleShape,
            )
            // "Wrong word?": the learner says what they heard; a plausible correction is rewarded.
            var wrongOpen by remember(lookup) { mutableStateOf(false) }
            var suggestion by remember(lookup) { mutableStateOf("") }
            var checking by remember(lookup) { mutableStateOf(false) }
            var checked by remember(lookup) { mutableStateOf<WrongWordResult?>(null) }
            val speak = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
            ) { r ->
                r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { suggestion = it.trim().trimEnd('.', '!', '?'); checked = null }
            }
            val canSpeak = remember { android.speech.SpeechRecognizer.isRecognitionAvailable(context) }
            if (!wrongOpen) {
                androidx.compose.material3.OutlinedButton(
                    onClick = { wrongOpen = true },
                    modifier = Modifier.padding(top = 4.dp),

                ) { Text(stringResource(R.string.wrong_word)) }
            } else {
                WrongWordPanel(
                    heard = lookup.word, input = suggestion, onInput = { suggestion = it; checked = null },
                    canSpeak = canSpeak,
                    onSpeak = {
                        runCatching {
                            speak.launch(
                                android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, language)
                                    .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.wrong_word_prompt)),
                            )
                        }
                    },
                    onPlayWord = timing?.takeIf { canPlayWord }?.let { (from, to) -> { onPlayWord(from, to) } },
                    checking = checking, result = checked,
                    onCheck = {
                        checking = true
                        scope.launch {
                            checked = checkSuggestion(context, lookup, suggestion, language, episode, podcastTitle)
                            checking = false
                        }
                    },
                    onEditAnyway = onEditSentence,
                )
            }
            if (noVoice) {
                // No phone voice for this language: explain and offer to install one.
                Text(stringResource(R.string.no_tts_voice, com.ascolta.app.data.languageName(language)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(android.content.Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }) { Text(stringResource(R.string.install_voice)) }
            }
            // How familiar the word is (when known-word tracking is on): Familiar and Known remove
            // its highlight in transcripts.
            val tracking by graph.settings.highlightNewWords.flow.collectAsState()
            if (tracking) {
                val levels by remember(language) { graph.knownWords.levels(language) }.collectAsState()
                val level = levels[com.ascolta.app.data.KnownWords.key(lookup.word)]
                Spacer(Modifier.height(8.dp))
                FamiliarityRow(level) { graph.knownWords.set(language, lookup.word, it) }
            }
            Spacer(Modifier.height(4.dp))
            if (translation == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            else Text(translation!!, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            entry?.let { (e, meanings) ->
                // From the dictionary: the word's dictionary form, how it reads, and its meanings.
                Spacer(Modifier.height(8.dp))
                Text(
                    listOfNotNull(
                        e.word.takeIf { !it.equals(lookup.word, ignoreCase = true) },
                        e.romanization,
                        e.pos.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                meanings.take(5).forEachIndexed { i, m ->
                    Text("${i + 1}. $m", style = MaterialTheme.typography.bodyMedium)
                }
                Text(stringResource(R.string.dictionary_credit), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
                                    // The dictionary's meanings when there are some (more precise than one guess).
                                    translation = entry?.second?.take(3)?.joinToString("; ") ?: translation.orEmpty(),
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
internal fun Controls(
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
    val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 500
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = if (compact) 2.dp else 8.dp)) {
            Slider(
                value = dragging ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { onSeek((it * duration).toLong()) }
                    dragging = null
                },
                enabled = duration > 0,
            )
            if (duration <= 0) {
                // Length unknown (no duration in the feed, and not downloaded): say why the bar can't move.
                Text(stringResource(R.string.length_unknown), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth()) {
                Text(formatTime(dragging?.let { (it * duration).toLong() } ?: position), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                Text(if (duration > 0) "−" + formatTime((duration - (dragging?.let { (it * duration).toLong() } ?: position)).coerceAtLeast(0)) else "--:--", style = MaterialTheme.typography.labelSmall)
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
                FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(if (compact) 48.dp else 64.dp)) {
                    if (buffering) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    else Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(R.string.play_pause), Modifier.size(36.dp))
                }
                IconButton(onClick = onNext) { Icon(Icons.Filled.SkipNext, stringResource(R.string.next_sentence)) }
                IconButton(onClick = onRepeat) { Icon(Icons.Filled.Replay, stringResource(R.string.repeat_sentence)) }
            }
        }
    }
}

/** Docked selection actions; playback controls stay available below. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun SelectionBar(
    text: String,
    canJoin: Boolean,
    joined: Boolean,
    onJoin: (Boolean) -> Unit,
    onSave: () -> Unit,
    onShadow: () -> Unit,
    onListen: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onClose: () -> Unit,
    onTranslate: () -> Unit = {},
    /** The selection in the learner's language (the app's translator), null while it comes. */
    translation: String? = null,
    translating: Boolean = false,
    saving: Boolean = false,
) {
    var more by remember { mutableStateOf(false) }
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().heightIn(max = 200.dp).androidxVerticalScroll().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "“$text”", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.action_close)) }
            }
            when {
                translation != null -> Text(
                    translation, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.primary, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                translating -> Text("…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (more && canJoin) {
                // The model sometimes ends a sentence too early: let the learner drop the stray "." or "?".
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.select_join_question), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(
                        selected = !joined, onClick = { onJoin(false) },
                        label = { Text(stringResource(R.string.select_keep_punctuation)) },
                    )
                    androidx.compose.material3.FilterChip(
                        selected = joined, onClick = { onJoin(true) },
                        label = { Text(stringResource(R.string.select_remove_punctuation)) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave, enabled = !saving, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.select_save), maxLines = 1)
                }
                OutlinedButton(onClick = onListen, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.listen_selection), maxLines = 1)
                }
                IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, stringResource(R.string.select_copy)) }
                IconButton(onClick = { more = !more }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more)) }
            }
            if (more) {
                TextButton(onClick = onShadow) { Text(stringResource(R.string.select_shadow)) }
                TextButton(onClick = onEdit) { Text(stringResource(R.string.edit_sentence)) }
                TextButton(onClick = onTranslate) { Text(stringResource(R.string.google_translate)) }
            }
        }
    }
}
