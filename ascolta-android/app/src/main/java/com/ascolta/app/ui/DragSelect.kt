package com.ascolta.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.text.TextLayoutResult

/**
 * Selecting by sliding: long press a word, slide to the last word wanted, release. The finger
 * can cross sentences, so each sentence on screen registers here how to find the word under a
 * point of the window.
 */
class DragSelect {
    /** Where one sentence's words are on screen. */
    class Target(
        /** The whole sentence card (to know which sentence the finger is over). */
        val card: () -> LayoutCoordinates?,
        /** The word index under a window position (-1: the sentence has no words to select). */
        val wordAt: (Offset) -> Int,
    )

    private val targets = HashMap<Int, Target>()

    fun register(index: Int, target: Target) { targets[index] = target }
    fun unregister(index: Int, target: Target) { if (targets[index] === target) targets.remove(index) }

    /** The sentence (index) and word under [window], or of the nearest sentence when between two. */
    fun at(window: Offset): Pair<Int, Int>? {
        var best: Pair<Int, Target>? = null
        var bestDistance = Float.MAX_VALUE
        for ((index, target) in targets) {
            val coords = target.card()?.takeIf { it.isAttached } ?: continue
            val r = coords.boundsInWindow()
            if (r.height <= 0f) continue
            val d = when {
                window.y < r.top -> r.top - window.y
                window.y > r.bottom -> window.y - r.bottom
                else -> 0f
            }
            if (d < bestDistance) { bestDistance = d; best = index to target }
        }
        return best?.let { (index, target) -> index to target.wordAt(window) }
    }
}

/** Where a sentence's text and (Japanese) word boxes were last laid out. */
class SentenceLayout {
    var card: LayoutCoordinates? = null
    /** The text area the finger slides on. */
    var area: LayoutCoordinates? = null
    var text: LayoutCoordinates? = null
    var textLayout: TextLayoutResult? = null
    /** Japanese words: their first character → their box. */
    val tokens = HashMap<Int, LayoutCoordinates>()

    /** The character under [window]: from the text's layout, or the nearest Japanese word; null when unknown. */
    fun charAt(window: Offset): Int? {
        val t = text
        val layout = textLayout
        if (t != null && t.isAttached && layout != null) return layout.getOffsetForPosition(t.windowToLocal(window))
        var best: Int? = null
        var bestDistance = Float.MAX_VALUE
        for ((start, coords) in tokens) {
            if (!coords.isAttached) continue
            val r = coords.boundsInWindow()
            val dx = when { window.x < r.left -> r.left - window.x; window.x > r.right -> window.x - r.right; else -> 0f }
            val dy = when { window.y < r.top -> r.top - window.y; window.y > r.bottom -> window.y - r.bottom; else -> 0f }
            // Lines first: a word on the finger's line beats a closer one on another line.
            val d = dy * 10 + dx
            if (d < bestDistance) { bestDistance = d; best = start }
        }
        return best
    }
}
