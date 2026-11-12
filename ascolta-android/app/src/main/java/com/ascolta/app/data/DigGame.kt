package com.ascolta.app.data

import kotlin.math.ceil
import kotlin.random.Random

/**
 * The Dig site: a small incremental game whose only currency is listening time in a foreign
 * language. Rules only (pure, unit-tested); [DigStore] keeps the state, DigScreen shows it.
 *
 * The site is a wall [COLS] blocks wide and endlessly deep. Breaking a block costs minutes
 * (more in deeper layers, less with a better pickaxe); some blocks hide a find or a crate.
 */
object DigGame {
    const val COLS = 5
    const val LAYER_ROWS = 6
    const val MINUTE = 60_000L
    const val GIFT_MAX_MS = 60 * MINUTE
    const val CRATE_EVERY_MS = 30 * MINUTE
    private const val COMBO_BREAK_MS = 2 * MINUTE

    enum class Rarity(val refundMinutes: Int) { COMMON(1), UNCOMMON(2), RARE(5), EPIC(12), LEGENDARY(30) }

    data class Find(val id: String, val rarity: Rarity)

    /** 40 finds, 8 per rarity (ids match the drawings `dig_<id>` and the names `dig_find_<id>`). */
    val FINDS: List<Find> = listOf(
        Rarity.COMMON to listOf("coin_copper", "shell_snail", "bone", "flint", "shard", "nail", "button", "shell_clam"),
        Rarity.UNCOMMON to listOf("arrowhead", "coin_silver", "pot", "key", "ammonite", "bottle", "horseshoe", "beads"),
        Rarity.RARE to listOf("amber", "ring", "lamp", "trilobite", "sapphire", "dagger", "tablet", "compass"),
        Rarity.EPIC to listOf("geode", "goblet", "skull_dino", "emerald", "idol", "ruby", "pearl", "helmet"),
        Rarity.LEGENDARY to listOf("mask", "crown", "diamond", "egg", "scarab", "meteorite", "skull_crystal", "sceptre"),
    ).flatMap { (rarity, ids) -> ids.map { Find(it, rarity) } }

    fun find(id: String): Find? = FINDS.firstOrNull { it.id == id }

    enum class Layer(val minutes: Int) { TOPSOIL(1), CLAY(2), SANDSTONE(4), LIMESTONE(8), GRANITE(15), CRYSTAL(30) }

    /** 0 for the first six rows, 1 for the next six… (keeps counting through the cycles). */
    fun depth(row: Int): Int = row / LAYER_ROWS
    fun layer(row: Int): Layer = Layer.entries[depth(row) % Layer.entries.size]

    /** Minutes for a block of [row] with bare hands: the six layers, then again 40× harder. */
    fun baseMinutes(row: Int): Int {
        var cost = layer(row).minutes.toLong()
        repeat(depth(row) / Layer.entries.size) { cost *= 40 }
        return cost.coerceAtMost(1_000_000).toInt()
    }

    enum class Tool(val priceMinutes: Int, val power: Double) { WOOD(0, 1.0), STONE(30, 1.5), IRON(90, 2.0), STEEL(250, 3.0), DIAMOND(600, 4.0) }

    fun costMinutes(row: Int, tool: Tool): Int = ceil(baseMinutes(row) / tool.power).toInt().coerceAtLeast(1)

    enum class CrateType(val priceMinutes: Int, val minRarity: Rarity) { WOOD(15, Rarity.COMMON), IRON(40, Rarity.RARE), GOLD(100, Rarity.EPIC) }

    sealed interface Content {
        data object Nothing : Content
        data class Treasure(val find: Find) : Content
        data class Crate(val type: CrateType) : Content
    }

    /** Chances (in %) of each rarity for a find dug at [depth]: better deeper, legendary from limestone. */
    fun rarityOdds(depth: Int): List<Int> = when (depth) {
        0 -> listOf(70, 25, 5, 0, 0)
        1 -> listOf(60, 28, 10, 2, 0)
        2 -> listOf(50, 30, 15, 5, 0)
        3 -> listOf(42, 30, 18, 8, 2)
        4 -> listOf(35, 28, 22, 11, 4)
        else -> listOf(28, 27, 25, 14, 6)
    }

