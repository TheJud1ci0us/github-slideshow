package com.ascolta.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ascolta.app.graph
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordsScreen() {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val words by graph.repo.vocab.collectAsState(initial = emptyList())

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Words (${words.size})") },
                actions = {
                    if (words.isNotEmpty()) {
                        IconButton(onClick = {
                            scope.launch {
                                val tsv = graph.repo.exportVocabTsv()
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "Ascolta words")
                                    putExtra(Intent.EXTRA_TEXT, tsv)
                                }
                                context.startActivity(Intent.createChooser(send, "Export words (Anki: tab-separated)"))
                            }
                        }) { Icon(Icons.Filled.IosShare, "Export") }
                    }
                },
            )
        },
    ) { padding ->
        if (words.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Tap any word in a transcript to translate it, then save it here.\n\n" +
                        "Export the list (tab-separated) to import into Anki or another flashcard app.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(words, key = { it.id }) { w ->
                    ListItem(
                        headlineContent = { Text("${w.word}  →  ${w.translation}") },
                        supportingContent = {
                            Column {
                                w.sentence?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                w.sentenceTranslation?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        },
                        trailingContent = {
                            IconButton(onClick = { scope.launch { graph.repo.deleteWord(w.id) } }) {
                                Icon(Icons.Filled.Delete, "Delete")
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
