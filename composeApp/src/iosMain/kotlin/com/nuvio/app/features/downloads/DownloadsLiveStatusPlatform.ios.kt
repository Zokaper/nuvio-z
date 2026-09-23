package com.nuvio.app.features.downloads

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSUserDefaults

internal actual object DownloadsLiveStatusPlatform {
    const val NOTIFICATION_NAME = "NuvioDownloadsLiveStatusUpdated"
    const val USER_DEFAULTS_PAYLOAD_KEY = "nuvio.downloads.live_status.payload"

    private val json = Json {
        encodeDefaults = true
    }

    private var lastPayload: String? = null
    private var lastSelectedDownloadId: String? = null
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

    fun writePayloadDirect(payload: DownloadsLiveStatusPayload?) {
        val encoded = payload?.let { json.encodeToString(it) }
        if (encoded == lastPayload) return
        lastPayload = encoded

        val defaults = NSUserDefaults.standardUserDefaults
        if (encoded == null) {
            defaults.removeObjectForKey(USER_DEFAULTS_PAYLOAD_KEY)
        } else {
            defaults.setObject(encoded, forKey = USER_DEFAULTS_PAYLOAD_KEY)
        }
        NSNotificationCenter.defaultCenter.postNotificationName(NOTIFICATION_NAME, null)
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
            currentSelectedId = lastSelectedDownloadId,
        )
        val primaryItem = presentation?.candidate?.id?.let(candidatesById::get)
        lastSelectedDownloadId = primaryItem?.id ?: activeBatch?.id

        val payload = when {
            primaryItem != null -> {
                DownloadsLiveStatusPayload(
                    id = primaryItem.id,
                    title = primaryItem.title,
                    subtitle = primaryItem.displaySubtitle,
                    status = primaryItem.liveActivityStatus(),
                    downloadedBytes = primaryItem.downloadedBytes,
                    totalBytes = primaryItem.totalBytes,
                    progressPercent = presentation.progressPercent ?: -1,
                    activeCount = presentation.activeCount,
                    remainingCount = presentation.remainingCount,
                    queueSummaryText = presentation.queueSummaryText,
                )
            }
            activeBatch != null -> {
                DownloadsLiveStatusPayload(
                    id = activeBatch.id,
                    title = activeBatch.title,
                    subtitle = "Finding sources",
                    status = "FINDING_SOURCES",
                    downloadedBytes = 0L,
                    totalBytes = null,
                    progressPercent = -1,
                    activeCount = 1,
                    remainingCount = 0,
                    queueSummaryText = null,
                )
            }
            else -> null
        }

        writePayloadDirect(payload)
    }

    private fun DownloadItem.liveActivityCandidate() = DownloadsLiveStatusPolicy.Candidate(
        id = id,
        state = liveActivityState(),
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        queuePosition = queuePosition,
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
internal data class DownloadsLiveStatusPayload(
    val id: String,
    val title: String,
    val subtitle: String,
    val status: String,
    val downloadedBytes: Long,
    val totalBytes: Long? = null,
    val progressPercent: Int,
    val activeCount: Int = 1,
    val remainingCount: Int = 0,
    val queueSummaryText: String? = null,
)