    /** Chances (in %) of each rarity in a crate, for a player who has dug down to [deepest]. */
    fun crateOdds(type: CrateType, deepest: Int): List<Int> = when (type) {
        CrateType.WOOD -> rarityOdds(deepest + 1)
        CrateType.IRON -> listOf(0, 0, 70, 24, 6)
        CrateType.GOLD -> listOf(0, 0, 0, 78, 22)
    }

    private fun pick(odds: List<Int>, roll: Int): Rarity {
        var left = roll.mod(odds.sum())
        for ((i, o) in odds.withIndex()) { if (left < o) return Rarity.entries[i]; left -= o }
        return Rarity.COMMON
    }

    /** A well-mixed number for one block, the same every time. */
    private fun hash(seed: Long, row: Int, col: Int, salt: Int): Int {
        var h = seed xor (row.toLong() * -0x61c8864680b583ebL) xor (col.toLong() * -0x3d4d51c2d82b14b1L) xor (salt.toLong() * 0x165667b19e3779f9L)
        h = (h xor (h ushr 30)) * -0x40a7b892e31b1a47L
        h = (h xor (h ushr 27)) * -0x6b2fb644ecceee15L
        return ((h xor (h ushr 31)) ushr 33).toInt()
    }

    /** What the block at ([row], [col]) hides: a crate in about 1 of 15, a find in about 1 of 4. */
    fun content(seed: Long, row: Int, col: Int): Content {
        val roll = hash(seed, row, col, 1) % 60
        if (roll < 4) {
            val d = depth(row)
            return Content.Crate(if (d >= Layer.entries.size) CrateType.GOLD else if (d >= 3) CrateType.IRON else CrateType.WOOD)
        }
        if (roll >= 19) return Content.Nothing
        val rarity = pick(rarityOdds(depth(row)), hash(seed, row, col, 2))
        val choices = FINDS.filter { it.rarity == rarity }
        return Content.Treasure(choices[hash(seed, row, col, 3) % choices.size])
    }

    /** Longer sessions earn more: ×1.2 after 10 minutes without a break, ×1.5 after 20, ×2 after 30. */
    fun multiplier(comboMs: Long): Double = when {
        comboMs >= 30 * MINUTE -> 2.0
        comboMs >= 20 * MINUTE -> 1.5
        comboMs >= 10 * MINUTE -> 1.2
        else -> 1.0
    }

    data class State(
        val seed: Long = 1,
        val walletMs: Long = 0,
        val tool: Tool = Tool.WOOD,
        /** Dug blocks: per row, one bit per column. */
        val dug: Map<Int, Int> = emptyMap(),
        /** Find id → how many. */
        val finds: Map<String, Int> = emptyMap(),
        val crates: Map<CrateType, Int> = emptyMap(),
        val comboMs: Long = 0,
        val lastListenAt: Long = 0,
        /** Real listening time towards the next earned crate. */
        val crateProgressMs: Long = 0,
        val gifted: Boolean = false,
        /** Earned since the game was last opened (the "+12" on Profile). */
        val unseenMs: Long = 0,
        val episodeId: Long = 0,
        val episodeMs: Long = 0,
        /** Episodes whose "finished" crate was given (the latest 300). */
        val rewarded: List<Long> = emptyList(),
    ) {
        val minutes: Int get() = (walletMs / MINUTE).toInt()
        fun isDug(row: Int, col: Int) = ((dug[row] ?: 0) shr col) and 1 == 1
        val deepestRow: Int get() = dug.keys.maxOrNull() ?: -1
        val deepest: Int get() = depth(deepestRow.coerceAtLeast(0))
        val multiplier: Double get() = multiplier(comboMs)
        fun crates(type: CrateType) = crates[type] ?: 0
        /** A block can be dug when it's at the surface or touches a dug block. */
        fun exposed(row: Int, col: Int): Boolean = !isDug(row, col) && (
            row == 0 || isDug(row - 1, col) || isDug(row + 1, col) || (col > 0 && isDug(row, col - 1)) || (col < COLS - 1 && isDug(row, col + 1))
        )
    }

    private fun State.addCrates(type: CrateType, n: Int) = copy(crates = crates + (type to crates(type) + n))

