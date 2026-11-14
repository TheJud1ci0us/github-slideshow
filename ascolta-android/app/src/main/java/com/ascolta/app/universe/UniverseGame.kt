package com.ascolta.app.universe

import kotlin.math.*

/** Pure idle-world rules. Wall time is settled on interaction; no background game loop is needed. */
object UniverseGame {
    const val OFFLINE_CAP_MS = 8 * 60 * 60_000L
    const val BOOST_CAP_MS = 30 * 60_000L
    const val MAX_LEVEL = 80
    const val MAX_WORKERS = 20
    const val MAX_RESEARCH = 12
    const val LIMIT = 1e100
    const val RADIUS = 4

    enum class Theme(val country: String) { FRANCE("FR"), JAPAN("JP"), ITALY("IT"), BRAZIL("BR") }
    enum class Kind { HOME, FOREST, QUARRY, FARM, SANCTUARY }
    enum class Research { FORESTRY, MASONRY, TRADE, AUTOMATION }
    data class Tile(val q: Int, val r: Int) {
        val id get() = "$q,$r"
        val distance get() = maxOf(abs(q), abs(r), abs(q + r))
        val kind: Kind get() = when {
            q == 0 && r == 0 -> Kind.HOME
            q == 1 && r == 0 -> Kind.FOREST
            q == 0 && r == 1 -> Kind.QUARRY
            (q == 2 && r == -1) || (q == -2 && r == 1) || (q == 1 && r == 2) -> Kind.SANCTUARY
            else -> when (((q * 73_856_093L) xor (r * 19_349_663L)).absoluteValue % 7) {
                0L, 1L, 2L -> Kind.FOREST
                3L, 4L -> Kind.QUARRY
                else -> Kind.FARM
            }
        }
        fun neighbors() = listOf(Tile(q + 1, r), Tile(q - 1, r), Tile(q, r + 1), Tile(q, r - 1), Tile(q + 1, r - 1), Tile(q - 1, r + 1))
    }
    val tiles: List<Tile> = (-RADIUS..RADIUS).flatMap { q -> (-RADIUS..RADIUS).map { r -> Tile(q, r) } }.filter { it.distance <= RADIUS }.sortedWith(compareBy({ it.distance }, { it.q }, { it.r }))
    private val byId = tiles.associateBy { it.id }
    fun tile(id: String): Tile? = byId[id]

    data class Wallet(val wood: Double = 0.0, val stone: Double = 0.0, val gold: Double = 0.0) {
        fun canPay(cost: Wallet) = wood >= cost.wood && stone >= cost.stone && gold >= cost.gold
        operator fun plus(other: Wallet) = Wallet(safe(wood + other.wood), safe(stone + other.stone), safe(gold + other.gold))
        operator fun minus(other: Wallet) = Wallet((wood - other.wood).coerceAtLeast(0.0), (stone - other.stone).coerceAtLeast(0.0), (gold - other.gold).coerceAtLeast(0.0))
        operator fun times(factor: Double) = Wallet(safe(wood * factor), safe(stone * factor), safe(gold * factor))
    }
    data class State(
        val wallet: Wallet = Wallet(wood = 18.0, gold = 24.0),
        val levels: Map<String, Int> = mapOf("0,0" to 1, "1,0" to 1),
        val workers: Int = 1,
        val research: Map<Research, Int> = emptyMap(),
        val insight: Int = 0,
        val listeningMs: Long = 0,
        val listeningRemainderMs: Long = 0,
        val boostMs: Long = 0,
        val updatedAt: Long = 0,
        val lifetimeGold: Double = 0.0,
        val stars: Int = 0,
        val voyages: Int = 0,
        val theme: Theme = Theme.FRANCE,
        val reviewDay: Long = -1,
        val reviewed: Set<Long> = emptySet(),
        val episodeId: Long = 0,
        val episodeMs: Long = 0,
        val rewardedEpisodes: List<Long> = emptyList(),
        val legacyRelics: Int = 0,
        val legacyGold: Long = 0,
    ) {
        fun level(tile: Tile) = levels[tile.id] ?: 0
        val totalLevels get() = levels.values.sum()
        val permanentMultiplier get() = 1.0 + stars * 0.5
    }
    private fun safe(n: Double): Double = if (n.isFinite()) n.coerceIn(0.0, LIMIT) else if (n > 0) LIMIT else 0.0
    fun initial(now: Long, language: String = "fr", legacyCoins: Long = 0, legacyRelics: Int = 0): State = State(
        updatedAt = now,
        wallet = Wallet(18.0, 0.0, 24.0 + legacyCoins.coerceIn(0, 10_000_000)),
        insight = (legacyCoins.coerceAtLeast(0) / 100).coerceAtMost(200).toInt(),
        legacyGold = legacyCoins.coerceIn(0, 10_000_000), legacyRelics = legacyRelics.coerceIn(0, 50),
        theme = when (language.substringBefore('-')) { "ja" -> Theme.JAPAN; "it" -> Theme.ITALY; "pt" -> Theme.BRAZIL; else -> Theme.FRANCE },
    )
    fun frontier(s: State): List<Tile> = s.levels.keys.mapNotNull(::tile).flatMap { it.neighbors() }.filter { it.distance <= RADIUS && it.id !in s.levels }.distinctBy { it.id }.sortedWith(compareBy({ it.distance }, { it.q }, { it.r }))

