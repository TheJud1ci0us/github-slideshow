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
 * First launch: which languages are being learned, and the user's own language (for
 * translations). Shown once; both can be changed later in Settings and the flag bar.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val settings = LocalContext.current.graph.settings
    val learning = remember { mutableStateListOf<String>() }
    var native by remember { mutableStateOf(settings.nativeLanguage.value) }
    var pickNative by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val sorted = remember { LANGUAGES.sortedBy { languageName(it.code) } }
    val shown = sorted.filter { it.code != native && (query.isBlank() || languageName(it.code).contains(query, ignoreCase = true)) }
    var step by remember { mutableStateOf(0) }
    val levels = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    val interests = remember { mutableStateListOf<Int>() }

    fun finish() {
        settings.nativeLanguage.set(native)
        settings.setLearningList(learning.toList())
        settings.learningLanguage.set(learning.first())
        levels.forEach { (code, level) -> settings.setLevel(code, level) }
        settings.interests.set(interests.joinToString(","))
        settings.onboarded.set(true)
        onDone()
    }

    if (step == 1) {
        LevelAndInterests(learning, levels, interests, onBack = { step = 0 }, onDone = { finish() })
        return
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.onboarding_text), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        ListItem(
            modifier = Modifier.clickable { pickNative = true },
            headlineContent = { Text(stringResource(R.string.onboarding_native)) },
            supportingContent = { Text(flagAndName(native)) },
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            placeholder = { Text(stringResource(R.string.languages_search)) },
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.code }) { lang ->
                val selected = lang.code in learning
                ListItem(
                    modifier = Modifier.clickable { if (selected) learning.remove(lang.code) else learning.add(lang.code) },
                    headlineContent = { Text(flagAndName(lang.code)) },
                    trailingContent = {
                        Checkbox(checked = selected, onCheckedChange = { if (selected) learning.remove(lang.code) else learning.add(lang.code) })
                    },
                )
            }
        }
        Text(
            stringResource(R.string.onboarding_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        Button(
            enabled = learning.isNotEmpty(),
            onClick = { step = 1 },
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Text(
                if (learning.isEmpty()) stringResource(R.string.onboarding_choose)
                else stringResource(R.string.onboarding_start, learning.joinToString(" ") { languageFlag(it) }),
            )
        }
    }

    if (pickNative) {
        LanguagePickerDialog(
            title = stringResource(R.string.onboarding_native),
            initial = listOf(native),
            multi = false,
            onDone = { native = it.first(); learning.remove(native); pickNative = false },
            onDismiss = { pickNative = false },
        )
    }
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

/**
 * Second onboarding step: the level in each language (CEFR, A1–C2) and favourite themes, so
 * Discover can suggest podcasts that fit.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LevelAndInterests(
    learning: List<String>,
    levels: MutableMap<String, String>,
    interests: MutableList<Int>,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)
            .verticalScroll(androidx.compose.foundation.rememberScrollState()),
    ) {
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.onboarding_level_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.onboarding_level_text), color = MaterialTheme.colorScheme.onSurfaceVariant)
        learning.forEach { code ->
            Spacer(Modifier.height(12.dp))
            Text(flagAndName(code), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CEFR_LEVELS.forEach { level ->
                    FilterChip(
                        selected = levels[code] == level,
                        onClick = { levels[code] = level },
                        label = { Text(level) },
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.onboarding_interests_title), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GENRES.filter { it.id != GENRE_LEARNING }.forEach { g ->
                val on = g.id in interests
                FilterChip(selected = on, onClick = { if (on) interests.remove(g.id) else interests.add(g.id) }, label = { Text(stringResource(g.nameRes)) })
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            Spacer(Modifier.weight(1f))
            Button(onClick = onDone) { Text(stringResource(R.string.onboarding_finish)) }
        }
    }
}
