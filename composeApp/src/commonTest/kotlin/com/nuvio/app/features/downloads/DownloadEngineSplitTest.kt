package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The pure decisions Phase 9 stage 4 pulled out of the download engine. */
class DownloadEngineSplitTest {
    private fun item(
        id: String,
        position: Long,
        owner: Int? = 1,
        status: DownloadStatus = DownloadStatus.Queued,
        allowMetered: Boolean = false,
        activity: DownloadActivity? = null,
    ) = DownloadItem(
        id = id,
        ownerProfileId = owner,
        contentType = "series",
        parentMetaId = "tt1",
        parentMetaType = "series",
        videoId = "tt1:1:$position",
        title = "Show",
        seasonNumber = 1,
        episodeNumber = position.toInt() + 1,
        streamTitle = "s",
        providerName = "p",
        fileName = "$id.mkv",
        status = status,
        activity = activity,
        allowMeteredNetwork = allowMetered,
        queuePosition = position,
        createdAtEpochMs = position,
        updatedAtEpochMs = 0L,
    )

    private fun batch(id: String, owner: Int?) = DownloadBatch(
        id = id,
        ownerProfileId = owner,
        scope = DownloadScope.Movie,
        presetSnapshot = DownloadPreset.BuiltIns.first(),
        sourcePolicySnapshot = DownloadSourcePolicy(),
        entries = emptyList(),
        createdAtEpochMs = 0L,
    )

    // --- Store migration -------------------------------------------------------------

    @Test
    fun legacyDownloadsWithNoOwnerGoToThePrimaryProfile() {
        val migrated = DownloadStoreMigration.assignOwners(
            StoredDownloadsPayload(
                items = listOf(item("a", 0, owner = null), item("b", 1, owner = 3)),
                batches = listOf(batch("x", owner = null)),
            ),
        )
        assertEquals(listOf(1, 3), migrated.items.map { it.ownerProfileId })
        assertEquals(listOf(1), migrated.batches.map { it.ownerProfileId })
    }

    @Test
    fun ownerMigrationIsIdempotent() {
        val owned = StoredDownloadsPayload(items = listOf(item("a", 0, owner = 2)))
        assertSame(owned, DownloadStoreMigration.assignOwners(owned))
    }

    @Test
    fun desktopProfilePayloadsMergeIntoOneQueueTaggedByProfile() {
        val primarySettings = DownloadDeviceSettings(maxConcurrent = 3)
        val merged = DownloadStoreMigration.mergeProfilePayloads(
            mapOf(
                2 to StoredDownloadsPayload(
                    items = listOf(item("b1", 0, owner = null), item("shared", 1, owner = null)),
                    batches = listOf(batch("bx", owner = null)),
                ),
                1 to StoredDownloadsPayload(
                    items = listOf(item("shared", 0, owner = null), item("a2", 1, owner = null)),
                    deviceSettings = primarySettings,
                ),
            ),
        )
        assertEquals(listOf("shared", "a2", "b1", "shared_p2"), merged.items.map { it.id })
        assertEquals(listOf(1, 1, 2, 2), merged.items.map { it.ownerProfileId })
        assertEquals(listOf(0L, 1L, 2L, 3L), merged.items.map { it.queuePosition })
        assertEquals(listOf(2), merged.batches.map { it.ownerProfileId })
        assertEquals(primarySettings, merged.deviceSettings, "device-level parts come from the primary profile")
    }

    @Test
    fun withoutAPrimaryPayloadTheLowestProfileSuppliesTheDeviceParts() {
        val settings = DownloadDeviceSettings(mobileData = DownloadMobileDataRule.ALWAYS)
        val merged = DownloadStoreMigration.mergeProfilePayloads(
            mapOf(
                4 to StoredDownloadsPayload(),
                3 to StoredDownloadsPayload(deviceSettings = settings),
            ),
        )
        assertEquals(settings, merged.deviceSettings)
    }

    @Test
    fun completedDownloadsKeepTheirPositionWhenMerged() {
        val merged = DownloadStoreMigration.mergeProfilePayloads(
            mapOf(1 to StoredDownloadsPayload(items = listOf(item("done", 7, status = DownloadStatus.Completed)))),
        )
        assertEquals(7L, merged.items.single().queuePosition)
    }

    @Test
    fun theOwnerSurvivesTheCodec() {
        val encoded = DownloadsCodec.encode(
            items = listOf(item("a", 0, owner = 4)),
            sourcePolicy = DownloadSourcePolicy(),
            batches = listOf(batch("x", owner = 4)),
            presets = DownloadPreset.BuiltIns,
        )
        val decoded = DownloadsCodec.decode(encoded)
        assertEquals(4, decoded.items.single().ownerProfileId)
        assertEquals(4, decoded.batches.single().ownerProfileId)
    }

