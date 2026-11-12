package com.ascolta.app.data

import kotlin.random.Random

/**
 * The Aquarium: a fishing game whose only currency is coins earned by listening in a foreign
 * language. Rules only (pure, unit-tested); [FishStore] keeps the state, AquariumScreen shows it.
 *
 * Coins buy bait; a cast catches one of ten species in one of five tiers; three of the same
 * fish merge into the next tier; coins also buy decorations and backgrounds for the tank.
 */
object FishGame {
    const val MINUTE = 60_000L
    /** Listening time (after multipliers) per coin: 10 coins a minute. */
    const val COIN_MS = 6_000L
    const val GIFT_MAX_MS = 60 * MINUTE
    const val FREE_CAST_EVERY_MS = 30 * MINUTE
    const val DAILY_BONUS_MS = 10 * MINUTE
    const val GUARANTEE_AFTER = 10
    const val MERGE = 3
    const val TANK_SIZE = 18
    private const val COMBO_BREAK_MS = 2 * MINUTE

    enum class Tier { COMMON, UNCOMMON, RARE, VERY_RARE, LEGENDARY }

    enum class Species { CLOWNFISH, BLUETANG, GOLDFISH, ANGELFISH, PUFFERFISH, SHARK, BETTA, NEONTETRA, BUTTERFLYFISH, ANGLERFISH }

    data class Fish(val species: Species, val tier: Tier) {
        val key: String get() = "${species.name}:${tier.name}"
    }

    val ALL: List<Fish> = Species.entries.flatMap { s -> Tier.entries.map { Fish(s, it) } }

    fun fish(key: String): Fish? = ALL.firstOrNull { it.key == key }

    /** Bait: its price in coins and the chances of each tier, out of 1000. */
    enum class Bait(val price: Int, val odds: List<Int>) {
        WORM(50, listOf(620, 250, 90, 32, 8)),
        SHRIMP(150, listOf(0, 0, 750, 200, 50)),
        LURE(400, listOf(0, 0, 0, 800, 200)),
    }

    enum class Decoration(val price: Int) {
        SEAWEED(60), ROCKS(80), REDPLANT(100), STARFISH(120), CORAL_PINK(180), CORAL_BRAIN(220),
        CLAM(300), AMPHORA(350), ANCHOR(450), CHEST(600), CASTLE(900), SHIP(1200),
    }

    enum class Background(val price: Int) { REEF(0), LAGOON(500), DEEP(800) }

    /** The species biting on [day] (days since 1970): four times as likely that day. */
    fun biting(day: Long): Species {
        var h = day * -0x61c8864680b583ebL
        h = (h xor (h ushr 29)) * -0x40a7b892e31b1a47L
        return Species.entries[((h xor (h ushr 32)) and 0x7fffffff).toInt() % Species.entries.size]
    }

    /** Longer sessions earn more: ×1.2 after 10 minutes without a break, ×1.5 after 20, ×2 after 30. */
    fun multiplier(comboMs: Long): Double = when {
        comboMs >= 30 * MINUTE -> 2.0
        comboMs >= 20 * MINUTE -> 1.5
        comboMs >= 10 * MINUTE -> 1.2
        else -> 1.0
    }

    data class State(
        /** Coins, as listening time after multipliers ([COIN_MS] per coin). */
        val coinMs: Long = 0,
        /** Fish key → how many. */
        val fish: Map<String, Int> = emptyMap(),
        val decorations: Set<Decoration> = emptySet(),
        val backgrounds: Set<Background> = setOf(Background.REEF),
        val background: Background = Background.REEF,
        val freeCasts: Int = 0,
        /** Worm casts since the last Rare or better. */
        val sinceRare: Int = 0,
        val comboMs: Long = 0,
        val lastListenAt: Long = 0,
        /** Real listening time towards the next free cast. */
        val castProgressMs: Long = 0,
        /** The day of [dayMs], and how much was listened to that day (for the daily double). */
        val day: Long = 0,
        val dayMs: Long = 0,
        val gifted: Boolean = false,
        /** Earned since the game was last opened (the "+120" on Profile). */
        val unseenMs: Long = 0,
        val episodeId: Long = 0,
        val episodeMs: Long = 0,
        /** Episodes whose "finished" free cast was given (the latest 300). */
        val rewarded: List<Long> = emptyList(),
    ) {
        val coins: Int get() = (coinMs / COIN_MS).toInt()
        val multiplier: Double get() = multiplier(comboMs)
        fun count(fish: Fish) = this.fish[fish.key] ?: 0
        val collected: Int get() = fish.count { it.value > 0 }
        /** Minutes of today's double coins still to come on [today]. */
        fun doubleLeftMs(today: Long): Long = if (day == today) (DAILY_BONUS_MS - dayMs).coerceAtLeast(0) else DAILY_BONUS_MS
        fun canMerge(fish: Fish) = fish.tier != Tier.LEGENDARY && count(fish) >= MERGE
        fun canCast(bait: Bait) = coins >= bait.price || (bait == Bait.WORM && freeCasts > 0)
    }

