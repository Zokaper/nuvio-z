package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosBackgroundTransferReconcilerTest {
    private val r = IosBackgroundTransferReconciler
    private val running = IosBackgroundTransferReconciler.NativeState.RUNNING

    @Test fun sessionIdentifierIsStableAndBundleSpecific() {
        assertEquals("com.nuvio.app.z.debug.downloads.background.v1", r.sessionIdentifier("com.nuvio.app.z.debug"))
        assertEquals("com.nuvio.app.z.downloads.background.v1", r.sessionIdentifier(null))
    }
    @Test fun nativeTaskReattachesAfterRelaunch() {
        assertEquals(IosBackgroundTransferReconciler.Action.ATTACH, r.reconcile(snapshot(IosBackgroundTransferReconciler.RepositoryState.DOWNLOADING, running)).action)
    }
    @Test fun nativeProgressWinsByteReconciliation() {
        val result = r.reconcile(snapshot(IosBackgroundTransferReconciler.RepositoryState.DOWNLOADING, running, 10, 25))
        assertEquals(IosBackgroundTransferReconciler.Action.UPDATE_PROGRESS, result.action)
        assertEquals(25, result.reconciledBytes)
    }
    @Test fun missingNativeTaskCreatesExactlyOneReplacement() {
        assertEquals(IosBackgroundTransferReconciler.Action.CREATE, r.reconcile(snapshot(IosBackgroundTransferReconciler.RepositoryState.QUEUED, IosBackgroundTransferReconciler.NativeState.MISSING)).action)
    }
    @Test fun completedNativeTaskCompletesRepository() {
        assertEquals(IosBackgroundTransferReconciler.Action.COMPLETE, r.reconcile(snapshot(IosBackgroundTransferReconciler.RepositoryState.DOWNLOADING, IosBackgroundTransferReconciler.NativeState.COMPLETED, destinationExists = true)).action)
    }
    @Test fun failedNativeTaskRetries() {
        assertEquals(IosBackgroundTransferReconciler.Action.RETRY, r.reconcile(snapshot(IosBackgroundTransferReconciler.RepositoryState.DOWNLOADING, IosBackgroundTransferReconciler.NativeState.FAILED)).action)
    }
    @Test fun userPauseAndCancellationAreDistinct() {
        assertEquals(IosBackgroundTransferReconciler.Action.CANCEL_NATIVE, r.reconcile(snapshot(IosBackgroundTransferReconciler.RepositoryState.USER_PAUSED, running)).action)
        assertEquals(IosBackgroundTransferReconciler.Action.KEEP_PAUSED, r.reconcile(snapshot(IosBackgroundTransferReconciler.RepositoryState.USER_PAUSED, IosBackgroundTransferReconciler.NativeState.MISSING)).action)
    }
    @Test fun deletedDownloadRemovesStaleNativeTask() {
        assertEquals(IosBackgroundTransferReconciler.Action.REMOVE_STALE_NATIVE, r.reconcile(snapshot(IosBackgroundTransferReconciler.RepositoryState.MISSING, running)).action)
    }
    @Test fun legacyPartRestartsOnlyWithoutNativeOwner() {
        assertTrue(r.shouldRestartLegacyPartial(50, false)); assertFalse(r.shouldRestartLegacyPartial(50, true))
        assertFalse(r.shouldRestartLegacyPartial(0, false))
    }
    @Test fun rangeHeaderOnlyEmitsForPositiveBytes() {
        assertNull(r.computeRangeHeader(0)); assertEquals("bytes=1048576-", r.computeRangeHeader(1_048_576))
    }
    @Test fun ignoredRangeResponseRestartsFromZero() {
        val result = r.reconcileResponse(200, true, 5_000, null, 100_000, 100_000)
        assertTrue(result.isSuccess); assertFalse(result.isPartialResume); assertEquals(0, result.startingBytes)
    }
    @Test fun aligned206MayAppend() {
        val result = r.reconcileResponse(206, true, 5_000, "bytes 5000-99999/100000", 95_000, 100_000)
        assertTrue(result.isSuccess); assertTrue(result.isPartialResume); assertEquals(100_000, result.totalBytes)
    }
    @Test fun misaligned206MustRestartInsteadOfConcatenating() {
        val result = r.reconcileResponse(206, true, 5_000, "bytes 0-99999/100000", 100_000, 100_000)
        assertFalse(result.isSuccess); assertTrue(result.shouldRestartFromZero)
    }
    @Test fun exact416MeansExistingPartialIsComplete() {
        val result = r.reconcileResponse(416, true, 100_000, "bytes */100000", 0, 100_000)
        assertTrue(result.isAlreadyComplete); assertFalse(result.shouldRestartFromZero)
    }
    @Test fun overlong416PartialRestarts() {
        val result = r.reconcileResponse(416, true, 105_000, "bytes */100000", 0, 100_000)
        assertFalse(result.isSuccess); assertTrue(result.shouldRestartFromZero)
    }

    // --- Native queue scheduler tests ----------------------------------------------

    @Test fun schedulerFillsSlotsByQueueRank() {
        val now = 1_000_000L
        val queue = listOf(
            prepared("d3", queuePos = 3, resolvedAt = now),
            prepared("d1", queuePos = 1, resolvedAt = now),
            prepared("d4", queuePos = 4, resolvedAt = now),
            prepared("d2", queuePos = 2, resolvedAt = now),
        )
        val plan = r.scheduleNextTransfers(
            maxConcurrent = 2,
            activeDownloadIds = emptySet(),
            preparedQueue = queue,
            nowEpochMs = now,
        )
        assertEquals(2, plan.tasksToStart.size)
        assertEquals("d1", plan.tasksToStart[0].downloadId)
        assertEquals("d2", plan.tasksToStart[1].downloadId)
        assertEquals(2, plan.runningCount)
        assertEquals(2, plan.remainingCount)
    }

    @Test fun schedulerAdvancesNextPreparedWhenSlotFrees() {
        val now = 1_000_000L
        val queue = listOf(
            prepared("d1", queuePos = 1, state = IosBackgroundTransferReconciler.IosPreparedState.COMPLETED),
            prepared("d2", queuePos = 2, state = IosBackgroundTransferReconciler.IosPreparedState.RUNNING),
            prepared("d3", queuePos = 3, resolvedAt = now),
            prepared("d4", queuePos = 4, resolvedAt = now),
        )
        // d1 completed, so active set only has d2
        val plan = r.scheduleNextTransfers(
            maxConcurrent = 2,
            activeDownloadIds = setOf("d2"),
            preparedQueue = queue,
            nowEpochMs = now,
        )
        assertEquals(1, plan.tasksToStart.size)
        assertEquals("d3", plan.tasksToStart[0].downloadId)
        assertEquals(2, plan.runningCount)
        assertEquals(1, plan.remainingCount)
    }

    @Test fun schedulerDoesNotDuplicateAlreadyActiveTasks() {
        val now = 1_000_000L
        val queue = listOf(
            prepared("d1", queuePos = 1, resolvedAt = now),
            prepared("d2", queuePos = 2, resolvedAt = now),
        )
        val plan = r.scheduleNextTransfers(
            maxConcurrent = 2,
            activeDownloadIds = setOf("d1", "d2"),
            preparedQueue = queue,
            nowEpochMs = now,
        )
        assertTrue(plan.tasksToStart.isEmpty())
        assertEquals(2, plan.runningCount)
    }

    @Test fun schedulerDetectsExpiredPreparedSourceAndPausesStrictFifo() {
        val now = 2_000_000L
        val fresh = now - 60_000L // 1 min ago
        val expired = now - (20L * 60L * 1000L) // 20 mins ago (freshness is 15 mins)

        val queue = listOf(
            prepared("d1", queuePos = 1, resolvedAt = fresh),
            prepared("d2", queuePos = 2, resolvedAt = expired),
            prepared("d3", queuePos = 3, resolvedAt = fresh),
        )
        val plan = r.scheduleNextTransfers(
            maxConcurrent = 2,
            activeDownloadIds = emptySet(),
            preparedQueue = queue,
            nowEpochMs = now,
            strictFifo = true,
        )
        assertEquals(1, plan.tasksToStart.size)
        assertEquals("d1", plan.tasksToStart[0].downloadId)
        assertEquals(1, plan.tasksNeedingRefresh.size)
        assertEquals("d2", plan.tasksNeedingRefresh[0].downloadId)
        // Strict FIFO: d3 must not be started ahead of d2!
        assertFalse(plan.tasksToStart.any { it.downloadId == "d3" })
    }

    // --- Journal reconciliation tests ----------------------------------------------

    @Test fun journalReconcilesProgressAndCompletionIdempotently() {
        val item = itemState("d1", IosBackgroundTransferReconciler.RepositoryState.DOWNLOADING, downloaded = 100, total = 1000)
        val events = listOf(
            IosBackgroundTransferReconciler.IosJournalEvent.Progress("e1", "d1", 500, 1000, 10),
            IosBackgroundTransferReconciler.IosJournalEvent.Completed("e2", "d1", "file:///path/d1.mp4", 1000, 20),
            // Duplicate completion event arriving later:
            IosBackgroundTransferReconciler.IosJournalEvent.Completed("e3", "d1", "file:///path/d1.mp4", 1000, 25),
            // Out of order stale progress after completion:
            IosBackgroundTransferReconciler.IosJournalEvent.Progress("e4", "d1", 600, 1000, 30),
        )

        val result = r.reconcileJournal(listOf(item), events)
        val updated = result.updatedItems[0]

        assertEquals(IosBackgroundTransferReconciler.RepositoryState.COMPLETED, updated.state)
        assertEquals(1000, updated.downloadedBytes)
        assertEquals("file:///path/d1.mp4", updated.localFileUri)
        assertEquals(setOf("e1", "e2", "e3", "e4"), result.acknowledgedEventIds)
    }

    @Test fun journalReconcilerNeedsSourceRefreshMarksWaiting() {
        val item = itemState("d2", IosBackgroundTransferReconciler.RepositoryState.QUEUED)
        val events = listOf(
            IosBackgroundTransferReconciler.IosJournalEvent.NeedsSourceRefresh("e1", "d2", "Source link expired", 50),
        )
        val result = r.reconcileJournal(listOf(item), events)
        val updated = result.updatedItems[0]

        assertEquals(IosBackgroundTransferReconciler.RepositoryState.QUEUED, updated.state)
        assertTrue(updated.isWaitingForProvider)
        assertEquals("Source link expired", updated.errorMessage)
        assertEquals(setOf("d2"), result.needsRefreshIds)
    }

    @Test fun journalReconcilerRespectsUserPause() {
        val item = itemState("d1", IosBackgroundTransferReconciler.RepositoryState.USER_PAUSED, isUserPaused = true)
        val events = listOf(
            IosBackgroundTransferReconciler.IosJournalEvent.Progress("e1", "d1", 500, 1000, 10),
            IosBackgroundTransferReconciler.IosJournalEvent.Completed("e2", "d1", "file:///path/d1.mp4", 1000, 20),
        )
        val result = r.reconcileJournal(listOf(item), events)
        val updated = result.updatedItems[0]

        assertEquals(IosBackgroundTransferReconciler.RepositoryState.USER_PAUSED, updated.state)
        assertTrue(updated.isUserPaused)
    }

    // --- Codec roundtrip tests ----------------------------------------------------

    @Test fun preparedTransferCodecRoundtrips() {
        val original = IosBackgroundTransferReconciler.IosPreparedTransfer(
            downloadId = "down_123%special|name",
            destinationFileName = "movie|season 1.mp4",
            sourceUrl = "https://example.com/stream?token=abc%7Cxyz",
            sourceHeaders = mapOf("Authorization" to "Bearer 123", "X-Custom" to "val=1"),
            knownTotalBytes = 1_048_576L,
            queuePosition = 42L,
            title = "Lanterns S01E01",
            subtitle = "Episode 1",
            sourceUrlResolvedAtEpochMs = 1_700_000_000L,
            allowMeteredNetwork = true,
            state = IosBackgroundTransferReconciler.IosPreparedState.PREPARED,
        )
        val encoded = r.encodePreparedTransfer(original)
        val decoded = r.decodePreparedTransfer(encoded)
        assertEquals(original, decoded)
    }

    @Test fun journalEventCodecRoundtripsAllTypes() {
        val events = listOf(
            IosBackgroundTransferReconciler.IosJournalEvent.Progress("e1", "d1", 500L, 1000L, 100L),
            IosBackgroundTransferReconciler.IosJournalEvent.Completed("e2", "d1", "file:///path/movie.mp4", 1000L, 200L),
            IosBackgroundTransferReconciler.IosJournalEvent.Failed("e3", "d2", DownloadFailureReason.SourceExpired, "Link expired | invalid", 0L, 300L),
            IosBackgroundTransferReconciler.IosJournalEvent.NeedsSourceRefresh("e4", "d2", "Refresh required", 400L),
            IosBackgroundTransferReconciler.IosJournalEvent.TaskStarted("e5", "d3", 42L, 500L),
            IosBackgroundTransferReconciler.IosJournalEvent.TaskCancelled("e6", "d4", 600L),
        )
        val encoded = r.encodeJournalEvents(events)
        val decoded = r.decodeJournalEvents(encoded)
        assertEquals(events, decoded)
    }

    private fun snapshot(
        repositoryState: IosBackgroundTransferReconciler.RepositoryState,
        nativeState: IosBackgroundTransferReconciler.NativeState,
        repositoryBytes: Long = 0,
        nativeBytes: Long = 0,
        destinationExists: Boolean = false,
    ) = IosBackgroundTransferReconciler.Snapshot(
        repositoryState, nativeState, repositoryBytes, nativeBytes, destinationExists,
    )

    private fun prepared(
        id: String,
        queuePos: Long,
        resolvedAt: Long? = 1_000_000L,
        state: IosBackgroundTransferReconciler.IosPreparedState = IosBackgroundTransferReconciler.IosPreparedState.PREPARED,
    ) = IosBackgroundTransferReconciler.IosPreparedTransfer(
        downloadId = id,
        destinationFileName = "$id.mp4",
        sourceUrl = "https://example.com/$id.mp4",
        knownTotalBytes = 1000L,
        queuePosition = queuePos,
        sourceUrlResolvedAtEpochMs = resolvedAt,
        state = state,
    )

    private fun itemState(
        id: String,
        state: IosBackgroundTransferReconciler.RepositoryState,
        downloaded: Long = 0,
        total: Long? = null,
        isUserPaused: Boolean = false,
    ) = IosBackgroundTransferReconciler.ReconciledItemState(
        id = id,
        state = state,
        downloadedBytes = downloaded,
        totalBytes = total,
        isUserPaused = isUserPaused,
    )
}
