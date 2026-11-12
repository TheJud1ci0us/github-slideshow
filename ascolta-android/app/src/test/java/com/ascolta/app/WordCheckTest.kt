package com.ascolta.app

import com.ascolta.app.data.FishGame
import com.ascolta.app.data.WordCheck
import com.ascolta.app.data.WordCheck.Verdict
import com.ascolta.app.data.WordLists
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class WordCheckTest {
    private fun asset(name: String) = listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name")).firstOrNull { it.exists() }?.inputStream()
    private val lists = WordLists({ asset(WordLists.assetName(it)) }, { asset(WordLists.moreName(it)) })

    @Test
    fun aRealCloseDifferentWordPassesTheQuickChecks() {
        assertNull(WordCheck.quick("kläddsträger", "klädstilar", "sv", lists))
        assertEquals(Verdict.TOO_DIFFERENT, WordCheck.quick("kläddsträger", "banan", "sv", lists))
        assertEquals(Verdict.NOT_A_WORD, WordCheck.quick("kläddsträger", "klädstrarg", "sv", lists))
        assertEquals(Verdict.SAME, WordCheck.quick("Associée", "associee", "fr", lists)) // accents and case aside
        assertEquals(Verdict.EMPTY, WordCheck.quick("and", " … ", "fr", lists))
        // Two words for one: "en public" heard as "and public".
        assertNull(WordCheck.quick("and", "en", "fr", lists))
        assertNull(WordCheck.quick("hommes and", "hommes en", "fr", lists))
    }

    @Test
    fun theSentenceMustMakeSenseThereAndBack() {
        val old = "Une étude menée auprès de 690 hommes and public."
        val new = "Une étude menée auprès de 690 hommes en public."
        // Kept by the round trip, and it holds together at least as well as before: plausible.
        assertNull(WordCheck.meaning("en", new, old, "A study of 690 men in public.", "Une étude menée auprès de 690 hommes en public.", "Une étude auprès de 690 hommes et le public.", "en", lists))
        // The round trip lost the word: it doesn't fit the sentence.
        assertEquals(Verdict.DOESNT_FIT, WordCheck.meaning("crocodiles", "La croissance des crocodiles ralentit.", "La croissance économique ralentit.",
            "The growth of crocodiles slows down.", "La croissance ralentit.", "La croissance économique ralentit.", "en", lists))
        // Copied untranslated, and not an English word: the translator found no meaning for it.
        assertEquals(Verdict.NOT_UNDERSTOOD, WordCheck.meaning("klädstilar", "olika klädstilar", "olika kläddsträger",
            "different klädstilar", "olika klädstilar", "olika kläddsträger", "en", lists))
        // The new sentence holds together worse than the old one did.
        assertEquals(Verdict.DOESNT_FIT, WordCheck.meaning("partout", "Il parle partout ce matin.", "Il parle français ce matin.",
            "He speaks everywhere this morning.", "Partout il discute le matin.", "Il parle français ce matin.", "en", lists))
    }

    @Test
    fun caughtMistakesAreRewardedOnceEachAndTwentyADay() {
        var s = FishGame.State()
        s = FishGame.rewardCatch(s, "ep:1:0", day = 3)!!
        assertEquals(FishGame.CATCH_COINS, s.coins)
        assertNull(FishGame.rewardCatch(s, "ep:1:0", day = 3)) // the same word again
        repeat(FishGame.CATCHES_PER_DAY - 1) { s = FishGame.rewardCatch(s, "ep:1:${it + 1}", day = 3)!! }
        assertNull(FishGame.rewardCatch(s, "ep:2:0", day = 3)) // today's limit
        assertEquals(FishGame.CATCH_COINS * (FishGame.CATCHES_PER_DAY + 1), FishGame.rewardCatch(s, "ep:2:0", day = 4)!!.coins) // tomorrow
    }
}
