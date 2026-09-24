package com.nuvio.app.features.downloads

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class DownloadsNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val downloadId = intent.getStringExtra(extraDownloadId)?.trim().orEmpty()
        if (downloadId.isBlank() && action != actionPauseAll) return

        DownloadsStorage.initialize(context.applicationContext)
        DownloadsPlatformDownloader.initialize(context.applicationContext)
        DownloadsLiveStatusPlatform.initialize(context.applicationContext)
        DownloadsRepository.ensureLoaded()

        when (action) {
            actionPause -> DownloadsRepository.pauseDownload(downloadId)
            actionResume -> DownloadsRepository.resumeDownload(downloadId)
            actionCancel -> DownloadsRepository.cancelDownload(downloadId)
            actionApproveSize -> DownloadsRepository.approveUnexpectedSize(downloadId)
            // The summary notification's one action: a user pause of everything unfinished.
            actionPauseAll -> DownloadsRepository.uiState.value.items
                .filter { it.status == DownloadStatus.Queued || it.status == DownloadStatus.Downloading }
                .forEach { DownloadsRepository.pauseDownload(it.id) }
        }
    }

    companion object {
        const val actionPause = "com.nuvio.app.downloads.action.PAUSE"
        const val actionResume = "com.nuvio.app.downloads.action.RESUME"
        const val actionCancel = "com.nuvio.app.downloads.action.CANCEL"
        const val actionApproveSize = "com.nuvio.app.downloads.action.APPROVE_SIZE"
        const val actionPauseAll = "com.nuvio.app.downloads.action.PAUSE_ALL"
        const val extraDownloadId = "download_id"
    }
}
