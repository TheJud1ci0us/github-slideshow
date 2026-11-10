package com.ascolta.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ascolta.app.R
import androidx.compose.material.icons.filled.Translate
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.SportsSoccer
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.School
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.ascolta.app.data.LANGUAGES
import com.ascolta.app.data.CEFR_LEVELS
import com.ascolta.app.data.GENRES
import com.ascolta.app.data.GENRE_LEARNING
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.verticalScroll
import com.ascolta.app.data.languageFlag
import com.ascolta.app.data.languageName
import com.ascolta.app.graph
import com.ascolta.app.whisper.PARAKEET_LANGUAGES

/** "🇮🇹 Italiano" */
fun flagAndName(code: String) = "${languageFlag(code)}  ${languageName(code)}"

/**
 * Quick switch between the languages being learned: one flag chip per language (the
 * selected one's library, search and words are shown), plus a chip to add languages.
 */
@Composable
fun LanguageBar(modifier: Modifier = Modifier) {
    val settings = LocalContext.current.graph.settings
    val current by settings.learningLanguage.flow.collectAsState()
    val all by settings.learningLanguages.flow.collectAsState()
    val list = remember(all, current) { settings.learningList() }
    var picking by remember { mutableStateOf(false) }

    LazyRow(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(list, key = { it }) { code ->
            FilterChip(
                selected = code == current,
                onClick = { settings.switchLanguage(code) },
                label = { Text(flagAndName(code)) },
            )
        }
        item {
            AssistChip(
                onClick = { picking = true },
                label = { Text(stringResource(R.string.languages_edit)) },
                leadingIcon = { Icon(Icons.Filled.Add, null) },
            )
        }
    }

    if (picking) {
        LanguagePickerDialog(
            title = stringResource(R.string.languages_learning),
            initial = list,
            multi = true,
            onDone = { settings.setLearningList(it); picking = false },
            onDismiss = { picking = false },
        )
    }
}

/**
 * The language being learned as plain text with a small arrow ("Armenian ▾"), for a screen's
 * title row: tapping it lists your languages to switch, and "Edit languages".
 */
@Composable
fun LanguageMenu(modifier: Modifier = Modifier) {
    val settings = LocalContext.current.graph.settings
    val current by settings.learningLanguage.flow.collectAsState()
    val all by settings.learningLanguages.flow.collectAsState()
    val list = remember(all, current) { settings.learningList() }
    var open by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    Box(modifier) {
        androidx.compose.material3.TextButton(onClick = { open = true }) {
            Text(languageName(current), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Icon(androidx.compose.material.icons.Icons.Filled.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            list.forEach { code ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(flagAndName(code)) },
                    trailingIcon = { if (code == current) Icon(androidx.compose.material.icons.Icons.Filled.Check, null) },
                    onClick = { settings.switchLanguage(code); open = false },
                )
            }
            androidx.compose.material3.HorizontalDivider()
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.languages_edit)) },
                leadingIcon = { Icon(Icons.Filled.Add, null) },
                onClick = { open = false; picking = true },
            )
        }
    }
    if (picking) {
        LanguagePickerDialog(
            title = stringResource(R.string.languages_learning),
            initial = list,
            multi = true,
            onDone = { settings.setLearningList(it); picking = false },
            onDismiss = { picking = false },
        )
    }
}

