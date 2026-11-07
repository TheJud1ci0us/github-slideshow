package com.ascolta.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ascolta.app.data.DownloadState
import com.ascolta.app.data.EpisodeEntity
import com.ascolta.app.data.TranscriptState
import com.ascolta.app.graph
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    openPodcast: (Long?) -> Unit,
    openPlayer: (Long) -> Unit,
    openDiscover: (String?) -> Unit,
) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val podcasts by graph.repo.podcasts.collectAsState(initial = null)
    val importedCount by graph.repo.importedCount.collectAsState(initial = 0)
    val active by graph.repo.activeEpisodes.collectAsState(initial = emptyList())
    val recent by graph.repo.recentlyPlayed.collectAsState(initial = emptyList())

    var menuOpen by remember { mutableStateOf(false) }
    var linkDialog by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }

    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { graph.repo.importAudio(uri) }
                .onSuccess { Toast.makeText(context, "Imported. Transcription queued.", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "Import failed: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    val pickOpml = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        Toast.makeText(context, "Importing subscriptions…", Toast.LENGTH_SHORT).show()
        scope.launch {
            runCatching { graph.repo.importOpml(uri) }
                .onSuccess { Toast.makeText(context, "Added $it podcasts", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "OPML import failed: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Ascolta") },
                actions = {
                    IconButton(
                        enabled = !refreshing,
                        onClick = {
                            refreshing = true
                            scope.launch {
                                val added = runCatching { graph.repo.refreshAll() }.getOrDefault(0)
                                refreshing = false
                                Toast.makeText(context, if (added == 0) "No new episodes" else "$added new episodes", Toast.LENGTH_SHORT).show()
                            }
                        },
                    ) {
                        if (refreshing) SmallSpinner() else Icon(Icons.Filled.Refresh, contentDescription = "Check for new episodes")
                    }
                },
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Search podcasts") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        onClick = { menuOpen = false; openDiscover(null) },
                    )
                    DropdownMenuItem(
                        text = { Text("Paste link (RSS, Spotify, Apple)") },
                        leadingIcon = { Icon(Icons.Filled.Link, null) },
                        onClick = { menuOpen = false; linkDialog = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Import audio file") },
                        leadingIcon = { Icon(Icons.Filled.AudioFile, null) },
                        onClick = { menuOpen = false; pickAudio.launch(arrayOf("audio/*", "video/mp4")) },
                    )
                    DropdownMenuItem(
                        text = { Text("Import subscriptions (OPML)") },
                        leadingIcon = { Icon(Icons.Filled.UploadFile, null) },
                        onClick = { menuOpen = false; pickOpml.launch(arrayOf("*/*")) },
                    )
                }
                ExtendedFloatingActionButton(
                    onClick = { menuOpen = true },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("Add") },
                )
            }
        },
    ) { padding ->
        val list = podcasts
        if (list != null && list.isEmpty() && importedCount == 0) {
            EmptyLibrary(
                Modifier.padding(padding),
                onSearch = { openDiscover(null) },
                onLink = { linkDialog = true },
                onImport = { pickAudio.launch(arrayOf("audio/*")) },
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                if (active.isNotEmpty()) {
                    item { SectionTitle("In progress") }
                    items(active, key = { "active-${it.id}" }) { ep ->
                        ActiveRow(ep, onClick = { openPlayer(ep.id) })
                    }
                }
                if (recent.isNotEmpty()) {
                    item { SectionTitle("Continue listening") }
                    items(recent, key = { "recent-${it.id}" }) { ep ->
                        ListItem(
                            modifier = Modifier.clickable { openPlayer(ep.id) },
                            headlineContent = { Text(ep.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                val total = ep.durationSec * 1000
                                Text(
                                    if (total > 0) "${formatTime(ep.playbackPositionMs)} / ${formatTime(total)}"
                                    else formatTime(ep.playbackPositionMs),
                                )
                            },
                            leadingContent = { Artwork(ep.imageUrl, 40.dp) },
                        )
                    }
                }
                item { SectionTitle("Podcasts") }
                if (importedCount > 0) {
                    item {
                        ListItem(
                            modifier = Modifier.clickable { openPodcast(null) },
                            headlineContent = { Text("Imported files") },
                            supportingContent = { Text("$importedCount files") },
                            leadingContent = { Artwork(null, 56.dp) },
                        )
                    }
                }
                items(list.orEmpty(), key = { it.podcast.id }) { p ->
                    ListItem(
                        modifier = Modifier.clickable { openPodcast(p.podcast.id) },
                        headlineContent = { Text(p.podcast.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    p.podcast.author,
                                    "${p.episodeCount} episodes",
                                    if (p.transcribedCount > 0) "${p.transcribedCount} transcribed" else null,
                                ).joinToString(" · "),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingContent = { Artwork(p.podcast.imageUrl, 56.dp) },
                    )
                }
            }
        }
    }

    if (linkDialog) {
        AddLinkDialog(
            onDismiss = { linkDialog = false },
            openPodcast = { linkDialog = false; openPodcast(it) },
            openSearch = { linkDialog = false; openDiscover(it) },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ActiveRow(ep: EpisodeEntity, onClick: () -> Unit) {
    val (label, progress) = when {
        ep.downloadState == DownloadState.DOWNLOADING -> "Downloading" to ep.downloadProgress
        ep.downloadState == DownloadState.QUEUED -> (ep.error ?: "Waiting to download") to null
        ep.transcriptState == TranscriptState.RUNNING -> (ep.transcriptStatus ?: "Transcribing") to ep.transcriptProgress
        else -> (ep.transcriptStatus ?: "Waiting to transcribe") to null
    }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Text(ep.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                if (progress != null) "$label · ${(progress * 100).toInt()}%" else label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier, onSearch: () -> Unit, onLink: () -> Unit, onImport: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Learn with podcasts", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "Subscribe to a podcast and new episodes are downloaded and transcribed on your phone, " +
                "with a translation under every sentence. Nothing is sent to a server.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onSearch, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Find a podcast")
        }
        OutlinedButton(onClick = onLink, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Paste a link")
        }
        OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.AudioFile, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Import an audio file")
        }
    }
}
