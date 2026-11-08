package com.ascolta.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ascolta.app.data.LANGUAGES
import com.ascolta.app.R
import com.ascolta.app.graph
import androidx.compose.ui.res.stringResource
import com.ascolta.app.net.DirectoryResult
import kotlinx.coroutines.launch

/** Starter searches per learning language. */
private val SUGGESTIONS = mapOf(
    "it" to listOf("Italiano semplice", "Learn Italian", "Coffee Break Italian", "storia", "notizie", "Easy Italian"),
    "es" to listOf("Español sencillo", "Learn Spanish", "Coffee Break Spanish", "noticias"),
    "fr" to listOf("Français facile", "Learn French", "Coffee Break French", "journal en français facile"),
    "de" to listOf("Deutsch lernen", "Learn German", "Slow German", "Nachrichten"),
    "pt" to listOf("Português", "Learn Portuguese", "Brazilian Portuguese", "notícias"),
    "ja" to listOf("Learn Japanese", "Nihongo con Teppei", "日本語", "ニュース"),
    "zh" to listOf("Learn Chinese", "Mandarin", "中文", "Slow Chinese"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(initialQuery: String?, openPodcast: (Long) -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val learning by graph.settings.learningLanguage.flow.collectAsState()
    val subscribed by graph.repo.subscribedFeedUrls.collectAsState(initial = emptyList())

    var query by rememberSaveable { mutableStateOf(initialQuery.orEmpty()) }
    var results by remember { mutableStateOf<List<DirectoryResult>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val subscribing = remember { mutableStateMapOf<String, Boolean>() }

    fun search(term: String) {
        if (term.isBlank()) return
        focus.clearFocus()
        loading = true
        error = null
        scope.launch {
            val country = LANGUAGES.firstOrNull { it.code == learning }?.country
            runCatching { graph.directory.search(term, country) }
                .onSuccess { results = it; if (it.isEmpty()) error = context.getString(R.string.no_results) }
                .onFailure { error = context.getString(R.string.search_failed, it.message ?: "") }
            loading = false
        }
    }

    LaunchedEffect(initialQuery) {
        if (!initialQuery.isNullOrBlank()) {
            query = initialQuery
            search(initialQuery)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_discover)) }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Search the directory of the language being learned (its country's store).
            LanguageBar(Modifier.padding(bottom = 6.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search(query) }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SUGGESTIONS[learning].orEmpty().forEach { s ->
                    AssistChip(onClick = { query = s; search(s) }, label = { Text(s) })
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let {
                Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            }
            if (results.isEmpty() && !loading && error == null) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopCenter) {
                    Text(
                        stringResource(R.string.discover_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(results.distinctBy { it.feedUrl }, key = { it.feedUrl }) { r ->
                    val isSubscribed = r.feedUrl in subscribed
                    ListItem(
                        headlineContent = { Text(r.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(listOfNotNull(r.author, r.genre).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        leadingContent = { Artwork(r.artworkUrl, 56.dp) },
                        trailingContent = {
                            when {
                                subscribing[r.feedUrl] == true -> SmallSpinner()
                                isSubscribed -> TextButton(onClick = {
                                    scope.launch { openPodcast(graph.repo.subscribe(r.feedUrl)) }
                                }) { Icon(Icons.Filled.Check, null); Text(stringResource(R.string.action_open)) }
                                else -> FilledTonalButton(onClick = {
                                    subscribing[r.feedUrl] = true
                                    scope.launch {
                                        runCatching { graph.repo.subscribe(r.feedUrl) }
                                            .onFailure { error = context.getString(R.string.feed_failed, it.message ?: "") }
                                        subscribing[r.feedUrl] = false
                                    }
                                }) { Text(stringResource(R.string.action_follow)) }
                            }
                        },
                    )
                }
            }
        }
    }
}