/** Searchable list of languages with flags; several can be ticked when [multi]. */
@Composable
fun LanguagePickerDialog(
    title: String,
    initial: List<String>,
    multi: Boolean,
    onDone: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val chosen = remember { mutableStateListOf<String>().apply { addAll(initial) } }
    var query by remember { mutableStateOf("") }
    val sorted = remember { LANGUAGES.sortedBy { languageName(it.code) } }
    val shown = sorted.filter { query.isBlank() || languageName(it.code).contains(query, ignoreCase = true) || it.name.contains(query, ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    placeholder = { Text(stringResource(R.string.languages_search)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(shown, key = { it.code }) { lang ->
                        val selected = lang.code in chosen
                        fun toggle() {
                            if (multi) { if (selected) chosen.remove(lang.code) else chosen.add(lang.code) }
                            else { chosen.clear(); chosen.add(lang.code) }
                        }
                        ListItem(
                            modifier = Modifier.clickable { toggle() },
                            headlineContent = { Text(flagAndName(lang.code)) },
                            supportingContent = if (lang.code in PARAKEET_LANGUAGES) {
                                { Text(stringResource(R.string.languages_parakeet_badge), style = MaterialTheme.typography.bodySmall) }
                            } else null,
                            trailingContent = {
                                if (multi) Checkbox(checked = selected, onCheckedChange = { toggle() })
                                else RadioButton(selected = selected, onClick = { toggle() })
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = chosen.isNotEmpty(), onClick = { onDone(chosen.toList()) }) { Text(stringResource(R.string.action_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * First launch, in four short steps (dashes at the top, Skip where it makes sense, one big
 * Continue button): languages, level, favourite themes, favourite podcasts.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val settings = graph.settings
    val scope = rememberCoroutineScope()
    val learning = remember { mutableStateListOf<String>() }
    var native by remember { mutableStateOf(settings.nativeLanguage.value) }
    val levels = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    val interests = remember { mutableStateListOf<Int>() }
    val chosenPodcasts = remember { mutableStateListOf<String>() }
    var shareStats by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf(0) }

    fun finish() {
        settings.nativeLanguage.set(native)
        settings.setLearningList(learning.toList())
        settings.learningLanguage.set(learning.first())
        levels.forEach { (code, level) -> settings.setLevel(code, level) }
        settings.interests.set(interests.joinToString(","))
        settings.onboarded.set(true)
        if (com.ascolta.app.data.Analytics.available) {
            com.ascolta.app.data.Analytics.setConsent(shareStats)
            com.ascolta.app.data.Analytics.track(
                "TelemetryDeck.Activation.onboardingCompleted",
                mapOf("languages" to learning.joinToString(","), "levels" to levels.size.toString(),
                    "interests" to interests.size.toString(), "podcasts" to chosenPodcasts.size.toString()),
            )
        }
        // Follow the chosen podcasts in the background.
        val feeds = chosenPodcasts.toList()
        graph.scope.launch { feeds.forEach { runCatching { graph.repo.subscribe(it, downloadLatest = false) } } }
        onDone()
    }

    androidx.activity.compose.BackHandler(enabled = step > 0) { step-- }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        StepHeader(step, 4, onSkip = if (step == 0) null else ({ if (step == 3) finish() else step++ }), skipLabel = stringResource(R.string.onboarding_skip))
        Box(Modifier.weight(1f)) {
            when (step) {
                0 -> LanguagesStep(learning, native, onNative = { native = it; learning.remove(it) })
                1 -> LevelStep(learning, levels)
                2 -> CategoryStep(interests)
                else -> PodcastStep(
                    language = learning.firstOrNull() ?: settings.learningLanguage.value,
                    genres = interests,
                    band = com.ascolta.app.data.LevelBand.of(levels[learning.firstOrNull()]),
                    chosen = chosenPodcasts,
                    footer = if (com.ascolta.app.data.Analytics.available) ({
                        ListItem(
                            modifier = Modifier.clickable { shareStats = !shareStats },
                            headlineContent = { Text(stringResource(R.string.analytics_title)) },
                            supportingContent = { Text(stringResource(R.string.analytics_text)) },
                            trailingContent = { androidx.compose.material3.Switch(checked = shareStats, onCheckedChange = { shareStats = it }) },
                        )
                    }) else null,
                )
            }
        }
        PillButton(
            text = when {
                step == 0 && learning.isEmpty() -> stringResource(R.string.onboarding_choose)
                step == 3 && chosenPodcasts.isNotEmpty() -> stringResource(R.string.onboarding_follow, chosenPodcasts.size)
                step == 3 -> stringResource(R.string.onboarding_finish)
                else -> stringResource(R.string.onboarding_continue)
            },
            enabled = step != 0 || learning.isNotEmpty(),
            onClick = { if (step == 3) finish() else step++ },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
    }
}

@Composable
private fun LanguagesStep(learning: MutableList<String>, native: String, onNative: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var pickNative by remember { mutableStateOf(false) }
    val sorted = remember { LANGUAGES.sortedBy { languageName(it.code) } }
    val shown = sorted.filter { it.code != native && (query.isBlank() || languageName(it.code).contains(query, ignoreCase = true) || it.name.contains(query, ignoreCase = true)) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                ScreenTitle(stringResource(R.string.onboarding_title), stringResource(R.string.onboarding_text))
                SettingsRow(androidx.compose.material.icons.Icons.Filled.Translate, stringResource(R.string.onboarding_native), subtitle = flagAndName(native)) { pickNative = true }
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    placeholder = { Text(stringResource(R.string.languages_search)) },
                    shape = androidx.compose.foundation.shape.CircleShape,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                )
            }
        }
        gridItems(shown, key = { it.code }) { lang ->
            val on = lang.code in learning
            SelectTile(
                label = languageName(lang.code),
                selected = on,
                emoji = languageFlag(lang.code),
                onClick = { if (on) learning.remove(lang.code) else learning.add(lang.code) },
            )
        }
    }
    if (pickNative) {
        LanguagePickerDialog(
            title = stringResource(R.string.onboarding_native),
            initial = listOf(native),
            multi = false,
            onDone = { onNative(it.first()); pickNative = false },
            onDismiss = { pickNative = false },
        )
    }
}

@Composable
private fun LevelStep(learning: List<String>, levels: MutableMap<String, String>) {
    val labels = listOf(R.string.cefr_a1, R.string.cefr_a2, R.string.cefr_b1, R.string.cefr_b2, R.string.cefr_c1, R.string.cefr_c2)
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            ScreenTitle(stringResource(R.string.onboarding_level_title), stringResource(R.string.onboarding_level_text))
        }
        learning.forEach { code ->
            if (learning.size > 1) item(span = { GridItemSpan(maxLineSpan) }) {
                Text(flagAndName(code), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
            }
            CEFR_LEVELS.forEachIndexed { i, level ->
                item(key = "$code-$level") {
                    SelectTile(
                        label = stringResource(labels[i]),
                        selected = levels[code] == level,
                        emoji = level,
                        onClick = { levels[code] = level },
                    )
                }
            }
        }
    }
}

/** "Which categories do you prefer?": a grid of themes with icons. */
@Composable
fun CategoryStep(interests: MutableList<Int>) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            ScreenTitle(stringResource(R.string.onboarding_interests_title), stringResource(R.string.onboarding_interests_text))
        }
        gridItems(GENRES.filter { it.id != GENRE_LEARNING }, key = { it.id }) { g ->
            val on = g.id in interests
            SelectTile(
                label = stringResource(g.nameRes),
                selected = on,
                icon = genreIcon(g.id),
                onClick = { if (on) interests.remove(g.id) else interests.add(g.id) },
            )
        }
    }
}

/** "Pick your favorite podcasts": suggestions for the language, themes and level. */
@Composable
fun PodcastStep(
    language: String,
    genres: List<Int>,
    band: com.ascolta.app.data.LevelBand?,
    chosen: MutableList<String>,
    footer: (@Composable () -> Unit)? = null,
) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    // The suggestions shown; each podcast picked brings a few more like it, right after it.
    val shown = remember(language, genres.toList(), band) { mutableStateListOf<com.ascolta.app.data.Pick>() }
    var loaded by remember(language, genres.toList(), band) { mutableStateOf(false) }
    LaunchedEffect(language, genres.toList(), band) {
        val first = runCatching { com.ascolta.app.data.Recommendations.picks(graph.directory, graph.feedLanguage, language, graph.settings.nativeLanguage.value, genres.toList(), band, 30) }
            .getOrDefault(emptyList())
        shown.clear()
        shown.addAll(first)
        loaded = true
    }
    val moreLikeThis = remember(language) {
        com.ascolta.app.data.MoreLikeThis(graph.directory, graph.feedLanguage, language, graph.settings.nativeLanguage.value)
    }
    fun addSimilar(pick: com.ascolta.app.data.Pick) {
        scope.launch {
            val more = runCatching { moreLikeThis.next(pick.podcast, shown.map { it.podcast.feedUrl }.toSet()) }.getOrDefault(emptyList())
                .filter { m -> shown.none { it.podcast.feedUrl == m.podcast.feedUrl } }
            val at = shown.indexOfFirst { it.podcast.feedUrl == pick.podcast.feedUrl }
            if (at >= 0) shown.addAll(at + 1, more) else shown.addAll(more)
        }
    }
    val picks: List<com.ascolta.app.data.Pick>? = if (loaded) shown else null
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            ScreenTitle(stringResource(R.string.onboarding_podcasts_title), stringResource(R.string.onboarding_podcasts_text))
        }
        val list = picks
        when {
            list == null -> items(9) {
                // Placeholders while suggestions load.
                Box(Modifier.aspectRatio(0.84f).clip(MaterialTheme.shapes.extraLarge).background(MaterialTheme.colorScheme.surfaceContainer))
            }
            list.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.onboarding_podcasts_none), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
            }
            else -> gridItems(list, key = { it.podcast.feedUrl }) { pick ->
                val on = pick.podcast.feedUrl in chosen
                CoverTile(
                    imageUrl = pick.podcast.artworkUrl,
                    title = pick.podcast.title,
                    checked = on,
                    showTitle = true,
                    onClick = {
                        if (on) chosen.remove(pick.podcast.feedUrl)
                        else {
                            chosen.add(pick.podcast.feedUrl)
                            addSimilar(pick)
                        }
                    },
                    modifier = Modifier.animateItem(),
                )
            }
        }
        if (footer != null) item(span = { GridItemSpan(maxLineSpan) }) { footer() }
    }
}

