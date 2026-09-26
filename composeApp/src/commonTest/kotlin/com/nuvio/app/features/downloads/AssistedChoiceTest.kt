package com.nuvio.app.features.downloads

import com.nuvio.app.core.deeplink.AppDeepLink
import com.nuvio.app.core.deeplink.buildChooseQualityDeepLinkUrl
import com.nuvio.app.core.deeplink.parseAppDeepLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Assisted "choose when ready" (Phase 9, decided 2026-09-25): several episodes find their sources in
 * the background and the user chooses a quality when that is done. The rules that decide where the
 * user is told, and the one semantic line that must not move: ready to choose is **not** "Needs you".
 */
class AssistedChoiceTest {

    private fun entry(episode: Int, state: DownloadBatchEntryState) = DownloadBatchEntry(
        id = "tt1:1:$episode|1|$episode",
        videoId = "tt1:1:$episode",
        title = "E$episode",
        season = 1,
        episode = episode,
        state = state,
    )

    private fun batch(vararg entries: DownloadBatchEntry, awaits: Boolean = true, early: Int? = null) = DownloadBatch(
        id = "batch_1",
        scope = DownloadScope.Season(1),
        contentType = "series",
        parentMetaId = "tt1",
        parentMetaType = "series",
        title = "Lanterns",
        sourcePolicySnapshot = DownloadSourcePolicy(),
        entries = entries.toList(),
        createdAtEpochMs = 0L,
        awaitsQualityChoice = awaits,
        earlyResolutionHeight = early,
    )

    @Test
    fun severalEpisodesGoToTheBackgroundAndASingleOneKeepsTheSheet() {
        assertTrue(AssistedChoiceRules.runsInBackground(targetCount = 22, changing = false, intoExistingBatch = false))
        assertFalse(AssistedChoiceRules.runsInBackground(targetCount = 1, changing = false, intoExistingBatch = false))
        // "Change" on one item and Manual's "pick the rest" write into what already exists.
        assertFalse(AssistedChoiceRules.runsInBackground(targetCount = 5, changing = true, intoExistingBatch = false))
        assertFalse(AssistedChoiceRules.runsInBackground(targetCount = 5, changing = false, intoExistingBatch = true))
    }

    @Test
    fun theUserIsToldOnceAndInTheRightPlace() {
        assertEquals(
            AssistedChoiceRules.Announcement.OPEN_SHEET,
            AssistedChoiceRules.announcement(sheetShowsBatch = true, alreadyAnnounced = true, appInForeground = false),
            "the finding sheet still open goes straight to the choice, whatever else is true",
        )
        assertEquals(
            AssistedChoiceRules.Announcement.IN_APP,
            AssistedChoiceRules.announcement(sheetShowsBatch = false, alreadyAnnounced = false, appInForeground = true),
            "on screen: an in-app prompt, no system notification",
        )
        assertEquals(
            AssistedChoiceRules.Announcement.SYSTEM,
            AssistedChoiceRules.announcement(sheetShowsBatch = false, alreadyAnnounced = false, appInForeground = false),
        )
        assertEquals(
            AssistedChoiceRules.Announcement.NONE,
            AssistedChoiceRules.announcement(sheetShowsBatch = false, alreadyAnnounced = true, appInForeground = false),
            "a refresh after a process death does not say it twice - the row does",
        )
        assertEquals(4, AssistedChoiceRules.Announcement.entries.size)
    }

    @Test
    fun readyToChooseIsNotNeedsYou() {
        val presentation = DownloadPresenter.entry(entry(1, DownloadBatchEntryState.AWAITING_CHOICE))
        assertEquals(DownloadUserPhase.READY_TO_CHOOSE, presentation?.phase)
        assertNull(presentation?.needsYou)
        val ready = batch(entry(1, DownloadBatchEntryState.AWAITING_CHOICE), entry(2, DownloadBatchEntryState.AWAITING_CHOICE))
        assertTrue(
            AttentionGrouping.group(items = emptyList(), batches = listOf(ready), nowEpochMs = 0L).isEmpty(),
            "no Needs-you card for a season waiting for its quality",
        )
    }

    @Test
    fun aBatchIsReadyOnlyOnceEverySourceIsFound() {
        val finding = batch(entry(1, DownloadBatchEntryState.AWAITING_CHOICE), entry(2, DownloadBatchEntryState.DISCOVERING))
        assertTrue(finding.isPreparing)
        assertFalse(finding.isAwaitingQualityChoice)
        assertEquals(1, finding.preparedEntryCount, "Finding sources · 1 of 2")

        val ready = batch(entry(1, DownloadBatchEntryState.AWAITING_CHOICE), entry(2, DownloadBatchEntryState.AWAITING_CHOICE))
        assertFalse(ready.isPreparing)
        assertTrue(ready.isAwaitingQualityChoice)

        val automatic = batch(entry(1, DownloadBatchEntryState.AWAITING_CHOICE), awaits = false)
        assertFalse(automatic.isAwaitingQualityChoice)
    }

    @Test
    fun onlyBatchesWithSourcesStillToFindAreRefreshed() {
        assertTrue(AssistedChoiceRules.needsDiscovery(batch(entry(1, DownloadBatchEntryState.DISCOVERING))))
        assertTrue(AssistedChoiceRules.needsDiscovery(batch(entry(1, DownloadBatchEntryState.AWAITING_CHOICE))))
        assertFalse(AssistedChoiceRules.needsDiscovery(batch(entry(1, DownloadBatchEntryState.QUEUED))))
    }

