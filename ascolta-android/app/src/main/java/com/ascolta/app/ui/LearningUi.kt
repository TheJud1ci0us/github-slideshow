package com.ascolta.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ascolta.app.R
import com.ascolta.app.data.VocabEntity
import com.ascolta.app.work.TranscriptionPhase

@Composable
internal fun Modifier.androidxVerticalScroll(): Modifier = verticalScroll(rememberScrollState())

internal fun filterLearningCards(cards: List<VocabEntity>, kind: Int, query: String): List<VocabEntity> {
    val needle = query.trim()
    return cards.filter { card ->
        (kind == 0 || (kind == 2) == card.isSentence) &&
            (needle.isEmpty() || listOfNotNull(card.word, card.translation, card.sentence, card.sentenceTranslation)
                .any { it.contains(needle, ignoreCase = true) })
    }
}

@Composable
internal fun SavedLearningRow(word: VocabEntity, onClick: () -> Unit, onListen: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(word.word, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(word.translation, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = { IconButton(onClick = onListen) { Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.listen_selection)) } },
    )
}

@Composable
internal fun TranscriptionStatusCard(
    phase: TranscriptionPhase,
    detail: String,
    action: String,
    onAction: () -> Unit,
    bufferedMs: Long = -1,
    error: String? = null,
    onRestart: (() -> Unit)? = null,
    onDiagnostics: (() -> Unit)? = null,
    initiallyExpanded: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val label = stringResource(when (phase) {
        TranscriptionPhase.WAITING -> R.string.phase_waiting
        TranscriptionPhase.DOWNLOADING -> R.string.phase_downloading
        TranscriptionPhase.LOADING -> R.string.phase_loading
        TranscriptionPhase.PROCESSING -> R.string.phase_processing
        TranscriptionPhase.READY_AHEAD -> R.string.phase_ready
        TranscriptionPhase.PAUSED -> R.string.phase_paused
        TranscriptionPhase.TRANSLATING -> R.string.translating
        TranscriptionPhase.FAILED -> R.string.phase_failed
    })
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.heightIn(max = 230.dp).androidxVerticalScroll().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { expanded = !expanded }, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(label, style = MaterialTheme.typography.labelLarge, color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        if (bufferedMs > 0 && (phase == TranscriptionPhase.READY_AHEAD || phase == TranscriptionPhase.PROCESSING)) Text(
                            stringResource(R.string.buffer_ready, (bufferedMs / 60_000).toInt(), ((bufferedMs / 1000) % 60).toInt()),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(stringResource(if (expanded) R.string.details_less else R.string.details_more), style = MaterialTheme.typography.labelSmall)
                    }
                }
                TextButton(onClick = onAction, modifier = Modifier.widthIn(max = 150.dp)) { Text(action) }
            }
            if (expanded) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                onDiagnostics?.let { TextButton(onClick = it) { Text(stringResource(R.string.section_diagnostics)) } }
                onRestart?.let { TextButton(onClick = it) { Text(stringResource(R.string.stuck_restart)) } }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** The entire collection scrolls, including its actions, on landscape and large-text screens. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SavedWordsContent(
    words: List<VocabEntity>, due: Int, query: String, kind: Int, exporting: Boolean,
    listState: LazyListState = rememberLazyListState(), modifier: Modifier = Modifier,
    onQuery: (String) -> Unit, onKind: (Int) -> Unit, onReview: () -> Unit, onExport: () -> Unit,
    onWord: (VocabEntity) -> Unit, onListen: (VocabEntity) -> Unit,
) {
    val shown = remember(words, kind, query) { filterLearningCards(words, kind, query) }
    LazyColumn(modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 96.dp)) {
        if (words.isNotEmpty()) item(key = "review") {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (due > 0) R.string.review_ready else R.string.review_caught_up), style = MaterialTheme.typography.titleMedium)
                    Text(if (due > 0) stringResource(R.string.cards_due, due) else stringResource(R.string.cards_none_due), style = MaterialTheme.typography.bodyMedium)
                    if (due > 0) Button(onClick = onReview, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cards_review)) }
                }
            }
        }
        item(key = "export") {
            OutlinedButton(onClick = onExport, enabled = words.isNotEmpty() && !exporting,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(stringResource(if (exporting) R.string.anki_exporting else R.string.export_anki_short))
            }
        }
        if (words.isNotEmpty()) {
            item(key = "search") { OutlinedTextField(
                value = query, onValueChange = onQuery,
                label = { Text(stringResource(R.string.search_saved), maxLines = 1, overflow = TextOverflow.Ellipsis) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) }
            item(key = "filters") { FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.words_all, R.string.words_only_words, R.string.words_only_sentences).forEachIndexed { k, res ->
                    FilterChip(selected = kind == k, onClick = { onKind(k) }, label = { Text(stringResource(res)) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            } }
        }
        if (words.isEmpty()) item(key = "empty") {
            Text(stringResource(R.string.words_empty_guide), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (shown.isEmpty()) item(key = "no-results") {
            Text(stringResource(R.string.search_no_results), Modifier.padding(24.dp))
        }
        items(shown, key = { it.id }) { word ->
            SavedLearningRow(word, { onWord(word) }, { onListen(word) })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}

@Composable
internal fun SavedCardDetails(
    word: VocabEntity, reading: String? = null, onEpisode: (Long) -> Unit,
    onAudio: (String) -> Unit, onSpeak: () -> Unit, onDelete: () -> Unit,
) {
    var deleting by rememberSaveable(word.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().androidxVerticalScroll().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(word.word, style = MaterialTheme.typography.headlineSmall)
        reading?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(word.translation, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        word.sentence?.let { Text(highlightWord(it, word.word)) }
        word.sentenceTranslation?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        word.episodeId?.let { id -> TextButton(onClick = { onEpisode(id) }) { Text(word.episodeTitle ?: stringResource(R.string.open_episode)) } }
        word.audioPath?.takeIf { java.io.File(it).isFile }?.let { clip -> OutlinedButton(onClick = { onAudio(clip) }) { Text(stringResource(R.string.play_clip)) } }
        OutlinedButton(onClick = onSpeak) { Text(stringResource(R.string.hear_voice)) }
        word.myAudioPath?.takeIf { java.io.File(it).isFile }?.let { clip -> OutlinedButton(onClick = { onAudio(clip) }) { Text(stringResource(R.string.play_mine)) } }
        TextButton(onClick = { deleting = true }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false }, title = { Text(stringResource(R.string.delete_card_title)) }, text = { Text(word.word) },
        confirmButton = { TextButton(onClick = { deleting = false; onDelete() }) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
internal fun TranscriptSearchContent(
    segments: List<com.ascolta.app.data.Segment>, query: String, onQuery: (String) -> Unit, onSeek: (Long) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query, onValueChange = onQuery, label = { Text(stringResource(R.string.search_transcript)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        val results = remember(segments, query) {
            val needle = query.trim()
            if (needle.isBlank()) emptyList() else segments.filter {
                it.text.contains(needle, true) || it.translation?.contains(needle, true) == true
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(vertical = 16.dp)) {
            if (results.isEmpty()) item { Text(stringResource(if (query.isBlank()) R.string.search_transcript_hint else R.string.search_no_results), Modifier.padding(16.dp)) }
            items(results) { segment ->
                ListItem(
                    headlineContent = { Text(segment.text, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(listOfNotNull(formatTime(segment.startMs), segment.translation).joinToString(" · "), maxLines = 3, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.clickable { onSeek(segment.startMs) },
                )
            }
        }
    }
}
