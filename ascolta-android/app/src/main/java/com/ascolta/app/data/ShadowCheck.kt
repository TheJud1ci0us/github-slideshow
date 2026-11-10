package com.ascolta.app.data

/**
 * "Check what I said": which words of the sentence the learner's recording contains (pure,
 * unit-tested). Words are compared without case or punctuation, in order (longest common
 * subsequence), so one missed word doesn't mark all the following ones wrong. Languages
 * written without spaces (Japanese, Chinese) are compared character by character.
 */
object ShadowCheck {
    data class Result(val words: List<Pair<String, Boolean>>) {
        val matched get() = words.count { it.second }
        val score get() = if (words.isEmpty()) 0f else matched.toFloat() / words.size
    }

    fun compare(expected: String, heard: String): Result {
        val target = tokens(expected)
        val got = tokens(heard).map(::norm)
        val keys = target.map(::norm)
        // LCS table.
        val n = keys.size
        val m = got.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            dp[i][j] = if (keys[i] == got[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val matched = BooleanArray(n)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                keys[i] == got[j] -> { matched[i] = true; i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return Result(target.mapIndexed { k, w -> w to matched[k] })
    }

    private fun unspaced(text: String) = text.any {
        when (Character.UnicodeScript.of(it.code)) {
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.THAI -> true
            else -> false
        }
    }

    private fun tokens(text: String): List<String> =
        if (unspaced(text)) text.filter { it.isLetterOrDigit() }.map { it.toString() }
        else text.split(Regex("\\s+")).map { it.trim { c -> !c.isLetterOrDigit() } }.filter { it.isNotEmpty() }

    private fun norm(w: String) = java.text.Normalizer.normalize(w.lowercase(), java.text.Normalizer.Form.NFKC)
        .filter { it.isLetterOrDigit() }
}
