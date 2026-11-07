package com.ascolta.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ascolta.app.data.LANGUAGES
import com.ascolta.app.data.Pref
import com.ascolta.app.data.languageName
import com.ascolta.app.graph
import com.ascolta.app.whisper.WhisperModel
import com.ascolta.app.work.WorkScheduler
import kotlinx.coroutines.launch

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
        Toast.makeText(context, "Importing model…", Toast.LENGTH_SHORT).show()
        scope.launch {
            runCatching { graph.models.import(uri) }
                .onSuccess {
                    settings.modelId.set(WhisperModel.CUSTOM.id)
                    refreshKey++
                    Toast.makeText(context, "Model imported", Toast.LENGTH_SHORT).show()
                }
                .onFailure { Toast.makeText(context, "Import failed: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Section("Languages")
            LanguagePicker("I'm learning", learning) { settings.learningLanguage.set(it) }
            LanguagePicker("Translate into", native) { settings.nativeLanguage.set(it) }
            TranslationModelRow(learning, native)

            HorizontalDivider()
            Section("Speech recognition model")
            Text(
                "Transcripts are made on your phone with Whisper. Bigger models are more accurate but slower. " +
                    "The selected model downloads automatically the first time it's needed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            WhisperModel.entries.forEach { model ->
                if (model != WhisperModel.CUSTOM || graph.models.isDownloaded(model)) {
                    ModelRow(model, selected = model.id == modelId, refreshKey = refreshKey,
                        onSelect = { settings.modelId.set(model.id) }, onChanged = { refreshKey++ })
                }
            }
            TextButton(onClick = { importModel.launch(arrayOf("*/*")) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("Import a model file (.bin)…")
            }

            HorizontalDivider()
            Section("Automatic downloads")
            SwitchRow("Auto-download new episodes", "For newly followed podcasts", settings.autoDownloadNew)
            SwitchRow("Transcribe after downloading", "Creates the transcript in the background", settings.autoTranscribe)
            SwitchRow("Download only on Wi-Fi", "Episodes you download yourself ignore this", settings.wifiOnly)
            SwitchRow("Transcribe only while charging", "Saves battery; manual requests ignore this", settings.chargingOnly)
            TextButton(onClick = {
                WorkScheduler.refreshNow(context)
                Toast.makeText(context, "Checking feeds…", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Check all feeds now") }

            HorizontalDivider()
            Section("About")
            Text(
                "Ascolta plays public podcast RSS feeds. Speech recognition (whisper.cpp) and translation " +
                    "(ML Kit) both run on this phone. Models are downloaded once; audio and transcripts never leave the device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
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
                DropdownMenuItem(text = { Text(lang.name) }, onClick = { open = false; onChange(lang.code) })
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
        headlineContent = { Text("Offline translation") },
        supportingContent = {
            Column {
                Text(
                    when {
                        !supported -> "Pick two different languages"
                        downloaded == true -> "${languageName(source)} → ${languageName(target)} ready"
                        else -> "Downloads ~30 MB per language the first time it's used"
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
                }) { Text("Download") }
            }
        },
    )
}

@Composable
private fun ModelRow(
    model: WhisperModel,
    selected: Boolean,
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
    LaunchedEffect(info?.state) { if (info?.state == WorkInfo.State.SUCCEEDED) onChanged() }

    ListItem(
        modifier = Modifier.clickable(onClick = onSelect),
        leadingContent = { RadioButton(selected = selected, onClick = onSelect) },
        headlineContent = {
            Text(if (model.approxSizeMb > 0) "${model.label} · ${model.approxSizeMb} MB" else model.label)
        },
        supportingContent = {
            Column {
                Text(model.description)
                when {
                    running -> {
                        Text(if (progress >= 0) "Downloading ${(progress * 100).toInt()}%" else "Waiting for network…")
                        if (progress >= 0) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                    downloaded -> Text("Downloaded", color = MaterialTheme.colorScheme.primary)
                    info?.state == WorkInfo.State.FAILED -> Text("Download failed", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    running -> TextButton(onClick = { WorkScheduler.cancelModelDownload(context, model) }) { Text("Cancel") }
                    downloaded -> TextButton(onClick = { graph.models.delete(model); onChanged() }) { Text("Delete") }
                    model.url != null -> TextButton(onClick = { WorkScheduler.downloadModel(context, model) }) { Text("Get") }
                }
            }
        },
    )
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
