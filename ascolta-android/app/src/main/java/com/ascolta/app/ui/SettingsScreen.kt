package com.ascolta.app.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ascolta.app.R
import com.ascolta.app.data.AppLog
import com.ascolta.app.data.LANGUAGES
import com.ascolta.app.data.Pref
import androidx.compose.ui.res.pluralStringResource
import com.ascolta.app.data.languageName
import com.ascolta.app.graph
import com.ascolta.app.whisper.CpuTopology
import com.ascolta.app.whisper.Engine
import com.ascolta.app.whisper.SpeechModel
import androidx.compose.ui.graphics.Color
import com.ascolta.app.whisper.SpeedResult
import com.ascolta.app.whisper.SpeedTest
import com.ascolta.app.whisper.WhisperLib
import com.ascolta.app.work.WorkScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** Languages the app's own interface is translated into. */
private val APP_LANGUAGES = listOf("en", "it", "fr", "es", "de", "pt")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(section: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val settings = graph.settings
    val scope = rememberCoroutineScope()

    val learning by settings.learningLanguage.flow.collectAsState()
    val native by settings.nativeLanguage.flow.collectAsState()
    val modelId by settings.modelId.flow.collectAsState()
    var refreshKey by remember { mutableIntStateOf(0) }

    val importModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        Toast.makeText(context, context.getString(R.string.importing_model), Toast.LENGTH_SHORT).show()
        scope.launch {
            runCatching { graph.models.import(uri) }
                .onSuccess {
                    settings.modelId.set(SpeechModel.CUSTOM.id)
                    settings.setModelFor(settings.learningLanguage.value, SpeechModel.CUSTOM)
                    refreshKey++
                    Toast.makeText(context, context.getString(R.string.model_imported), Toast.LENGTH_SHORT).show()
                }
                .onFailure { Toast.makeText(context, context.getString(R.string.import_failed, it.message ?: ""), Toast.LENGTH_LONG).show() }
        }
    }

    // One page per group of settings (opened from Profile).
    val learningAll by settings.learningLanguages.flow.collectAsState()
    val title = when (section) {
        "languages" -> stringResource(R.string.section_languages)
        "speech" -> stringResource(R.string.section_speech_for, flagAndName(learning))
        "speed" -> stringResource(R.string.section_speed)
        "display" -> stringResource(R.string.section_display)
        "downloads" -> stringResource(R.string.section_downloads)
        "privacy" -> stringResource(R.string.section_privacy)
        "diagnostics" -> stringResource(R.string.section_diagnostics)
        else -> stringResource(R.string.section_about)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle(title, onBack = onBack)
        when (section) {
            "languages" -> {
                AppLanguagePicker()
                // Languages being learned (the flags in the quick-switch bar) and the current one.
                var pickLearning by remember { mutableStateOf(false) }
                ListItem(
                    modifier = Modifier.clickable { pickLearning = true },
                    headlineContent = { Text(stringResource(R.string.languages_learning)) },
                    supportingContent = { Text(remember(learningAll, learning) { settings.learningList() }.joinToString("   ") { flagAndName(it) }) },
                )
                if (pickLearning) {
                    LanguagePickerDialog(
                        title = stringResource(R.string.languages_learning),
                        initial = settings.learningList(),
                        multi = true,
                        onDone = { settings.setLearningList(it); pickLearning = false },
                        onDismiss = { pickLearning = false },
                    )
                }
                LanguageBar(Modifier.padding(vertical = 4.dp))
                // CEFR level in the current language (used to suggest podcasts in Discover).
                val levelsPref by settings.levels.flow.collectAsState()
                var levelMenu by remember { mutableStateOf(false) }
                Box {
                    ListItem(
                        modifier = Modifier.clickable { levelMenu = true },
                        headlineContent = { Text(stringResource(R.string.level_title, languageName(learning))) },
                        supportingContent = { Text(remember(levelsPref, learning) { settings.levelFor(learning) } ?: stringResource(R.string.level_unset)) },
                    )
                    DropdownMenu(expanded = levelMenu, onDismissRequest = { levelMenu = false }) {
                        com.ascolta.app.data.CEFR_LEVELS.forEach { l ->
                            DropdownMenuItem(text = { Text(l) }, onClick = { settings.setLevel(learning, l); levelMenu = false })
                        }
                    }
                }
                SwitchRow(stringResource(R.string.translate_title), stringResource(R.string.translate_text), settings.translate)
                val translateOn by settings.translate.flow.collectAsState()
                if (translateOn) {
                    LanguagePicker(stringResource(R.string.translate_into), native) { settings.nativeLanguage.set(it) }
                    TranslationModelRow(learning, native)
                }
            }
            "speech" -> {
                // Each language has its own speech model; the list is for the language selected above.
                Hint(stringResource(R.string.speech_intro))
                val languageModels by settings.languageModels.flow.collectAsState()
                val current = remember(languageModels, modelId, learning) { settings.modelFor(learning) }
                // Three choices for this language, lightest to best.
                val tiers = remember(learning) { SpeechModel.tiers(learning) }
                val tierNames = listOf(R.string.tier_light, R.string.tier_balanced, R.string.tier_best)
                val pro by graph.billing.isPro.collectAsState()
                tiers.forEachIndexed { i, model ->
                    // The Best model is part of LingoPod Pro.
                    val proOnly = i == tiers.lastIndex && !pro
                    ModelRow(
                        model, selected = model == current && !proOnly, learning = learning, refreshKey = refreshKey,
                        onSelect = { if (proOnly) graph.billing.requestPaywall("best_model") else settings.setModelFor(learning, model) },
                        onChanged = { refreshKey++ },
                        tier = stringResource(tierNames[i]) + if (proOnly) " · Pro" else "",
                    )
                }
                // Whisper Large v3: the most accurate, for episodes downloaded in advance.
                ModelRow(
                    SpeechModel.MOST_ACCURATE, selected = current == SpeechModel.MOST_ACCURATE, learning = learning, refreshKey = refreshKey,
                    onSelect = { settings.setModelFor(learning, SpeechModel.MOST_ACCURATE) },
                    onChanged = { refreshKey++ },
                    tier = stringResource(R.string.tier_most_accurate),
                )
                if (current == SpeechModel.MOST_ACCURATE || current !in tiers) Hint(stringResource(R.string.tier_most_accurate_hint))
                // A model chosen earlier that isn't one of the choices stays usable until another is picked.
                if (current !in tiers && current != SpeechModel.MOST_ACCURATE) {
                    ModelRow(
                        current, selected = true, learning = learning, refreshKey = refreshKey,
                        onSelect = {}, onChanged = { refreshKey++ },
                        tier = stringResource(R.string.tier_current),
                    )
                }
                // Skip music and silence (also under Speed): on or off for every model.
                SwitchRow(stringResource(R.string.skip_title), stringResource(R.string.skip_text), settings.skipNonSpeech)
                // Benchmark of the model chosen above.
                SpeedTestRow()
            }
            "speed" -> {
                ThreadsRow(settings.threads)
                SwitchRow(stringResource(R.string.flash_title), stringResource(R.string.flash_text), settings.flashAttention)
                SwitchRow(stringResource(R.string.skip_title), stringResource(R.string.skip_text), settings.skipNonSpeech)
                SwitchRow(stringResource(R.string.gpu_title), stringResource(R.string.gpu_text), settings.useGpu)

            }
            "display" -> {
                // Transcript display (also in the player's menu).
                SwitchRow(stringResource(R.string.highlight_words), stringResource(R.string.highlight_words_text), settings.wordHighlight)
                SwitchRow(stringResource(R.string.pause_each_sentence), stringResource(R.string.pause_each_sentence_text), settings.autoPause)
                SwitchRow(stringResource(R.string.highlight_new_words), stringResource(R.string.highlight_new_words_text), settings.highlightNewWords)
                SwitchRow(stringResource(R.string.skip_ads_title), stringResource(R.string.skip_ads_text), settings.skipAdsAutomatically)
                SwitchRow(stringResource(R.string.show_original_title), stringResource(R.string.show_original_text), settings.showOriginal)
                SwitchRow(stringResource(R.string.show_translation_title), stringResource(R.string.show_translation_text), settings.showTranslation)
                // Japanese: show transcripts as written, with furigana, or in romaji.
                if ("ja" in remember(learningAll, learning) { settings.learningList() }) {
                    ChoiceRow(
                        stringResource(R.string.ja_script_title),
                        settings.japaneseScript,
                        listOf(
                            "furigana" to stringResource(R.string.ja_script_furigana),
                            "kanji" to stringResource(R.string.ja_script_kanji),
                            "romaji" to stringResource(R.string.ja_script_romaji),
                        ),
                    )
                }
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    SwitchRow(stringResource(R.string.dynamic_color_title), stringResource(R.string.dynamic_color_text), settings.dynamicColor)
                }

            }
            "downloads" -> {
                Section(stringResource(R.string.section_network))
                NetworkModeRows(settings.wifiOnly)
                // Ask before downloading on mobile data, showing the size.
                SwitchRow(stringResource(R.string.ask_mobile_title), stringResource(R.string.ask_mobile_text), settings.askOnMobileData)

                Section(stringResource(R.string.section_auto))
                SwitchRow(stringResource(R.string.auto_new_title), stringResource(R.string.auto_new_text), settings.autoDownloadNew)
                SwitchRow(stringResource(R.string.auto_transcribe_title), stringResource(R.string.auto_transcribe_text), settings.autoTranscribe)
                SwitchRow(stringResource(R.string.charging_only_title), stringResource(R.string.charging_only_text), settings.chargingOnly)
                IntChoiceRow(
                    stringResource(R.string.refresh_title),
                    settings.refreshHours,
                    listOf(0, 1, 3, 6, 12, 24).map { h -> h to if (h == 0) stringResource(R.string.refresh_manual) else pluralStringResource(R.plurals.every_hours, h, h) },
                    onChanged = { WorkScheduler.schedulePeriodicRefresh(context) },
                )
                SwitchRow(stringResource(R.string.notify_fav_title), stringResource(R.string.notify_fav_text), settings.notifyFavourites)
                IntChoiceRow(
                    stringResource(R.string.delete_after_title),
                    settings.deleteAfterDays,
                    listOf(0, 1, 3, 7, 14, 30).map { d -> d to if (d == 0) stringResource(R.string.delete_never) else pluralStringResource(R.plurals.after_days, d, d) },
                    onChanged = { scope.launch { runCatching { graph.repo.deleteOldDownloads() } } },
                )
                TextButton(onClick = {
                    WorkScheduler.refreshNow(context)
                    Toast.makeText(context, context.getString(R.string.checking_feeds), Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.check_feeds)) }

            }
            "privacy" -> {
                if (com.ascolta.app.data.Analytics.available) {
                    val consent by settings.analyticsConsent.flow.collectAsState()
                    val on = consent == com.ascolta.app.data.Analytics.CONSENT_YES
                    ListItem(
                        modifier = Modifier.clickable { com.ascolta.app.data.Analytics.setConsent(!on) },
                        headlineContent = { Text(stringResource(R.string.analytics_title)) },
                        supportingContent = { Text(stringResource(R.string.analytics_text)) },
                        trailingContent = { Switch(checked = on, onCheckedChange = { com.ascolta.app.data.Analytics.setConsent(it) }) },
                    )
                }
            }
            "diagnostics" -> {
                DiagnosticsSection()

            }
            else -> {
                Hint(stringResource(R.string.about_text), Modifier.padding(16.dp))
                // Show the guided tours again.
                TextButton(onClick = {
                    settings.tourSeen.set(false)
                    settings.playerTourSeen.set(false)
                    settings.selectTourSeen.set(false)
                    onBack()
                }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.tour_replay)) }
                LegalSection()
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** A setting with a few named choices, shown as a dropdown. */
@Composable
private fun ChoiceRow(title: String, pref: Pref<String>, choices: List<Pair<String, String>>) {
    val value by pref.flow.collectAsState()
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            modifier = Modifier.clickable { open = true },
            headlineContent = { Text(title) },
            supportingContent = { Text(choices.firstOrNull { it.first == value }?.second ?: value) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { (key, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; pref.set(key) })
            }
        }
    }
}

