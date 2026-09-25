package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 9 stage 7: one vocabulary, attention cards, the grouped queue, storage and cleanup. */
class DownloadPresentationTest {
    private val now = 1_000_000L
    private val gb = 1_000_000_000L

    private fun item(
        id: String,
        season: Int? = 1,
        episode: Int? = id.filter(Char::isDigit).toIntOrNull() ?: 1,
        status: DownloadStatus = DownloadStatus.Queued,
        activity: DownloadActivity? = DownloadActivity.QUEUED_FOR_SLOT,
        pause: DownloadPauseReason? = null,
        failure: DownloadFailureKind? = null,
        downloaded: Long = 0L,
        total: Long? = null,
        position: Long = 0L,
        parent: String = "tt1",
        owner: Int = 1,
        sizeApproval: Boolean = false,
        retryAt: Long? = null,
    ) = DownloadItem(
        id = id,
        ownerProfileId = owner,
        contentType = if (season != null) "series" else "movie",
        parentMetaId = parent,
        parentMetaType = if (season != null) "series" else "movie",
        videoId = "$parent:$season:$episode",
        title = "Show",
        seasonNumber = season,
        episodeNumber = if (season != null) episode else null,
        streamTitle = "s",
        providerName = "p",
        fileName = "$id.mkv",
        status = status,
        activity = activity,
        pauseReason = pause,
        failureKind = failure,
        downloadedBytes = downloaded,
        totalBytes = total,
        queuePosition = position,
        sizeApprovalRequired = sizeApproval,
        nextRetryAtEpochMs = retryAt,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
    )

    private fun phase(item: DownloadItem) = DownloadPresenter.item(item, now)

    // --- one vocabulary --------------------------------------------------------------------

    @Test
    fun waitsAreNamedForWhatTheyWaitFor() {
        assertEquals(DownloadWaitReason.CONNECTION, phase(item("a", activity = DownloadActivity.WAITING_FOR_CONNECTION)).waitReason)
        assertEquals(DownloadWaitReason.WIFI, phase(item("a", activity = DownloadActivity.WAITING_FOR_WIFI)).waitReason)
        assertEquals(DownloadWaitReason.RETRYING_SHORTLY, phase(item("a", activity = DownloadActivity.RETRY_BACKOFF)).waitReason)
        assertEquals(DownloadWaitReason.RETRYING_SHORTLY, phase(item("a", activity = null, retryAt = now + 5_000)).waitReason)
        assertEquals(DownloadUserPhase.QUEUED, phase(item("a")).phase)
    }

    @Test
    fun theRetryCountdownIsDetailNotThePlainLine() {
        val p = phase(item("a", activity = DownloadActivity.RETRY_BACKOFF, retryAt = now + 5_000).copy(attemptCount = 3))
        assertEquals(DownloadUserPhase.WAITING, p.phase)
        assertEquals(now + 5_000, p.detail.nextRetryAtEpochMs)
        assertEquals(3, p.detail.attempts)
    }

    @Test
    fun userPausesArePausedAndSystemPausesResumeByThemselves() {
        assertEquals(DownloadUserPhase.PAUSED, phase(item("a", status = DownloadStatus.Paused, pause = DownloadPauseReason.User, activity = DownloadActivity.USER_PAUSED)).phase)
        val system = phase(item("a", status = DownloadStatus.Paused, pause = DownloadPauseReason.System, activity = DownloadActivity.SYSTEM_PAUSED))
        assertEquals(DownloadUserPhase.WAITING, system.phase)
        assertEquals(DownloadWaitReason.RESUMING, system.waitReason)
    }

    @Test
    fun theFourThingsTheUserIsAskedAbout() {
        val overLimit = phase(item("a", status = DownloadStatus.Paused, pause = DownloadPauseReason.SizeApproval, sizeApproval = true))
        assertEquals(DownloadNeedsYouKind.NO_SOURCE_FITS, overLimit.needsYou)
        assertEquals(DownloadEntryDecisionKind.OVER_LIMIT, overLimit.noSourceReason)
        assertEquals(DownloadNeedsYouKind.STORAGE, phase(item("a", status = DownloadStatus.Failed, failure = DownloadFailureKind.STORAGE)).needsYou)
        assertEquals(DownloadEntryDecisionKind.NOTHING_CACHED, phase(item("a", status = DownloadStatus.Failed, failure = DownloadFailureKind.NOT_CACHED)).noSourceReason)
        assertEquals(DownloadNeedsYouKind.GAVE_UP, phase(item("a", status = DownloadStatus.Failed)).needsYou)
    }

