package com.ascolta.app

import com.ascolta.app.data.FishGame
import com.ascolta.app.data.FishGame.Bait
import com.ascolta.app.data.FishGame.COIN_MS
import com.ascolta.app.data.FishGame.Fish
import com.ascolta.app.data.FishGame.MINUTE
import com.ascolta.app.data.FishGame.Species
import com.ascolta.app.data.FishGame.State
import com.ascolta.app.data.FishGame.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FishGameTest {
    private fun coins(n: Int) = n * COIN_MS

    @Test
    fun onlyForeignListeningEarnsTenCoinsAMinuteAndLongSessionsEarnMore() {
        var s = State(day = 5, dayMs = 10 * MINUTE) // today's double already used
        s = FishGame.listen(s, 5 * MINUTE, now = 0, day = 5, foreign = false)
        assertEquals(0, s.coins)
        s = FishGame.listen(s, 9 * MINUTE, now = 1_000, day = 5, foreign = true)
        assertEquals(90, s.coins)
        s = FishGame.listen(s, MINUTE, now = 2_000, day = 5, foreign = true)
        assertEquals(1.2, s.multiplier, 0.0)
        assertEquals(102, s.coins)
        s = FishGame.listen(s, 10 * MINUTE, now = 3_000, day = 5, foreign = true)
        assertEquals(1.5, s.multiplier, 0.0)
        s = FishGame.listen(s, 10 * MINUTE, now = 4_000, day = 5, foreign = true)
        assertEquals(2.0, s.multiplier, 0.0)
        // A break of more than two minutes starts again at ×1.
        s = FishGame.listen(s, MINUTE, now = 4_000 + 3 * MINUTE, day = 5, foreign = true)
        assertEquals(1.0, s.multiplier, 0.0)
    }

    @Test
    fun theFirstTenMinutesOfADayCountDouble() {
        var s = FishGame.listen(State(), 4 * MINUTE, now = 0, day = 7, foreign = true)
        assertEquals(80, s.coins)
        assertEquals(6 * MINUTE, s.doubleLeftMs(7))
        s = FishGame.listen(s, 4 * MINUTE, now = 1_000, day = 7, foreign = true)
        assertEquals(160, s.coins)
        // 2 minutes double, 2 plain.
        s = FishGame.listen(s, 4 * MINUTE, now = 2_000 + 5 * MINUTE, day = 7, foreign = true)
        assertEquals(220, s.coins)
        assertEquals(0, s.doubleLeftMs(7))
        // The next day it's back.
        assertEquals(10 * MINUTE, s.doubleLeftMs(8))
        s = FishGame.listen(s, MINUTE, now = 100 * MINUTE, day = 8, foreign = true)
        assertEquals(240, s.coins)
    }

    @Test
    fun baitGivesItsTiersAtTheShownOdds() {
        for (bait in Bait.entries) {
            assertEquals(1000, bait.odds.sum())
            val counts = IntArray(5)
            val random = Random(bait.ordinal + 11)
            val n = 20_000
            repeat(n) {
                // A fresh player each time, so the guarantee doesn't change the shares.
                val c = FishGame.cast(State(coinMs = coins(bait.price)), bait, day = 3, random)!!
                counts[c.fish.tier.ordinal]++
                assertEquals(0, c.state.coins)
            }
            bait.odds.forEachIndexed { i, odds ->
                assertEquals(odds / 1000.0, counts[i] / n.toDouble(), 0.012)
                if (odds == 0) assertEquals(0, counts[i])
            }
        }
        assertNull(FishGame.cast(State(coinMs = coins(49)), Bait.WORM, 3))
    }

    @Test
    fun afterTenWormsWithoutARareTheNextIsAtLeastRare() {
        val random = Random(1)
        var guaranteed = 0
        repeat(300) {
            val c = FishGame.cast(State(coinMs = coins(50), sinceRare = 10), Bait.WORM, 3, random)!!
            assertTrue(c.fish.tier >= Tier.RARE)
            assertEquals(0, c.state.sinceRare)
            if (c.guaranteed) guaranteed++
        }
        assertTrue(guaranteed > 200)
        // Below ten, a common catch counts one more; a Shrimp resets the count.
        var s = State(coinMs = coins(5_000), sinceRare = 4)
        var c = FishGame.cast(s, Bait.WORM, 3, Random(5))!!
        assertEquals(if (c.fish.tier >= Tier.RARE) 0 else 5, c.state.sinceRare)
        c = FishGame.cast(s, Bait.SHRIMP, 3, Random(5))!!
        assertEquals(0, c.state.sinceRare)
        assertFalse(c.guaranteed)
    }

    @Test
    fun oneSpeciesBitesFourTimesAsOftenEachDay() {
        assertTrue((0L..60L).map { FishGame.biting(it) }.toSet().size >= 8)
        assertNotEquals((0L..9L).map { FishGame.biting(it) }, (10L..19L).map { FishGame.biting(it) })
        val day = 20_000L
        val today = FishGame.biting(day)
        val counts = IntArray(Species.entries.size)
        val random = Random(9)
        val n = 26_000
        repeat(n) { counts[FishGame.cast(State(coinMs = coins(50)), Bait.WORM, day, random)!!.fish.species.ordinal]++ }
        assertEquals(4 / 13.0, counts[today.ordinal] / n.toDouble(), 0.015)
        Species.entries.filter { it != today }.forEach { assertEquals(1 / 13.0, counts[it.ordinal] / n.toDouble(), 0.01) }
    }

    @Test
    fun threeOfAKindMergeIntoTheNextTier() {
        val clown = Fish(Species.CLOWNFISH, Tier.COMMON)
        assertNull(FishGame.merge(State(fish = mapOf(clown.key to 2)), clown))
        val merged = FishGame.merge(State(fish = mapOf(clown.key to 4)), clown)!!
        assertEquals(1, merged.count(clown))
        assertEquals(1, merged.count(Fish(Species.CLOWNFISH, Tier.UNCOMMON)))
        val gold = Fish(Species.SHARK, Tier.LEGENDARY)
        assertNull(FishGame.merge(State(fish = mapOf(gold.key to 3)), gold))
        // The tank shows one of each kind, the rarest first.
        val tank = FishGame.tank(State(fish = mapOf(clown.key to 5, gold.key to 1, Fish(Species.BETTA, Tier.RARE).key to 0)))
        assertEquals(listOf(gold, clown), tank)
        assertEquals(50, FishGame.ALL.size)
        assertEquals(18, FishGame.tank(State(fish = FishGame.ALL.associate { it.key to 1 })).size)
    }

    @Test
    fun freeCastsComeEvery30MinutesAndOncePerFinishedEpisode() {
        var s = State()
        repeat(65) { s = FishGame.listen(s, MINUTE, now = it * 1_000L, day = 1, foreign = true, episodeId = 7) }
        assertEquals(2, s.freeCasts)
        s = FishGame.finishEpisode(s, 7, 80 * MINUTE)
        assertEquals(3, s.freeCasts)
        assertEquals(3, FishGame.finishEpisode(s, 7, 80 * MINUTE).freeCasts)
        val skipped = FishGame.listen(State(), MINUTE, now = 0, day = 1, foreign = true, episodeId = 9)
        assertEquals(0, FishGame.finishEpisode(skipped, 9, 40 * MINUTE).freeCasts)
        // A free cast is used before coins, and only for the Worm.
        val before = s.coins
        val c = FishGame.cast(s, Bait.WORM, 1, Random(2))!!
        assertTrue(c.free)
        assertEquals(before, c.state.coins)
        assertEquals(2, c.state.freeCasts)
        assertNull(FishGame.cast(State(freeCasts = 3), Bait.SHRIMP, 1))
    }

    @Test
    fun theShopTakesCoinsOnceAndDigMinutesMoveOver() {
        var s = FishGame.fromDig(42 * MINUTE, gifted = true)
        assertEquals(420, s.coins)
        assertEquals(420, FishGame.gift(s, 500 * MINUTE).coins) // the gift was already given
        assertEquals(600, FishGame.gift(State(), 500 * MINUTE).coins)
        assertNull(FishGame.buy(s, FishGame.Decoration.CASTLE))
        s = FishGame.buy(s, FishGame.Decoration.SEAWEED)!!
        assertEquals(360, s.coins)
        assertNull(FishGame.buy(s, FishGame.Decoration.SEAWEED))
        assertNull(FishGame.choose(s, FishGame.Background.LAGOON))
        s = FishGame.choose(s.copy(coinMs = coins(900)), FishGame.Background.LAGOON)!!
        assertEquals(400, s.coins)
        assertEquals(FishGame.Background.LAGOON, s.background)
        s = FishGame.choose(s, FishGame.Background.REEF)!!
        s = FishGame.choose(s, FishGame.Background.LAGOON)!! // owned: free to switch back
        assertEquals(400, s.coins)
    }
}
