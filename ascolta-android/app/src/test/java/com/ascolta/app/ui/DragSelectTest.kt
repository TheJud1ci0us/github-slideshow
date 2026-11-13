package com.ascolta.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import com.ascolta.app.data.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Long press a word, slide into the next sentence, release: the words in between are selected. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h851dp-xxhdpi", application = android.app.Application::class)
class DragSelectTest {
    @get:Rule val rule = createComposeRule()

    private val first = "Bonjour tout le monde."
    private val second = "Comment allez vous aujourd'hui ?"
    private fun ranges(text: String) = Regex("""[\p{L}']+""").findAll(text).map { it.range }.toList()

    private var start by mutableStateOf<Pair<Int, Int>?>(null)
    private var end by mutableStateOf<Pair<Int, Int>?>(null)
    private var ended = false

    private fun show() {
        val drag = DragSelect()
        rule.setContent {
            MaterialTheme {
                Column(Modifier.width(360.dp)) {
                    listOf(first, second).forEachIndexed { i, text ->
                        SegmentItem(
                            segment = Segment(i * 5_000L, i * 5_000L + 4_000, text),
                            words = null, language = "fr", selectRanges = ranges(text),
                            active = false, activeWord = -1, showOriginal = true, showTranslation = false, textScale = 1f,
                            onSeek = {}, onWord = {},
                            dragSelect = drag, index = i,
                            onDragStart = { p -> start = drag.at(p); end = start },
                            onDragMove = { p -> end = drag.at(p) },
                            onDragEnd = { ended = true },
                        )
                    }
                }
            }
        }
    }

    @Test fun slideAcrossSentences() {
        show()
        val a = rule.onNodeWithText(first).fetchSemanticsNode().boundsInRoot
        val b = rule.onNodeWithText(second).fetchSemanticsNode().boundsInRoot
        rule.onNodeWithText(first).performTouchInput {
            val from = Offset(4f, height / 2f) // on "Bonjour"
            down(from)
            advanceEventTime(1_000) // long press
            // Slide to the end of the second sentence (positions are relative to the first one).
            val to = Offset(b.right - a.left - 4f, b.center.y - a.top)
            for (k in 1..10) moveTo(from + (to - from) * (k / 10f))
            up()
        }
        rule.waitForIdle()
        assertEquals(0 to 0, start)
        assertEquals(1 to ranges(second).lastIndex, end)
        assertEquals(true, ended)
    }

    @Test fun aTapDoesNotSelect() {
        show()
        rule.onNodeWithText(first).performTouchInput { click(Offset(4f, height / 2f)) }
        rule.waitForIdle()
        assertNull(start)
    }
}
