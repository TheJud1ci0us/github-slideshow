package com.ascolta.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.clip
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.ascolta.app.R
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
                .onSuccess { Toast.makeText(context, context.getString(R.string.imported_transcribing), Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, context.getString(R.string.import_failed, it.message ?: ""), Toast.LENGTH_LONG).show() }
        }
    }
    val pickOpml = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        Toast.makeText(context, context.getString(R.string.importing_subscriptions), Toast.LENGTH_SHORT).show()
        scope.launch {
            runCatching { graph.repo.importOpml(uri) }
                .onSuccess { Toast.makeText(context, context.resources.getQuantityString(R.plurals.added_podcasts, it, it), Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, context.getString(R.string.opml_failed, it.message ?: ""), Toast.LENGTH_LONG).show() }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_home), style = MaterialTheme.typography.headlineMedium) },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    IconButton(
                        enabled = !refreshing,
                        onClick = {
                            refreshing = true
                            scope.launch {
                                val added = runCatching { graph.repo.refreshAll() }.getOrDefault(0)
                                refreshing = false
                                Toast.makeText(
                                    context,
                                    if (added == 0) context.getString(R.string.no_new_episodes)
                                    else context.resources.getQuantityString(R.plurals.new_episodes, added, added),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    ) {
                        if (refreshing) SmallSpinner() else Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh_feeds))
                    }
                },
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_search)) },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        onClick = { menuOpen = false; openDiscover(null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_paste_link)) },
                        leadingIcon = { Icon(Icons.Filled.Link, null) },
                        onClick = { menuOpen = false; linkDialog = true },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_import_audio)) },
                        leadingIcon = { Icon(Icons.Filled.AudioFile, null) },
                        onClick = { menuOpen = false; pickAudio.launch(arrayOf("audio/*", "video/mp4")) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_import_opml)) },
                        leadingIcon = { Icon(Icons.Filled.UploadFile, null) },
                        onClick = { menuOpen = false; pickOpml.launch(arrayOf("*/*")) },
                    )
                }
                ExtendedFloatingActionButton(
                    onClick = { menuOpen = true },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text(stringResource(R.string.action_add)) },
                )
            }
        },
    ) { padding ->
        val list = podcasts
        Column(Modifier.fillMaxSize().padding(padding)) {
        // Switch between the languages being learned; each has its own library.
        LanguageBar(Modifier.padding(bottom = 4.dp))
        if (list != null && list.isEmpty() && importedCount == 0) {
            EmptyLibrary(
                Modifier,
                onSearch = { openDiscover(null) },
                onLink = { linkDialog = true },
                onImport = { pickAudio.launch(arrayOf("audio/*")) },
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                if (active.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.section_in_progress)) }
                    items(active, key = { "active-${it.id}" }) { ep ->
                        ActiveRow(ep, onClick = { openPlayer(ep.id) })
                    }
                }
                if (recent.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.section_continue)) }
                    // Continue listening: big artwork cards with how far you got.
                    item(key = "recent-row") {
                        androidx.compose.foundation.lazy.LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(recent, key = { it.id }) { ep -> ContinueCard(ep) { openPlayer(ep.id) } }
                        }
                    }
                }
                val favourites = list.orEmpty().filter { it.podcast.favourite }
                if (favourites.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.section_favourites)) }
                    items(favourites.chunked(3), key = { row -> "fav-" + row.joinToString { it.podcast.id.toString() } }) { row ->
                        PodcastGridRow(row, openPodcast)
                    }
                }
                item { SectionTitle(stringResource(R.string.section_podcasts)) }
                if (importedCount > 0) {
                    item {
                        ListItem(
                            modifier = Modifier.clickable { openPodcast(null) },
                            headlineContent = { Text(stringResource(R.string.imported_files)) },
                            supportingContent = { Text(pluralStringResource(R.plurals.files_count, importedCount, importedCount)) },
                            leadingContent = { Artwork(null, 56.dp) },
                        )
                    }
                }
                items(list.orEmpty().filter { !it.podcast.favourite }.chunked(3), key = { row -> "pod-" + row.joinToString { it.podcast.id.toString() } }) { row ->
                    PodcastGridRow(row, openPodcast)
                }
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

