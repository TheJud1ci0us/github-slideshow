package com.ascolta.app.work

import android.content.Context

/**
 * Notices when the speech engine crashed the app (a native crash can't be caught) so a broken
 * model doesn't make WorkManager restart the same crashing job forever.
 *
 * A mark "model|episode|crashes" is written (synchronously) before the engine is loaded and
 * removed once the first block is transcribed. If a job finds its own mark still there, the
 * previous attempt died on the engine: the first time the model is downloaded again, the
 * second time the episode is marked as failed.
 */
object CrashGuard {
    enum class Action { CONTINUE, REDOWNLOAD, GIVE_UP }

    private const val PREFS = "crash_guard"
    private const val KEY = "mark"

    /**
     * What to do given the mark left behind ([mark], or null), for this model and episode.
     * [crashed]: the app's last exit was a crash (not the user or Android closing it).
     */
    fun decide(mark: String?, model: String, episode: Long, crashed: Boolean): Action {
        if (!crashed) return Action.CONTINUE
        val parts = mark?.split('|') ?: return Action.CONTINUE
        if (parts.size != 3 || parts[0] != model || parts[1] != episode.toString()) return Action.CONTINUE
        return if ((parts[2].toIntOrNull() ?: 0) >= 1) Action.GIVE_UP else Action.REDOWNLOAD
    }

    /** Crashes already counted for this model and episode (0 if the mark is for something else). */
    fun crashes(mark: String?, model: String, episode: Long): Int {
        val parts = mark?.split('|') ?: return 0
        if (parts.size != 3 || parts[0] != model || parts[1] != episode.toString()) return 0
        return parts[2].toIntOrNull() ?: 0
    }

    /** True if Android reports the app's last exit as a crash (unknown before Android 11: assumed yes). */
    fun lastExitWasCrash(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 30) return true
        val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return true
        // The current process isn't listed; the most recent entry is the previous one.
        val last = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 1) }.getOrNull()?.firstOrNull() ?: return false
        return last.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ||
            last.reason == android.app.ApplicationExitInfo.REASON_CRASH
    }

    fun read(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    /** Written with commit(): it must be on disk before native code can bring the app down. */
    fun arm(context: Context, model: String, episode: Long, crashes: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, "$model|$episode|$crashes").commit()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).commit()
    }
}
