package com.ascolta.app.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ascolta.app.data.AppLog
import com.ascolta.app.data.DownloadState
import com.ascolta.app.data.TranscriptState
import com.ascolta.app.graph
import com.ascolta.app.net.downloadTo
import com.ascolta.app.whisper.SpeechModel
import kotlinx.coroutines.CancellationException
import java.io.File

private const val TAG = "AscoltaWork"

/** Starting a foreground service from the background can be refused; the work then just runs normally. */
internal suspend fun CoroutineWorker.trySetForeground(info: ForegroundInfo) {
    try {
        setForeground(info)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.w(TAG, "Could not start foreground: ${e.message}")
    }
}

/** Checks all subscribed feeds for new episodes. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val added = applicationContext.graph.repo.refreshAll()
        Log.i(TAG, "Feed refresh found $added new episodes")
        return Result.success()
    }
}

/** Downloads one episode's audio, then queues transcription. */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.graph
        val dao = graph.db.episodeDao()
        val id = inputData.getLong(WorkScheduler.KEY_EPISODE_ID, -1)
        val episode = dao.get(id) ?: return Result.success()
        val url = episode.audioUrl ?: return Result.success()
        val notificationId = (id % 100_000).toInt() + 1000

        val ext = url.substringBefore('?').substringAfterLast('.', "mp3").takeIf { it.length in 2..4 } ?: "mp3"
        val dest = File(applicationContext.filesDir, "episodes/$id.$ext")
        trySetForeground(Notifications.foregroundInfo(applicationContext, notificationId, applicationContext.getString(com.ascolta.app.R.string.status_downloading), episode.title, null, false))
        dao.setDownload(id, DownloadState.DOWNLOADING, 0f)
        AppLog.i(TAG, "Downloading #$id from $url")
        return try {
            graph.http.downloadTo(url, dest) { fraction ->
                dao.setDownload(id, DownloadState.DOWNLOADING, fraction.coerceAtLeast(0f))
                setProgress(workDataOf(WorkScheduler.KEY_PROGRESS to fraction))
            }
            dao.setLocalPath(id, dest.path, DownloadState.DONE)
            AppLog.i(TAG, "Downloaded #$id (${dest.length() / 1_000_000} MB)")
            if (dao.get(id)?.transcriptState == TranscriptState.QUEUED) {
                // A transcription was waiting for this download.
                WorkScheduler.transcribe(applicationContext, id, manual = true)
            } else if (inputData.getBoolean(WorkScheduler.KEY_TRANSCRIBE_AFTER, true)) {
                graph.repo.transcribe(id, manual = true)
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Download #$id failed (attempt $runAttemptCount)", e)
            if (runAttemptCount < 3) {
                dao.setDownload(id, DownloadState.QUEUED, 0f, "Retrying: ${e.message}")
                Result.retry()
            } else {
                dao.setDownload(id, DownloadState.FAILED, 0f, e.message ?: "Download failed")
                Result.success()
            }
        }
    }
}

/** Downloads a speech model (from Settings), plus the small voice-activity model. */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val model = SpeechModel.byId(inputData.getString(WorkScheduler.KEY_MODEL_ID) ?: return Result.failure())
        val graph = applicationContext.graph
        if (graph.models.isDownloaded(model) && graph.models.isVadDownloaded()) return Result.success()
        trySetForeground(
            Notifications.foregroundInfo(
                applicationContext, 900,
                applicationContext.getString(com.ascolta.app.R.string.status_downloading_model, model.label),
                model.label, null, false,
            ),
        )
        return try {
            graph.models.downloadVad()
            graph.models.download(model) { written, total ->
                setProgress(workDataOf(WorkScheduler.KEY_PROGRESS to if (total > 0) written.toFloat() / total else -1f))
            }
            AppLog.i(TAG, "Model ${model.id} downloaded from Settings")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Model ${model.id} download failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure(workDataOf("error" to e.message))
        }
    }
}
