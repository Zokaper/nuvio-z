package com.nuvio.app.features.downloads

import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.streams.StreamItem
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

internal sealed interface DownloadSourceResolution {
    data class Ready(val stream: StreamItem) : DownloadSourceResolution
    data class NotReady(val message: String) : DownloadSourceResolution
    data class RetryableFailure(val message: String) : DownloadSourceResolution
    data class FatalFailure(val message: String, val storage: Boolean = false) : DownloadSourceResolution
    data class SourceChanged(val message: String) : DownloadSourceResolution
}

internal sealed interface RefreshedDownloadSource {
    data class Ready(val item: DownloadItem) : RefreshedDownloadSource
    data class NeedsApproval(val item: DownloadItem, val message: String) : RefreshedDownloadSource
    data class Failed(val resolution: DownloadSourceResolution) : RefreshedDownloadSource
}

/**
 * Turns a queued download's source into a URL a transfer can use (Phase 9, stage 4): re-minting a
 * debrid link, verifying the size it now serves, and checking it still fits on disk. Split out of
 * `DownloadsRepository` unchanged; the scheduler decides *when*, this decides *what*.
 */
internal object SourceRealizer {
    /** What a failed refresh means for the download. Pure; see [failureOutcome]. */
    data class FailureOutcome(
        val reason: DownloadFailureReason,
        val retryable: Boolean,
        /** The addon said uncached, and asking again gets the same answer from the same snapshot. */
        val uncachedForGood: Boolean,
        /** The source now serves a different file, so the bytes on disk are not part of it. */
        val sourceChanged: Boolean,
        val retryActivity: DownloadActivity,
    )

    /**
     * The rules for a refresh that did not produce a URL.
     *
     * Waiting cannot help a source the addon reported as uncached: the resolver answers from that
     * snapshot without asking the service, so every retry gets the same answer (Phase 8 Batch 6).
     * Nuvio cannot ask a debrid service to cache something and wait for it, so that item fails at
     * once and is charged no loop of "Waiting for provider".
     */
    fun failureOutcome(
        resolution: DownloadSourceResolution,
        attempt: Int,
        knownUncached: Boolean,
        canReresolveSource: Boolean,
    ): FailureOutcome {
        val reason = when (resolution) {
            is DownloadSourceResolution.NotReady -> DownloadFailureReason.SourceNotReady
            is DownloadSourceResolution.SourceChanged -> DownloadFailureReason.SourceChanged
            else -> DownloadFailureReason.SourceExpired
        }
        val uncachedForGood = resolution is DownloadSourceResolution.NotReady && knownUncached
        val retryable = !uncachedForGood &&
            resolution !is DownloadSourceResolution.FatalFailure &&
            resolution !is DownloadSourceResolution.SourceChanged &&
            shouldRetry(reason, attempt, canReresolveSource)
        return FailureOutcome(
            reason = reason,
            retryable = retryable,
            uncachedForGood = uncachedForGood,
            sourceChanged = resolution is DownloadSourceResolution.SourceChanged,
            retryActivity = if (resolution is DownloadSourceResolution.NotReady) {
                DownloadActivity.WAITING_FOR_PROVIDER
            } else {
                DownloadActivity.RETRY_BACKOFF
            },
        )
    }

    /**
     * How a download asks its source for a fresh URL.
     *
     * The app always goes to [DirectDebridPlaybackResolver]. It is a variable so the
     * desktop download harness can stand in for the provider: re-minting is otherwise
     * only reachable with a real debrid account and a link left to expire in a real
     * queue, which is why it shipped with no runtime coverage at all.
     */
    var resolvePlayableStream: suspend (StreamItem, Int?, Int?) -> DownloadSourceResolution =
        { stream, season, episode ->
            when (
                val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(
                    stream,
                    season,
                    episode,
                    forceRefresh = true,
                )
            ) {
                is DirectDebridPlayableResult.Success -> DownloadSourceResolution.Ready(resolved.stream)
                DirectDebridPlayableResult.NotCached -> DownloadSourceResolution.NotReady(
                    getString(Res.string.debrid_not_cached),
                )
                DirectDebridPlayableResult.MissingApiKey -> DownloadSourceResolution.FatalFailure(
                    getString(Res.string.debrid_missing_api_key),
                )
                DirectDebridPlayableResult.Stale -> DownloadSourceResolution.RetryableFailure(
                    getString(Res.string.debrid_stream_stale),
                )
                DirectDebridPlayableResult.Error -> DownloadSourceResolution.RetryableFailure(
                    getString(Res.string.debrid_resolve_failed),
                )
            }
        }