    @Test
    fun resolvingASourceIsFindingASource() {
        assertEquals(DownloadUserPhase.FINDING_SOURCE, phase(item("a", status = DownloadStatus.Downloading, activity = DownloadActivity.RESOLVING_SOURCE)).phase)
        assertEquals(DownloadWaitReason.STARTING, phase(item("a", status = DownloadStatus.Downloading, activity = DownloadActivity.TRANSFERRING)).waitReason)
        val moving = phase(item("a", status = DownloadStatus.Downloading, activity = DownloadActivity.TRANSFERRING, downloaded = 1 * gb, total = 4 * gb))
        assertEquals(DownloadUserPhase.DOWNLOADING, moving.phase)
        assertEquals(25, moving.progressPercent)
    }

    @Test
    fun anEntryWithADownloadBehindItIsNotPresentedTwice() {
        val entry = DownloadBatchEntry(id = "e", videoId = "v", title = "E1", state = DownloadBatchEntryState.QUEUED)
        assertNull(DownloadPresenter.entry(entry))
        assertEquals(DownloadUserPhase.FINDING_SOURCE, DownloadPresenter.entry(entry.copy(state = DownloadBatchEntryState.DISCOVERING))?.phase)
        assertEquals(
            DownloadNeedsYouKind.MANUAL_PICK,
            DownloadPresenter.entry(entry.copy(state = DownloadBatchEntryState.SKIPPED, decision = DownloadEntryDecisionKind.MANUAL_PICK))?.needsYou,
        )
    }

    // --- the Live Activity reads the same presentation ----------------------------------------

    @Test
    fun liveActivityStatesComeFromThePresentation() {
        fun state(i: DownloadItem) = liveActivityStateOf(phase(i))
        assertEquals(DownloadsLiveStatusPolicy.State.RETRYING, state(item("a", activity = DownloadActivity.RETRY_BACKOFF)))
        assertEquals(DownloadsLiveStatusPolicy.State.WAITING, state(item("a")))
        assertEquals(DownloadsLiveStatusPolicy.State.WAITING, state(item("a", activity = DownloadActivity.WAITING_FOR_WIFI)))
        assertEquals(DownloadsLiveStatusPolicy.State.PREPARING, state(item("a", status = DownloadStatus.Downloading, activity = DownloadActivity.RESOLVING_SOURCE)))
        // Unchanged for the widget: a system pause still reads as paused.
        assertEquals(
            DownloadsLiveStatusPolicy.State.PAUSED,
            state(item("a", status = DownloadStatus.Paused, pause = DownloadPauseReason.System, activity = DownloadActivity.SYSTEM_PAUSED)),
        )
        assertEquals(DownloadsLiveStatusPolicy.State.FAILED, state(item("a", status = DownloadStatus.Failed)))
    }

    @Test
    fun theNotificationCountsWhatNeedsTheUser() {
        val summary = DownloadsSummaryPolicy.summarize(
            listOf(item("e1", activity = DownloadActivity.WAITING_FOR_WIFI), item("e2", status = DownloadStatus.Failed)),
            emptyList(),
            now,
        )!!
        assertEquals(DownloadWaitReason.WIFI, summary.waitingReason)
        assertEquals(1, summary.needsYouCount)
    }

    // --- attention -----------------------------------------------------------------------------

    private fun batch(entries: List<DownloadBatchEntry>) = DownloadBatch(
        id = "b",
        ownerProfileId = 1,
        scope = DownloadScope.Season(2),
        contentType = "series",
        parentMetaId = "tt1",
        parentMetaType = "series",
        title = "Show",
        sourcePolicySnapshot = DownloadSourcePolicy(),
        entries = entries,
        createdAtEpochMs = 0L,
    )

