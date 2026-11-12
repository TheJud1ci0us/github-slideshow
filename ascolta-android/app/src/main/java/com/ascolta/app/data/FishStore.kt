package com.ascolta.app.data

import android.content.Context
import com.ascolta.app.data.FishGame.State
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Keeps the Aquarium's state (in SharedPreferences) and applies the rules of [FishGame] to it. */
class FishStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("aquarium", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<State> = _state
    private var lastSave = 0L
    private var pendingMs = 0L
    private var pendingEpisode = 0L

    val today: Long get() = LocalDate.now().toEpochDay()

    private fun load(): State {
        val saved = prefs.getString("state", null) ?: return fromDig()
        return runCatching {
            val o = JSONObject(saved)
            val fish = o.getJSONObject("fish")
            val rewarded = o.getJSONArray("rewarded")
            fun <T : Enum<T>> names(key: String, all: List<T>): Set<T> {
                val a = o.optJSONArray(key) ?: return emptySet()
                val wanted = (0 until a.length()).map { a.getString(it) }.toSet()
                return all.filter { it.name in wanted }.toSet()
            }
            val backgrounds = names("backgrounds", FishGame.Background.entries) + FishGame.Background.REEF
            State(
                coinMs = o.getLong("coins"),
                fish = fish.keys().asSequence().filter { FishGame.fish(it) != null }.associateWith { fish.getInt(it) },
                decorations = names("decorations", FishGame.Decoration.entries),
                backgrounds = backgrounds,
                background = FishGame.Background.entries.firstOrNull { it.name == o.optString("background") && it in backgrounds } ?: FishGame.Background.REEF,
                freeCasts = o.optInt("free"), sinceRare = o.optInt("sinceRare"),
                comboMs = o.optLong("combo"), lastListenAt = o.optLong("last"), castProgressMs = o.optLong("castMs"),
                day = o.optLong("day"), dayMs = o.optLong("dayMs"),
                gifted = o.optBoolean("gifted"), unseenMs = o.optLong("unseen"),
                episodeId = o.optLong("episode"), episodeMs = o.optLong("episodeMs"),
                rewarded = (0 until rewarded.length()).map { rewarded.getLong(it) },
                catchDay = o.optLong("catchDay"), catchesToday = o.optInt("catchesToday"),
                caught = o.optJSONArray("caught")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            )
        }.onFailure { AppLog.w("Aquarium", "Saved game unreadable: $it") }.getOrNull() ?: State()
    }

    /** First start after the Dig site: its unspent minutes become coins. */
    private fun fromDig(): State = runCatching {
        val dig = context.getSharedPreferences("dig", Context.MODE_PRIVATE)
        val o = JSONObject(dig.getString("state", null) ?: return@runCatching State())
        dig.edit().clear().apply()
        FishGame.fromDig(o.optLong("wallet"), o.optBoolean("gifted")).also { AppLog.i("Aquarium", "Dig site minutes moved over: ${it.coins} coins") }
    }.getOrDefault(State())

    private fun save(s: State) {
        lastSave = System.currentTimeMillis()
        val o = JSONObject()
            .put("coins", s.coinMs)
            .put("fish", JSONObject().apply { s.fish.forEach { (k, n) -> if (n > 0) put(k, n) } })
            .put("decorations", JSONArray(s.decorations.map { it.name }))
            .put("backgrounds", JSONArray(s.backgrounds.map { it.name })).put("background", s.background.name)
            .put("free", s.freeCasts).put("sinceRare", s.sinceRare)
            .put("combo", s.comboMs).put("last", s.lastListenAt).put("castMs", s.castProgressMs)
            .put("day", s.day).put("dayMs", s.dayMs)
            .put("gifted", s.gifted).put("unseen", s.unseenMs).put("episode", s.episodeId).put("episodeMs", s.episodeMs)
            .put("rewarded", JSONArray(s.rewarded))
            .put("catchDay", s.catchDay).put("catchesToday", s.catchesToday).put("caught", JSONArray(s.caught))
        prefs.edit().putString("state", o.toString()).apply()
    }

    @Synchronized
    private fun <T> change(saveNow: Boolean = true, action: (State) -> Pair<State, T>?): T? {
        val (next, result) = action(_state.value) ?: return null
        _state.value = next
        if (saveNow || System.currentTimeMillis() - lastSave >= 15_000) save(next)
        return result
    }

    /**
     * Called ten times a second while an episode plays: gathered and counted every 5 s (so
     * the screens showing the coins aren't redrawn all the time), saved every 15 s.
     */
    @Synchronized
    fun addListening(ms: Long, foreign: Boolean, episodeId: Long) {
        if (ms <= 0 || !foreign) return
        if (episodeId != pendingEpisode) { count(); pendingEpisode = episodeId }
        pendingMs += ms
        if (pendingMs >= 5_000) count()
    }

    @Synchronized
    private fun count() {
        val ms = pendingMs
        if (ms <= 0) return
        pendingMs = 0
        change(saveNow = false) { s -> FishGame.listen(s, ms, System.currentTimeMillis(), today, true, pendingEpisode) to Unit }
    }

    fun episodeFinished(episodeId: Long, durationMs: Long) {
        if (episodeId in _state.value.rewarded) return
        count()
        change { s -> FishGame.finishEpisode(s, episodeId, durationMs).takeIf { it !== s }?.let { it to Unit } }
    }

    fun flush() = synchronized(this) { count(); save(_state.value) }

    /** Opening the game: the welcome gift (once) and the "+120" badge cleared. */
    fun opened(pastListeningMs: Long) {
        count()
        change { s -> FishGame.gift(s, pastListeningMs).copy(unseenMs = 0) to Unit }
    }

    /** Coins for a caught transcription mistake; false when already rewarded or the daily limit is reached. */
    fun rewardCatch(key: String): Boolean = change { s -> FishGame.rewardCatch(s, key, today)?.let { it to true } } ?: false

    fun cast(bait: FishGame.Bait): FishGame.Catch? = change { s -> FishGame.cast(s, bait, today)?.let { it.state to it } }
    fun merge(fish: FishGame.Fish): Boolean = change { s -> FishGame.merge(s, fish)?.let { it to true } } ?: false
    fun buy(item: FishGame.Decoration): Boolean = change { s -> FishGame.buy(s, item)?.let { it to true } } ?: false
    fun choose(bg: FishGame.Background): Boolean = change { s -> FishGame.choose(s, bg)?.let { it to true } } ?: false
}
