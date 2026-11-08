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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.School
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
    val now = remember(words) { System.currentTimeMillis() }
    val due = words.count { it.dueAt <= now }
    var adding by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.words_title, words.size)) },
                actions = {
                    if (words.isNotEmpty()) {
                        IconButton(onClick = {
                            scope.launch {
                                val tsv = graph.repo.exportVocabTsv()
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.export_subject))
                                    putExtra(Intent.EXTRA_TEXT, tsv)
                                }
                                context.startActivity(Intent.createChooser(send, context.getString(R.string.export_chooser)))
                            }
                        }) { Icon(Icons.Filled.IosShare, stringResource(R.string.export)) }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, stringResource(R.string.card_add)) }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LanguageBar(Modifier.padding(bottom = 4.dp))
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
            if (words.isEmpty()) {
                EmptyHint(stringResource(R.string.words_empty))
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(words, key = { it.id }) { w ->
                        ListItem(
                            headlineContent = {
                                Text(if (w.isSentence) w.word else "${w.word}  →  ${w.translation}")
                            },
                            supportingContent = {
                                Column {
                                    if (w.isSentence) {
                                        Text(w.translation, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
                                    } else {
                                        w.sentence?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                        w.sentenceTranslation?.let {
                                            Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            },
                            trailingContent = {
                                IconButton(onClick = { scope.launch { graph.repo.deleteWord(w.id) } }) {
                                    Icon(Icons.Filled.Delete, stringResource(R.string.action_delete))
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
    var front by remember { mutableStateOf("") }
    var back by remember { mutableStateOf("") }
    var sentence by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
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
                    label = { Text(stringResource(R.string.card_front, languageName(language))) },
                    singleLine = !sentence, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = back, onValueChange = { back = it },
                    label = { Text(stringResource(R.string.card_back)) },
                    supportingText = { Text(stringResource(R.string.card_back_hint)) },
                    singleLine = !sentence, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = front.isNotBlank() && !busy, onClick = {
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
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    var queue by remember { mutableStateOf<List<VocabEntity>?>(null) }
    var flipped by remember { mutableStateOf(false) }
    var reviewed by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { queue = graph.repo.dueCardsNow() }

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
                                Spacer(Modifier.height(16.dp))
                                Text(card.translation, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                                if (!card.isSentence) {
                                    card.sentence?.let {
                                        Spacer(Modifier.height(16.dp))
                                        Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
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