    private fun entry(ep: Int, state: DownloadBatchEntryState, decision: DownloadEntryDecisionKind?, usable: Boolean? = true) =
        DownloadBatchEntry(
            id = "e$ep",
            videoId = "tt1:2:$ep",
            title = "E$ep",
            season = 2,
            episode = ep,
            state = state,
            decision = decision,
            hasUsableSources = usable,
            selection = if (state == DownloadBatchEntryState.APPROVAL_NEEDED) {
                SourceSelectionResult.ApprovalNeeded(
                    streamUrl = "https://a/$ep.mkv",
                    facts = SourceFacts(sizeBytes = 2 * gb),
                    addonKey = AddonSourceKey("a", "u"),
                    calculatedCapBytes = 0L,
                    reason = "r",
                )
            } else {
                null
            },
        )

    @Test
    fun oneCardPerSeasonAndReasonWithTheRightActions() {
        val cards = AttentionGrouping.group(
            items = emptyList(),
            batches = listOf(
                batch(
                    listOf(
                        entry(1, DownloadBatchEntryState.APPROVAL_NEEDED, DownloadEntryDecisionKind.RESOLUTION_MISSING),
                        entry(2, DownloadBatchEntryState.APPROVAL_NEEDED, DownloadEntryDecisionKind.RESOLUTION_MISSING),
                        entry(3, DownloadBatchEntryState.SKIPPED, DownloadEntryDecisionKind.NOTHING_CACHED, usable = false),
                    ),
                ),
            ),
            nowEpochMs = now,
        )
        assertEquals(2, cards.size)
        val nearest = cards.single { it.noSourceReason == DownloadEntryDecisionKind.RESOLUTION_MISSING }
        assertEquals(2, nearest.members.size)
        assertEquals(listOf(AttentionAction.USE_NEAREST, AttentionAction.REMOVE), nearest.actions)
        assertEquals(4 * gb, nearest.allowBytes)
        assertTrue(nearest.members.all { it.offersChooseManually })
        val cached = cards.single { it.noSourceReason == DownloadEntryDecisionKind.NOTHING_CACHED }
        assertEquals(listOf(AttentionAction.CHECK_AGAIN, AttentionAction.REMOVE), cached.actions)
        assertFalse(cached.members.single().offersChooseManually, "the Phase 8 dead end stays closed")
    }

    @Test
    fun aFailedEntryWithADownloadIsThatDownloadsCard() {
        val failed = item("e1", season = 2, episode = 1, status = DownloadStatus.Failed)
        val cards = AttentionGrouping.group(
            items = listOf(failed),
            batches = listOf(batch(listOf(entry(1, DownloadBatchEntryState.FAILED, null)))),
            nowEpochMs = now,
        )
        assertEquals(1, cards.single().members.size)
        assertTrue(cards.single().members.single() is AttentionMember.Item)
        assertEquals(listOf(AttentionAction.RETRY, AttentionAction.REMOVE), cards.single().actions)
    }

    @Test
    fun manualPicksOfferChooseSourcesAndPickTheRest() {
        val cards = AttentionGrouping.group(
            items = emptyList(),
            batches = listOf(batch(listOf(entry(1, DownloadBatchEntryState.SKIPPED, DownloadEntryDecisionKind.MANUAL_PICK)))),
            nowEpochMs = now,
        )
        assertEquals(
            listOf(AttentionAction.CHOOSE_SOURCES, AttentionAction.PICK_THE_REST, AttentionAction.REMOVE),
            cards.single().actions,
        )
        assertEquals("b", cards.single().batch?.id)
    }

    @Test
    fun storageComesFirst() {
        val cards = AttentionGrouping.group(
            items = listOf(
                item("e1", status = DownloadStatus.Failed),
                item("m", season = null, parent = "tt9", status = DownloadStatus.Failed, failure = DownloadFailureKind.STORAGE),
            ),
            batches = emptyList(),
            nowEpochMs = now,
        )
        assertEquals(DownloadNeedsYouKind.STORAGE, cards.first().kind)
        assertEquals(listOf(AttentionAction.FREE_UP_SPACE, AttentionAction.RETRY, AttentionAction.REMOVE), cards.first().actions)
    }