@Composable
private fun IntChoiceRow(title: String, pref: Pref<Int>, choices: List<Pair<Int, String>>, onChanged: () -> Unit = {}) {
    val value by pref.flow.collectAsState()
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            modifier = Modifier.clickable { open = true },
            headlineContent = { Text(title) },
            supportingContent = { Text(choices.firstOrNull { it.first == value }?.second ?: value.toString()) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { (key, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; pref.set(key); onChanged() })
            }
        }
    }
}

/** Privacy policy, terms of use and open-source licenses / credits, from bundled texts. */
@Composable
private fun LegalSection() {
    val context = LocalContext.current
    var shown by remember { mutableStateOf<Pair<String, String>?>(null) }
    for ((title, file) in listOf(
        R.string.legal_privacy to "legal/privacy.txt",
        R.string.legal_terms to "legal/terms.txt",
        R.string.legal_licenses to "legal/licenses.txt",
    )) {
        val label = stringResource(title)
        ListItem(
            modifier = Modifier.clickable {
                shown = label to runCatching { context.assets.open(file).bufferedReader().readText() }.getOrDefault("")
            },
            headlineContent = { Text(label) },
        )
    }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }
    Hint(stringResource(R.string.legal_version, version ?: "?"), Modifier.padding(16.dp))
    shown?.let { (title, text) ->
        AlertDialog(
            onDismissRequest = { shown = null },
            title = { Text(title) },
            text = {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.height(420.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { shown = null }) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier.padding(horizontal = 16.dp)) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** The language of the app's own menus and buttons (per-app language, Android 13+ style). */
@Composable
private fun AppLanguagePicker() {
    var open by remember { mutableStateOf(false) }
    val current = AppCompatDelegate.getApplicationLocales().takeIf { !it.isEmpty }?.get(0)?.language
    Box {
        ListItem(
            modifier = Modifier.clickable { open = true },
            headlineContent = { Text(stringResource(R.string.app_language)) },
            supportingContent = { Text(current?.let { nativeName(it) } ?: stringResource(R.string.app_language_system)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.app_language_system)) }, onClick = {
                open = false
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            })
            APP_LANGUAGES.forEach { code ->
                DropdownMenuItem(text = { Text(nativeName(code)) }, onClick = {
                    open = false
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code))
                })
            }
        }
    }
}