    /** One-time welcome: what was listened to before the game existed, up to an hour (600 coins). */
    fun gift(s: State, pastMs: Long): State = if (s.gifted) s else s.copy(gifted = true, coinMs = s.coinMs + pastMs.coerceIn(0, GIFT_MAX_MS))

    /** Moving over from the Dig site: its unspent minutes become coins (10 a minute), its welcome gift counts. */
    fun fromDig(walletMs: Long, gifted: Boolean): State = State(coinMs = walletMs.coerceAtLeast(0), gifted = gifted)

    /**
     * [ms] of an episode just played at time [now] on [day]. Only foreign-language listening
     * counts. The first ten minutes of a day count double; a break of more than two minutes ends
     * the combo; every 30 minutes gives a free cast.
     */
    fun listen(s: State, ms: Long, now: Long, day: Long, foreign: Boolean, episodeId: Long = 0): State {
        if (ms <= 0 || !foreign) return s
        val combo = (if (now - s.lastListenAt > COMBO_BREAK_MS) 0 else s.comboMs) + ms
        val dayMs = if (day == s.day) s.dayMs else 0
        val doubled = ms.coerceAtMost((DAILY_BONUS_MS - dayMs).coerceAtLeast(0))
        val earned = ((ms + doubled) * multiplier(combo)).toLong()
        val progress = s.castProgressMs + ms
        return s.copy(
            coinMs = s.coinMs + earned, unseenMs = s.unseenMs + earned, comboMs = combo, lastListenAt = now,
            castProgressMs = progress % FREE_CAST_EVERY_MS, freeCasts = s.freeCasts + (progress / FREE_CAST_EVERY_MS).toInt(),
            day = day, dayMs = dayMs + ms,
            episodeId = episodeId, episodeMs = if (episodeId == s.episodeId) s.episodeMs + ms else ms,
        )
    }

    /** A free cast for an episode listened to its end (really listened: 10 minutes or half of it), once. */
    fun finishEpisode(s: State, episodeId: Long, durationMs: Long): State {
        if (episodeId == 0L || episodeId != s.episodeId || episodeId in s.rewarded) return s
        if (s.episodeMs < minOf(10 * MINUTE, durationMs / 2)) return s
        return s.copy(rewarded = (s.rewarded + episodeId).takeLast(300), freeCasts = s.freeCasts + 1)
    }

    data class Catch(val state: State, val fish: Fish, val isNew: Boolean, val guaranteed: Boolean, val free: Boolean)

    /**
     * Catches a fish with [bait] on [day], or null without the coins (a Worm uses a free cast
     * first). After [GUARANTEE_AFTER] Worm casts without a Rare, the next one is at least Rare.
     */
    fun cast(s: State, bait: Bait, day: Long, random: Random = Random.Default): Catch? {
        if (!s.canCast(bait)) return null
        val free = bait == Bait.WORM && s.freeCasts > 0
        var roll = random.nextInt(0, bait.odds.sum())
        var tier = Tier.COMMON
        for ((i, o) in bait.odds.withIndex()) { if (roll < o) { tier = Tier.entries[i]; break }; roll -= o }
        val guaranteed = bait == Bait.WORM && s.sinceRare >= GUARANTEE_AFTER && tier < Tier.RARE
        if (guaranteed) tier = Tier.RARE
        val today = biting(day)
        var pick = random.nextInt(0, Species.entries.size + 3)
        val species = if (pick >= Species.entries.size) today else Species.entries[pick]
        val fish = Fish(species, tier)
        val next = s.copy(
            coinMs = if (free) s.coinMs else s.coinMs - bait.price * COIN_MS,
            freeCasts = if (free) s.freeCasts - 1 else s.freeCasts,
            sinceRare = if (tier >= Tier.RARE) 0 else if (bait == Bait.WORM) s.sinceRare + 1 else s.sinceRare,
            fish = s.fish + (fish.key to s.count(fish) + 1),
        )
        return Catch(next, fish, s.count(fish) == 0, guaranteed, free)
    }

    /** Three of [fish] become one of the next tier, or null when there aren't three (or it's Legendary). */
    fun merge(s: State, fish: Fish): State? {
        if (!s.canMerge(fish)) return null
        val better = Fish(fish.species, Tier.entries[fish.tier.ordinal + 1])
        return s.copy(fish = s.fish + (fish.key to s.count(fish) - MERGE) + (better.key to s.count(better) + 1))
    }

    fun buy(s: State, item: Decoration): State? =
        if (item in s.decorations || s.coins < item.price) null else s.copy(coinMs = s.coinMs - item.price * COIN_MS, decorations = s.decorations + item)

    /** Buys [bg] if needed, and puts it in the tank. */
    fun choose(s: State, bg: Background): State? = when {
        bg in s.backgrounds -> s.copy(background = bg)
        s.coins < bg.price -> null
        else -> s.copy(coinMs = s.coinMs - bg.price * COIN_MS, backgrounds = s.backgrounds + bg, background = bg)
    }

    /** The fish swimming in the tank: one of each kind owned, the rarest first, [TANK_SIZE] at most. */
    fun tank(s: State): List<Fish> =
        ALL.filter { s.count(it) > 0 }.sortedWith(compareByDescending<Fish> { it.tier }.thenBy { it.species }).take(TANK_SIZE)
}
