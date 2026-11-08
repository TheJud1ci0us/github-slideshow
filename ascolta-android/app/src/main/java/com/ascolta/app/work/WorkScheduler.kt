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
import com.ascolta.app.whisper.SpeechModel
import java.util.concurrent.TimeUnit

object WorkScheduler {
    const val KEY_EPISODE_ID = "episode_id"
    const val KEY_TRANSCRIBE_AFTER = "transcribe_after"
    const val KEY_MODEL_ID = "model_id"
    const val KEY_PROGRESS = "progress"

    private const val REFRESH_WORK = "refresh-feeds"

    fun modelWorkName(model: SpeechModel) = "model-${model.id}"
    fun transcribeWorkName(episodeId: Long) = "transcribe-$episodeId"
    fun downloadWorkName(episodeId: Long) = "download-$episodeId"

    /** Network allowed by the network mode: Wi-Fi only (unmetered), or any connection. */
    fun allowedNetwork(context: Context): NetworkType =
        if (context.graph.settings.wifiOnly.value) NetworkType.UNMETERED else NetworkType.CONNECTED

    /** True if the network mode forbids using the current connection (mobile data in Wi-Fi mode). */
    fun blockedByNetworkMode(context: Context): Boolean {
        if (!context.graph.settings.wifiOnly.value) return false
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        return cm.isActiveNetworkMetered
    }

    /** Feed checks every N hours (Settings → refresh policy); none when set to "only by hand". */
    fun schedulePeriodicRefresh(context: Context) {
        val hours = context.graph.settings.refreshHours.value
        if (hours <= 0) {
            WorkManager.getInstance(context).cancelUniqueWork(REFRESH_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(hours.toLong(), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(allowedNetwork(context)).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(REFRESH_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun download(context: Context, episodeId: Long, transcribeAfter: Boolean, manual: Boolean) {
        // The network mode applies to every download, including ones started by hand
        // (in Wi-Fi mode they wait for Wi-Fi).
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(KEY_EPISODE_ID to episodeId, KEY_TRANSCRIBE_AFTER to transcribeAfter))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(allowedNetwork(context))
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

    fun cancelDownload(context: Context, episodeId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(downloadWorkName(episodeId))
    }

    /** True if a job with this unique name is waiting or running. */
    suspend fun isActive(context: Context, uniqueName: String): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            WorkManager.getInstance(context).getWorkInfosForUniqueWork(uniqueName).get()
                .any { !it.state.isFinished }
        }

    fun downloadModel(context: Context, model: SpeechModel) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(KEY_MODEL_ID to model.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(allowedNetwork(context)).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(modelWorkName(model), ExistingWorkPolicy.KEEP, request)
    }

    fun cancelModelDownload(context: Context, model: SpeechModel) {
        WorkManager.getInstance(context).cancelUniqueWork(modelWorkName(model))
    }

    fun refreshNow(context: Context) {
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(allowedNetwork(context)).build())
                .build(),
        )
    }
}

object Notifications {
    const val CHANNEL_WORK = "background-work"
    const val CHANNEL_EPISODES = "new-episodes"

    /** "New episode of <favourite podcast>", opening the app. Skipped without permission. */
    fun newEpisodes(context: Context, podcast: com.ascolta.app.data.PodcastEntity, episodes: List<com.ascolta.app.data.EpisodeEntity>) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val intent = PendingIntent.getActivity(
            context, podcast.id.toInt(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val first = episodes.first()
        val notification = NotificationCompat.Builder(context, CHANNEL_EPISODES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                if (episodes.size == 1) context.getString(R.string.notif_new_episode, podcast.title)
                else context.resources.getQuantityString(R.plurals.notif_new_episodes, episodes.size, episodes.size, podcast.title),
            )
            .setContentText(first.title)
            .setStyle(NotificationCompat.InboxStyle().also { style -> episodes.take(5).forEach { style.addLine(it.title) } })
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(50_000 + podcast.id.toInt(), notification)
    }

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_WORK, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.notif_channel_desc)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_EPISODES, context.getString(R.string.notif_channel_episodes), NotificationManager.IMPORTANCE_DEFAULT),
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
