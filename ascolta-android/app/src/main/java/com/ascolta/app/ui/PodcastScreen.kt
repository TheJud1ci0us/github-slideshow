package com.ascolta.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import com.ascolta.app.data.DownloadState
import com.ascolta.app.data.EpisodeEntity
import com.ascolta.app.data.TranscriptState
import com.ascolta.app.graph
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastScreen(podcastId: Long?, onBack: () -> Unit, openPlayer: (Long) -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val podcast by remember(podcastId) {
        if (podcastId == null) kotlinx.coroutines.flow.flowOf(null) else graph.repo.podcast(podcastId)
    }.collectAsState(initial = null)
    val episodes by remember(podcastId) { graph.repo.episodes(podcastId) }.collectAsState(initial = emptyList())
    var menu by remember { mutableStateOf(false) }
    var confirmUnsubscribe by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(podcast?.title ?: "Imported files", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (podcastId != null) {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Check for new episodes") }, onClick = {
                                menu = false
                                scope.launch { podcast?.let { runCatching { graph.repo.refresh(it) } } }
                            })
                            DropdownMenuItem(text = { Text("Unfollow") }, onClick = {
                                menu = false
                                confirmUnsubscribe = true
                            })
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            podcast?.let { p ->
                item {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(p.imageUrl, 96.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.title, style = MaterialTheme.typography.titleMedium)
                            p.author?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Auto-download & transcribe", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "New episodes are prepared in the background",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = p.autoDownload, onCheckedChange = { on ->
                            scope.launch { graph.repo.setAutoDownload(p.id, on) }
                        })
                    }
                    HorizontalDivider(Modifier.padding(top = 12.dp))
                }
            }
            if (episodes.isEmpty()) {
                item {
                    Text("No episodes yet.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(episodes, key = { it.id }) { ep ->
                EpisodeRow(ep, onOpen = { openPlayer(ep.id) })
                HorizontalDivider()
            }
        }
    }

    if (confirmUnsubscribe) {
        AlertDialog(
            onDismissRequest = { confirmUnsubscribe = false },
            title = { Text("Unfollow?") },
            text = { Text("Downloaded episodes and transcripts of this podcast will be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnsubscribe = false
                    scope.launch {
                        podcastId?.let { graph.repo.unsubscribe(it) }
                        onBack()
                    }
                }) { Text("Unfollow") }
            },
            dismissButton = { TextButton(onClick = { confirmUnsubscribe = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EpisodeRow(ep: EpisodeEntity, onOpen: () -> Unit) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                listOf(formatDate(ep.pubDate), formatDuration(ep.durationSec)).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(ep.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            ep.description?.let { d ->
                val plain = remember(d) { HtmlCompat.fromHtml(d, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim() }
                if (plain.isNotEmpty()) {
                    Text(
                        plain, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            StatusLine(ep)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Episode actions") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (ep.downloadState != DownloadState.DONE && ep.audioUrl != null) {
                    DropdownMenuItem(text = { Text("Download") }, onClick = {
                        menu = false; scope.launch { graph.repo.download(ep.id) }
                    })
                }
                if (ep.transcriptState == TranscriptState.NONE || ep.transcriptState == TranscriptState.FAILED) {
                    DropdownMenuItem(text = { Text("Create transcript") }, onClick = {
                        menu = false; scope.launch { graph.repo.transcribe(ep.id) }
                    })
                }
                if (ep.transcriptState == TranscriptState.DONE) {
                    DropdownMenuItem(text = { Text("Re-transcribe (current model)") }, onClick = {
                        menu = false; scope.launch { graph.repo.retranscribe(ep.id) }
                    })
                }
                if (ep.localPath != null) {
                    DropdownMenuItem(text = { Text(if (ep.podcastId == null) "Delete file" else "Delete download") }, onClick = {
                        menu = false; scope.launch { graph.repo.deleteDownload(ep.id) }
                    })
                }
            }
        }
    }
}

@Composable
private fun StatusLine(ep: EpisodeEntity) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        when (ep.downloadState) {
            DownloadState.DONE -> Chip(Icons.Filled.CheckCircle, "Downloaded", MaterialTheme.colorScheme.primary)
            DownloadState.DOWNLOADING -> ProgressChip("Downloading", ep.downloadProgress)
            DownloadState.QUEUED -> ProgressChip("Waiting to download", null)
            DownloadState.FAILED -> Chip(Icons.Filled.ErrorOutline, "Download failed", MaterialTheme.colorScheme.error)
            else -> if (ep.audioUrl != null) Chip(Icons.Filled.Download, "Streams online", MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (ep.transcriptState) {
            TranscriptState.DONE -> Chip(Icons.Filled.Subtitles, "Transcript", MaterialTheme.colorScheme.primary)
            TranscriptState.RUNNING -> ProgressChip(ep.transcriptStatus ?: "Transcribing", ep.transcriptProgress)
            TranscriptState.QUEUED -> ProgressChip(ep.transcriptStatus ?: "Queued", null)
            TranscriptState.FAILED -> Chip(Icons.Filled.ErrorOutline, "Transcript failed", MaterialTheme.colorScheme.error)
            else -> {}
        }
    }
}

@Composable
private fun Chip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun ProgressChip(label: String, progress: Float?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (progress != null) CircularProgressIndicator(progress = { progress }, Modifier.size(12.dp), strokeWidth = 2.dp)
        else CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(4.dp))
        Text(
            if (progress != null) "$label ${(progress * 100).toInt()}%" else label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