    @Test
    fun aSeasonIsNamedByItsNumberAndSeveralByTheTitleAlone() {
        assertEquals(1, AssistedChoiceRules.seasonOf(batch(entry(1, DownloadBatchEntryState.DISCOVERING))))
        val twoSeasons = batch(entry(1, DownloadBatchEntryState.DISCOVERING)).let { b ->
            b.copy(entries = b.entries + b.entries.first().copy(id = "x", season = 2))
        }
        assertNull(AssistedChoiceRules.seasonOf(twoSeasons))
    }

    @Test
    fun awaitingEntriesAreNotDownloadsYetAndSurviveReconciliation() {
        val ready = batch(entry(1, DownloadBatchEntryState.AWAITING_CHOICE))
        assertFalse(DownloadBatchEntryState.AWAITING_CHOICE.isItemBacked)
        assertEquals(listOf(ready), reconcileBatches(listOf(ready), items = emptyList()))
    }

    @Test
    fun theNotificationOpensTheQualityChoiceForItsBatch() {
        val url = buildChooseQualityDeepLinkUrl("batch_abc_12")
        assertEquals(AppDeepLink.ChooseDownloadQuality("batch_abc_12"), parseAppDeepLink(url))
        assertEquals(AppDeepLink.Downloads, parseAppDeepLink("nuvio://downloads"))
        assertEquals(url, DownloadChoiceNotice("batch_abc_12", "Lanterns", 1, ready = true).deepLinkUrl)
        assertEquals("nuvio://downloads", DownloadChoiceNotice("batch_abc_12", "Lanterns", 1, ready = false).deepLinkUrl)
    }

    // --- physical `.56` (iOS): after "Choose now" the batch vanished while its sources were checked ---

    @Test
    fun aBatchChosenEarlyKeepsItsRowWhileTheSourcesFoundAreChecked() {
        val finding = batch(
            entry(1, DownloadBatchEntryState.AWAITING_CHOICE),
            entry(2, DownloadBatchEntryState.DISCOVERING),
            early = 1080,
        )
        assertEquals(DownloadChoiceStatus(DownloadChoicePhase.FINDING, 1, 2, 1080), finding.choiceStatus(refreshing = false))

        // Discovery done, the choice applied: the flag is cleared and each entry is being decided.
        val checking = batch(
            entry(1, DownloadBatchEntryState.READY),
            entry(2, DownloadBatchEntryState.RESOLVING),
            awaits = false,
            early = 1080,
        )
        assertTrue(checking.isStartingEarlyChoice)
        assertTrue(checking.showsAsChoiceRow, "the row the screen, Live Activity and notification read")
        assertEquals(DownloadChoiceStatus(DownloadChoicePhase.CHECKING, 1, 2, 1080), checking.choiceStatus(refreshing = false))

        // Queued: the downloads carry it from here, not the batch.
        val queued = batch(
            entry(1, DownloadBatchEntryState.QUEUED),
            entry(2, DownloadBatchEntryState.QUEUED),
            awaits = false,
            early = 1080,
        )
        assertFalse(queued.showsAsChoiceRow)
        assertNull(queued.choiceStatus(refreshing = false))
    }

    @Test
    fun theOldClaimLeftTheBatchInNoRowAtAll() {
        // What `.56` wrote: flag cleared, entries still AWAITING_CHOICE, nothing queued yet.
        val claimedTheOldWay = batch(
            entry(1, DownloadBatchEntryState.AWAITING_CHOICE),
            entry(2, DownloadBatchEntryState.AWAITING_CHOICE),
            awaits = false,
            early = 1080,
        )
        assertFalse(claimedTheOldWay.showsAsChoiceRow)
        assertFalse(claimedTheOldWay.isPreparing, "and not an Automatic 'preparing' row either")
    }

    @Test
    fun readyAndRefreshingPhasesAreUnchanged() {
        val ready = batch(entry(1, DownloadBatchEntryState.AWAITING_CHOICE), entry(2, DownloadBatchEntryState.AWAITING_CHOICE))
        assertEquals(DownloadChoicePhase.READY, ready.choiceStatus(refreshing = false)?.phase)
        val refreshing = batch(entry(1, DownloadBatchEntryState.DISCOVERING))
        assertEquals(DownloadChoicePhase.REFRESHING, refreshing.choiceStatus(refreshing = true)?.phase)
        // An Automatic batch is not an Assisted row.
        assertNull(batch(entry(1, DownloadBatchEntryState.DISCOVERING), awaits = false).choiceStatus(refreshing = false))
    }

    @Test
    fun aProcessDeathWhileCheckingFindsTheSourcesAgainAndKeepsTheChoice() {
        val checking = batch(
            entry(1, DownloadBatchEntryState.READY),
            entry(2, DownloadBatchEntryState.RESOLVING),
            entry(3, DownloadBatchEntryState.APPROVAL_NEEDED),
            awaits = false,
            early = 720,
        )
        val resumed = EarlyChoiceRestart.resume(checking)
        assertTrue(resumed.awaitsQualityChoice)
        assertEquals(720, resumed.earlyResolutionHeight)
        assertEquals(
            listOf(DownloadBatchEntryState.DISCOVERING, DownloadBatchEntryState.DISCOVERING, DownloadBatchEntryState.APPROVAL_NEEDED),
            resumed.entries.map { it.state },
        )
        assertTrue(AssistedChoiceRules.needsDiscovery(resumed), "AssistedDiscovery.resumeInterrupted picks it up")
    }
}
