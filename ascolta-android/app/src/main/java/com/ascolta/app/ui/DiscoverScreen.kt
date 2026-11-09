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
import androidx.compose.runtime.produceState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.items
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
        com.ascolta.app.data.Analytics.track("Discover.searched", mapOf("language" to learning)) // never the search text
        load { graph.directory.search(term, country) }
    }

    /** Podcasts of a theme in the learning language's store; for learners, courses in that language. */
    fun browse(genreId: Int) {
        genre = genreId
        com.ascolta.app.data.Analytics.track("Discover.themeBrowsed", mapOf("genre" to genreId.toString(), "language" to learning))
        query = ""
        load {
            val lang = learning
            val native = settings.nativeLanguage.value
            val course = genreId == GENRE_LEARNING
            val found = if (course) com.ascolta.app.data.Recommendations.courses(graph.directory, lang, country ?: "US")
            else graph.directory.topByGenre(genreId, country ?: "US")
            // Only podcasts really in the language: the store of a small language is often
            // mostly in a neighbouring one (e.g. Russian in Georgia's).
            val declared = graph.feedLanguage.ofAll(found.map { it.feedUrl })
            val kept = found.filter { com.ascolta.app.data.LanguageCheck.keep(it.title, it.summary, declared[it.feedUrl], lang, native, course) }
                .map { it.copy(language = declared[it.feedUrl]) }
            // Too few in this language's charts: add local podcasts found by the word "podcast".
            val word = com.ascolta.app.data.LanguageCheck.PODCAST_WORD[lang]
            if (!course && kept.size < 8 && word != null) {
                val local = runCatching { graph.directory.search(word, country ?: "US") }.getOrDefault(emptyList())
                    .filter { l -> kept.none { it.feedUrl == l.feedUrl } }
                val localLang = graph.feedLanguage.ofAll(local.map { it.feedUrl })
                kept + local.filter { com.ascolta.app.data.LanguageCheck.keep(it.title, it.summary, localLang[it.feedUrl], lang, native, false) }
                    .map { it.copy(language = localLang[it.feedUrl]) }
            } else kept
        }
    }

    LaunchedEffect(initialQuery) {
        if (!initialQuery.isNullOrBlank()) {
            query = initialQuery
            search(initialQuery)
        }
    }
    // Nothing searched yet: suggestions for this learner (courses for beginners, else a favourite theme).
    // "For you": suggestions for the language, favourite themes and level (shown before any search).
    val forYou by produceState<List<com.ascolta.app.data.Pick>?>(null, learning, interestsPref, levelsPref) {
        value = runCatching {
            com.ascolta.app.data.Recommendations.picks(graph.directory, graph.feedLanguage, learning, settings.nativeLanguage.value, settings.interestList(), myBand, 20)
        }.getOrDefault(emptyList())
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

    // Language each search result's feed declares, shown when it isn't the language learned.
    val resultLanguages by produceState<Map<String, String?>>(emptyMap(), results) {
        value = if (genre == null && results.isNotEmpty()) graph.feedLanguage.ofAll(results.take(40).map { it.feedUrl }) else emptyMap()
    }

    // One scrolling grid: the header scrolls away, so results get the whole screen.
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
        contentPadding = PaddingValues(bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, key = "header") {
            Column {
                Text(stringResource(R.string.tab_discover), style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp))
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
                    shape = androidx.compose.foundation.shape.CircleShape,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                // Categories: one row of small round-icon chips.
                androidx.compose.foundation.lazy.LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    items(genres, key = { it.id }) { g ->
                        FilterChip(
                            selected = genre == g.id,
                            onClick = { browse(g.id) },
                            label = { Text(stringResource(g.nameRes)) },
                            leadingIcon = { Icon(genreIcon(g.id), null, Modifier.size(18.dp)) },
                            shape = androidx.compose.foundation.shape.CircleShape,
                        )
                    }
                }
                // Level pills and starter searches in one row.
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = band == null, onClick = { band = null }, label = { Text(stringResource(R.string.level_all)) },
                        shape = androidx.compose.foundation.shape.CircleShape)
                    LevelBand.entries.forEach { b ->
                        FilterChip(
                            selected = band == b,
                            onClick = {
                                band = if (band == b) null else b
                                com.ascolta.app.data.Analytics.track("Discover.levelFilter", mapOf("band" to (band?.name ?: "all")))
                            },
                            label = { Text(b.label) },
                            shape = androidx.compose.foundation.shape.CircleShape,
                        )
                    }
                    SUGGESTIONS[learning].orEmpty().forEach { s ->
                        AssistChip(onClick = { query = s; search(s) }, label = { Text(s) }, shape = androidx.compose.foundation.shape.CircleShape)
                    }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                error?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error) }
                if (results.isNotEmpty() && atLevel.isEmpty() && band != null) {
                    Text(
                        stringResource(R.string.level_none_found, band!!.label),
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (results.isEmpty() && !loading && error == null) {
            // Before any search: suggestions for this learner. Tap to follow (or open).
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, key = "for-you") {
                Text(stringResource(R.string.discover_for_you), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 20.dp, top = 6.dp))
            }
            val picks = forYou
            when {
                picks == null -> item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) }
                picks.isEmpty() -> item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Text(stringResource(R.string.discover_intro), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
                }
                else -> items(picks.size, key = { picks[it].podcast.feedUrl }) { i ->
                    val r = picks[i].podcast
                    Box(Modifier.padding(start = if (i % 3 == 0) 16.dp else 0.dp, end = if (i % 3 == 2) 16.dp else 0.dp)) {
                        SelectTile(
                            label = r.title,
                            selected = r.feedUrl in subscribed,
                            imageUrl = r.artworkUrl,
                            onClick = {
                                scope.launch {
                                    runCatching { graph.repo.subscribe(r.feedUrl) }
                                        .onSuccess { if (r.feedUrl in subscribed) openPodcast(it) }
                                        .onFailure { error = context.getString(R.string.feed_failed, it.message ?: "") }
                                }
                            },
                        )
                    }
                }
            }
        }

        items(shown.distinctBy { it.feedUrl }, key = { it.feedUrl }, span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { r ->
            val isSubscribed = r.feedUrl in subscribed
            val level = estimates[r.feedUrl]
            val lang = r.language ?: resultLanguages[r.feedUrl]
            ListItem(
                headlineContent = { Text(r.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Column {
                        Text(listOfNotNull(r.author, r.genre).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            level?.let {
                                Text(stringResource(R.string.level_estimated, it.label), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            // Another language than the one learned: say so.
                            if (lang != null && lang != learning) {
                                Text(flagAndName(lang), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
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
