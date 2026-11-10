package com.ascolta.app

import android.app.Application
import android.content.Context
import com.ascolta.app.data.AppDatabase
import com.ascolta.app.data.AppLog
import kotlinx.coroutines.launch
import com.ascolta.app.data.PodcastRepository
import com.ascolta.app.data.Settings
import com.ascolta.app.data.TranscriptStore
import com.ascolta.app.net.Http
import com.ascolta.app.net.PodcastDirectory
import com.ascolta.app.player.PlayerConnection
import com.ascolta.app.translate.TranslationService
import com.ascolta.app.whisper.ModelManager
import com.ascolta.app.work.Notifications
import com.ascolta.app.work.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

class AscoltaApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppVisibility.register(this)
        // Write crashes to the in-app log (Settings → Diagnostics → View log) before the app closes.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { AppLog.w("Crash", "on ${thread.name}: ${error.stackTraceToString().take(4000)}") }
            // Reported (as the error type and place only) on the next launch, if analytics is on.
            runCatching {
                getSharedPreferences("analytics", MODE_PRIVATE).edit()
                    .putString("crash", "${error.javaClass.simpleName} @ ${error.stackTrace.firstOrNull { it.className.startsWith("com.ascolta") }?.let { "${it.className.substringAfterLast('.')}.${it.methodName}" } ?: "?"}")
                    .commit()
            }
            previous?.uncaughtException(thread, error)
        }
        graph = AppGraph(this)
        com.ascolta.app.data.Analytics.init(this, graph.settings, graph.http)
        com.ascolta.app.data.Analytics.track("TelemetryDeck.Session.started")
        getSharedPreferences("analytics", MODE_PRIVATE).getString("crash", null)?.let { crash ->
            com.ascolta.app.data.Analytics.track(
                "TelemetryDeck.Error.occurred",
                mapOf("TelemetryDeck.Error.id" to crash, "TelemetryDeck.Error.category" to "crash"),
            )
            getSharedPreferences("analytics", MODE_PRIVATE).edit().remove("crash").apply()
        }
        graph.scope.launch {
            runCatching { logLastExit() }
            // Load the speech engine's CPU code early (safely chosen on x86_64 emulators).
            runCatching { com.ascolta.app.whisper.WhisperLib.ensureBackends(applicationInfo.nativeLibraryDir) }
                .onFailure { AppLog.w("App", "Speech engine libraries", it) }
        }
        // Statistics are sent when the app goes to the background (and every minute).
        graph.scope.launch { AppVisibility.visible.collect { if (!it) com.ascolta.app.data.Analytics.flush() } }
        Notifications.createChannels(this)
        WorkScheduler.schedulePeriodicRefresh(this)
        graph.scope.launch {
            runCatching { graph.repo.recoverStuckWork() }.onFailure { AppLog.w("App", "Recovery failed", it) }
            runCatching { graph.models.removeRetiredModels() }
            runCatching { graph.repo.assignLibraryLanguages() }.onFailure { AppLog.w("App", "Library languages", it) }
            runCatching { graph.repo.deleteOldDownloads() }.onFailure { AppLog.w("App", "Old downloads", it) }
            // Small cover copies for podcasts followed before (saves data on every thumbnail).
            runCatching { graph.repo.backfillArtwork(graph.directory) }.onFailure { AppLog.w("App", "Artwork", it) }
        }
    }
}

/**
 * Writes why the app's previous run ended to the log (Settings → Diagnostics), when it wasn't
 * a normal exit: a native crash (e.g. an unsupported CPU instruction), running out of memory,
 * being stopped while not responding… Java crashes are already logged when they happen.
 */
private fun AscoltaApp.logLastExit() {
    if (android.os.Build.VERSION.SDK_INT < 30) return
    val am = getSystemService(android.app.ActivityManager::class.java) ?: return
    val prefs = getSharedPreferences("diagnostics", android.content.Context.MODE_PRIVATE)
    val seen = prefs.getLong("last_exit_seen", 0)
    val exits = am.getHistoricalProcessExitReasons(packageName, 0, 5).filter { it.timestamp > seen }
    exits.firstOrNull()?.let { prefs.edit().putLong("last_exit_seen", it.timestamp).apply() }
    val reasons = mapOf(
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE to "native crash",
        android.app.ApplicationExitInfo.REASON_CRASH to "crash",
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY to "killed: phone low on memory",
        android.app.ApplicationExitInfo.REASON_ANR to "not responding",
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE to "killed: too much CPU/battery",
        android.app.ApplicationExitInfo.REASON_SIGNALED to "killed by signal",
        android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE to "failed to start",
    )
    for (e in exits.reversed()) {
        val reason = reasons[e.reason] ?: continue
        AppLog.w(
            "LastExit",
            "${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(e.timestamp))}: $reason" +
                " (status ${e.status}, ${e.description ?: "no details"}, memory ${e.pss / 1024} MB)",
        )
        com.ascolta.app.data.Analytics.track(
            "TelemetryDeck.Error.occurred",
            mapOf("TelemetryDeck.Error.id" to "exit:$reason status ${e.status}", "TelemetryDeck.Error.category" to "exit"),
        )
    }
}

/** Simple manual dependency container. */
class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = Settings(app)
    val progress = com.ascolta.app.data.ProgressStore(app)
    val db = AppDatabase.build(app)
    val http = Http.client()
    val transcripts = TranscriptStore(File(app.filesDir, "transcripts"))
    val models = ModelManager(app, http)
    val translation = TranslationService(com.ascolta.app.translate.OpusModels(app, http))
    val directory = PodcastDirectory(http)
    val dictionary = com.ascolta.app.data.Dictionary(app)
    val feedLanguage = com.ascolta.app.net.FeedLanguage(http)
    val repo = PodcastRepository(app, db, http, settings, transcripts, translation)
    val player = PlayerConnection(app, db, settings, progress)

    /** Text shared to the app (e.g. a Spotify/Apple link), waiting to be handled by the UI. */
    val pendingSharedText = MutableStateFlow<String?>(null)
}

val Context.graph: AppGraph get() = (applicationContext as AscoltaApp).graph
