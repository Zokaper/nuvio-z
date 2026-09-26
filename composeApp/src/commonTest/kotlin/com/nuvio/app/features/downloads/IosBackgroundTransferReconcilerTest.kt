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

    // --- Background scheduler (`.45` ownership) -------------------------------------

    private val now = 10_000_000L
    private val fresh = now - 60_000L
    private val stale = now - 20L * 60L * 1000L

    @Test fun schedulerFillsSlotsInQueueOrder() {
        val plan = schedule(
            queue = listOf(prepared("d3", 3), prepared("d1", 1), prepared("d4", 4), prepared("d2", 2)),
        )
        assertEquals(listOf("d1", "d2"), plan.toStart.map { it.downloadId })
        assertNull(plan.refreshBoundary)
    }

    /** The `.44` fault: #1 and #2 claimed and resolving, with no task yet, must not free their slots. */
    @Test fun claimedButResolvingItemsHoldTheirSlots() {
        val plan = schedule(
            claimed = setOf("d1", "d2"),
            queue = listOf(prepared("d3", 3), prepared("d4", 4)),
        )
        assertTrue(plan.toStart.isEmpty())
    }

    @Test fun oneClaimAndOneRunningTaskLeaveNoSlot() {
        val plan = schedule(
            running = setOf("d1"),
            claimed = setOf("d2"),
            queue = listOf(prepared("d3", 3)),
        )
        assertTrue(plan.toStart.isEmpty())
    }

    @Test fun claimAndRunningTaskForTheSameItemTakeOneSlot() {
        val plan = schedule(
            running = setOf("d1"),
            claimed = setOf("d1"),
            queue = listOf(prepared("d2", 2), prepared("d3", 3)),
        )
        assertEquals(listOf("d2"), plan.toStart.map { it.downloadId })
    }

    @Test fun backgroundCompletionStartsTheNextFreshItem() {
        // d1 finished (no longer running or queued); d2 still running.
        val plan = schedule(
            running = setOf("d2"),
            queue = listOf(prepared("d3", 3), prepared("d4", 4)),
        )
        assertEquals(listOf("d3"), plan.toStart.map { it.downloadId })
    }

    @Test fun staleHeadIsAFifoBoundaryNotSomethingToSkip() {
        val plan = schedule(
            running = setOf("d1"),
            queue = listOf(prepared("d2", 2, resolvedAt = stale), prepared("d3", 3)),
        )
        assertTrue(plan.toStart.isEmpty())
        assertEquals("d2", plan.refreshBoundary?.downloadId)
    }

    @Test fun blankUrlIsAlsoABoundary() {
        val plan = schedule(queue = listOf(prepared("d1", 1, url = ""), prepared("d2", 2)))
        assertTrue(plan.toStart.isEmpty())
        assertEquals("d1", plan.refreshBoundary?.downloadId)
    }

    @Test fun freshItemsAheadOfTheBoundaryStillStart() {
        val plan = schedule(
            queue = listOf(prepared("d1", 1), prepared("d2", 2, resolvedAt = stale), prepared("d3", 3)),
        )
        assertEquals(listOf("d1"), plan.toStart.map { it.downloadId })
        assertEquals("d2", plan.refreshBoundary?.downloadId)
    }

    @Test fun suspendedTaskIsResumedNotDuplicatedAndNeedsNoFreshUrl() {
        val plan = schedule(
            suspended = setOf("d1"),
            queue = listOf(prepared("d1", 1, resolvedAt = stale), prepared("d2", 2)),
        )
        assertEquals(listOf("d1", "d2"), plan.toStart.map { it.downloadId })
        assertEquals(setOf("d1"), plan.toResume)
    }

    @Test fun suspendedTasksHoldNoSlot() {
        val plan = schedule(
            suspended = setOf("d9"),
            queue = listOf(prepared("d1", 1), prepared("d2", 2)),
        )
        assertEquals(2, plan.toStart.size)
    }

    @Test fun runningTasksAreNeverStartedAgain() {
        val plan = schedule(
            running = setOf("d1"),
            queue = listOf(prepared("d1", 1), prepared("d2", 2)),
        )
        assertEquals(listOf("d2"), plan.toStart.map { it.downloadId })
    }

    @Test fun finishedItemAwaitingCompletionIsNotRestarted() {
        val plan = schedule(finished = setOf("d1"), queue = listOf(prepared("d1", 1), prepared("d2", 2)))
        assertEquals(listOf("d2"), plan.toStart.map { it.downloadId })
    }

    @Test fun duplicateQueueEntriesStartOnce() {
        val plan = schedule(queue = listOf(prepared("d1", 1), prepared("d1", 1)))
        assertEquals(listOf("d1"), plan.toStart.map { it.downloadId })
    }

    @Test fun itemInRetryBackoffIsPassedOverNotABoundary() {
        val plan = schedule(
            queue = listOf(prepared("d1", 1, notBefore = now + 60_000L), prepared("d2", 2)),
        )
        assertEquals(listOf("d2"), plan.toStart.map { it.downloadId })
        assertNull(plan.refreshBoundary)
    }

    @Test fun concurrencyNeverExceedsTwo() {
        val queue = (1..6).map { prepared("d$it", it.toLong()) }
        listOf(
            emptySet<String>() to emptySet<String>(),
            setOf("d1") to emptySet(),
            setOf("d1") to setOf("d2"),
            setOf("d1", "d2") to setOf("d3"),
        ).forEach { (running, claimed) ->
            val plan = schedule(running = running, claimed = claimed, queue = queue)
            assertEquals(maxOf(0, 2 - (running + claimed).size), plan.toStart.size, "running=$running claimed=$claimed")
        }
    }

    // --- The `.46` submission window ------------------------------------------------

    /** One global queue: films and episodes of different shows share the window in order. */
    @Test fun windowFillsFromTheGlobalQueueInOrder() {
        val queue = listOf(
            prepared("showA-e2", 2), prepared("film", 1), prepared("showB-e1", 3),
            prepared("showA-e3", 4), prepared("showB-e2", 5),
        )
        val plan = schedule(running = setOf("showA-e1"), queue = queue, maxConcurrent = 4)
        assertEquals(listOf("film", "showA-e2", "showB-e1"), plan.toStart.map { it.downloadId })
    }

    @Test fun windowStillStopsAtTheFirstStaleItem() {
        val queue = listOf(prepared("d1", 1), prepared("d2", 2, resolvedAt = stale), prepared("d3", 3))
        val plan = schedule(queue = queue, maxConcurrent = 12)
        assertEquals(listOf("d1"), plan.toStart.map { it.downloadId })
        assertEquals("d2", plan.refreshBoundary?.downloadId)
    }

    @Test fun windowNeverExceedsItsBound() {
        val queue = (1..20).map { prepared("d$it", it.toLong()) }
        val plan = schedule(running = setOf("d1", "d2"), claimed = setOf("d3"), queue = queue, maxConcurrent = 12)
        assertEquals((4..12).map { "d$it" }, plan.toStart.map { it.downloadId })
    }

    @Test fun relaunchAdoptsTheWholeSubmittedWindow() {
        val plan = r.planAdoption(
            items = (1..8).map { adoption("d$it", IosBackgroundTransferReconciler.AdoptionState.DOWNLOADING, it.toLong()) },
            live = (1..8).map { live("d$it") },
            maxConcurrent = 12,
        )
        assertEquals((1..8).map { "d$it" }, plan.adopt)
        assertTrue(plan.suspend.isEmpty())
        assertTrue(plan.requeue.isEmpty())
    }

    /**
     * A force-quit cancels every task. On relaunch the session reports none, so the whole
     * window goes back to the queue where it stood, with no attempt charged and nothing
     * adopted twice.
     */
    @Test fun forceQuitRequeuesTheWindowInPlace() {
        val plan = r.planAdoption(
            items = listOf(
                adoption("d1", IosBackgroundTransferReconciler.AdoptionState.DOWNLOADING, 1),
                adoption("d2", IosBackgroundTransferReconciler.AdoptionState.DOWNLOADING, 2),
                adoption("d3", IosBackgroundTransferReconciler.AdoptionState.DOWNLOADING, 3),
                adoption("d4", IosBackgroundTransferReconciler.AdoptionState.QUEUED, 4),
            ),
            live = emptyList(),
            maxConcurrent = 12,
        )
        assertEquals(listOf("d1", "d2", "d3"), plan.requeue)
        assertTrue(plan.adopt.isEmpty())
        assertTrue(plan.cancel.isEmpty())
    }

    // --- Lost claims and tasks stuck at 100% (`.46` Pilot) --------------------------

    /** Held, handed to the session, and no task left: nothing will ever report for it. */
    @Test fun transferringClaimWithNoTaskIsReleased() {
        val plan = r.planAdoption(
            items = listOf(
                adoption("pilot", IosBackgroundTransferReconciler.AdoptionState.DOWNLOADING, 1, claimed = true, transferring = true),
                adoption("e2", IosBackgroundTransferReconciler.AdoptionState.DOWNLOADING, 2, claimed = true, transferring = true),
            ),
            live = listOf(live("e2")),
            maxConcurrent = 12,
        )
        assertEquals(listOf("pilot"), plan.releaseLost)
        assertTrue(plan.requeue.isEmpty())
    }

    @Test fun resolvingClaimIsNotReleased() {
        val plan = r.planAdoption(
            items = listOf(adoption("d1", IosBackgroundTransferReconciler.AdoptionState.DOWNLOADING, 1, claimed = true, transferring = false)),
            live = emptyList(),
            maxConcurrent = 12,
        )
        assertTrue(plan.releaseLost.isEmpty())
    }

    @Test fun transferringClaimWithASuspendedTaskIsNotReleased() {
        val plan = r.planAdoption(
            items = listOf(adoption("d1", IosBackgroundTransferReconciler.AdoptionState.DOWNLOADING, 1, claimed = true, transferring = true)),
            live = listOf(live("d1", running = false)),
            maxConcurrent = 12,
        )
        assertTrue(plan.releaseLost.isEmpty())
    }

    @Test fun releasedClaimNoLongerHoldsASlot() {
        val plan = r.planAdoption(
            items = listOf(
                adoption("lost", IosBackgroundTransferReconciler.AdoptionState.DOWNLOADING, 1, claimed = true, transferring = true),
                adoption("d2", IosBackgroundTransferReconciler.AdoptionState.QUEUED, 2),
            ),
            live = listOf(live("d2")),
            maxConcurrent = 1,
        )
        assertEquals(listOf("d2"), plan.adopt)
    }

    @Test fun taskFullForLongerThanTheGraceIsStalledAtEnd() {
        assertTrue(r.isStalledAtEnd(true, 100, 100, fullSinceEpochMs = 0L, nowEpochMs = r.STALLED_AT_END_GRACE_MS))
    }

    @Test fun taskJustFullOrNotFullOrNotRunningIsNotStalled() {
        assertFalse(r.isStalledAtEnd(true, 100, 100, fullSinceEpochMs = 0L, nowEpochMs = r.STALLED_AT_END_GRACE_MS - 1))
        assertFalse(r.isStalledAtEnd(true, 99, 100, fullSinceEpochMs = 0L, nowEpochMs = Long.MAX_VALUE))
        assertFalse(r.isStalledAtEnd(false, 100, 100, fullSinceEpochMs = 0L, nowEpochMs = Long.MAX_VALUE))
        assertFalse(r.isStalledAtEnd(true, 100, -1, fullSinceEpochMs = 0L, nowEpochMs = Long.MAX_VALUE))
        assertFalse(r.isStalledAtEnd(true, 100, 100, fullSinceEpochMs = null, nowEpochMs = Long.MAX_VALUE))
    }

    /** Pilot, `.46`: five transactions, last one a 206 for the remaining range. */
    @Test fun resumed206IsSizedFromContentRangeNotContentLength() {
        assertEquals(
            296_733_318L,
            r.finishedTransferTotal(206, contentLength = 1_048_576L, contentRange = "bytes 295684742-296733317/296733318", knownTotalBytes = null),
        )
    }

    @Test fun resumed206WithoutATotalFallsBackToTheKnownSize() {
        assertEquals(500L, r.finishedTransferTotal(206, 100L, "bytes 400-499/*", knownTotalBytes = 500L))
        assertNull(r.finishedTransferTotal(206, 100L, null, knownTotalBytes = null))
    }

    @Test fun plain200IsSizedFromContentLength() {
        assertEquals(1_000L, r.finishedTransferTotal(200, 1_000L, null, knownTotalBytes = 900L))
        assertEquals(900L, r.finishedTransferTotal(200, null, null, knownTotalBytes = 900L))
    }

    // --- Submission order ---------------------------------------------------------------

    @Test fun resolvedItemsAreReleasedInQueueOrder() {
        val release = r.releaseInQueueOrder(
            parkedIds = setOf("d3", "d1", "d2"),
            resolvingIds = emptySet(),
            positions = mapOf("d1" to 1L, "d2" to 2L, "d3" to 3L),
        )
        assertEquals(listOf("d1", "d2", "d3"), release)
    }

    @Test fun anItemStillResolvingHoldsBackEverythingBehindIt() {
        val release = r.releaseInQueueOrder(
            parkedIds = setOf("d1", "d3", "d4"),
            resolvingIds = setOf("d2"),
            positions = mapOf("d1" to 1L, "d2" to 2L, "d3" to 3L, "d4" to 4L),
        )
        assertEquals(listOf("d1"), release)
    }

    @Test fun nothingIsReleasedWhileTheHeadIsResolving() {
        val release = r.releaseInQueueOrder(
            parkedIds = setOf("d2"),
            resolvingIds = setOf("d1"),
            positions = mapOf("d1" to 1L, "d2" to 2L),
        )
        assertTrue(release.isEmpty())
    }

    @Test fun resolvingBehindTheParkedItemsDoesNotHoldThem() {
        val release = r.releaseInQueueOrder(
            parkedIds = setOf("d1", "d2"),
            resolvingIds = setOf("d3"),
            positions = mapOf("d1" to 1L, "d2" to 2L, "d3" to 3L),
        )
        assertEquals(listOf("d1", "d2"), release)
    }

    // --- Adoption on launch and on return to the foreground --------------------------

    /** Relaunch with #3 and #4 really running: adopt them, do not start #1 and #2 on top. */
    @Test fun relaunchAdoptsRealTasksWithoutReordering() {
        val plan = r.planAdoption(
            items = listOf(
                adoption("d1", IosBackgroundTransferReconciler.AdoptionState.QUEUED, 1),
                adoption("d2", IosBackgroundTransferReconciler.AdoptionState.QUEUED, 2),
                adoption("d3", IosBackgroundTransferReconciler.AdoptionState.QUEUED, 3),
                adoption("d4", IosBackgroundTransferReconciler.AdoptionState.QUEUED, 4),
            ),
            live = listOf(live("d3"), live("d4")),
            maxConcurrent = 2,
        )
        assertEquals(listOf("d3", "d4"), plan.adopt)
        assertTrue(plan.suspend.isEmpty())
        assertTrue(plan.cancel.isEmpty())
        assertTrue(plan.requeue.isEmpty())
    }

    /** `.44`'s stale RUNNING, or a force-quit: recorded as downloading, no task behind it. */
    @Test fun downloadingWithNoTaskIsRequeuedInPlace() {
        val plan = r.planAdoption(
            items = listOf(
                adoption("d1", IosBackgroundTransferReconciler.AdoptionState.DOWNLOADING, 1),
                adoption("d2", IosBackgroundTransferReconciler.AdoptionState.DOWNLOADING, 2),
            ),
            live = emptyList(),
            maxConcurrent = 2,
        )
        assertEquals(listOf("d1", "d2"), plan.requeue)
        assertTrue(plan.adopt.isEmpty())
    }

    @Test fun downloadingWithOnlyASuspendedTaskIsRequeued() {
        val plan = r.planAdoption(
            items = listOf(adoption("d1", IosBackgroundTransferReconciler.AdoptionState.DOWNLOADING, 1)),
            live = listOf(live("d1", running = false)),
            maxConcurrent = 2,
        )
        assertEquals(listOf("d1"), plan.requeue)
        assertTrue(plan.adopt.isEmpty())
    }

    @Test fun downloadingThatTheRepositoryAlreadyHoldsIsLeftAlone() {
        val plan = r.planAdoption(
            items = listOf(adoption("d1", IosBackgroundTransferReconciler.AdoptionState.DOWNLOADING, 1, claimed = true)),
            live = emptyList(),
            maxConcurrent = 2,
        )
        assertTrue(plan.requeue.isEmpty())
    }

    @Test fun runningTasksBeyondCapacityAreSuspendedInQueueOrder() {
        val plan = r.planAdoption(
            items = (1..4).map { adoption("d$it", IosBackgroundTransferReconciler.AdoptionState.DOWNLOADING, it.toLong()) },
            live = (1..4).map { live("d$it") },
            maxConcurrent = 2,
        )
        assertEquals(listOf("d1", "d2"), plan.adopt)
        assertEquals(setOf("d3", "d4"), plan.suspend.toSet())
    }

    @Test fun resolvingClaimKeepsItsSlotDuringAdoption() {
        val plan = r.planAdoption(
            items = listOf(
                adoption("d1", IosBackgroundTransferReconciler.AdoptionState.DOWNLOADING, 1, claimed = true),
                adoption("d3", IosBackgroundTransferReconciler.AdoptionState.QUEUED, 3),
                adoption("d4", IosBackgroundTransferReconciler.AdoptionState.QUEUED, 4),
            ),
            live = listOf(live("d3"), live("d4")),
            maxConcurrent = 2,
        )
        assertEquals(listOf("d3"), plan.adopt)
        assertEquals(listOf("d4"), plan.suspend)
    }

    @Test fun alreadyHeldRunningTaskIsNotAdoptedTwice() {
        val plan = r.planAdoption(
            items = listOf(
                adoption("d1", IosBackgroundTransferReconciler.AdoptionState.DOWNLOADING, 1, claimed = true),
                adoption("d2", IosBackgroundTransferReconciler.AdoptionState.QUEUED, 2),
            ),
            live = listOf(live("d1"), live("d2")),
            maxConcurrent = 2,
        )
        assertEquals(listOf("d2"), plan.adopt)
    }

    @Test fun tasksForGoneFinishedOrFailedDownloadsAreCancelled() {
        val plan = r.planAdoption(
            items = listOf(
                adoption("done", IosBackgroundTransferReconciler.AdoptionState.COMPLETED, 1),
                adoption("bad", IosBackgroundTransferReconciler.AdoptionState.FAILED, 2),
            ),
            live = listOf(live("done"), live("bad"), live("deleted")),
            maxConcurrent = 2,
        )
        assertEquals(setOf("done", "bad", "deleted"), plan.cancel.toSet())
        assertTrue(plan.adopt.isEmpty())
    }

    @Test fun userPausedRunningTaskIsSuspendedNotAdopted() {
        val plan = r.planAdoption(
            items = listOf(adoption("d1", IosBackgroundTransferReconciler.AdoptionState.USER_PAUSED, 1)),
            live = listOf(live("d1"), live("d1-not-there", running = false)),
            maxConcurrent = 2,
        )
        assertEquals(listOf("d1"), plan.suspend)
        assertTrue(plan.adopt.isEmpty())
    }

    @Test fun systemPausedRunningTaskIsAdopted() {
        val plan = r.planAdoption(
            items = listOf(adoption("d1", IosBackgroundTransferReconciler.AdoptionState.SYSTEM_PAUSED, 1)),
            live = listOf(live("d1")),
            maxConcurrent = 2,
        )
        assertEquals(listOf("d1"), plan.adopt)
    }

    // --- The submission window (30 since 2026-09-26) ---------------------------------------

    @Test fun aTwentyTwoEpisodeSeasonIsHandedOverWhole() {
        // The case the window of 12 failed: episodes 13-22 were never submitted while the app was
        // open, so they waited for the next unlock + open.
        val season = (1..22).map { prepared("e$it", it.toLong()) }
        val plan = schedule(queue = season, maxConcurrent = IosBackgroundTransferReconciler.SUBMISSION_WINDOW)
        assertEquals(season.map { it.downloadId }, plan.toStart.map { it.downloadId })
        assertNull(plan.refreshBoundary)
    }

    @Test fun theWindowStillBoundsALongerQueueInQueueOrder() {
        val queue = (1..40).map { prepared("q$it", it.toLong()) }
        val plan = schedule(queue = queue, maxConcurrent = IosBackgroundTransferReconciler.SUBMISSION_WINDOW)
        assertEquals(30, IosBackgroundTransferReconciler.SUBMISSION_WINDOW)
        assertEquals((1..30).map { "q$it" }, plan.toStart.map { it.downloadId })
    }

    @Test fun runningTasksCountAgainstTheWindow() {
        val running = (1..25).map { "r$it" }.toSet()
        val queue = (1..10).map { prepared("q$it", it.toLong()) }
        val plan = schedule(running = running, queue = queue, maxConcurrent = IosBackgroundTransferReconciler.SUBMISSION_WINDOW)
        assertEquals((1..5).map { "q$it" }, plan.toStart.map { it.downloadId })
    }

    @Test fun aStaleLinkInsideTheWiderWindowIsStillABoundary() {
        // The freshness safeguard is unchanged by the wider window: nothing past a stale link starts.
        val queue = (1..20).map { prepared("q$it", it.toLong(), resolvedAt = if (it == 14) null else fresh) }
        val plan = schedule(queue = queue, maxConcurrent = IosBackgroundTransferReconciler.SUBMISSION_WINDOW)
        assertEquals((1..13).map { "q$it" }, plan.toStart.map { it.downloadId })
        assertEquals("q14", plan.refreshBoundary?.downloadId)
    }

    private fun schedule(
        running: Set<String> = emptySet(),
        claimed: Set<String> = emptySet(),
        suspended: Set<String> = emptySet(),
        finished: Set<String> = emptySet(),
        queue: List<IosBackgroundTransferReconciler.IosPreparedTransfer>,
        maxConcurrent: Int = 2,
    ) = r.scheduleNextTransfers(
        maxConcurrent = maxConcurrent,
        runningIds = running,
        claimedIds = claimed,
        suspendedIds = suspended,
        finishedIds = finished,
        preparedQueue = queue,
        nowEpochMs = now,
    )

    private fun prepared(
        id: String,
        queuePos: Long,
        resolvedAt: Long? = fresh,
        url: String = "https://example.invalid/$id.mkv",
        notBefore: Long? = null,
    ) = IosBackgroundTransferReconciler.IosPreparedTransfer(
        downloadId = id,
        destinationFileName = "$id.mkv",
        sourceUrl = url,
        queuePosition = queuePos,
        sourceUrlResolvedAtEpochMs = resolvedAt,
        notBeforeEpochMs = notBefore,
    )

    private fun adoption(
        id: String,
        state: IosBackgroundTransferReconciler.AdoptionState,
        queuePos: Long,
        claimed: Boolean = false,
        transferring: Boolean = false,
    ) = IosBackgroundTransferReconciler.AdoptionItem(id, state, queuePos, claimed, transferring)

    private fun live(id: String, running: Boolean = true) =
        IosBackgroundTransferReconciler.LiveTransfer(id, running)

    private fun snapshot(
        repositoryState: IosBackgroundTransferReconciler.RepositoryState,
        nativeState: IosBackgroundTransferReconciler.NativeState,
        repositoryBytes: Long = 0,
        nativeBytes: Long = 0,
        destinationExists: Boolean = false,
    ) = IosBackgroundTransferReconciler.Snapshot(
        repositoryState, nativeState, repositoryBytes, nativeBytes, destinationExists,
    )
}
