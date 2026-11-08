package com.ascolta.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ascolta.app.R
import com.ascolta.app.graph
import androidx.compose.ui.res.stringResource
import com.ascolta.app.net.LinkResolution
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun Artwork(url: String?, size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size / 8)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Podcasts,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(size / 2),
        )
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        }
    }
}

fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatDuration(sec: Long): String {
    if (sec <= 0) return ""
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return if (h > 0) "${h} h ${m} min" else "$m min"
}

fun formatDate(ms: Long): String =
    if (ms <= 0) "" else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

/** Small player bar shown above the bottom navigation while something is loaded. */
@Composable
fun MiniPlayer(onOpen: (Long) -> Unit) {
    val player = LocalContext.current.graph.player
    val state by player.state.collectAsState()
    val id = state.episodeId ?: return
    Surface(tonalElevation = 3.dp) {
        Column {
            if (state.durationMs > 0) {
                LinearProgressIndicator(
                    progress = { state.positionMs.toFloat() / state.durationMs },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(id) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(state.artworkUrl, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    state.subtitle?.let {
                        Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = { player.togglePlay() }) {
                    Icon(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.play_pause))
                }
            }
        }
        HorizontalDivider()
    }
}

/**
 * Paste an RSS feed, Apple Podcasts or Spotify link (or just a show's name).
 * Spotify doesn't share audio, so for Spotify links we look up the same show's open feed.
 */
@Composable
fun AddLinkDialog(
    initialText: String = "",
    onDismiss: () -> Unit,
    openPodcast: (Long) -> Unit,
    openSearch: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(initialText) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.add_podcast_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.add_podcast_hint), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    placeholder = { Text("https://…") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val graph = context.graph
                        try {
                            when (val r = graph.directory.resolve(text)) {
                                is LinkResolution.Feed -> openPodcast(graph.repo.subscribe(r.feedUrl))
                                is LinkResolution.Search -> openSearch(r.query)
                                is LinkResolution.Unsupported -> error = r.reason
                            }
                        } catch (e: Exception) {
                            error = context.getString(R.string.add_podcast_error, e.message ?: "")
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(stringResource(R.string.action_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun SmallSpinner() = CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
