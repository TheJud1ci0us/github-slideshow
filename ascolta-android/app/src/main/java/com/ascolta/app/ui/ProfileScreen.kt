package com.ascolta.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ascolta.app.R
import com.ascolta.app.data.Analytics
import com.ascolta.app.data.Badges
import com.ascolta.app.data.languageFlag
import com.ascolta.app.data.languageName
import com.ascolta.app.graph

/**
 * Profile: a learner card (language, level, hours, streak, badges) and the settings grouped
 * in rounded cards, each opening its own page.
 */
@Composable
fun ProfileScreen(openSettings: (String) -> Unit, openProgress: () -> Unit, openPersonalize: () -> Unit, openPaywall: () -> Unit = {}, openAquarium: () -> Unit = {}) {
    val proActive by LocalContext.current.graph.billing.isPro.collectAsState()
    val graph = LocalContext.current.graph
    val settings = graph.settings
    val learning by settings.learningLanguage.flow.collectAsState()
    val levels by settings.levels.flow.collectAsState()
    val all by settings.learningLanguages.flow.collectAsState()
    val stats by graph.progress.stats.collectAsState()
    LaunchedEffect(Unit) { graph.progress.flush(); graph.fish.flush(); graph.repo.refreshProgressCounts() }
    val level = remember(levels, learning) { settings.levelFor(learning) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // Learner card.
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(72.dp).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) { Text(languageFlag(learning), style = MaterialTheme.typography.headlineLarge) }
            Spacer(Modifier.width(18.dp))
            Column {
                Text(stringResource(R.string.profile_learner), style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(R.string.profile_learning, languageName(learning)) + (level?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Stat(formatHours(stats.hours(learning)), stringResource(R.string.profile_listened), Modifier.weight(1f), openProgress)
            Stat("🔥 ${stats.streakDays}", stringResource(R.string.profile_streak), Modifier.weight(1f), openProgress)
            Stat("${stats.earned.size}/${Badges.all().size}", stringResource(R.string.profile_badges), Modifier.weight(1f), openProgress)
        }

        // The Aquarium: a fishing game paid for with listening coins.
        val aquarium by graph.fish.state.collectAsState()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainer).clickable(onClick = openAquarium).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.aq_tank), null, Modifier.size(48.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.aq_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.aq_card_text, aquarium.coins), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val fresh = (aquarium.unseenMs / com.ascolta.app.data.FishGame.COIN_MS).toInt()
            if (fresh >= 50) {
                Text(
                    "+$fresh", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(androidx.compose.foundation.shape.CircleShape).background(androidx.compose.ui.graphics.Color(0xFFD9453C)).padding(horizontal = 9.dp, vertical = 2.dp),
                )
            }
        }

        SectionCard(stringResource(R.string.profile_learning_section)) {
            SettingsRow(
                Icons.Outlined.Language, stringResource(R.string.section_languages),
                subtitle = remember(all, learning) { settings.learningList() }.joinToString("  ") { languageFlag(it) },
            ) { openSettings("languages") }
            SettingsRow(Icons.Outlined.RecordVoiceOver, stringResource(R.string.profile_speech_models), subtitle = settings.modelFor(learning).label) {
                openSettings("speech")
            }
            SettingsRow(Icons.Outlined.Category, stringResource(R.string.profile_personalize), subtitle = stringResource(R.string.profile_personalize_text)) {
                openPersonalize()
            }
            // LingoPod Pro: plans, or "active".
            if (com.ascolta.app.BuildConfig.PAYWALL_ENABLED) SettingsRow(Icons.Outlined.WorkspacePremium, stringResource(R.string.pro_title), subtitle = stringResource(if (proActive) R.string.pro_active else R.string.pro_profile_subtitle)) { openPaywall() }
            SettingsRow(Icons.Outlined.BarChart, stringResource(R.string.stats_title)) { openProgress() }
        }
        SectionCard(stringResource(R.string.profile_app_section)) {
            SettingsRow(Icons.Outlined.Subtitles, stringResource(R.string.section_display)) { openSettings("display") }
            SettingsRow(Icons.Outlined.CloudDownload, stringResource(R.string.section_downloads)) { openSettings("downloads") }
            SettingsRow(Icons.Outlined.Speed, stringResource(R.string.section_speed)) { openSettings("speed") }
            if (Analytics.available) SettingsRow(Icons.Outlined.Lock, stringResource(R.string.section_privacy)) { openSettings("privacy") }
            SettingsRow(Icons.Outlined.BugReport, stringResource(R.string.section_diagnostics)) { openSettings("diagnostics") }
            SettingsRow(Icons.Outlined.Info, stringResource(R.string.section_about)) { openSettings("about") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainer).clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
