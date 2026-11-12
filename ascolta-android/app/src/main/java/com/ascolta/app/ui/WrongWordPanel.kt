package com.ascolta.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ascolta.app.R
import com.ascolta.app.data.WordCheck

/** What the "Wrong word?" check concluded, for the panel. */
internal data class WrongWordResult(
    val verdict: WordCheck.Verdict,
    val suggested: String,
    val newSentence: String? = null,
    val newTranslation: String? = null,
    /** Coins given (0: already rewarded, or today's rewards used). */
    val coins: Int = 0,
    /** Names the podcast's list learned from the correction. */
    val learned: List<String> = emptyList(),
    val podcast: String? = null,
    /** The meaning couldn't be checked (no translator for this language on the phone). */
    val meaningUnchecked: Boolean = false,
    val languageName: String = "",
)

/**
 * "Wrong word?": the learner says or types what they heard instead of [heard]; the app checks
 * it's a real word, close to what was transcribed, that makes sense in the whole sentence.
 */
@Composable
internal fun WrongWordPanel(
    heard: String,
    input: String,
    onInput: (String) -> Unit,
    canSpeak: Boolean,
    onSpeak: () -> Unit,
    onPlayWord: (() -> Unit)?,
    checking: Boolean,
    result: WrongWordResult?,
    onCheck: () -> Unit,
    onEditAnyway: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.wrong_word_prompt), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (onPlayWord != null) IconButton(onClick = onPlayWord) { Icon(Icons.Filled.PlayArrow, stringResource(R.string.hear_podcast)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = onInput, singleLine = true, modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.wrong_word_hint, heard)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (input.isNotBlank() && !checking) onCheck() }),
            )
            if (canSpeak) IconButton(onClick = onSpeak) { Icon(Icons.Filled.Mic, stringResource(R.string.wrong_word_speak)) }
        }
        if (checking) {
            Text(stringResource(R.string.wrong_word_checking), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if (result == null) {
            Button(onClick = onCheck, enabled = input.isNotBlank(), modifier = Modifier.fillMaxWidth()) { FitText(stringResource(R.string.wrong_word_check)) }
        }
        result?.let { r -> ResultCard(r, onEditAnyway) }
        Text(stringResource(R.string.wrong_word_small_print), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ResultCard(r: WrongWordResult, onEditAnyway: () -> Unit) {
    val ok = r.verdict == WordCheck.Verdict.PLAUSIBLE
    val tint = if (ok) Color(0xFF1E8A4C) else MaterialTheme.colorScheme.error
    Column(
        Modifier.fillMaxWidth().background(tint.copy(alpha = 0.10f), RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (ok) {
            Text(stringResource(R.string.wrong_ok_title), style = MaterialTheme.typography.titleSmall, color = tint, fontWeight = FontWeight.Bold)
            if (r.coins > 0) Text(stringResource(R.string.wrong_ok_coins, r.coins), style = MaterialTheme.typography.titleMedium, color = tint)
            else Text(stringResource(R.string.wrong_ok_no_coins), style = MaterialTheme.typography.bodySmall)
            r.newSentence?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            r.newTranslation?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (r.meaningUnchecked) Text(stringResource(R.string.wrong_ok_unchecked), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (r.learned.isNotEmpty() && r.podcast != null) {
                Text(stringResource(R.string.wrong_ok_learned, r.podcast), style = MaterialTheme.typography.bodySmall)
            }
        } else {
            val why = when (r.verdict) {
                WordCheck.Verdict.SAME -> stringResource(R.string.wrong_same)
                WordCheck.Verdict.NOT_A_WORD -> stringResource(R.string.wrong_not_word, r.suggested, r.languageName)
                WordCheck.Verdict.TOO_DIFFERENT -> stringResource(R.string.wrong_too_different)
                WordCheck.Verdict.NOT_UNDERSTOOD -> stringResource(R.string.wrong_not_understood)
                WordCheck.Verdict.DOESNT_FIT -> stringResource(R.string.wrong_doesnt_fit)
                else -> stringResource(R.string.wrong_word_hint, r.suggested)
            }
            Text(why, style = MaterialTheme.typography.bodyMedium, color = tint)
            r.newTranslation?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            TextButton(onClick = onEditAnyway) { Text(stringResource(R.string.wrong_edit_anyway)) }
        }
    }
}