/** A language's name in that language ("Italiano", "Français"…). */
private fun nativeName(code: String): String {
    val locale = Locale.forLanguageTag(code)
    return locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
}

@Composable
private fun LanguagePicker(label: String, value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            modifier = Modifier.clickable { open = true },
            headlineContent = { Text(label) },
            supportingContent = { Text(languageName(value)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LANGUAGES.sortedBy { languageName(it.code) }.forEach { lang ->
                DropdownMenuItem(text = { Text(languageName(lang.code)) }, onClick = { open = false; onChange(lang.code) })
            }
        }
    }
}

@Composable
private fun TranslationModelRow(source: String, target: String) {
    val dataWarning = rememberDataWarning()
    val context = LocalContext.current
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var key by remember { mutableIntStateOf(0) }
    val supported = graph.translation.isSupported(source, target)
    val downloaded by produceState<Boolean?>(null, source, target, key) {
        value = if (supported) runCatching { graph.translation.isModelDownloaded(source, target) }.getOrNull() else null
    }
    ListItem(
        headlineContent = { Text(stringResource(R.string.offline_translation)) },
        supportingContent = {
            Column {
                Text(
                    when {
                        !supported -> stringResource(R.string.translation_pick_different)
                        downloaded == true -> stringResource(R.string.translation_ready, languageName(source), languageName(target))
                        else -> stringResource(R.string.translation_size)
                    },
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        },
        trailingContent = {
            if (supported && downloaded == false && !busy) {
                OutlinedButton(onClick = {
                    dataWarning.run(needs = {
                        graph.translation.missingDownloads(source, target).map { (n, b, e) ->
                            com.ascolta.app.data.DownloadCost.Item(context.getString(R.string.translation_model_named, n), b, e, "tr:$source-$target")
                        }
                    }) {
                        busy = true
                        error = null
                        scope.launch {
                            runCatching { graph.repo.ensureTranslationModel(source, target) }
                                .onFailure { error = it.message }
                            busy = false
                            key++
                        }
                    }
                }) { Text(stringResource(R.string.action_download)) }
            }
        },
    )
}

private fun SpeechModel.descriptionRes() = when (this) {
    SpeechModel.TINY -> R.string.model_desc_tiny
    SpeechModel.BASE -> R.string.model_desc_base
    SpeechModel.SMALL -> R.string.model_desc_small
    SpeechModel.TURBO -> R.string.model_desc_turbo
    SpeechModel.LARGE_V3 -> R.string.model_desc_large_v3
    SpeechModel.PARAKEET -> R.string.model_desc_parakeet
    SpeechModel.SENSEVOICE -> R.string.model_desc_sensevoice
    SpeechModel.REAZONSPEECH -> R.string.model_desc_reazonspeech
    SpeechModel.KOTOBA -> R.string.model_desc_kotoba
    SpeechModel.OMNILINGUAL -> R.string.model_desc_omnilingual
    SpeechModel.CANARY_180M -> R.string.model_desc_canary
    SpeechModel.BASE_GPU, SpeechModel.SMALL_GPU -> R.string.model_desc_gpu
    SpeechModel.CUSTOM -> R.string.model_desc_custom
}

@Composable
private fun ModelRow(
    model: SpeechModel,
    selected: Boolean,
    learning: String,
    refreshKey: Int,
    onSelect: () -> Unit,
    onChanged: () -> Unit,
    /** "Light" / "Balanced" / "Best": shown as the title, the model's name below it. */
    tier: String? = null,
) {
    val dataWarning = rememberDataWarning()
    val context = LocalContext.current
    val graph = context.graph
    val work by remember(model) {
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WorkScheduler.modelWorkName(model))
    }.collectAsState(initial = emptyList())
    val info = work.firstOrNull()
    val running = info?.state == WorkInfo.State.RUNNING || info?.state == WorkInfo.State.ENQUEUED
    val progress = info?.progress?.getFloat(WorkScheduler.KEY_PROGRESS, -1f) ?: -1f
    val downloaded = remember(model, refreshKey, info?.state) { graph.models.isDownloaded(model) }
    val supported = model.supports(learning)
    // Greyed out when it doesn't understand the language being learned (Parakeet: 25 European languages).
    val enabled = supported
    LaunchedEffect(info?.state) { if (info?.state == WorkInfo.State.SUCCEEDED) onChanged() }

    ListItem(
        modifier = Modifier.clickable(enabled = enabled, onClick = onSelect),
        leadingContent = { RadioButton(selected = selected, onClick = onSelect, enabled = enabled) },
        headlineContent = {
            Text(
                tier ?: if (model.approxSizeMb > 0) "${model.label} · ${model.approxSizeMb} MB" else model.label,
                color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                style = if (tier != null) MaterialTheme.typography.titleMedium else LocalTextStyle.current,
            )
        },
        supportingContent = {
            Column {
                if (tier != null) {
                    Text(
                        if (model.approxSizeMb > 0) "${model.label} · ${model.approxSizeMb} MB" else model.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(stringResource(model.descriptionRes()))
                if (!supported) {
                    Text(stringResource(R.string.model_not_for_language, languageName(learning)), color = MaterialTheme.colorScheme.error)
                }
                // Which languages this model understands.
                Text(
                    model.languages?.let { codes ->
                        stringResource(R.string.model_languages, codes.size, codes.map { languageName(it) }.sorted().joinToString(", "))
                    } ?: stringResource(R.string.model_languages_whisper, LANGUAGES.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    running -> {
                        Text(
                            if (progress >= 0) stringResource(R.string.model_downloading, (progress * 100).toInt())
                            else stringResource(R.string.waiting_network),
                        )
                        if (progress >= 0) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                    downloaded -> Text(stringResource(R.string.model_downloaded), color = MaterialTheme.colorScheme.primary)
                    info?.state == WorkInfo.State.FAILED -> Text(stringResource(R.string.model_download_failed), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    running -> TextButton(onClick = { WorkScheduler.cancelModelDownload(context, model) }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    downloaded -> TextButton(onClick = { graph.models.delete(model); onChanged() }) {
                        Text(stringResource(R.string.action_delete))
                    }
                    model != SpeechModel.CUSTOM -> TextButton(enabled = enabled, onClick = {
                        // On mobile data: the exact size first, with the option to wait for Wi-Fi.
                        dataWarning.run(
                            needs = {
                                listOfNotNull(
                                    com.ascolta.app.data.DownloadCost.Item(model.label, graph.models.missingBytes(model), true, "model:${model.id}"),
                                    // Models other than Whisper also need voice detection.
                                    graph.models.vadMissingBytes().takeIf { it > 0 && model.engine != com.ascolta.app.whisper.Engine.WHISPER }
                                        ?.let { com.ascolta.app.data.DownloadCost.Item(context.getString(R.string.voice_detection), it, true, "vad") },
                                )
                            },
                            onWifi = { WorkScheduler.downloadModel(context, model, wifiOnly = true) },
                        ) {
                            WorkScheduler.downloadModel(context, model)
                            if (WorkScheduler.blockedByNetworkMode(context)) {
                                Toast.makeText(context, context.getString(R.string.waiting_for_wifi), Toast.LENGTH_LONG).show()
                            }
                        }
                    }) {
                        Text(stringResource(R.string.action_get))
                    }
                }
            }
        },
    )
}
@Composable
private fun ThreadsRow(pref: Pref<Int>) {
    val threads by pref.flow.collectAsState()
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging?.let { Math.round(it) } ?: threads
    val fastCores = remember { CpuTopology.current.fast.size }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            if (shown == 0) stringResource(R.string.threads_auto, fastCores)
            else stringResource(R.string.threads_title, shown),
            style = MaterialTheme.typography.bodyLarge,
        )
        // 0 = automatic: as many threads as the phone has fast cores.
        Slider(
            value = dragging ?: threads.toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { pref.set(Math.round(it).coerceIn(0, 8)) }
                dragging = null
            },
            valueRange = 0f..8f,
            steps = 7,
        )
        Hint(stringResource(R.string.threads_text_cores, CpuTopology.current.fast.size, CpuTopology.current.efficiency.size), Modifier)
    }
}
@Composable
private fun SpeedTestRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<SpeedResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        FilledTonalButton(
            enabled = !running,
            onClick = {
                running = true
                error = null
                scope.launch {
                    runCatching { SpeedTest.run(context) { s -> scope.launch { status = s } } }
                        .onSuccess { result = it }
                        .onFailure { error = context.getString(R.string.speed_test_failed, it.message ?: it.javaClass.simpleName) }
                    running = false
                }
            },
        ) { Text(stringResource(if (running) R.string.speed_test_running else R.string.speed_test)) }
        Hint(stringResource(R.string.speed_test_text), Modifier.padding(top = 4.dp))
        if (running) {
            status?.let { Hint(it, Modifier.padding(top = 8.dp)) }
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        result?.let { r ->
            val on = stringResource(R.string.speed_test_on)
            val off = stringResource(R.string.speed_test_off)
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        stringResource(
                            R.string.speed_test_result,
                            r.model,
                            String.format(Locale.getDefault(), "%.1f", r.medianMs / 1000f),
                            String.format(Locale.getDefault(), "%.1f", r.speedFactor),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(
                            R.string.speed_test_spread,
                            r.runsMs.joinToString(" · ") { String.format(Locale.getDefault(), "%.1f", it / 1000f) },
                            String.format(Locale.getDefault(), "%.0f", (r.maxMs - r.minMs) * 100f / r.medianMs.coerceAtLeast(1)),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.speed_test_accuracy, (r.accuracy * 100).toInt()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(
                            R.string.speed_test_details,
                            String.format(Locale.getDefault(), "%.1f", r.loadMs / 1000f),
                            "${r.threads} (${r.cores.joinToString(",")})",
                            if (r.flashAttention) on else off,
                            if (r.skipNonSpeech) on else off,
                            r.cpu,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (r.transcript.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text("“${r.transcript}”", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Hint(stringResource(R.string.speed_test_credit), Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun DiagnosticsSection() {
    val context = LocalContext.current
    var showLog by remember { mutableStateOf(false) }
    val powerManager = remember { context.getSystemService(PowerManager::class.java) }
    var unrestricted by remember { mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName)) }
    val engine = remember {
        runCatching {
            WhisperLib.ensureBackends(context.applicationInfo.nativeLibraryDir)
            WhisperLib.nativeCpuVariant() + " · GPU: " + WhisperLib.nativeGpuInfo().ifEmpty { "—" }
        }.getOrElse { "not loaded: ${it.message}" }
    }

    ListItem(
        modifier = Modifier.clickable {
            // OnePlus/Oppo and others stop background work aggressively unless the app is exempt:
            // opens the phone's battery list, where LingoPod can be set to "Don't optimise".
            runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        },
        headlineContent = { Text(stringResource(R.string.allow_background)) },
        supportingContent = { Text(stringResource(if (unrestricted) R.string.battery_ok else R.string.battery_hint)) },
    )
    LaunchedEffect(Unit) {
        while (true) {
            unrestricted = powerManager.isIgnoringBatteryOptimizations(context.packageName)
            delay(2000)
        }
    }
    ListItem(
        modifier = Modifier.clickable { showLog = true },
        headlineContent = { Text(stringResource(R.string.view_log)) },
        supportingContent = { Text(stringResource(R.string.view_log_text)) },
    )
    Hint(stringResource(R.string.speech_engine_info, engine))
    if (showLog) {
        val log = remember { AppLog.read().takeLast(20_000) }
        AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text(stringResource(R.string.log_title)) },
            text = {
                Text(
                    log,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.height(400.dp).verticalScroll(rememberScrollState(Int.MAX_VALUE)),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, AppLog.read().takeLast(50_000))
                    }
                    context.startActivity(Intent.createChooser(send, context.getString(R.string.share_log)))
                }) { Text(stringResource(R.string.action_share)) }
            },
            dismissButton = { TextButton(onClick = { showLog = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

/** Network mode: Wi-Fi only, or Wi-Fi and mobile data. */
@Composable
private fun NetworkModeRows(wifiOnly: Pref<Boolean>) {
    val value by wifiOnly.flow.collectAsState()
    val context = LocalContext.current
    fun choose(wifi: Boolean) {
        wifiOnly.set(wifi)
        WorkScheduler.schedulePeriodicRefresh(context) // feed checks follow the new mode
    }
    for ((wifi, title, text) in listOf(
        Triple(true, R.string.network_wifi_title, R.string.network_wifi_text),
        Triple(false, R.string.network_mobile_title, R.string.network_mobile_text),
    )) {
        ListItem(
            modifier = Modifier.clickable { choose(wifi) },
            leadingContent = { RadioButton(selected = value == wifi, onClick = { choose(wifi) }) },
            headlineContent = { Text(stringResource(title)) },
            supportingContent = { Text(stringResource(text)) },
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, pref: Pref<Boolean>) {
    val value by pref.flow.collectAsState()
    ListItem(
        modifier = Modifier.clickable { pref.set(!value) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = value, onCheckedChange = { pref.set(it) }) },
    )
}
