package com.ascolta.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ascolta.app.data.JaToken
import com.ascolta.app.data.JapaneseScript

/**
 * A Japanese sentence with furigana (the reading in small hiragana above each word written
 * with kanji) or in romaji. Words are the same as for word-by-word highlighting, so
 * [activeWord] (an index among the word tokens) highlights the word being spoken, and
 * tapping a word reports it as written (kanji), for looking up and saving.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JapaneseSentence(
    tokens: List<JaToken>,
    script: JapaneseScript,
    activeWord: Int,
    style: TextStyle,
    /** The learner's selection in this sentence (characters), or null. */
    selectedChars: IntRange? = null,
    /** A colour for a word (how common it is), or null. */
    tokenColor: ((JaToken) -> androidx.compose.ui.graphics.Color?)? = null,
    /** Tap on a token: the dictionary, or extending a selection. */
    onTap: (JaToken) -> Unit,
    /** Long press on a token: starts (or extends) a selection. */
    onLongPress: (JaToken) -> Unit = {},
) {
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    val selectColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)
    val tap by androidx.compose.runtime.rememberUpdatedState(onTap)
    val longPress by androidx.compose.runtime.rememberUpdatedState(onLongPress)
    val rubyStyle = style.copy(fontSize = style.fontSize * 0.55f, lineHeight = style.fontSize * 0.7f, fontWeight = FontWeight.Normal)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(if (script == JapaneseScript.ROMAJI) 5.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        var wordIndex = -1
        tokens.forEach { token ->
            if (token.isWord) wordIndex++
            val active = token.isWord && wordIndex == activeWord
            val chosen = selectedChars != null && token.start <= selectedChars.last && token.end > selectedChars.first
            val mod = Modifier
                .then(if (chosen) Modifier.background(selectColor) else if (active) Modifier.background(highlight, RoundedCornerShape(4.dp)) else Modifier)
                .then(
                    if (token.isWord) Modifier.pointerInput(token) {
                        detectTapGestures(onTap = { tap(token) }, onLongPress = { longPress(token) })
                    } else Modifier,
                )
            when (script) {
                JapaneseScript.ROMAJI -> Text(
                    token.romaji,
                    style = (if (active) style.copy(fontWeight = FontWeight.SemiBold) else style).let { st -> tokenColor?.invoke(token)?.let { st.copy(color = it) } ?: st },
                    modifier = mod.padding(horizontal = 1.dp),
                )
                else -> Column(mod, horizontalAlignment = Alignment.CenterHorizontally) {
                    // Reading above words with kanji; an empty line elsewhere keeps the baseline even.
                    Text(if (token.hasKanji) token.reading else " ", style = rubyStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(token.surface, style = (if (active) style.copy(fontWeight = FontWeight.SemiBold) else style).let { st -> tokenColor?.invoke(token)?.let { st.copy(color = it) } ?: st })
                }
            }
        }
    }
}

/** Saved Japanese word with its reading: "天気 (てんき · tenki)". */
fun withReading(word: String, reading: String?, romaji: String?): String =
    if (reading == null || reading == word) word else "$word ($reading${romaji?.let { " · $it" } ?: ""})"

@Suppress("unused")
private val unusedSp = 0.sp
