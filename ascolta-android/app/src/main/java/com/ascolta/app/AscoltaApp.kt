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
import com.ascolta.app.whisper.WhisperModelManager
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
        graph = AppGraph(this)
        Notifications.createChannels(this)
        WorkScheduler.schedulePeriodicRefresh(this)
        graph.scope.launch {
            runCatching { graph.repo.recoverStuckWork() }.onFailure { AppLog.w("App", "Recovery failed", it) }
        }
    }
}

/** Simple manual dependency container. */
class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = Settings(app)
    val db = AppDatabase.build(app)
    val http = Http.client()
    val transcripts = TranscriptStore(File(app.filesDir, "transcripts"))
    val models = WhisperModelManager(app, http)
    val translation = TranslationService()
    val directory = PodcastDirectory(http)
    val repo = PodcastRepository(app, db, http, settings, transcripts, translation)
    val player = PlayerConnection(app, db, settings)

    /** Text shared to the app (e.g. a Spotify/Apple link), waiting to be handled by the UI. */
    val pendingSharedText = MutableStateFlow<String?>(null)
}

val Context.graph: AppGraph get() = (applicationContext as AscoltaApp).graph
