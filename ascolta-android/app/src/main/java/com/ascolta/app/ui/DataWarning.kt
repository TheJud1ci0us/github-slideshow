package com.ascolta.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ascolta.app.R
import com.ascolta.app.data.DownloadCost
import com.ascolta.app.graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Before a download on mobile data: how big it is (exactly, when known) and that it uses the
 * data allowance and may cost extra; download now, wait for Wi-Fi, or cancel. On Wi-Fi (or when
 * "Don't ask again" was ticked) the action runs straight away.
 */
class DataWarning internal constructor(private val scope: CoroutineScope, private val context: android.content.Context) {
    internal class Pending(val items: List<DownloadCost.Item>, val now: () -> Unit, val onWifi: (() -> Unit)?, val metered: Boolean)

    internal var pending by mutableStateOf<Pending?>(null)

    /**
     * Runs [now], after asking if on mobile data. [needs] lists what will be downloaded (computed
     * only on mobile data); nothing to download → no question. [onWifi]: queue it for Wi-Fi instead.
     */
    fun run(needs: suspend () -> List<DownloadCost.Item>, onWifi: (() -> Unit)? = null, now: () -> Unit) {
        val settings = context.graph.settings
        val metered = DownloadCost.isMetered(context)
        scope.launch {
            val items = runCatching { needs() }.getOrDefault(emptyList()).filter { it.bytes > 0 }
            // Models are always asked (with their size), on Wi-Fi too; other downloads only on
            // mobile data, unless "Don't ask again" was ticked.
            val ask = items.any { it.approveKey != null } || (metered && settings.askOnMobileData.value && items.isNotEmpty())
            if (!ask) now() else pending = Pending(items, now, onWifi?.takeIf { metered }, metered)
        }
    }

    /** The learner agreed: remember it, so background jobs may download these. */
    internal fun approve(items: List<DownloadCost.Item>) {
        val settings = context.graph.settings
        items.mapNotNull { it.approveKey }.forEach(settings::approve)
    }
}

@Composable
fun rememberDataWarning(): DataWarning {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val warning = remember { DataWarning(scope, context) }
    warning.pending?.let { p ->
        var dontAsk by remember { mutableStateOf(false) }
        val total = p.items.sumOf { it.bytes }
        val exact = p.items.all { it.exact }
        val size = (if (exact) "" else stringResource(R.string.size_about) + " ") + DownloadCost.formatSize(total)
        fun close() {
            if (dontAsk) context.graph.settings.askOnMobileData.set(false)
            warning.pending = null
        }
        AlertDialog(
            onDismissRequest = { warning.pending = null },
            title = { Text(stringResource(if (p.metered) R.string.data_warning_title else R.string.download_confirm_title, size)) },
            text = {
                Column {
                    if (p.metered) Text(stringResource(R.string.data_warning_text))
                    else Text(stringResource(R.string.download_confirm_text))
                    if (p.items.size > 1 || !p.metered) {
                        // What makes up the total.
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        p.items.forEach { item ->
                            Row(Modifier.fillMaxWidth()) {
                                Text(item.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.weight(1f))
                                Text(
                                    (if (item.exact) "" else "≈ ") + DownloadCost.formatSize(item.bytes),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    if (p.metered) Row(Modifier.fillMaxWidth().padding(top = 8.dp).clickable { dontAsk = !dontAsk }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = dontAsk, onCheckedChange = { dontAsk = it })
                        Text(stringResource(R.string.data_warning_dont_ask), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { close(); warning.approve(p.items); p.now() }) { Text(stringResource(R.string.data_warning_now)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { warning.pending = null }) { Text(stringResource(R.string.action_cancel)) }
                    if (p.onWifi != null) TextButton(onClick = { close(); warning.approve(p.items); p.onWifi.invoke() }) { Text(stringResource(R.string.data_warning_wifi)) }
                }
            },
        )
    }
    return warning
}