    // --- Reordering one profile's view of a device queue -------------------------------

    private val mixedQueue = listOf(
        item("a1", 0, owner = 1),
        item("b1", 1, owner = 2),
        item("a2", 2, owner = 1),
        item("a3", 3, owner = 1),
    )
    private val inProfileOne: (DownloadItem) -> Boolean = { it.ownerProfileId == 1 }

    @Test
    fun upSwapsWithTheNeighbourTheUserCanSee() {
        val moved = DownloadQueuePlanner.reorderedInView(mixedQueue, "a2", QueueMove.Up, inProfileOne)
        assertEquals(
            listOf("a2", "b1", "a1", "a3"),
            moved.sortedWith(downloadQueueComparator).map { it.id },
        )
    }

    @Test
    fun downSwapsWithTheNeighbourTheUserCanSee() {
        val moved = DownloadQueuePlanner.reorderedInView(mixedQueue, "a1", QueueMove.Down, inProfileOne)
        assertEquals(
            listOf("a2", "b1", "a1", "a3"),
            moved.sortedWith(downloadQueueComparator).map { it.id },
        )
    }

    @Test
    fun toTopIsQueueWideBecauseItMeansStartNow() {
        val moved = DownloadQueuePlanner.reorderedInView(mixedQueue, "a3", QueueMove.ToTop, inProfileOne)
        assertEquals("a3", moved.sortedWith(downloadQueueComparator).first().id)
    }

    @Test
    fun aMovePastTheEdgeOfTheViewChangesNothing() {
        assertSame(mixedQueue, DownloadQueuePlanner.reorderedInView(mixedQueue, "a1", QueueMove.Up, inProfileOne))
        assertSame(mixedQueue, DownloadQueuePlanner.reorderedInView(mixedQueue, "a3", QueueMove.Down, inProfileOne))
        assertSame(mixedQueue, DownloadQueuePlanner.reorderedInView(mixedQueue, "b1", QueueMove.Up, inProfileOne))
    }

    // --- Android background host -----------------------------------------------------

    @Test
    fun theHostHasWorkOnlyWhileSomethingIsQueuedOrDownloading() {
        assertTrue(DownloadHostPlanner.hasWork(listOf(item("a", 0))))
        assertTrue(DownloadHostPlanner.hasWork(listOf(item("a", 0, status = DownloadStatus.Downloading))))
        assertFalse(
            DownloadHostPlanner.hasWork(
                listOf(
                    item("a", 0, status = DownloadStatus.Paused),
                    item("b", 1, status = DownloadStatus.Failed),
                    item("c", 2, status = DownloadStatus.Completed),
                ),
            ),
        )
    }

    @Test
    fun theHostMayUseMobileDataWhenAnyUnfinishedItemMay() {
        val wifiOnly = DownloadMobileDataRule.WIFI_ONLY
        // `.49`: every item Wi-Fi only, so the job waited for an unmetered network.
        assertFalse(DownloadHostPlanner.mayUseMeteredNetwork(listOf(item("a", 0), item("b", 1)), wifiOnly))
        // One "Download now anyway" is enough for the job to run on mobile data.
        assertTrue(
            DownloadHostPlanner.mayUseMeteredNetwork(listOf(item("a", 0), item("b", 1, allowMetered = true)), wifiOnly),
        )
        assertTrue(DownloadHostPlanner.mayUseMeteredNetwork(listOf(item("a", 0)), DownloadMobileDataRule.ALWAYS))
        // A paused item's permission does not keep a job on mobile data.
        assertFalse(
            DownloadHostPlanner.mayUseMeteredNetwork(
                listOf(item("a", 0, status = DownloadStatus.Paused, allowMetered = true)),
                wifiOnly,
            ),
        )
    }

    @Test
    fun theHostSummaryCountsWifiWaits() {
        val summary = DownloadHostPlanner.describe(
            listOf(item("a", 0, activity = DownloadActivity.WAITING_FOR_WIFI), item("b", 1)),
            DownloadMobileDataRule.WIFI_ONLY,
        )
        assertTrue("work=2" in summary && "wifiWait=1" in summary && "metered=false" in summary, summary)
    }

    // --- TransferHost ------------------------------------------------------------------

    private class FakeCoordinator(var backgrounded: Boolean) : SystemTransferCoordinator {
        override fun isBackgrounded() = backgrounded
        override fun requestInventory(onResult: (List<IosBackgroundTransferReconciler.LiveTransfer>?) -> Unit) =
            onResult(emptyList())
        override fun suspend(downloadId: String) = Unit
        override fun cancel(downloadId: String) = Unit
    }

