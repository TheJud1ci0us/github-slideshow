package com.ascolta.app.data

import android.content.Context
import com.ascolta.app.data.DigGame.CrateType
import com.ascolta.app.data.DigGame.State
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Keeps the Dig site's state (in SharedPreferences) and applies the rules of [DigGame] to it. */
class DigStore(context: Context) {
    private val prefs = context.getSharedPreferences("dig", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<State> = _state
    private var lastSave = 0L

    private fun load(): State = runCatching {
        val o = JSONObject(prefs.getString("state", null) ?: return@runCatching null)
        val dug = o.getJSONObject("dug")
        val finds = o.getJSONObject("finds")
        val crates = o.getJSONObject("crates")
        val rewarded = o.getJSONArray("rewarded")
        State(
            seed = o.getLong("seed"), walletMs = o.getLong("wallet"),
            tool = DigGame.Tool.entries.getOrElse(o.getInt("tool")) { DigGame.Tool.WOOD },
            dug = dug.keys().asSequence().associate { it.toInt() to dug.getInt(it) },
            finds = finds.keys().asSequence().associateWith { finds.getInt(it) },
            crates = CrateType.entries.associateWith { crates.optInt(it.name, 0) },
            comboMs = o.optLong("combo"), lastListenAt = o.optLong("last"), crateProgressMs = o.optLong("crateMs"),
            gifted = o.optBoolean("gifted"), unseenMs = o.optLong("unseen"),
            episodeId = o.optLong("episode"), episodeMs = o.optLong("episodeMs"),
            rewarded = (0 until rewarded.length()).map { rewarded.getLong(it) },
        )
    }.onFailure { AppLog.w("Dig", "Saved game unreadable: $it") }.getOrNull() ?: State(seed = System.nanoTime())

    private fun save(s: State) {
        lastSave = System.currentTimeMillis()
        val o = JSONObject()
            .put("seed", s.seed).put("wallet", s.walletMs).put("tool", s.tool.ordinal)
            .put("dug", JSONObject().apply { s.dug.forEach { (r, bits) -> put(r.toString(), bits) } })
            .put("finds", JSONObject().apply { s.finds.forEach { (id, n) -> put(id, n) } })
            .put("crates", JSONObject().apply { s.crates.forEach { (t, n) -> put(t.name, n) } })
            .put("combo", s.comboMs).put("last", s.lastListenAt).put("crateMs", s.crateProgressMs)
            .put("gifted", s.gifted).put("unseen", s.unseenMs).put("episode", s.episodeId).put("episodeMs", s.episodeMs)
            .put("rewarded", JSONArray(s.rewarded))
        prefs.edit().putString("state", o.toString()).apply()
    }

    @Synchronized
    private fun <T> change(saveNow: Boolean = true, action: (State) -> Pair<State, T>?): T? {
        val (next, result) = action(_state.value) ?: return null
        _state.value = next
        if (saveNow || System.currentTimeMillis() - lastSave >= 15_000) save(next)
        return result
    }

    private var pendingMs = 0L
    private var pendingEpisode = 0L

    /**
     * Called ten times a second while an episode plays: gathered and counted every 5 s (so
     * the screens showing the wallet aren't redrawn all the time), saved every 15 s.
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
        change(saveNow = false) { s ->
            val next = DigGame.listen(s, ms, System.currentTimeMillis(), true, pendingEpisode)
            if (next.crates != s.crates) AppLog.i("Dig", "Crate earned by listening")
            next to Unit
        }
    }

    fun episodeFinished(episodeId: Long, durationMs: Long) {
        if (episodeId in _state.value.rewarded) return
        count()
        change { s -> DigGame.finishEpisode(s, episodeId, durationMs).takeIf { it !== s }?.let { it to Unit } }
    }

    fun flush() = synchronized(this) { count(); save(_state.value) }

    /** Opening the game: the welcome gift (once) and the "+12" badge cleared. */
    fun opened(pastListeningMs: Long) {
        count()
        change { s -> DigGame.gift(s, pastListeningMs).copy(unseenMs = 0) to Unit }
    }

    fun dig(row: Int, col: Int): DigGame.Dug? = change { s -> DigGame.dig(s, row, col)?.let { it.state to it } }
    fun buyTool(): Boolean = change { s -> DigGame.buyTool(s)?.let { it to true } } ?: false
    fun buyCrate(type: CrateType): Boolean = change { s -> DigGame.buyCrate(s, type)?.let { it to true } } ?: false
    fun openCrate(type: CrateType): DigGame.Opened? = change { s -> DigGame.openCrate(s, type)?.let { it.state to it } }
}