    /**
     * Asks the source for a new URL, returning the updated item or null if it could not.
     *
     * Runs outside [DownloadStore.lock] because it suspends. The write back is the only part
     * that touches state.
     */
    suspend fun refresh(item: DownloadItem): RefreshedDownloadSource {
        val fallbackMessage = getString(Res.string.debrid_resolve_failed)
        val origin = item.sourceOrigin
            ?: return RefreshedDownloadSource.Failed(
                DownloadSourceResolution.RetryableFailure(fallbackMessage),
            )
        val resolution = try {
            withTimeoutOrNull(DownloadsTiming.sourceResolveTimeoutMs) {
                resolvePlayableStream(origin.stream, origin.season, origin.episode)
            } ?: DownloadSourceResolution.RetryableFailure(fallbackMessage)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            DownloadSourceResolution.RetryableFailure(fallbackMessage)
        }
        if (resolution !is DownloadSourceResolution.Ready) {
            return RefreshedDownloadSource.Failed(resolution)
        }
        val stream = resolution.stream
        val sourceUrl = stream.playableDirectUrl?.trim()?.takeIf { it.isNotBlank() }
            ?: return RefreshedDownloadSource.Failed(
                DownloadSourceResolution.RetryableFailure(fallbackMessage),
            )
        val refreshedHeaders = sanitizeRequestHeaders(stream.behaviorHints.proxyHeaders?.request)
        val refreshedSize = AutomaticDownloadDiscovery.verifyHttpSize(sourceUrl, refreshedHeaders)
            ?: stream.behaviorHints.videoSize?.takeIf { it > 0L }
        val expectedSize = item.expectedSizeBytes?.takeIf { it > 0L }
        if (
            item.downloadedBytes > 0L &&
            expectedSize != null &&
            refreshedSize != null &&
            sizesMateriallyConflict(listOf(expectedSize, refreshedSize))
        ) {
            return RefreshedDownloadSource.Failed(
                DownloadSourceResolution.SourceChanged(
                    getString(Res.string.debrid_stream_stale),
                ),
            )
        }
        val freeStorage = DownloadsPlatformDownloader.freeStorageBytes()
        if (
            refreshedSize != null &&
            freeStorage > 0L &&
            refreshedSize - item.downloadedBytes.coerceAtLeast(0L) > freeStorage
        ) {
            return RefreshedDownloadSource.Failed(
                DownloadSourceResolution.FatalFailure(
                    getString(Res.string.downloads_enqueue_insufficient_storage),
                    storage = true,
                ),
            )
        }

        val now = DownloadsClock.nowEpochMs()
        var updated: DownloadItem? = null
        synchronized(DownloadStore.lock) {
            DownloadStore.mutateLocked(item.id, immediate = true) { current ->
                current.copy(
                    sourceUrl = sourceUrl,
                    sourceHeaders = sanitizeRequestHeaders(
                        stream.behaviorHints.proxyHeaders?.request,
                    ).ifEmpty { current.sourceHeaders },
                    sourceResponseHeaders = sanitizeResponseHeaders(
                        stream.behaviorHints.proxyHeaders?.response,
                    ).ifEmpty { current.sourceResponseHeaders },
                    sourceUrlResolvedAtEpochMs = now,
                    expectedSizeBytes = refreshedSize ?: current.expectedSizeBytes,
                    totalBytes = refreshedSize ?: current.totalBytes,
                    exceedsSizeCap = current.exceedsSizeCap ||
                        (
                            current.calculatedCapBytes != null && refreshedSize != null &&
                                refreshedSize > current.calculatedCapBytes
                            ),
                    // Keep the validator from the bytes already on disk. If the fresh
                    // URL now points at a different object, If-Range makes a compliant
                    // host answer with 200 and the platform replaces the partial file.
                    updatedAtEpochMs = now,
                )
            }
            updated = DownloadStore.allItems.firstOrNull { it.id == item.id }
        }
        return updated
            ?.let { refreshed ->
                DownloadDiagnostics.resolved(refreshed, refreshedSize)
                if (
                    refreshedSize != null &&
                    refreshed.calculatedCapBytes != null &&
                    refreshedSize > refreshed.calculatedCapBytes &&
                    !refreshed.sizeCapOverrideApproved
                ) {
                    RefreshedDownloadSource.NeedsApproval(
                        refreshed,
                        getString(Res.string.download_size_approval_message),
                    )
                } else {
                    RefreshedDownloadSource.Ready(refreshed)
                }
            }
            ?: RefreshedDownloadSource.Failed(
                DownloadSourceResolution.RetryableFailure(fallbackMessage),
            )
    }
}

internal fun sanitizeRequestHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (
                normalizedKey.isBlank() ||
                normalizedValue.isBlank() ||
                normalizedKey.equals("Accept-Encoding", ignoreCase = true) ||
                normalizedKey.equals("Range", ignoreCase = true) ||
                normalizedKey.equals("If-Range", ignoreCase = true)
            ) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

internal fun sanitizeResponseHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (normalizedKey.isBlank() || normalizedValue.isBlank()) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()