/**
 * Personalize (from Profile): the same theme and podcast steps as the first launch, for
 * people who have already set up the app.
 */
@Composable
fun PersonalizeScreen(onDone: () -> Unit) {
    val graph = LocalContext.current.graph
    val settings = graph.settings
    val interests = remember { mutableStateListOf<Int>().apply { addAll(settings.interestList()) } }
    val chosen = remember { mutableStateListOf<String>() }
    var step by remember { mutableStateOf(0) }
    androidx.activity.compose.BackHandler(enabled = step > 0) { step-- }
    Column(Modifier.fillMaxSize()) {
        StepHeader(step, 2, onSkip = null, skipLabel = "")
        Box(Modifier.weight(1f)) {
            if (step == 0) CategoryStep(interests)
            else PodcastStep(
                language = settings.learningLanguage.value,
                genres = interests,
                band = com.ascolta.app.data.LevelBand.of(settings.levelFor(settings.learningLanguage.value)),
                chosen = chosen,
            )
        }
        PillButton(
            text = if (step == 1 && chosen.isNotEmpty()) stringResource(R.string.onboarding_follow, chosen.size) else stringResource(R.string.onboarding_continue),
            onClick = {
                if (step == 0) {
                    settings.interests.set(interests.joinToString(","))
                    step = 1
                } else {
                    val feeds = chosen.toList()
                    graph.scope.launch { feeds.forEach { runCatching { graph.repo.subscribe(it, downloadLatest = false) } } }
                    onDone()
                }
            },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
    }
}

/** Icon for a podcast theme. */
fun genreIcon(id: Int): androidx.compose.ui.graphics.vector.ImageVector = when (id) {
    1489 -> Icons.Outlined.Newspaper
    1324 -> Icons.Outlined.Groups
    1487 -> Icons.Outlined.AccountBalance
    1303 -> Icons.Outlined.SentimentSatisfied
    1533 -> Icons.Outlined.Science
    1318 -> Icons.Outlined.Memory
    1545 -> Icons.Outlined.SportsSoccer
    1483 -> Icons.Outlined.MenuBook
    1321 -> Icons.Outlined.WorkOutline
    1512 -> Icons.Outlined.FavoriteBorder
    1305 -> Icons.Outlined.ChildCare
    1488 -> Icons.Outlined.Gavel
    1301 -> Icons.Outlined.Palette
    1309 -> Icons.Outlined.Movie
    1310 -> Icons.Outlined.MusicNote
    else -> Icons.Outlined.School
}

/** Centered placeholder used by screens with nothing to show. */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun FlagRow(codes: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { codes.forEach { Text(languageFlag(it)) } }
}

