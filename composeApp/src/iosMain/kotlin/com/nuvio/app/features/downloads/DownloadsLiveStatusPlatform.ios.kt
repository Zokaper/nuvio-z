package com.nuvio.app.features.downloads

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSUserDefaults

internal actual object DownloadsLiveStatusPlatform {
    private const val notificationName = "NuvioDownloadsLiveStatusUpdated"
    private const val userDefaultsPayloadKey = "nuvio.downloads.live_status.payload"

    private val json = Json {
        encodeDefaults = true
    }

    private var lastPayload: String? = null
    private var currentItems: List<DownloadItem> = emptyList()
    private var currentBatches: List<DownloadBatch> = emptyList()

    actual fun onItemsChanged(items: List<DownloadItem>) {
        currentItems = items
        updatePayload()
    }

    actual fun onBatchesChanged(batches: List<DownloadBatch>) {
        currentBatches = batches
        updatePayload()
    }

    private fun updatePayload() {
        val eligibleItems = currentItems.filter { it.status != DownloadStatus.Completed }
        val candidatesById = eligibleItems.associateBy { it.id }
        val activeBatch = currentBatches.firstOrNull { it.isPreparing }
        val presentation = DownloadsLiveStatusPolicy.select(
            items = eligibleItems.map { it.liveActivityCandidate() },
            resolvingBatch = activeBatch?.let {
                DownloadsLiveStatusPolicy.Candidate(it.id, DownloadsLiveStatusPolicy.State.FINDING_SOURCES)
            },
        )
        val primaryItem = presentation?.candidate?.id?.let(candidatesById::get)

        val payload = when {
            primaryItem != null -> {
                json.encodeToString(
                    DownloadsLiveStatusPayload(
                        id = primaryItem.id,
                        title = primaryItem.title,
                        subtitle = primaryItem.displaySubtitle,
                        status = primaryItem.liveActivityStatus(),
                        downloadedBytes = primaryItem.downloadedBytes,
                        totalBytes = primaryItem.totalBytes,
                        progressPercent = presentation.progressPercent ?: -1,
                    ),
                )
            }
            activeBatch != null -> {
                json.encodeToString(
                    DownloadsLiveStatusPayload(
                        id = activeBatch.id,
                        title = activeBatch.title,
                        subtitle = "Finding sources",
                        status = "FINDING_SOURCES",
                        downloadedBytes = 0L,
                        totalBytes = null,
                        progressPercent = -1,
                    ),
                )
            }
            else -> null
        }

        if (payload == lastPayload) return
        lastPayload = payload

        val defaults = NSUserDefaults.standardUserDefaults
        if (payload == null) {
            defaults.removeObjectForKey(userDefaultsPayloadKey)
        } else {
            defaults.setObject(payload, forKey = userDefaultsPayloadKey)
        }

        NSNotificationCenter.defaultCenter.postNotificationName(notificationName, null)
    }

    private fun DownloadItem.liveActivityCandidate() = DownloadsLiveStatusPolicy.Candidate(
        id = id,
        state = liveActivityState(),
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        updatedAtEpochMs = updatedAtEpochMs,
    )

    private fun DownloadItem.liveActivityStatus(): String = liveActivityState().name

    private fun DownloadItem.liveActivityState(): DownloadsLiveStatusPolicy.State = when (status) {
        DownloadStatus.Downloading -> when {
            activity == DownloadActivity.RESOLVING_SOURCE -> DownloadsLiveStatusPolicy.State.PREPARING
            downloadedBytes <= 0L -> DownloadsLiveStatusPolicy.State.STARTING
            else -> DownloadsLiveStatusPolicy.State.DOWNLOADING
        }
        DownloadStatus.Queued -> when {
            activity == DownloadActivity.RETRY_BACKOFF || isWaitingForRetry(DownloadsClock.nowEpochMs()) -> DownloadsLiveStatusPolicy.State.RETRYING
            activity == DownloadActivity.WAITING_FOR_CONNECTION ||
                activity == DownloadActivity.WAITING_FOR_PROVIDER ||
                activity == DownloadActivity.QUEUED_FOR_SLOT -> DownloadsLiveStatusPolicy.State.WAITING
            else -> DownloadsLiveStatusPolicy.State.STARTING
        }
        DownloadStatus.Paused -> DownloadsLiveStatusPolicy.State.PAUSED
        DownloadStatus.Failed -> DownloadsLiveStatusPolicy.State.FAILED
        DownloadStatus.Completed -> DownloadsLiveStatusPolicy.State.COMPLETED
    }
}

@Serializable
private data class DownloadsLiveStatusPayload(
    val id: String,
    val title: String,
    val subtitle: String,
    val status: String,
    val downloadedBytes: Long,
    val totalBytes: Long? = null,
    val progressPercent: Int,
)
