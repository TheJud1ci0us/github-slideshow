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
import com.ascolta.app.data.GENRES
import com.ascolta.app.data.GENRE_LEARNING
import com.ascolta.app.data.LevelBand
import com.ascolta.app.data.LevelGuess
import androidx.compose.material3.FilterChip
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
    val settings = graph.settings
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val learning by settings.learningLanguage.flow.collectAsState()
    val levelsPref by settings.levels.flow.collectAsState()
    val interestsPref by settings.interests.flow.collectAsState()
    val subscribed by graph.repo.subscribedFeedUrls.collectAsState(initial = emptyList())

    var query by rememberSaveable { mutableStateOf(initialQuery.orEmpty()) }
    var results by remember { mutableStateOf<List<DirectoryResult>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val subscribing = remember { mutableStateMapOf<String, Boolean>() }
    // Theme (Apple genre) being browsed, and the level filter (null = all levels).
    var genre by rememberSaveable { mutableStateOf<Int?>(null) }
    val myBand = remember(learning, levelsPref) { LevelBand.of(settings.levelFor(learning)) }
    var band by remember(learning, levelsPref) { mutableStateOf(myBand) }
    var loadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun load(block: suspend () -> List<DirectoryResult>) {
        focus.clearFocus()
        loadJob?.cancel()
        loading = true
        error = null
        loadJob = scope.launch {
            runCatching { block() }
                .onSuccess { results = it; if (it.isEmpty()) error = context.getString(R.string.no_results) }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) error = context.getString(R.string.search_failed, it.message ?: "") }
            loading = false
        }
    }

    val country = LANGUAGES.firstOrNull { it.code == learning }?.country

    fun search(term: String) {
        if (term.isBlank()) return
        genre = null
        load { graph.directory.search(term, country) }
    }

    /** Podcasts of a theme in the learning language's store; for learners, courses in that language. */
    fun browse(genreId: Int) {
        genre = genreId
        query = ""
        load {
            val lang = learning
            if (genreId == GENRE_LEARNING) {
                val locale = java.util.Locale.forLanguageTag(lang)
                val names = listOf(locale.getDisplayLanguage(locale), locale.getDisplayLanguage(java.util.Locale.ENGLISH)).distinct()
                val found = names.flatMap { name ->
                    runCatching { graph.directory.searchGenre(name, GENRE_LEARNING, country) }.getOrDefault(emptyList()) +
                        runCatching { graph.directory.searchGenre(name, GENRE_LEARNING, "US") }.getOrDefault(emptyList())
                }.distinctBy { it.feedUrl }
                // Only courses for this language (not, say, English courses for Italians).
                found.filter { r -> names.any { n -> "${r.title} ${r.summary.orEmpty()}".contains(n, ignoreCase = true) } }
                    .ifEmpty { found }
            } else {
                graph.directory.topByGenre(genreId, country ?: "US")
                    .filter { lang == "en" || !LevelGuess.looksEnglish("${it.title} ${it.summary.orEmpty()}") }
            }
        }
    }

    LaunchedEffect(initialQuery) {
        if (!initialQuery.isNullOrBlank()) {
            query = initialQuery
            search(initialQuery)
        }
    }
    // Nothing searched yet: suggestions for this learner (courses for beginners, else a favourite theme).
    LaunchedEffect(learning) {
        if (initialQuery.isNullOrBlank() && query.isBlank()) {
            browse(if (myBand == LevelBand.BEGINNER) GENRE_LEARNING else settings.interestList().firstOrNull() ?: GENRE_LEARNING)
        }
    }

    val estimates = remember(results, genre) {
        results.associate { it.feedUrl to LevelGuess.estimate(it.title, it.summary, genre, it.genre) }
    }
    val atLevel = results.filter { band == null || estimates[it.feedUrl]?.band == band }
    val shown = atLevel.ifEmpty { results }
    val genres = remember(interestsPref) {
        val mine = settings.interestList()
        GENRES.sortedBy { g -> if (g.id == GENRE_LEARNING) -1 else mine.indexOf(g.id).let { if (it < 0) 100 else it } }
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
            // Level (CEFR) filter.
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = band == null, onClick = { band = null }, label = { Text(stringResource(R.string.level_all)) })
                LevelBand.entries.forEach { b ->
                    FilterChip(
                        selected = band == b,
                        onClick = { band = if (band == b) null else b },
                        label = { Text("${b.label} · ${stringResource(b.nameRes)}") },
                    )
                }
            }
            // Themes.
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                genres.forEach { g ->
                    FilterChip(selected = genre == g.id, onClick = { browse(g.id) }, label = { Text(stringResource(g.nameRes)) })
                }
                SUGGESTIONS[learning].orEmpty().forEach { s ->
                    AssistChip(onClick = { query = s; search(s) }, label = { Text(s) })
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let {
                Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            }
            if (results.isNotEmpty() && atLevel.isEmpty() && band != null) {
                Text(
                    stringResource(R.string.level_none_found, band!!.label),
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                items(shown.distinctBy { it.feedUrl }, key = { it.feedUrl }) { r ->
                    val isSubscribed = r.feedUrl in subscribed
                    val level = estimates[r.feedUrl]
                    ListItem(
                        headlineContent = { Text(r.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Column {
                                Text(listOfNotNull(r.author, r.genre).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                level?.let {
                                    Text(
                                        stringResource(R.string.level_estimated, it.label),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
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
