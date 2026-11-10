package com.ascolta.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import com.ascolta.app.data.ListeningStats
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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

/**
 * Statistics, in tabs: Overview (total, streaks, this week), Insights (today, records, recent
 * weeks, months), Podcasts (time per podcast) and Learning (words, reviews, badges). Built from
 * the listening log (since v0.15) and the all-time totals.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val stats by graph.progress.stats.collectAsState()
    LaunchedEffect(Unit) { graph.progress.flush(); graph.repo.refreshProgressCounts() }
    val days by remember { graph.db.listeningDao().observeDays() }.collectAsState(initial = emptyList())
    val podcasts by remember { graph.db.listeningDao().observePodcasts() }.collectAsState(initial = emptyList())
    val log = remember(days) { ListeningStats.from(days, java.time.LocalDate.now()) }
    val totalMs = maxOf(stats.listenedMs.values.sum(), log.loggedMs)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val pro by graph.billing.isPro.collectAsState()
    val tabs = listOf(R.string.stats_overview, R.string.stats_insights, R.string.stats_podcasts, R.string.stats_learning)

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.stats_title)) },
                    navigationIcon = {
                        androidx.compose.material3.IconButton(onClick = onBack) {
                            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, null)
                        }
                    },
                )
                androidx.compose.material3.PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp) {
                    tabs.forEachIndexed { i, res ->
                        androidx.compose.material3.Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(res).uppercase()) })
                    }
                }
            }
        },
    ) { padding ->
        val listModifier = Modifier.fillMaxSize().padding(padding)
        val content = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp)
        when (tab) {
            0 -> LazyColumn(listModifier, contentPadding = content, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    HighlightCard(stringResource(R.string.stats_total), formatListening(totalMs), icon = androidx.compose.material.icons.Icons.Outlined.Schedule)
                }
                if (log.today.ms == 0L) item {
                    StatsCard {
                        Text(stringResource(R.string.stats_start_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.stats_start_text), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatsCard(Modifier.weight(1f)) {
                            Text("🔥 " + stringResource(R.string.stats_daily_streak), style = MaterialTheme.typography.labelLarge)
                            Text(stringResource(R.string.stats_days, log.dailyStreak), style = MaterialTheme.typography.headlineSmall)
                        }
                        StatsCard(Modifier.weight(1f)) {
                            Text("⚡ " + stringResource(R.string.stats_weekly_streak), style = MaterialTheme.typography.labelLarge)
                            Text(stringResource(R.string.stats_weeks, log.weeklyStreak), style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
                item {
                    StatsCard {
                        Text(stringResource(R.string.stats_this_week), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            java.time.DayOfWeek.entries.forEachIndexed { i, d ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        Modifier.size(34.dp).clip(CircleShape).background(
                                            if (log.weekDays[i]) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                                        ),
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(d.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault()), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
            1, 2 -> if (!pro) ProLocked(listModifier) { graph.billing.requestPaywall("statistics") }
            else if (tab == 2) PodcastsTab(listModifier, content, podcasts)
            else LazyColumn(listModifier, contentPadding = content, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { SectionTitle(stringResource(R.string.stats_listening_time)) }
                item {
                    HighlightCard(stringResource(R.string.stats_today), formatListening(log.today.ms),
                        stringResource(R.string.stats_episodes, log.today.episodes))
                }
                item { SectionTitle(stringResource(R.string.stats_records)) }
                log.longestDay?.let { (d, ms) -> item { RecordRow(stringResource(R.string.stats_longest_day), formatListening(ms), d.format(longDate())) } }
                log.biggestWeek?.let { (monday, ms) -> item { RecordRow(stringResource(R.string.stats_biggest_week), formatListening(ms), "${monday.format(shortDate())} – ${monday.plusDays(6).format(shortDate())}") } }
                log.busiestMonth?.let { (m, ms) -> item { RecordRow(stringResource(R.string.stats_busiest_month), formatListening(ms), m.format(monthFormat())) } }
                log.bestYear?.let { (y, ms) -> item { RecordRow(stringResource(R.string.stats_best_year), formatListening(ms), y.toString()) } }
                item { SectionTitle(stringResource(R.string.stats_recent)) }
                item {
                    StatsCard {
                        PeriodRow(stringResource(R.string.stats_yesterday), log.yesterday)
                        PeriodRow(stringResource(R.string.stats_this_week), log.thisWeek)
                        PeriodRow(stringResource(R.string.stats_last_week), log.lastWeek)
                    }
                }
                if (log.months.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.stats_months)) }
                    item {
                        StatsCard {
                            log.months.forEach { (m, ms, eps) -> PeriodRow(m.format(monthFormat()), com.ascolta.app.data.DayTotal(0, ms, eps)) }
                        }
                    }
                }
                item {
                    Text(stringResource(R.string.stats_history_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> {
                val badges = Badges.all()
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = listModifier,
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatCard("📝", stats.wordsSaved.toString(), stringResource(R.string.progress_words), Modifier.weight(1f))
                            StatCard("🧠", stats.cardsReviewed.toString(), stringResource(R.string.progress_reviews), Modifier.weight(1f))
                            StatCard("📜", stats.transcribed.toString(), stringResource(R.string.stats_transcribed), Modifier.weight(1f))
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
    }
}

@Composable
private fun PodcastsTab(modifier: Modifier, content: PaddingValues, podcasts: List<com.ascolta.app.data.PodcastTotal>) {
    LazyColumn(modifier, contentPadding = content, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item { SectionTitle(stringResource(R.string.stats_by_podcast)) }
                val top = podcasts.firstOrNull()?.ms?.coerceAtLeast(1) ?: 1
                listItems(podcasts, key = { it.podcastId }) { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(p.imageUrl, 64.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.title ?: stringResource(R.string.stats_added_audio), style = MaterialTheme.typography.titleMedium, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(formatListening(p.ms), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(progress = { p.ms.toFloat() / top }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
                        }
                    }
                }
                if (podcasts.isEmpty()) item {
                    Text(stringResource(R.string.stats_history_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
}

/** Shown instead of a Pro-only tab. */
@Composable
private fun ProLocked(modifier: Modifier, onGetPro: () -> Unit) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.pro_feature), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.Button(onClick = onGetPro) { Text(stringResource(R.string.get_pro)) }
    }
}

/** "10 h 14 min", "25 min". */
fun formatListening(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
}

private fun longDate() = java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL)
private fun shortDate() = java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
private fun monthFormat() = java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun StatsCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(20.dp)) { content() }
    }
}

/** The tinted card for the main number of a tab. */
@Composable
private fun HighlightCard(label: String, value: String, sub: String? = null, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    androidx.compose.material3.Icon(icon, null, Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Text(label, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.headlineLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun RecordRow(label: String, value: String, date: String) {
    StatsCard {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(date, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PeriodRow(label: String, total: com.ascolta.app.data.DayTotal) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(formatListening(total.ms), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.stats_episodes, total.episodes), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
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
