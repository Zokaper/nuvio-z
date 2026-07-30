package com.nuvio.app.features.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal object DownloadsBackgroundScheduler {
    private const val uniqueWorkName = "nuvio-z-download-queue"
    private const val jobId = 0x4e5a44
    @Volatile
    var isHostingQueue: Boolean = false

    fun schedule(context: Context, allowMeteredNetwork: Boolean = false) {
        if (isHostingQueue) return
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= 34) {
            val info = JobInfo.Builder(
                jobId,
                ComponentName(appContext, DownloadsUserInitiatedJobService::class.java),
            )
                .setUserInitiated(true)
                .setRequiredNetworkType(
                    if (allowMeteredNetwork) {
                        JobInfo.NETWORK_TYPE_ANY
                    } else {
                        JobInfo.NETWORK_TYPE_UNMETERED
                    },
                )
                .setPersisted(true)
                .build()
            appContext.getSystemService(JobScheduler::class.java).schedule(info)
        } else {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (allowMeteredNetwork) NetworkType.CONNECTED else NetworkType.UNMETERED,
                )
                .build()
            val request = OneTimeWorkRequestBuilder<DownloadsForegroundWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                uniqueWorkName,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}

internal fun initializeDownloadsForBackground(context: Context) {
    val appContext = context.applicationContext
    DownloadsStorage.initialize(appContext)
    DownloadsPlatformDownloader.initialize(appContext)
    DownloadsLiveStatusPlatform.initialize(appContext)
    DownloadsRepository.ensureLoaded()
}

internal suspend fun awaitDownloadQueueIdle() {
    DownloadsRepository.uiState.first { state ->
        state.items.none { it.status == DownloadStatus.Downloading }
    }
}

internal fun downloadQueueNotification(context: Context): Notification {
    val channelId = "downloads_background"
    val manager = context.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= 26) {
        manager.createNotificationChannel(
            NotificationChannel(
                channelId,
                "Nuvio Z downloads",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }
    return NotificationCompat.Builder(context, channelId)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("Nuvio Z")
        .setContentText("Downloading for offline playback")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()
}

internal class DownloadsForegroundWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        setForeground(ForegroundInfo(0x4e5a45, downloadQueueNotification(applicationContext)))
        DownloadsBackgroundScheduler.isHostingQueue = true
        return try {
            initializeDownloadsForBackground(applicationContext)
            awaitDownloadQueueIdle()
            Result.success()
        } catch (_: CancellationException) {
            Result.retry()
        } finally {
            DownloadsBackgroundScheduler.isHostingQueue = false
        }
    }
}

class DownloadsUserInitiatedJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeJob: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            setNotification(
                params,
                0x4e5a46,
                downloadQueueNotification(this),
                JOB_END_NOTIFICATION_POLICY_DETACH,
            )
        }
        DownloadsBackgroundScheduler.isHostingQueue = true
        initializeDownloadsForBackground(this)
        activeJob = scope.launch {
            awaitDownloadQueueIdle()
            DownloadsBackgroundScheduler.isHostingQueue = false
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        activeJob?.cancel()
        activeJob = null
        DownloadsBackgroundScheduler.isHostingQueue = false
        DownloadsRepository.pauseActiveDownloads()
        return true
    }
}
