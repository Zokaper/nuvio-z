package com.nuvio.app.features.downloads

import android.app.Notification
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
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
            // A user-initiated job is refused unless the app is visible when it is scheduled, and
            // the refusal is either a RESULT_FAILURE or an exception depending on the reason. It
            // used to be neither checked nor logged, so "there is never a notification" could not
            // be told apart from "the job ran and its notification was blocked".
            val result = runCatching {
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
            }
            val accepted = result.getOrNull() == JobScheduler.RESULT_SUCCESS
            DownloadDiagnostics.note(
                "host_schedule",
                "kind=uij accepted=$accepted metered=$allowMeteredNetwork " +
                    "foreground=${DownloadsAndroidLifecycle.isForeground()} " +
                    "error=${result.exceptionOrNull()?.let { it::class.simpleName }}",
            )
            if (accepted) return
        }
        scheduleForegroundWorker(appContext, allowMeteredNetwork)
    }

    private fun scheduleForegroundWorker(appContext: Context, allowMeteredNetwork: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (allowMeteredNetwork) NetworkType.CONNECTED else NetworkType.UNMETERED,
            )
            .build()
        val request = OneTimeWorkRequestBuilder<DownloadsForegroundWorker>()
            .setConstraints(constraints)
            .build()
        val result = runCatching {
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                uniqueWorkName,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
        DownloadDiagnostics.note(
            "host_schedule",
            "kind=worker metered=$allowMeteredNetwork error=${result.exceptionOrNull()?.let { it::class.simpleName }}",
        )
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
        // Queued items are still work in hand: finishing the job while any remain would
        // tear down the foreground host with downloads left waiting for a slot.
        state.items.none {
            it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Queued
        }
    }
}

/**
 * The notification a background host runs under: the downloads summary itself, under the same id,
 * so the system-required host notification and the summary are one notification, not two.
 */
internal fun downloadQueueNotification(context: Context): Notification {
    DownloadsLiveStatusPlatform.initialize(context)
    return DownloadsLiveStatusPlatform.hostNotification(context)
}

internal class DownloadsForegroundWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        // API 29+ needs the type in ForegroundInfo, and a target of 34+ refuses a foreground
        // service started without one; the manifest declares `dataSync` on WorkManager's service
        // to match. Before this the worker was only ever reached below API 34, where it did not
        // matter - it is now also the fallback when a user-initiated job is refused.
        val info = if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(
                DownloadsLiveStatusPlatform.SUMMARY_NOTIFICATION_ID,
                downloadQueueNotification(applicationContext),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(
                DownloadsLiveStatusPlatform.SUMMARY_NOTIFICATION_ID,
                downloadQueueNotification(applicationContext),
            )
        }
        val started = runCatching { setForeground(info) }
        DownloadDiagnostics.note(
            "host_start",
            "kind=worker foreground=${started.isSuccess} error=${started.exceptionOrNull()?.let { it::class.simpleName }}",
        )
        DownloadsBackgroundScheduler.isHostingQueue = true
        return try {
            initializeDownloadsForBackground(applicationContext)
            DownloadsRepository.resumeSystemPausedDownloads()
            awaitDownloadQueueIdle()
            DownloadDiagnostics.note("host_idle", "kind=worker")
            Result.success()
        } catch (_: CancellationException) {
            DownloadDiagnostics.note("host_stop", "kind=worker")
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
                DownloadsLiveStatusPlatform.SUMMARY_NOTIFICATION_ID,
                downloadQueueNotification(this),
                // Removed with the job: the summary has nothing to say once the queue is idle.
                JOB_END_NOTIFICATION_POLICY_REMOVE,
            )
        }
        DownloadsBackgroundScheduler.isHostingQueue = true
        initializeDownloadsForBackground(this)
        DownloadDiagnostics.note(
            "host_start",
            "kind=uij notifications=${DownloadsAndroidLifecycle.notificationsAllowed(this)}",
        )
        // Items a previous host left System-paused come back now that a host exists again.
        // `resumeSystemPausedDownloads` had no production caller before Phase 9.
        DownloadsRepository.resumeSystemPausedDownloads()
        activeJob = scope.launch {
            awaitDownloadQueueIdle()
            DownloadDiagnostics.note("host_idle", "kind=uij")
            DownloadsBackgroundScheduler.isHostingQueue = false
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        DownloadDiagnostics.note(
            "host_stop",
            "kind=uij reason=${if (Build.VERSION.SDK_INT >= 31) params.stopReason else -1}",
        )
        activeJob?.cancel()
        activeJob = null
        DownloadsBackgroundScheduler.isHostingQueue = false
        DownloadsRepository.pauseActiveDownloads()
        return true
    }
}
