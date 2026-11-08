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
import com.ascolta.app.data.languageName
import com.ascolta.app.graph
import com.ascolta.app.whisper.SpeechModel
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
fun SettingsScreen() {
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
                    refreshKey++
                    Toast.makeText(context, context.getString(R.string.model_imported), Toast.LENGTH_SHORT).show()
                }
                .onFailure { Toast.makeText(context, context.getString(R.string.import_failed, it.message ?: ""), Toast.LENGTH_LONG).show() }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_settings)) }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Section(stringResource(R.string.section_languages))
            AppLanguagePicker()
            LanguagePicker(stringResource(R.string.learning_language), learning) { settings.learningLanguage.set(it) }
            LanguagePicker(stringResource(R.string.translate_into), native) { settings.nativeLanguage.set(it) }
            TranslationModelRow(learning, native)

            HorizontalDivider()
            Section(stringResource(R.string.section_speech))
            Hint(stringResource(R.string.speech_intro))
            SpeechModel.entries.forEach { model ->
                if (model != SpeechModel.CUSTOM || graph.models.isDownloaded(model)) {
                    ModelRow(
                        model, selected = model.id == modelId, learning = learning, refreshKey = refreshKey,
                        onSelect = { settings.modelId.set(model.id) }, onChanged = { refreshKey++ },
                    )
                }
            }
            TextButton(onClick = { importModel.launch(arrayOf("*/*")) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.import_model))
            }

            HorizontalDivider()
            Section(stringResource(R.string.section_speed))
            ThreadsRow(settings.threads)
            SwitchRow(stringResource(R.string.flash_title), stringResource(R.string.flash_text), settings.flashAttention)
            SwitchRow(stringResource(R.string.skip_title), stringResource(R.string.skip_text), settings.skipNonSpeech)
            SpeedTestRow()

            HorizontalDivider()
            Section(stringResource(R.string.section_auto))
            SwitchRow(stringResource(R.string.auto_new_title), stringResource(R.string.auto_new_text), settings.autoDownloadNew)
            SwitchRow(stringResource(R.string.auto_transcribe_title), stringResource(R.string.auto_transcribe_text), settings.autoTranscribe)
            SwitchRow(stringResource(R.string.wifi_only_title), stringResource(R.string.wifi_only_text), settings.wifiOnly)
            SwitchRow(stringResource(R.string.charging_only_title), stringResource(R.string.charging_only_text), settings.chargingOnly)
            TextButton(onClick = {
                WorkScheduler.refreshNow(context)
                Toast.makeText(context, context.getString(R.string.checking_feeds), Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.check_feeds)) }

            HorizontalDivider()
            Section(stringResource(R.string.section_diagnostics))
            DiagnosticsSection()

            HorizontalDivider()
            Section(stringResource(R.string.section_about))
            Hint(stringResource(R.string.about_text), Modifier.padding(16.dp))
            Spacer(Modifier.height(24.dp))
        }
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
            LANGUAGES.forEach { lang ->
                DropdownMenuItem(text = { Text(languageName(lang.code)) }, onClick = { open = false; onChange(lang.code) })
            }
        }
    }
}

@Composable
private fun TranslationModelRow(source: String, target: String) {
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
                    busy = true
                    error = null
                    scope.launch {
                        runCatching { graph.translation.ensureModel(source, target, requireWifi = false) }
                            .onFailure { error = it.message }
                        busy = false
                        key++
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
    SpeechModel.PARAKEET -> R.string.model_desc_parakeet
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
) {
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
    LaunchedEffect(info?.state) { if (info?.state == WorkInfo.State.SUCCEEDED) onChanged() }

    ListItem(
        modifier = Modifier.clickable(onClick = onSelect),
        leadingContent = { RadioButton(selected = selected, onClick = onSelect) },
        headlineContent = {
            Text(if (model.approxSizeMb > 0) "${model.label} · ${model.approxSizeMb} MB" else model.label)
        },
        supportingContent = {
            Column {
                Text(stringResource(model.descriptionRes()))
                if (!supported) {
                    Text(stringResource(R.string.model_not_for_language, languageName(learning)), color = MaterialTheme.colorScheme.error)
                }
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
                    model != SpeechModel.CUSTOM -> TextButton(onClick = { WorkScheduler.downloadModel(context, model) }) {
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
    val shown = dragging?.toInt() ?: threads
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.threads_title, shown), style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = dragging ?: threads.toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { pref.set(Math.round(it).coerceIn(1, 8)) }
                dragging = null
            },
            valueRange = 1f..8f,
            steps = 6,
        )
        Hint(stringResource(R.string.threads_text), Modifier)
    }
}

@Composable
private fun SpeedTestRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SpeedResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        FilledTonalButton(
            enabled = !running,
            onClick = {
                running = true
                error = null
                scope.launch {
                    runCatching { SpeedTest.run(context) }
                        .onSuccess { result = it }
                        .onFailure { error = context.getString(R.string.speed_test_failed, it.message ?: it.javaClass.simpleName) }
                    running = false
                }
            },
        ) { Text(stringResource(if (running) R.string.speed_test_running else R.string.speed_test)) }
        Hint(stringResource(R.string.speed_test_text), Modifier.padding(top = 4.dp))
        if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
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
                            String.format(Locale.getDefault(), "%.1f", r.transcribeMs / 1000f),
                            String.format(Locale.getDefault(), "%.1f", r.speedFactor),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(
                            R.string.speed_test_details,
                            String.format(Locale.getDefault(), "%.1f", r.loadMs / 1000f),
                            r.threads,
                            if (r.flashAttention) on else off,
                            if (r.skipNonSpeech) on else off,
                            r.cpu,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    r.firstSentence?.let {
                        Spacer(Modifier.height(4.dp))
                        Text("“$it”", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
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
            WhisperLib.nativeCpuVariant()
        }.getOrElse { "not loaded: ${it.message}" }
    }

    ListItem(
        modifier = Modifier.clickable {
            // OnePlus/Oppo and others stop background work aggressively unless the app is exempt.
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
            runCatching { context.startActivity(intent) }.onFailure {
                context.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
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
