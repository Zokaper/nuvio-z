package com.nuvio.app.features.downloads

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSUserDefaults
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_batch_state_discovering
import nuvio.composeapp.generated.resources.downloads_live_background_title
import nuvio.composeapp.generated.resources.downloads_live_in_background
import nuvio.composeapp.generated.resources.downloads_live_queue_active
import nuvio.composeapp.generated.resources.downloads_live_queue_active_remaining
import nuvio.composeapp.generated.resources.downloads_live_queue_remaining
import org.jetbrains.compose.resources.getString

/**
 * A payload that differs from the last one only in bytes is written at most this often.
 *
 * Every write posts a notification the Live Activity manager handles on the main queue,
 * and each one became an ActivityKit update. With progress arriving several times a
 * second per transfer that was a steady stream of main-queue work for a number nobody
 * can read that fast. State changes are never held back.
 */
private const val PROGRESS_ONLY_WRITE_INTERVAL_MS = 1_000L

internal actual object DownloadsLiveStatusPlatform {
    private const val NOTIFICATION_NAME = "NuvioDownloadsLiveStatusUpdated"
    private const val USER_DEFAULTS_PAYLOAD_KEY = "nuvio.downloads.live_status.payload"
    /** One id for the whole backgrounded queue, so nothing about it changes until the app is back. */
    private const val BACKGROUND_PAYLOAD_ID = "nuvio.downloads.background"

    private val json = Json {
        encodeDefaults = true
    }

    private var lastPayload: String? = null
    private var lastPayloadShape: DownloadsLiveStatusPayload? = null
    private var lastWriteAtEpochMs = 0L
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

    // A Live Activity needs no notification permission, so there is nothing to ask for.
    actual fun onDownloadRequested() = Unit

    /**
     * The app went to the background or came back.
     *
     * A suspended app hears nothing about progress - the session moves the bytes without
     * it - and the completion wakes it does get do not reliably reach the Lock Screen
     * either: `.46` showed "6 downloading" long after one had finished. So while
     * backgrounded the activity says only what stays true until the app is back: the
     * queue is downloading in the background. No title of one episode among several, no
     * percentage, bytes or counts.
     */
    fun onAppBackgroundChanged() {
        updatePayload()
    }

    private fun writePayload(payload: DownloadsLiveStatusPayload?) {
        val encoded = payload?.let { json.encodeToString(it) }
        if (encoded == lastPayload) return
        val shape = payload?.copy(downloadedBytes = 0L, progressPercent = 0)
        val now = DownloadsClock.nowEpochMs()
        if (shape != null && shape == lastPayloadShape && now - lastWriteAtEpochMs < PROGRESS_ONLY_WRITE_INTERVAL_MS) {
            return
        }
        lastPayload = encoded
        lastPayloadShape = shape
        lastWriteAtEpochMs = now

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

        val backgroundedWithQueue = DownloadsPlatformDownloader.schedulingDeferredToPlatform() &&
            eligibleItems.any { it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Queued }
        val payload = when {
            backgroundedWithQueue -> DownloadsLiveStatusPayload(
                id = BACKGROUND_PAYLOAD_ID,
                title = runBlocking { getString(Res.string.downloads_live_background_title) },
                subtitle = runBlocking { getString(Res.string.downloads_live_in_background) },
                status = "BACKGROUND",
                downloadedBytes = 0L,
                totalBytes = null,
                progressPercent = -1,
                activeCount = 0,
                remainingCount = 0,
                queueSummaryText = null,
                backgroundStatusText = runBlocking { getString(Res.string.downloads_live_in_background) },
            )
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
                    queueSummaryText = queueSummaryText(presentation.activeCount, presentation.remainingCount),
                )
            }
            activeBatch != null -> {
                DownloadsLiveStatusPayload(
                    id = activeBatch.id,
                    title = activeBatch.title,
                    subtitle = runBlocking { getString(Res.string.downloads_batch_state_discovering) },
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

        writePayload(payload)
    }

    /** The localized form of [DownloadsLiveStatusPolicy.buildSummaryText]; the policy stays resource-free. */
    private fun queueSummaryText(activeCount: Int, remainingCount: Int): String? {
        if (DownloadsLiveStatusPolicy.buildSummaryText(activeCount, remainingCount) == null) return null
        return runBlocking { localizedQueueSummary(activeCount, remainingCount) }
    }

    private suspend fun localizedQueueSummary(activeCount: Int, remainingCount: Int): String =
        when {
            activeCount > 1 && remainingCount > 0 ->
                getString(Res.string.downloads_live_queue_active_remaining, activeCount, remainingCount)
            activeCount > 1 -> getString(Res.string.downloads_live_queue_active, activeCount)
            else -> getString(Res.string.downloads_live_queue_remaining, remainingCount)
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
    /** Set while the app is backgrounded: the activity is queue-level and shows no progress. */
    val backgroundStatusText: String? = null,
)
