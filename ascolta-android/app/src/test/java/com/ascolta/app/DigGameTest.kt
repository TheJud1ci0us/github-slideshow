package com.ascolta.app

import com.ascolta.app.data.DigGame
import com.ascolta.app.data.DigGame.Content
import com.ascolta.app.data.DigGame.CrateType
import com.ascolta.app.data.DigGame.MINUTE
import com.ascolta.app.data.DigGame.Rarity
import com.ascolta.app.data.DigGame.State
import com.ascolta.app.data.DigGame.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DigGameTest {
    @Test
    fun onlyForeignListeningEarnsAndLongSessionsEarnMore() {
        var s = State()
        s = DigGame.listen(s, 5 * MINUTE, now = 0, foreign = false)
        assertEquals(0, s.minutes)
        s = DigGame.listen(s, 9 * MINUTE, now = 1_000, foreign = true)
        assertEquals(9, s.minutes)
        assertEquals(1.0, s.multiplier, 0.0)
        // Past 10 minutes without a break: ×1.2, past 20: ×1.5, past 30: ×2.
        s = DigGame.listen(s, MINUTE, now = 2_000, foreign = true)
        assertEquals(1.2, s.multiplier, 0.0)
        s = DigGame.listen(s, 10 * MINUTE, now = 3_000, foreign = true)
        assertEquals(1.5, s.multiplier, 0.0)
        s = DigGame.listen(s, 10 * MINUTE, now = 4_000, foreign = true)
        assertEquals(2.0, s.multiplier, 0.0)
        // A break of more than two minutes starts again at ×1.
        s = DigGame.listen(s, MINUTE, now = 4_000 + 3 * MINUTE, foreign = true)
        assertEquals(1.0, s.multiplier, 0.0)
        assertEquals(MINUTE, s.comboMs)
    }

    @Test
    fun cratesAreEarnedEvery30MinutesAndOncePerFinishedEpisode() {
        var s = State()
        repeat(65) { s = DigGame.listen(s, MINUTE, now = it * 1_000L, foreign = true, episodeId = 7) }
        assertEquals(2, s.crates(CrateType.WOOD))
        s = DigGame.finishEpisode(s, 7, 80 * MINUTE)
        assertEquals(3, s.crates(CrateType.WOOD))
        assertEquals(3, DigGame.finishEpisode(s, 7, 80 * MINUTE).crates(CrateType.WOOD)) // only once
        // Jumping to the end of an episode barely listened to gives nothing.
        val skipped = DigGame.listen(State(), MINUTE, now = 0, foreign = true, episodeId = 9)
        assertEquals(0, DigGame.finishEpisode(skipped, 9, 40 * MINUTE).crates(CrateType.WOOD))
    }

    @Test
    fun onlyExposedBlocksCanBeDugOnceAndWithEnoughMinutes() {
        val rich = State(walletMs = 100 * MINUTE)
        assertNull(DigGame.dig(rich, 1, 2)) // buried
        assertNull(DigGame.dig(State(), 0, 2)) // no minutes
        val first = DigGame.dig(rich, 0, 2)!!.state
        assertTrue(first.isDug(0, 2))
        assertNull(DigGame.dig(first, 0, 2)) // already dug
        assertTrue(first.exposed(1, 2))
        assertFalse(first.exposed(1, 0))
        assertNotNull(DigGame.dig(first, 1, 2))
        assertNull(DigGame.dig(rich, 0, 5))
    }

    @Test
    fun deeperLayersCostMoreAndBetterPickaxesLess() {
        assertEquals(listOf(1, 2, 4, 8, 15, 30), (0 until 6).map { DigGame.costMinutes(it * 6, Tool.WOOD) })
        assertEquals(40, DigGame.costMinutes(36, Tool.WOOD)) // the layers again, harder
        assertEquals(8, DigGame.costMinutes(30, Tool.DIAMOND))
        assertEquals(1, DigGame.costMinutes(0, Tool.DIAMOND))
        assertEquals(6, DigGame.costMinutes(18, Tool.STONE))
        val poor = State(walletMs = 29 * MINUTE)
        assertNull(DigGame.buyTool(poor))
        val bought = DigGame.buyTool(State(walletMs = 31 * MINUTE))!!
        assertEquals(Tool.STONE, bought.tool)
        assertEquals(1, bought.minutes)
        assertNull(DigGame.buyTool(State(walletMs = 10_000 * MINUTE, tool = Tool.DIAMOND)))
    }

    @Test
    fun blocksAlwaysHideTheSameThingAndEveryFindCanBeDug() {
        assertEquals(40, DigGame.FINDS.size)
        assertTrue(Rarity.entries.all { r -> DigGame.FINDS.count { it.rarity == r } == 8 })
        val seen = mutableSetOf<String>()
        var finds = 0; var crates = 0; var blocks = 0
        for (seed in 1L..40L) for (row in 0 until 60) for (col in 0 until DigGame.COLS) {
            val c = DigGame.content(seed, row, col)
            assertEquals(c, DigGame.content(seed, row, col))
            blocks++
            when (c) {
                is Content.Treasure -> {
                    finds++; seen += c.find.id
                    if (c.find.rarity == Rarity.LEGENDARY) assertTrue(DigGame.depth(row) >= 3) // from limestone
                }
                is Content.Crate -> crates++
                Content.Nothing -> Unit
            }
        }
        assertEquals(DigGame.FINDS.map { it.id }.toSet(), seen)
        assertEquals(0.25, finds.toDouble() / blocks, 0.02)
        assertEquals(1 / 15.0, crates.toDouble() / blocks, 0.015)
    }

    @Test
    fun cratesFollowTheirOddsAndDuplicatesGiveMinutesBack() {
        for (type in CrateType.entries) {
            assertEquals(100, DigGame.crateOdds(type, 0).sum())
            val counts = IntArray(5)
            val random = Random(type.ordinal + 1)
            var s = State(crates = mapOf(type to 4_000))
            repeat(4_000) {
                val opened = DigGame.openCrate(s, type, random)!!
                assertTrue(opened.find.rarity >= type.minRarity)
                counts[opened.find.rarity.ordinal]++
                s = opened.state
            }
            DigGame.crateOdds(type, 0).forEachIndexed { i, odds -> assertEquals(odds / 100.0, counts[i] / 4_000.0, 0.03) }
            assertEquals(0, s.crates(type))
        }
        assertNull(DigGame.openCrate(State(), CrateType.WOOD))
        // The second copper coin gives a minute back; the first gives none.
        val one = State(crates = mapOf(CrateType.GOLD to 1), finds = DigGame.FINDS.associate { it.id to 1 })
        val opened = DigGame.openCrate(one, CrateType.GOLD, Random(3))!!
        assertFalse(opened.isNew)
        assertEquals(opened.find.rarity.refundMinutes, opened.state.minutes)
        assertEquals(2, opened.state.finds[opened.find.id])
        assertNull(DigGame.buyCrate(State(walletMs = 14 * MINUTE), CrateType.WOOD))
        assertEquals(1, DigGame.buyCrate(State(walletMs = 15 * MINUTE), CrateType.WOOD)!!.crates(CrateType.WOOD))
    }

    @Test
    fun theWelcomeGiftIsCappedAndGivenOnce() {
        val s = DigGame.gift(State(), 500 * MINUTE)
        assertEquals(60, s.minutes)
        assertEquals(60, DigGame.gift(s, 500 * MINUTE).minutes)
        assertEquals(12, DigGame.gift(State(), 12 * MINUTE).minutes)
    }
}