/** Three podcasts side by side: square rounded artwork, title, ★ for favourites. */
@Composable
private fun PodcastGridRow(row: List<com.ascolta.app.data.PodcastWithStats>, openPodcast: (Long?) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        row.forEach { p ->
            Column(Modifier.weight(1f).clickable { openPodcast(p.podcast.id) }) {
                Box {
                    coil3.compose.AsyncImage(
                        // A third of the screen wide: a ~400-pixel copy.
                        model = com.ascolta.app.net.Artwork.sized(p.podcast.imageUrl, 400), contentDescription = p.podcast.title,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.large)
                            .background(MaterialTheme.colorScheme.surfaceContainer),
                    )
                    if (p.podcast.favourite) {
                        Icon(Icons.Filled.Star, null, tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(p.podcast.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

/** A "continue listening" card: artwork, episode title and a progress line. */
@Composable
private fun ContinueCard(ep: EpisodeEntity, onClick: () -> Unit) {
    Column(
        Modifier.width(150.dp).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick).padding(10.dp),
    ) {
        Artwork(ep.imageUrl, 130.dp)
        Spacer(Modifier.height(8.dp))
        Text(ep.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(40.dp))
        val total = ep.durationSec * 1000
        if (total > 0) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { (ep.playbackPositionMs.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PodcastRow(p: com.ascolta.app.data.PodcastWithStats, openPodcast: (Long?) -> Unit) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    ListItem(
        modifier = Modifier.clickable { openPodcast(p.podcast.id) },
        headlineContent = { Text(p.podcast.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                listOfNotNull(
                    p.podcast.author,
                    pluralStringResource(R.plurals.episodes_count, p.episodeCount, p.episodeCount),
                    if (p.transcribedCount > 0) stringResource(R.string.transcribed_count, p.transcribedCount) else null,
                ).joinToString(" · "),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = { Artwork(p.podcast.imageUrl, 56.dp) },
        trailingContent = {
            IconButton(onClick = { scope.launch { graph.repo.setFavourite(p.podcast.id, !p.podcast.favourite) } }) {
                if (p.podcast.favourite) Icon(Icons.Filled.Star, stringResource(R.string.unfavourite), tint = MaterialTheme.colorScheme.primary)
                else Icon(Icons.Filled.StarBorder, stringResource(R.string.favourite))
            }
        },
    )
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
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val downloading = ep.downloadState == DownloadState.DOWNLOADING || ep.downloadState == DownloadState.QUEUED
    val (label, progress) = when {
        ep.downloadState == DownloadState.DOWNLOADING -> stringResource(R.string.status_downloading) to ep.downloadProgress
        ep.downloadState == DownloadState.QUEUED -> (ep.error ?: stringResource(R.string.status_waiting_to_download)) to null
        ep.transcriptState == TranscriptState.RUNNING -> (ep.transcriptStatus ?: stringResource(R.string.status_transcribing)) to ep.transcriptProgress
        else -> (ep.transcriptStatus ?: stringResource(R.string.status_waiting_to_transcribe)) to null
    }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable(onClick = onClick)) {
        Column(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ep.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                )
                // Stops a running job, or removes a waiting one from the queue.
                IconButton(onClick = {
                    scope.launch {
                        if (ep.transcriptState == TranscriptState.QUEUED || ep.transcriptState == TranscriptState.RUNNING) {
                            graph.repo.stopTranscription(ep.id)
                        } else if (downloading) {
                            graph.repo.cancelDownload(ep.id)
                        }
                    }
                }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel)) }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (progress != null) "$label · ${(progress * 100).toInt()}%" else label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(end = 12.dp))
            else LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 12.dp))
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
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.empty_text),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onSearch, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.empty_find))
        }
        OutlinedButton(onClick = onLink, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.empty_paste))
        }
        OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.AudioFile, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.empty_import))
        }
    }
}
