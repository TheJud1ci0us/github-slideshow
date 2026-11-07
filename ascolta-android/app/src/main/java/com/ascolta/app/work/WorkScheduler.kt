package com.ascolta.app.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.ascolta.app.MainActivity
import com.ascolta.app.R
import com.ascolta.app.graph
import com.ascolta.app.whisper.WhisperModel
import java.util.concurrent.TimeUnit

object WorkScheduler {
    const val KEY_EPISODE_ID = "episode_id"
    const val KEY_TRANSCRIBE_AFTER = "transcribe_after"
    const val KEY_MODEL_ID = "model_id"
    const val KEY_PROGRESS = "progress"

    private const val REFRESH_WORK = "refresh-feeds"

    fun modelWorkName(model: WhisperModel) = "model-${model.id}"
    fun transcribeWorkName(episodeId: Long) = "transcribe-$episodeId"
    fun downloadWorkName(episodeId: Long) = "download-$episodeId"

    fun schedulePeriodicRefresh(context: Context) {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(REFRESH_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun download(context: Context, episodeId: Long, transcribeAfter: Boolean, manual: Boolean) {
        val wifiOnly = context.graph.settings.wifiOnly.value && !manual
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(KEY_EPISODE_ID to episodeId, KEY_TRANSCRIBE_AFTER to transcribeAfter))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // A manual request replaces an automatic one that may be waiting for Wi-Fi.
        WorkManager.getInstance(context).enqueueUniqueWork(
            downloadWorkName(episodeId),
            if (manual) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Each episode has its own job; TranscribeWorker makes them take turns, since
     * speech recognition uses most of the CPU and a lot of memory.
     */
    fun transcribe(context: Context, episodeId: Long, manual: Boolean, replace: Boolean = false) {
        val chargingOnly = context.graph.settings.chargingOnly.value && !manual
        val request = OneTimeWorkRequestBuilder<TranscribeWorker>()
            .setInputData(workDataOf(KEY_EPISODE_ID to episodeId))
            .setConstraints(Constraints.Builder().setRequiresCharging(chargingOnly).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            transcribeWorkName(episodeId),
            if (replace || manual) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelTranscription(context: Context, episodeId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(transcribeWorkName(episodeId))
    }

    /** True if a job with this unique name is waiting or running. */
    suspend fun isActive(context: Context, uniqueName: String): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            WorkManager.getInstance(context).getWorkInfosForUniqueWork(uniqueName).get()
                .any { !it.state.isFinished }
        }

    fun downloadModel(context: Context, model: WhisperModel) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(KEY_MODEL_ID to model.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(modelWorkName(model), ExistingWorkPolicy.KEEP, request)
    }

    fun cancelModelDownload(context: Context, model: WhisperModel) {
        WorkManager.getInstance(context).cancelUniqueWork(modelWorkName(model))
    }

    fun refreshNow(context: Context) {
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }
}

object Notifications {
    const val CHANNEL_WORK = "background-work"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_WORK, "Downloads & transcription", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress of episode downloads and on-device transcription"
            },
        )
    }

    fun foregroundInfo(
        context: Context,
        id: Int,
        title: String,
        text: String,
        progress: Float?,
        mediaProcessing: Boolean,
    ): ForegroundInfo {
        val intent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(intent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .apply {
                if (progress == null || progress < 0) setProgress(0, 0, true)
                else setProgress(1000, (progress * 1000).toInt(), false)
            }
            .build()
        return when {
            Build.VERSION.SDK_INT >= 35 && mediaProcessing ->
                ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= 29 ->
                ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> ForegroundInfo(id, notification)
        }
    }
}