    // --- the queue -----------------------------------------------------------------------------

    @Test
    fun aSeasonIsOneRowAndAFilmIsItsOwn() {
        val groups = DownloadQueueGrouping.group(
            unfinished = listOf(
                item("e3", position = 2),
                item("m", season = null, parent = "tt9", position = 1),
                item("e1", position = 0, status = DownloadStatus.Downloading, activity = DownloadActivity.TRANSFERRING, downloaded = 1 * gb, total = 2 * gb),
                item("e2", position = 3),
            ),
            completed = listOf(item("e0", episode = 0, status = DownloadStatus.Completed)),
            nowEpochMs = now,
        )
        assertEquals(listOf("tt1|1", "tt9|m"), groups.map { it.key })
        val season = groups.first()
        assertTrue(season.isSeason)
        assertEquals(listOf("e1", "e3", "e2"), season.items.map { it.id })
        assertEquals(1, season.completedCount)
        assertEquals(DownloadUserPhase.DOWNLOADING, season.lead.phase)
        assertFalse(groups[1].isSeason)
    }

    @Test
    fun movingASeasonSwapsItWithTheRowAboveAndLeavesOtherProfilesAlone() {
        val items = listOf(
            item("m", season = null, parent = "tt9", position = 0),
            item("x", season = null, parent = "tt8", position = 1, owner = 2),
            item("e1", position = 2),
            item("e2", position = 3),
        )
        val moved = DownloadQueuePlanner.movedGroup(
            items = items,
            groupKey = "tt1|1",
            up = true,
            groupOf = DownloadQueueGrouping::keyOf,
            inView = { it.ownerProfileId == 1 },
        )
        val byId = moved.associateBy { it.id }
        assertEquals(1L, byId.getValue("x").queuePosition, "another profile's item keeps its place")
        assertEquals(listOf("e1", "e2", "m"), moved.filter { it.ownerProfileId == 1 }.sortedBy { it.queuePosition }.map { it.id })
    }

    @Test
    fun theTopRowCannotMoveUp() {
        val items = listOf(item("e1", position = 0), item("m", season = null, parent = "tt9", position = 1))
        val moved = DownloadQueuePlanner.movedGroup(items, "tt1|1", up = true, groupOf = DownloadQueueGrouping::keyOf, inView = { true })
        assertTrue(moved === items)
    }

    // --- storage and cleanup ------------------------------------------------------------------

    @Test
    fun storageCountsEveryProfileAndAnUnknownFreeSpaceHasNoBar() {
        val summary = DownloadStorageSummary.of(
            listOf(
                item("a", status = DownloadStatus.Completed, total = 3 * gb, downloaded = 3 * gb),
                item("b", owner = 2, status = DownloadStatus.Downloading, downloaded = 1 * gb, total = 4 * gb),
            ),
            freeBytes = 4 * gb,
        )
        assertEquals(4 * gb, summary.usedBytes)
        assertEquals(0.5f, summary.usedFraction)
        assertNull(summary.copy(freeBytes = 0L).usedFraction)
    }

    @Test
    fun watchedCleanupIsSuggestedFromAGigabyteOfWatchedEpisodes() {
        val completed = listOf(
            item("e1", status = DownloadStatus.Completed, total = 700_000_000L),
            item("e2", status = DownloadStatus.Completed, total = 700_000_000L),
            item("e3", status = DownloadStatus.Completed, total = 700_000_000L),
            item("m", season = null, parent = "tt9", status = DownloadStatus.Completed, total = 9 * gb),
        )
        val suggestion = DownloadCleanup.watchedSuggestion(completed, isWatched = { it.id != "e3" })!!
        assertEquals(listOf("e1", "e2"), suggestion.items.map { it.id }, "films are never suggested, unwatched episodes neither")
        assertEquals(1_400_000_000L, suggestion.bytes)
        assertNull(DownloadCleanup.watchedSuggestion(completed, isWatched = { it.id == "e1" }))
    }
}
