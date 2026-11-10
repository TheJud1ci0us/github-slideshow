package com.ascolta.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ascolta.app.R
import com.ascolta.app.data.Flashcards
import com.ascolta.app.data.VocabEntity
import com.ascolta.app.data.languageName
import com.ascolta.app.graph
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordsScreen(openReview: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val words by graph.repo.vocab.collectAsState(initial = emptyList())
    // Japanese words are shown with their reading (hiragana and romaji).
    val learningNow by graph.settings.learningLanguage.flow.collectAsState()
    LaunchedEffect(learningNow) { if (learningNow == "ja") com.ascolta.app.data.Japanese.load() }
    val jaReady by com.ascolta.app.data.Japanese.ready.collectAsState()
    val now = remember(words) { System.currentTimeMillis() }
    val due = words.count { it.dueAt <= now }
    var adding by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }
    // Show all cards, only words, or only sentences.
    var kind by remember { mutableStateOf(0) }
    val shownWords = remember(words, kind) { when (kind) { 1 -> words.filter { !it.isSentence }; 2 -> words.filter { it.isSentence }; else -> words } }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.words_title, words.size), style = MaterialTheme.typography.headlineMedium) },
                actions = {
                    LanguageMenu()
                    if (words.isNotEmpty()) Box {
                        IconButton(onClick = { exportMenu = true }) { Icon(Icons.Filled.IosShare, stringResource(R.string.export)) }
                        androidx.compose.material3.DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                            // As text (any flashcard app).
                            androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.export_text)) }, onClick = {
                                exportMenu = false
                                scope.launch {
                                    val tsv = graph.repo.exportVocabTsv()
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.export_subject))
                                        putExtra(Intent.EXTRA_TEXT, tsv)
                                    }
                                    context.startActivity(Intent.createChooser(send, context.getString(R.string.export_chooser)))
                                }
                            })
                            // For Anki, with the speakers' audio.
                            androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.export_anki)) }, onClick = {
                                exportMenu = false
                                if (graph.requirePro("anki")) scope.launch {
                                    val zip = graph.repo.exportAnkiZip()
                                    val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", zip)
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/zip"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(send, context.getString(R.string.export_chooser)))
                                }
                            })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, stringResource(R.string.card_add)) }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (words.isNotEmpty()) {
                // Built-in flashcards: review what's due today.
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.School, null)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (due > 0) stringResource(R.string.cards_due, due) else stringResource(R.string.cards_none_due),
                            modifier = Modifier.weight(1f),
                        )
                        Button(enabled = due > 0, onClick = openReview) { Text(stringResource(R.string.cards_review)) }
                    }
                }
            }
            if (words.isNotEmpty()) {
                // All / Words / Sentences, as plain text tabs.
                Row(Modifier.padding(horizontal = 8.dp)) {
                    listOf(R.string.words_all, R.string.words_only_words, R.string.words_only_sentences).forEachIndexed { k, res ->
                        TextButton(onClick = { kind = k }) {
                            Text(stringResource(res), style = MaterialTheme.typography.titleSmall,
                                color = if (kind == k) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (words.isEmpty()) {
                EmptyHint(stringResource(R.string.words_empty))
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(shownWords, key = { it.id }) { w ->
                        ListItem(
                            headlineContent = {
                                val shown = if (w.language == "ja" && jaReady && !w.isSentence) {
                                    withReading(w.word, com.ascolta.app.data.Japanese.reading(w.word), com.ascolta.app.data.Japanese.romaji(w.word))
                                } else w.word
                                Text(if (w.isSentence) w.word else "$shown  →  ${w.translation}")
                            },
                            supportingContent = {
                                Column {
                                    if (w.isSentence) {
                                        Text(w.translation, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
                                    } else {
                                        // The sentence's translation is on the back of the flashcard.
                                        w.sentence?.let { Text(highlightWord(it, w.word), style = MaterialTheme.typography.bodySmall) }
                                    }
                                }
                            },
                            trailingContent = {
                                Row {
                                    // The word in the phone's voice.
                                    if (!w.isSentence) IconButton(onClick = { com.ascolta.app.data.Speech.speak(context, w.word, w.language) }) {
                                        Icon(Icons.Filled.RecordVoiceOver, stringResource(R.string.hear_voice))
                                    }
                                    // The sentence as it was said in the episode.
                                    w.audioPath?.let { clip ->
                                        IconButton(onClick = { com.ascolta.app.whisper.AudioClip.play(clip) }) {
                                            Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.play_clip))
                                        }
                                    }
                                    // The learner's own shadowing recording.
                                    w.myAudioPath?.takeIf { java.io.File(it).exists() }?.let { mine ->
                                        IconButton(onClick = { com.ascolta.app.whisper.AudioClip.play(mine) }) {
                                            Icon(Icons.Filled.Mic, stringResource(R.string.play_mine))
                                        }
                                    }
                                    IconButton(onClick = { scope.launch { graph.repo.deleteWord(w.id) } }) {
                                        Icon(Icons.Filled.Delete, stringResource(R.string.action_delete))
                                    }
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (adding) AddCardDialog(onDismiss = { adding = false })
}

/** Adds a word or a sentence as a flashcard; the translation is filled in if left empty. */
@Composable
private fun AddCardDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val language by graph.settings.learningLanguage.flow.collectAsState()
    val native by graph.settings.nativeLanguage.flow.collectAsState()
    var front by remember { mutableStateOf("") }
    var back by remember { mutableStateOf("") }
    var sentence by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var translating by remember { mutableStateOf(false) }
    // Fills the other box with the translation of this one, so it can be checked before adding.
    fun translate(text: String, from: String, to: String, into: (String) -> Unit) {
        translating = true
        scope.launch {
            runCatching { graph.repo.translateText(text.trim(), from, to) }
                .onSuccess { into(it.trim()) }
                .onFailure {
                    Toast.makeText(context, context.getString(R.string.translation_unavailable, it.message ?: ""), Toast.LENGTH_LONG).show()
                }
            translating = false
        }
    }
    @Composable
    fun translateButton(text: String, from: String, to: String, into: (String) -> Unit) {
        if (translating) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            IconButton(enabled = text.isNotBlank() && !busy, onClick = { translate(text, from, to, into) }) {
                Icon(androidx.compose.material.icons.Icons.Filled.Translate, stringResource(R.string.translating))
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.card_add_title, flagAndName(language))) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !sentence, onClick = { sentence = false })
                    Text(stringResource(R.string.card_word), Modifier.clickable { sentence = false })
                    Spacer(Modifier.width(12.dp))
                    RadioButton(selected = sentence, onClick = { sentence = true })
                    Text(stringResource(R.string.card_sentence), Modifier.clickable { sentence = true })
                }
                OutlinedTextField(
                    value = front, onValueChange = { front = it },
                    label = { Text(flagAndName(language)) },
                    trailingIcon = { translateButton(front, language, native) { back = it } },
                    singleLine = !sentence, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = back, onValueChange = { back = it },
                    label = { Text(flagAndName(native)) },
                    trailingIcon = { translateButton(back, native, language) { front = it } },
                    supportingText = { Text(stringResource(R.string.card_back_hint)) },
                    singleLine = !sentence, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = (front.isNotBlank() || back.isNotBlank()) && !busy && !translating, onClick = {
                busy = true
                scope.launch {
                    val added = graph.repo.addCard(front, back, sentence)
                    Toast.makeText(context, context.getString(if (added) R.string.card_added else R.string.already_saved), Toast.LENGTH_SHORT).show()
                    busy = false
                    onDismiss()
                }
            }) { Text(stringResource(R.string.action_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Flashcard review: the card's front, tap to see the back, then say how well you knew it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    var queue by remember { mutableStateOf<List<VocabEntity>?>(null) }
    var flipped by remember { mutableStateOf(false) }
    var shadowCard by remember { mutableStateOf<VocabEntity?>(null) }
    shadowCard?.let { c ->
        ShadowSheet(
            text = if (c.isSentence) c.word else c.sentence ?: c.word,
            translation = if (c.isSentence) c.translation else c.sentenceTranslation,
            language = c.language,
            loadOriginal = { c.audioPath?.takeIf { java.io.File(it).exists() } },
            onKeepRecording = { path -> scope.launch { graph.repo.setMyRecording(c, path) } },
            onDismiss = { shadowCard = null },
        )
    }
    var reviewed by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        queue = graph.repo.dueCardsNow()
        if (queue.orEmpty().any { it.language == "ja" }) com.ascolta.app.data.Japanese.load()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cards_review_title, reviewed, reviewed + (queue?.size ?: 0))) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        val card = queue?.firstOrNull()
        if (queue != null && card == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.cards_done, reviewed), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onBack) { Text(stringResource(R.string.action_done)) }
            }
            return@Scaffold
        }
        if (card == null) return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Card(Modifier.fillMaxWidth().weight(1f).clickable { flipped = !flipped }) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    AnimatedContent(targetState = flipped, label = "flip") { showBack ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(card.word, style = if (card.isSentence) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                            if (showBack) {
                                LaunchedEffect(card.id) { if (card.audioPath == null) com.ascolta.app.data.Speech.speak(context, card.word, card.language) }
                                if (card.language == "ja") {
                                    // The reading, once the learner has tried to read the kanji.
                                    val reading = remember(card.word) { com.ascolta.app.data.Japanese.reading(card.word) }
                                    val romaji = remember(card.word) { com.ascolta.app.data.Japanese.romaji(card.word) }
                                    listOfNotNull(reading, romaji).takeIf { it.isNotEmpty() }?.let {
                                        Text(it.joinToString(" · "), style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                                    }
                                }
                                Spacer(Modifier.height(16.dp))
                                Text(card.translation, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                                if (!card.isSentence) {
                                    card.sentence?.let {
                                        Spacer(Modifier.height(16.dp))
                                        Text(highlightWord(it, card.word), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                                    }
                                    card.sentenceTranslation?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, textAlign = TextAlign.Center,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            } else {
                                Spacer(Modifier.height(16.dp))
                                Text(stringResource(R.string.cards_tap_to_flip), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            card.audioPath?.let { clip ->
                // Hear it as the native speaker said it.
                LaunchedEffect(card.id, flipped) { if (flipped) com.ascolta.app.whisper.AudioClip.play(clip) }
                Row(Modifier.align(Alignment.CenterHorizontally)) {
                    TextButton(onClick = { com.ascolta.app.whisper.AudioClip.play(clip) }) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.play_clip))
                    }
                    // Repeat it after the speaker (works offline, even after the episode is deleted).
                    if (flipped) TextButton(onClick = { if (graph.allowPractice()) shadowCard = card }) {
                        Icon(Icons.Filled.Mic, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.select_shadow))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (!flipped) {
                Button(onClick = { flipped = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cards_show_answer)) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    fun answer(grade: Flashcards.Grade) {
                        scope.launch {
                            graph.repo.review(card, grade)
                            reviewed++
                            flipped = false
                            val rest = queue.orEmpty().drop(1)
                            // Forgotten cards come back at the end of this session.
                            queue = if (grade == Flashcards.Grade.AGAIN) rest + card.copy(reps = 0, intervalDays = 0f) else rest
                        }
                    }
                    OutlinedButton(onClick = { answer(Flashcards.Grade.AGAIN) }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text(stringResource(R.string.cards_again))
                    }
                    OutlinedButton(onClick = { answer(Flashcards.Grade.HARD) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.cards_hard)) }
                    FilledTonalButton(onClick = { answer(Flashcards.Grade.GOOD) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.cards_good)) }
                    Button(onClick = { answer(Flashcards.Grade.EASY) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.cards_easy)) }
                }
            }
            Spacer(Modifier.height(8.dp).size(0.dp))
        }
    }
}

/** The sentence with the saved word in bold, so its context stands out. */
@Composable
private fun highlightWord(sentence: String, word: String) = androidx.compose.ui.text.buildAnnotatedString {
    val i = sentence.lowercase().indexOf(word.lowercase())
    if (i < 0 || word.isBlank()) { append(sentence); return@buildAnnotatedString }
    append(sentence.substring(0, i))
    pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
    append(sentence.substring(i, i + word.length))
    pop()
    append(sentence.substring(i + word.length))
}
