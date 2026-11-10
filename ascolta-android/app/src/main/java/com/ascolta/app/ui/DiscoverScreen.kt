package com.ascolta.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
    // Endless lists: the pager behind the results shown, and whether a page is loading.
    var pager by remember { mutableStateOf<com.ascolta.app.data.PodcastPager?>(null) }
    var loadingMore by remember { mutableStateOf(false) }

    /** Shows the first page of [newPager]; more pages load as the list is scrolled. */
    fun load(newPager: com.ascolta.app.data.PodcastPager) {
        focus.clearFocus()
        loadJob?.cancel()
        pager = newPager
        results = emptyList()
        loading = true
        loadingMore = false
        error = null
        loadJob = scope.launch {
            runCatching { newPager.next() }
                .onSuccess { results = it; if (it.isEmpty()) error = context.getString(R.string.no_results) }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) error = context.getString(R.string.search_failed, it.message ?: "") }
            loading = false
        }
    }

    val country = LANGUAGES.firstOrNull { it.code == learning }?.country

    /**
     * Opens a podcast from the suggestions or results, following it first if needed. A feed
     * that can't be read says so right away (in a toast, since the header may be scrolled away).
     */
    fun followAndOpen(r: DirectoryResult) {
        if (subscribing[r.feedUrl] == true) return
        subscribing[r.feedUrl] = true
        scope.launch {
            runCatching { graph.repo.subscribe(r.feedUrl, artworkUrl = r.artworkUrl) }
                .onSuccess { openPodcast(it) }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    android.widget.Toast.makeText(context, context.getString(R.string.feed_failed, it.message ?: ""), android.widget.Toast.LENGTH_LONG).show()
                }
            subscribing[r.feedUrl] = false
        }
    }

    fun search(term: String) {
        if (term.isBlank()) return
        genre = null
        com.ascolta.app.data.Analytics.track("Discover.searched", mapOf("language" to learning)) // never the search text
        val store = country
        val lang = learning
        // A search for a theme ("news podcast", "समाचार"): first the most listened shows of that
        // theme in the language's country, kept only if they are in the language (not English).
        val themeNames = GENRES.associate { it.id to context.getString(it.nameRes) }
        val theme = com.ascolta.app.data.Recommendations.genreFor(term, themeNames)
        val inLanguage = com.ascolta.app.data.Recommendations.languageCheck(graph.feedLanguage, lang, settings.nativeLanguage.value, false)
        val top = theme?.let { g ->
            com.ascolta.app.data.Recommendations.themeSources(graph.directory, g, lang, store ?: "US").take(2)
                .map { src -> suspend { inLanguage(src().take(50)) } }
        }.orEmpty()
        // Then the search itself: this store first, then the US one (more shows), up to 200 each.
        load(com.ascolta.app.data.PodcastPager(
            top + listOf(suspend { graph.directory.search(term, store, 200) }, suspend { graph.directory.search(term, "US", 200) }),
            check = { it }, // the search results are shown in any language; the tag says which
            pageSize = 30,
        ))
    }

    /** Podcasts of a theme in the learning language's store; for learners, courses in that language. */
    fun browse(genreId: Int) {
        genre = genreId
        com.ascolta.app.data.Analytics.track("Discover.themeBrowsed", mapOf("genre" to genreId.toString(), "language" to learning))
        query = ""
        val lang = learning
        val course = genreId == GENRE_LEARNING
        val store = country ?: "US"
        // The theme's chart, then shows found by the theme's own name, then backup searches by
        // the language's name; for learners, the learner searches. Only podcasts really in the
        // language (the store of a small language is often mostly in a neighbouring one).
        load(com.ascolta.app.data.PodcastPager(
            if (course) com.ascolta.app.data.Recommendations.learnerSources(graph.directory, lang, store)
            else com.ascolta.app.data.Recommendations.themeSources(graph.directory, genreId, lang, store),
            check = com.ascolta.app.data.Recommendations.languageCheck(graph.feedLanguage, lang, settings.nativeLanguage.value, course),
        ))
    }

    LaunchedEffect(initialQuery) {
        if (!initialQuery.isNullOrBlank()) {
            query = initialQuery
            search(initialQuery)
        }
    }
    // Nothing searched yet: suggestions for this learner (courses for beginners, else a favourite theme).
    // "For you": suggestions for the language, favourite themes and level (shown before any search).
    // The suggestions first, then (as you scroll) the favourite themes, shows for learners and
    // the backup searches, endlessly until the store runs out.
    val forYouItems = remember(learning, interestsPref, levelsPref) { androidx.compose.runtime.mutableStateListOf<DirectoryResult>() }
    var forYouLoaded by remember(learning, interestsPref, levelsPref) { mutableStateOf(false) }
    val forYouPager = remember(learning, interestsPref, levelsPref) {
        val lang = learning
        val native = settings.nativeLanguage.value
        val store = country ?: "US"
        val picks = suspend {
            com.ascolta.app.data.Recommendations.picks(graph.directory, graph.feedLanguage, lang, native, settings.interestList(), myBand, 20).map { it.podcast }
        }
        com.ascolta.app.data.PodcastPager(
            listOf(picks) + com.ascolta.app.data.Recommendations.forYouSources(graph.directory, lang, store, settings.interestList()),
            check = com.ascolta.app.data.Recommendations.languageCheck(graph.feedLanguage, lang, native, isCourse = true),
            pageSize = 18,
        )
    }
    LaunchedEffect(forYouPager) {
        forYouItems.addAll(runCatching { forYouPager.next() }.getOrDefault(emptyList()))
        forYouLoaded = true
    }

    val estimates = remember(results, genre) {
        results.associate { it.feedUrl to LevelGuess.estimate(it.title, it.summary, genre, it.genre) }
    }
    val genres = remember(interestsPref) {
        val mine = settings.interestList()
        GENRES.sortedBy { g -> if (g.id == GENRE_LEARNING) -1 else mine.indexOf(g.id).let { if (it < 0) 100 else it } }
    }

    // Language each search result's feed declares, shown when it isn't the language learned.
    val resultLanguages by produceState<Map<String, String?>>(emptyMap(), results) {
        value = if (genre == null && results.isNotEmpty()) graph.feedLanguage.ofAll(results.map { it.feedUrl }) else emptyMap()
    }
    // Feeds found not to work are hidden (re-evaluated once their check is done).
    val working = remember(results, resultLanguages) { results.filter { !graph.feedLanguage.isDead(it.feedUrl) } }
    val atLevel = working.filter { band == null || estimates[it.feedUrl]?.band == band }
    val shown = atLevel.ifEmpty { working }

    // Endless scrolling: within 6 items of the end, the next page of whichever list is shown loads.
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val nearEnd by remember {
        androidx.compose.runtime.derivedStateOf {
            val info = gridState.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 6
        }
    }
    val showingForYou = results.isEmpty() && !loading && error == null
    val activePager = if (showingForYou) forYouPager.takeIf { forYouLoaded } else pager.takeIf { !loading }
    LaunchedEffect(nearEnd, activePager, results.size, forYouItems.size) {
        val p = activePager ?: return@LaunchedEffect
        if (!nearEnd || loadingMore || p.exhausted) return@LaunchedEffect
        loadingMore = true
        val more = runCatching { p.next() }.getOrDefault(emptyList())
        if (p === forYouPager) forYouItems.addAll(more.filter { m -> forYouItems.none { it.feedUrl == m.feedUrl } })
        else if (p === pager) results = (results + more).distinctBy { it.feedUrl }
        loadingMore = false
    }

    // One scrolling grid: the header scrolls away, so results get the whole screen.
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        state = gridState,
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
                                // Nothing on screen yet: a level opens the shows for learners at that level.
                                if (band != null && band != LevelBand.ADVANCED && results.isEmpty() && query.isBlank()) browse(GENRE_LEARNING)
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

        if (showingForYou) {
            // Before any search: suggestions for this learner. Tap to follow (or open).
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, key = "for-you") {
                Text(stringResource(R.string.discover_for_you), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 20.dp, top = 6.dp))
            }
            val picks = forYouItems
            when {
                !forYouLoaded -> item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) }
                picks.isEmpty() -> item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Text(stringResource(R.string.discover_intro), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
                }
                else -> items(picks.size, key = { picks[it].feedUrl }) { i ->
                    val r = picks[i]
                    Box(Modifier.padding(start = if (i % 3 == 0) 16.dp else 0.dp, end = if (i % 3 == 2) 16.dp else 0.dp)) {
                        SelectTile(
                            label = r.title,
                            selected = r.feedUrl in subscribed,
                            imageUrl = r.artworkUrl,
                            onClick = { followAndOpen(r) },
                        )
                    }
                }
            }
        }

        items(shown.distinctBy { it.feedUrl }, key = { it.feedUrl }, span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { r ->
            val isSubscribed = r.feedUrl in subscribed
            val level = estimates[r.feedUrl]
            // The feed's language, unless it's evidently wrong (Farsi shows declaring "en").
            val lang = com.ascolta.app.data.LanguageCheck.trusted(r.language ?: resultLanguages[r.feedUrl], "${r.title} ${r.summary.orEmpty()}", learning)
            ListItem(
                // Tapping the row opens the podcast (following it, as there's no preview yet).
                modifier = Modifier.clickable { followAndOpen(r) },
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
                                // Small neutral tag ("EN"), on the same line as the level.
                                Text(
                                    lang.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                        .padding(horizontal = 5.dp),
                                )
                            }
                        }
                    }
                },
                leadingContent = { Artwork(r.artworkUrl, 56.dp) },
                trailingContent = {
                    when {
                        subscribing[r.feedUrl] == true -> SmallSpinner()
                        isSubscribed -> TextButton(onClick = { followAndOpen(r) }) { Icon(Icons.Filled.Check, null); Text(stringResource(R.string.action_open)) }
                        else -> FilledTonalButton(onClick = {
                            subscribing[r.feedUrl] = true
                            scope.launch {
                                runCatching { graph.repo.subscribe(r.feedUrl, artworkUrl = r.artworkUrl) }
                                    .onFailure { error = context.getString(R.string.feed_failed, it.message ?: "") }
                                subscribing[r.feedUrl] = false
                            }
                        }) { Text(stringResource(R.string.action_follow)) }
                    }
                },
            )
        }

        // Bottom of the endless list: loading the next page, or the end of what was found.
        val listShown = if (showingForYou) forYouItems.isNotEmpty() else shown.isNotEmpty()
        if (listShown) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, key = "list-end") {
            when {
                loadingMore -> Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SmallSpinner()
                    Text(stringResource(R.string.discover_loading_more), Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                activePager?.exhausted == true -> Text(
                    stringResource(R.string.discover_end),
                    Modifier.fillMaxWidth().padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                else -> Box(Modifier.size(1.dp))
            }
        }
    }
}
