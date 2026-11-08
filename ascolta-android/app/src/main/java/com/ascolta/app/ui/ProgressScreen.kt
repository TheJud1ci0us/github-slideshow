package com.ascolta.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ascolta.app.R
import com.ascolta.app.data.Badge
import com.ascolta.app.data.Badges
import com.ascolta.app.data.languageName
import com.ascolta.app.graph
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Listening hours per language, streak, and badges (earned in colour, locked greyed out). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen() {
    val graph = LocalContext.current.graph
    val stats by graph.progress.stats.collectAsState()
    val learning by graph.settings.learningLanguage.flow.collectAsState()
    LaunchedEffect(Unit) { graph.progress.flush(); graph.repo.refreshProgressCounts() }
    val badges = Badges.all()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_progress)) }) },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { LanguageBar(Modifier.padding(vertical = 4.dp)) }
            item(span = { GridItemSpan(maxLineSpan) }) {
                // Auditory milestones: hours listened in this language, and the next one.
                val hours = stats.hours(learning)
                val next = Badges.nextListening(hours)
                val previous = Badges.LISTENING_HOURS.lastOrNull { it <= hours } ?: 0
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.progress_listened, flagAndName(learning)), style = MaterialTheme.typography.titleSmall)
                        Text(formatHours(hours), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                        if (next != null) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { ((hours - previous) / (next - previous)).toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.progress_next_milestone, next), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard("🔥", stringResource(R.string.progress_streak, stats.streakDays), stringResource(R.string.progress_best, stats.bestStreak), Modifier.weight(1f))
                    StatCard("📝", stats.wordsSaved.toString(), stringResource(R.string.progress_words), Modifier.weight(1f))
                    StatCard("🧠", stats.cardsReviewed.toString(), stringResource(R.string.progress_reviews), Modifier.weight(1f))
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    stringResource(R.string.progress_badges, stats.earned.size, badges.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(badges, key = { it.id }) { badge -> BadgeTile(badge, stats.earned[badge.id]) }
        }
    }
}

@Composable
private fun StatCard(emoji: String, value: String, label: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, style = MaterialTheme.typography.titleLarge)
            Text(value, style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BadgeTile(badge: Badge, earnedAt: Long?) {
    val earned = earnedAt != null
    Card(
        colors = if (earned) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
        modifier = Modifier.alpha(if (earned) 1f else 0.45f),
    ) {
        Column(Modifier.padding(8.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(badge.emoji, style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(badge.titleRes, badge.amount), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
            Text(
                if (earnedAt != null) DateFormat.getDateInstance(DateFormat.SHORT).format(Date(earnedAt))
                else stringResource(badge.descRes, badge.amount),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

fun formatHours(hours: Double): String =
    if (hours < 1) "${(hours * 60).toInt()} min" else String.format(Locale.getDefault(), "%.1f h", hours)

/** Celebration shown when a badge is earned while the app is open. */
@Composable
fun BadgeUnlockedDialog(badge: Badge, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Text(badge.emoji, style = MaterialTheme.typography.displaySmall) },
        title = { Text(stringResource(R.string.badge_unlocked)) },
        text = {
            Text(
                stringResource(badge.titleRes, badge.amount) + "\n" + stringResource(badge.descRes, badge.amount),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
    )
}

@Suppress("unused")
private fun languageLabel(code: String) = languageName(code)