    /** One-time welcome: what was listened to before the game existed, up to an hour. */
    fun gift(s: State, pastMs: Long): State = if (s.gifted) s else s.copy(gifted = true, walletMs = s.walletMs + pastMs.coerceIn(0, GIFT_MAX_MS))

    /**
     * [ms] of an episode just played at time [now]. Only foreign-language listening counts;
     * a break of more than two minutes ends the combo; every 30 minutes gives a wooden crate.
     */
    fun listen(s: State, ms: Long, now: Long, foreign: Boolean, episodeId: Long = 0): State {
        if (ms <= 0 || !foreign) return s
        val combo = (if (now - s.lastListenAt > COMBO_BREAK_MS) 0 else s.comboMs) + ms
        val earned = (ms * multiplier(combo)).toLong()
        val progress = s.crateProgressMs + ms
        val next = s.copy(
            walletMs = s.walletMs + earned, unseenMs = s.unseenMs + earned, comboMs = combo, lastListenAt = now,
            crateProgressMs = progress % CRATE_EVERY_MS,
            episodeId = episodeId, episodeMs = if (episodeId == s.episodeId) s.episodeMs + ms else ms,
        )
        return next.addCrates(CrateType.WOOD, (progress / CRATE_EVERY_MS).toInt())
    }

    /** A wooden crate for an episode listened to its end (really listened: 10 minutes or half of it), once. */
    fun finishEpisode(s: State, episodeId: Long, durationMs: Long): State {
        if (episodeId == 0L || episodeId != s.episodeId || episodeId in s.rewarded) return s
        if (s.episodeMs < minOf(10 * MINUTE, durationMs / 2)) return s
        return s.copy(rewarded = (s.rewarded + episodeId).takeLast(300)).addCrates(CrateType.WOOD, 1)
    }

    data class Dug(val state: State, val content: Content, val isNew: Boolean)

    /** Breaks a block, or null when it isn't exposed or the wallet is too small. */
    fun dig(s: State, row: Int, col: Int): Dug? {
        if (row < 0 || col !in 0 until COLS || !s.exposed(row, col)) return null
        val cost = costMinutes(row, s.tool) * MINUTE
        if (s.walletMs < cost) return null
        var next = s.copy(walletMs = s.walletMs - cost, dug = s.dug + (row to ((s.dug[row] ?: 0) or (1 shl col))))
        val content = content(s.seed, row, col)
        var isNew = false
        when (content) {
            is Content.Treasure -> { isNew = content.find.id !in s.finds; next = next.collect(content.find) }
            is Content.Crate -> next = next.addCrates(content.type, 1)
            Content.Nothing -> Unit
        }
        return Dug(next, content, isNew)
    }

    /** Adds a find; one already owned also gives a few minutes back. */
    private fun State.collect(find: Find): State {
        val had = finds[find.id] ?: 0
        return copy(finds = finds + (find.id to had + 1), walletMs = walletMs + if (had > 0) find.rarity.refundMinutes * MINUTE else 0)
    }

    /** The next pickaxe, or null with the best one. */
    fun nextTool(s: State): Tool? = Tool.entries.getOrNull(s.tool.ordinal + 1)

    fun buyTool(s: State): State? {
        val tool = nextTool(s) ?: return null
        val price = tool.priceMinutes * MINUTE
        return if (s.walletMs < price) null else s.copy(walletMs = s.walletMs - price, tool = tool)
    }

    fun buyCrate(s: State, type: CrateType): State? {
        val price = type.priceMinutes * MINUTE
        return if (s.walletMs < price) null else s.copy(walletMs = s.walletMs - price).addCrates(type, 1)
    }

    data class Opened(val state: State, val find: Find, val isNew: Boolean)

    /** Opens a crate of [type]: one random find, by the odds of [crateOdds]. */
    fun openCrate(s: State, type: CrateType, random: Random = Random.Default): Opened? {
        if (s.crates(type) <= 0) return null
        val rarity = pick(crateOdds(type, s.deepest), random.nextInt(0, 100))
        val find = FINDS.filter { it.rarity == rarity }.random(random)
        val next = s.copy(crates = s.crates + (type to s.crates(type) - 1)).collect(find)
        return Opened(next, find, find.id !in s.finds)
    }
}