    fun rates(s: State, boosted: Boolean = s.boostMs > 0): Wallet {
        var base = Wallet()
        s.levels.forEach { (id, level) -> tile(id)?.let { t ->
            val scale = level * 1.04.pow(level - 1) * 2.0.pow(level / 10)
            base += when (t.kind) {
                Kind.HOME -> Wallet(wood = 0.2 * scale, gold = 0.7 * scale)
                Kind.FOREST -> Wallet(wood = 1.5 * scale)
                Kind.QUARRY -> Wallet(stone = 1.1 * scale)
                Kind.FARM -> Wallet(gold = 1.4 * scale)
                Kind.SANCTUARY -> Wallet(gold = 2.5 * scale)
            }
        } }
        val common = (1 + (s.workers - 1) * 0.15) * 1.6.pow(s.research[Research.AUTOMATION] ?: 0) * s.permanentMultiplier * if (boosted) 3.0 else 1.0
        return Wallet(base.wood * 2.0.pow(s.research[Research.FORESTRY] ?: 0), base.stone * 2.0.pow(s.research[Research.MASONRY] ?: 0), base.gold * 2.0.pow(s.research[Research.TRADE] ?: 0)) * common
    }
    fun advance(s: State, now: Long): State {
        if (now <= s.updatedAt) return s
        val elapsed = (now - s.updatedAt).coerceIn(0, OFFLINE_CAP_MS)
        val boosted = min(elapsed, s.boostMs)
        val earned = rates(s, false) * ((elapsed + boosted * 2) / 1000.0)
        return s.copy(wallet = s.wallet + earned, lifetimeGold = safe(s.lifetimeGold + earned.gold), boostMs = (s.boostMs - elapsed).coerceAtLeast(0), updatedAt = now)
    }
    fun unlockCost(s: State, tile: Tile): Wallet {
        val n = (s.levels.size - 2).coerceAtLeast(0)
        val firstQuarry = tile.kind == Kind.QUARRY && s.levels.keys.none { this.tile(it)?.kind == Kind.QUARRY }
        return Wallet(14 * 1.36.pow(n), if (firstQuarry) 0.0 else 5 * 1.32.pow(n), 12 * 1.38.pow(n))
    }
    fun unlock(s: State, tile: Tile, now: Long): State? {
        val ready = advance(s, now)
        if (tile !in frontier(ready)) return null
        val cost = unlockCost(ready, tile)
        if (!ready.wallet.canPay(cost)) return null
        return ready.copy(wallet = ready.wallet - cost, levels = ready.levels + (tile.id to 1), insight = (ready.insight + if (tile.kind == Kind.SANCTUARY) 2 else 0).coerceAtMost(1_000_000))
    }
    fun upgradeCost(s: State, tile: Tile): Wallet {
        val level = s.level(tile).coerceAtLeast(1)
        val factor = 1.43.pow(level - 1)
        return Wallet(12 * factor, if (level < 3) 0.0 else 8 * factor, 9 * factor)
    }
    fun upgrade(s: State, tile: Tile, now: Long): State? {
        val ready = advance(s, now)
        if (ready.level(tile) !in 1 until MAX_LEVEL) return null
        val cost = upgradeCost(ready, tile)
        if (!ready.wallet.canPay(cost)) return null
        return ready.copy(wallet = ready.wallet - cost, levels = ready.levels + (tile.id to ready.level(tile) + 1))
    }
    fun workerCost(s: State) = Wallet(40 * 1.65.pow(s.workers - 1), 15 * (s.workers - 1) * 1.5.pow(s.workers - 1), 50 * 1.65.pow(s.workers - 1))
    fun recruit(s: State, now: Long): State? {
        val ready = advance(s, now)
        val cost = workerCost(ready)
        if (ready.workers >= MAX_WORKERS || !ready.wallet.canPay(cost)) return null
        return ready.copy(wallet = ready.wallet - cost, workers = ready.workers + 1)
    }
    fun researchCost(s: State, research: Research): Wallet {
        val factor = 2.35.pow(s.research[research] ?: 0)
        return Wallet(100 * factor, 65 * factor, 180 * factor)
    }
    fun insightCost(s: State, research: Research) = 1 + 2 * (s.research[research] ?: 0)
    fun research(s: State, research: Research, useInsight: Boolean, now: Long): State? {
        val ready = advance(s, now)
        if ((ready.research[research] ?: 0) >= MAX_RESEARCH) return null
        val insight = if (useInsight) insightCost(ready, research) else 0
        val cost = researchCost(ready, research) * if (useInsight) 0.5 else 1.0
        if (ready.insight < insight || !ready.wallet.canPay(cost)) return null
        return ready.copy(wallet = ready.wallet - cost, insight = ready.insight - insight, research = ready.research + (research to (ready.research[research] ?: 0) + 1))
    }
    fun listen(s: State, ms: Long, now: Long, episodeId: Long): State {
        if (ms <= 0) return s
        val ready = advance(s, now)
        val listened = ms.coerceAtMost(60_000)
        val total = ready.listeningRemainderMs + listened
        return ready.copy(
            insight = (ready.insight + (total / 300_000).toInt()).coerceAtMost(1_000_000),
            listeningMs = (ready.listeningMs + listened).coerceAtMost(Long.MAX_VALUE / 2), listeningRemainderMs = total % 300_000,
            boostMs = (ready.boostMs + listened * 2).coerceAtMost(BOOST_CAP_MS), episodeId = episodeId,
            episodeMs = if (ready.episodeId == episodeId) ready.episodeMs + listened else listened,
        )
    }
    fun review(s: State, cardId: Long, correct: Boolean, day: Long, now: Long): State? {
        if (!correct || cardId <= 0) return null
        val reviewed = if (s.reviewDay == day) s.reviewed else emptySet()
        if (cardId in reviewed || reviewed.size >= 40) return null
        return advance(s, now).copy(insight = (s.insight + 1).coerceAtMost(1_000_000), reviewDay = day, reviewed = reviewed + cardId)
    }
    fun finishEpisode(s: State, episodeId: Long, durationMs: Long, now: Long): State? {
        if (episodeId <= 0 || episodeId in s.rewardedEpisodes || episodeId != s.episodeId || durationMs < 60_000 || s.episodeMs < min(600_000, durationMs / 2)) return null
        return advance(s, now).copy(insight = (s.insight + 2).coerceAtMost(1_000_000), rewardedEpisodes = (s.rewardedEpisodes + episodeId).takeLast(200))
    }
    fun prestigeReward(s: State): Int = if (s.levels.size < 12 || s.lifetimeGold < 10_000) 0 else sqrt(s.lifetimeGold / 10_000).toInt().coerceIn(1, 1_000)
    fun voyage(s: State, theme: Theme, now: Long): State? {
        val ready = advance(s, now)
        val reward = prestigeReward(ready)
        if (reward <= 0) return null
        return initial(now).copy(
            insight = ready.insight, listeningMs = ready.listeningMs, listeningRemainderMs = ready.listeningRemainderMs, boostMs = ready.boostMs,
            stars = (ready.stars + reward).coerceAtMost(1_000_000), voyages = (ready.voyages + 1).coerceAtMost(1_000_000), theme = theme,
            reviewDay = ready.reviewDay, reviewed = ready.reviewed, rewardedEpisodes = ready.rewardedEpisodes,
            episodeId = ready.episodeId, episodeMs = ready.episodeMs, legacyRelics = ready.legacyRelics, legacyGold = ready.legacyGold,
        )
    }
}
