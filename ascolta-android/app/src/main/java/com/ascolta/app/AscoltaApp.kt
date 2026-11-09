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
        // Statistics are sent when the app goes to the background (and every minute).
        graph.scope.launch { AppVisibility.visible.collect { if (!it) com.ascolta.app.data.Analytics.flush() } }
        Notifications.createChannels(this)
        WorkScheduler.schedulePeriodicRefresh(this)
        graph.scope.launch {
            runCatching { graph.repo.recoverStuckWork() }.onFailure { AppLog.w("App", "Recovery failed", it) }
            runCatching { graph.models.removeRetiredModels() }
            runCatching { graph.repo.assignLibraryLanguages() }.onFailure { AppLog.w("App", "Library languages", it) }
            runCatching { graph.repo.deleteOldDownloads() }.onFailure { AppLog.w("App", "Old downloads", it) }
        }
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
    val translation = TranslationService()
    val directory = PodcastDirectory(http)
    val repo = PodcastRepository(app, db, http, settings, transcripts, translation)
    val player = PlayerConnection(app, db, settings, progress)

    /** Text shared to the app (e.g. a Spotify/Apple link), waiting to be handled by the UI. */
    val pendingSharedText = MutableStateFlow<String?>(null)
}

val Context.graph: AppGraph get() = (applicationContext as AscoltaApp).graph