    @Test
    fun inProcessSlotsComeFromTheDeviceSetting() {
        val host = TransferHost.InProcess(recoversSystemPauses = false)
        assertEquals(3, host.slotCount(DownloadDeviceSettings(maxConcurrent = 3)))
        assertEquals(4, host.slotCount(DownloadDeviceSettings(maxConcurrent = 12)))
        assertFalse(host.ownsTransferLiveness)
        assertFalse(host.schedulingDeferredToPlatform)
        assertNull(host.systemOwned)
    }

    @Test
    fun theIosWindowIgnoresTheDownloadsAtOnceSetting() {
        val coordinator = FakeCoordinator(backgrounded = false)
        val host = TransferHost.SystemOwned(window = IosBackgroundTransferReconciler.SUBMISSION_WINDOW, coordinator = coordinator)
        assertEquals(30, host.slotCount(DownloadDeviceSettings(maxConcurrent = 1)))
        assertTrue(host.ownsTransferLiveness)
        assertFalse(host.recoversSystemPauses)
        assertFalse(host.schedulingDeferredToPlatform)
        coordinator.backgrounded = true
        assertTrue(host.schedulingDeferredToPlatform)
    }

    @Test
    fun onlyAndroidRecoversItsOwnSystemPauses() {
        assertTrue(TransferHost.InProcess(recoversSystemPauses = true).recoversSystemPauses)
        assertFalse(TransferHost.InProcess(recoversSystemPauses = false).recoversSystemPauses)
    }

    // --- Source refresh failures (the NothingCached rule) ------------------------------

    @Test
    fun aKnownUncachedSourceFailsAtOnceInsteadOfWaitingForTheProvider() {
        val outcome = SourceRealizer.failureOutcome(
            resolution = DownloadSourceResolution.NotReady("not cached"),
            attempt = 1,
            knownUncached = true,
            canReresolveSource = true,
        )
        assertTrue(outcome.uncachedForGood)
        assertFalse(outcome.retryable)
    }

    @Test
    fun aSourceStillPreparingWaitsForTheProvider() {
        val outcome = SourceRealizer.failureOutcome(
            resolution = DownloadSourceResolution.NotReady("preparing"),
            attempt = 1,
            knownUncached = false,
            canReresolveSource = true,
        )
        assertTrue(outcome.retryable)
        assertEquals(DownloadActivity.WAITING_FOR_PROVIDER, outcome.retryActivity)
        assertEquals(DownloadFailureReason.SourceNotReady, outcome.reason)
    }

    @Test
    fun fatalAndChangedSourcesAreNeverRetried() {
        val fatal = SourceRealizer.failureOutcome(DownloadSourceResolution.FatalFailure("key"), 1, false, true)
        assertFalse(fatal.retryable)
        val changed = SourceRealizer.failureOutcome(DownloadSourceResolution.SourceChanged("other file"), 1, false, true)
        assertFalse(changed.retryable)
        assertTrue(changed.sourceChanged)
    }

    @Test
    fun aTransientResolveFailureBacksOff() {
        val outcome = SourceRealizer.failureOutcome(DownloadSourceResolution.RetryableFailure("503"), 1, false, true)
        assertTrue(outcome.retryable)
        assertEquals(DownloadActivity.RETRY_BACKOFF, outcome.retryActivity)
    }

    // --- Shared transfer-loop decisions ------------------------------------------------

    @Test
    fun a416OnACompletePartialFinishesWithoutRefetching() {
        assertEquals(RangeNotSatisfiableOutcome.PartialIsComplete, rangeNotSatisfiableOutcome(1_000L, 1_000L))
        assertEquals(RangeNotSatisfiableOutcome.RestartFromZero, rangeNotSatisfiableOutcome(2_000L, 1_000L))
        assertEquals(RangeNotSatisfiableOutcome.RestartFromZero, rangeNotSatisfiableOutcome(null, 1_000L))
    }

    @Test
    fun onlyA206ToARangeRequestContinuesThePartialFile() {
        assertTrue(responseAppendsToPartial(attemptedRangeRequest = true, statusCode = 206, resumeFromBytes = 10L))
        assertFalse(responseAppendsToPartial(attemptedRangeRequest = true, statusCode = 200, resumeFromBytes = 10L))
        assertFalse(responseAppendsToPartial(attemptedRangeRequest = false, statusCode = 206, resumeFromBytes = 10L))
        assertFalse(responseAppendsToPartial(attemptedRangeRequest = true, statusCode = 206, resumeFromBytes = 0L))
    }
}
