package com.ascolta.app.universe

import android.content.Context
import com.ascolta.app.data.AppLog
import com.ascolta.app.data.FishGame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** One immutable snapshot, serialized under a lock. The old aquarium save remains untouched. */
class UniverseStore(
    context: Context,
    language: String,
    legacy: FishGame.State = FishGame.State(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val prefs = context.getSharedPreferences("lingua_universe", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load(language, legacy))
    val state: StateFlow<UniverseGame.State> = _state
    private var lastSaved = 0L
    private var pendingMs = 0L
    private var pendingEpisode = 0L
    init { save(_state.value) }

    private fun load(language: String, legacy: FishGame.State): UniverseGame.State {
        val saved = prefs.getString("state", null)
        if (saved != null) runCatching { decode(saved) }.onSuccess { return it }.onFailure {
            prefs.edit().putString("recovery_state", saved).apply()
            AppLog.w("Universe", "Saved world unreadable; preserved a recovery copy", it)
        }
        return UniverseGame.initial(now(), language, legacy.coins.toLong(), legacy.collected)
    }
    private fun save(s: UniverseGame.State) {
        prefs.edit().putString("state", encode(s)).apply()
        lastSaved = now()
    }
    @Synchronized private fun change(saveNow: Boolean = true, action: (UniverseGame.State, Long) -> UniverseGame.State?): Boolean {
        val next = action(_state.value, now()) ?: return false
        _state.value = next
        if (saveNow || now() - lastSaved >= 15_000) save(next)
        return true
    }
    /** Refreshes the visible HUD only; this never schedules background work. */
    fun tick() { count(); change(saveNow = false) { s, n -> UniverseGame.advance(s, n) } }
    @Synchronized fun addListening(ms: Long, foreign: Boolean, episodeId: Long) {
        if (ms <= 0 || !foreign) return
        if (episodeId != pendingEpisode) { count(); pendingEpisode = episodeId }
        pendingMs += ms.coerceAtMost(1_000)
        if (pendingMs >= 5_000) count()
    }
    @Synchronized private fun count() {
        val ms = pendingMs
        if (ms <= 0) return
        pendingMs = 0
        change(saveNow = false) { s, n -> UniverseGame.listen(s, ms, n, pendingEpisode) }
    }
    @Synchronized fun flush() { count(); change { s, n -> UniverseGame.advance(s, n) } }
    fun unlock(tile: UniverseGame.Tile) = change { s, n -> UniverseGame.unlock(s, tile, n) }
    fun upgrade(tile: UniverseGame.Tile) = change { s, n -> UniverseGame.upgrade(s, tile, n) }
    fun recruit() = change { s, n -> UniverseGame.recruit(s, n) }
    fun research(research: UniverseGame.Research, insight: Boolean) = change { s, n -> UniverseGame.research(s, research, insight, n) }
    fun voyage(theme: UniverseGame.Theme) = change { s, n -> UniverseGame.voyage(s, theme, n) }
    fun review(cardId: Long, correct: Boolean) = change { s, n -> UniverseGame.review(s, cardId, correct, LocalDate.now().toEpochDay(), n) }
    fun episodeFinished(episodeId: Long, durationMs: Long) {
        if (episodeId in _state.value.rewardedEpisodes) return
        count()
        change { s, n -> UniverseGame.finishEpisode(s, episodeId, durationMs, n) }
    }
    /** Keeps the existing correction reward, now paid into the world's ordinary coins. */
    @Synchronized fun rewardCatch(key: String): Boolean {
        val day = LocalDate.now().toEpochDay()
        val seen = if (prefs.getLong("correction_day", -1) == day) prefs.getStringSet("corrections", emptySet()).orEmpty() else emptySet()
        if (key in seen || seen.size >= 20) return false
        change { s, n -> UniverseGame.advance(s, n).let { it.copy(wallet = it.wallet + UniverseGame.Wallet(gold = 30.0)) } }
        prefs.edit().putLong("correction_day", day).putStringSet("corrections", seen + key).apply()
        return true
    }
    companion object {
        fun encode(s: UniverseGame.State): String = JSONObject()
            .put("version", 1).put("wood", s.wallet.wood).put("stone", s.wallet.stone).put("gold", s.wallet.gold)
            .put("levels", JSONObject(s.levels)).put("workers", s.workers)
            .put("research", JSONObject().apply { s.research.forEach { (k, v) -> put(k.name, v) } })
            .put("insight", s.insight).put("listening", s.listeningMs).put("remainder", s.listeningRemainderMs)
            .put("boost", s.boostMs).put("updatedAt", s.updatedAt).put("lifetimeGold", s.lifetimeGold)
            .put("stars", s.stars).put("voyages", s.voyages).put("theme", s.theme.name)
            .put("reviewDay", s.reviewDay).put("reviewed", JSONArray(s.reviewed.toList()))
            .put("episodeId", s.episodeId).put("episodeMs", s.episodeMs).put("rewarded", JSONArray(s.rewardedEpisodes))
            .put("legacyRelics", s.legacyRelics).put("legacyGold", s.legacyGold).toString()
        fun decode(raw: String): UniverseGame.State {
            val o = JSONObject(raw)
            require(o.getInt("version") == 1)
            fun amount(key: String) = o.optDouble(key, 0.0).takeIf { it.isFinite() }?.coerceIn(0.0, UniverseGame.LIMIT) ?: 0.0
            fun longs(key: String, max: Int) = o.optJSONArray(key)?.let { a -> (0 until minOf(max, a.length())).map { a.optLong(it) }.filter { it > 0 } }.orEmpty()
            val levels = o.getJSONObject("levels").let { a -> a.keys().asSequence().filter { UniverseGame.tile(it) != null }
                .associateWith { a.optInt(it).coerceIn(1, UniverseGame.MAX_LEVEL) } } + mapOf("0,0" to o.getJSONObject("levels").optInt("0,0", 1).coerceIn(1, UniverseGame.MAX_LEVEL))
            val research = o.optJSONObject("research")
            return UniverseGame.State(
                wallet = UniverseGame.Wallet(amount("wood"), amount("stone"), amount("gold")), levels = levels,
                workers = o.optInt("workers", 1).coerceIn(1, UniverseGame.MAX_WORKERS),
                research = UniverseGame.Research.entries.associateWith { research?.optInt(it.name)?.coerceIn(0, UniverseGame.MAX_RESEARCH) ?: 0 },
                insight = o.optInt("insight").coerceIn(0, 1_000_000), listeningMs = o.optLong("listening").coerceIn(0, Long.MAX_VALUE / 2),
                listeningRemainderMs = o.optLong("remainder").coerceIn(0, 299_999), boostMs = o.optLong("boost").coerceIn(0, UniverseGame.BOOST_CAP_MS),
                updatedAt = o.optLong("updatedAt").coerceAtLeast(0), lifetimeGold = amount("lifetimeGold"), stars = o.optInt("stars").coerceIn(0, 1_000_000),
                voyages = o.optInt("voyages").coerceIn(0, 1_000_000), theme = UniverseGame.Theme.entries.firstOrNull { it.name == o.optString("theme") } ?: UniverseGame.Theme.FRANCE,
                reviewDay = o.optLong("reviewDay", -1), reviewed = longs("reviewed", 40).toSet(),
                episodeId = o.optLong("episodeId"), episodeMs = o.optLong("episodeMs").coerceIn(0, Long.MAX_VALUE / 2), rewardedEpisodes = longs("rewarded", 200),
                legacyRelics = o.optInt("legacyRelics").coerceIn(0, 50), legacyGold = o.optLong("legacyGold").coerceIn(0, 10_000_000),
            )
        }
    }
}
