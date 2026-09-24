package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DownloadFlowRulesTest {
    private val gb = 1_000_000_000L

    // --- "Choose manually" only where something could become a download -----------------------

    @Test
    fun nothingCachedAndNoSourcesNeverOfferChooseManually() {
        assertFalse(DownloadFlowRules.offersChooseManually(DownloadEntryDecisionKind.NOTHING_CACHED, hasUsableSources = true))
        assertFalse(DownloadFlowRules.offersChooseManually(DownloadEntryDecisionKind.NO_SOURCES, hasUsableSources = null))
    }

    @Test
    fun ruleBreakingButUsableSourcesOfferChooseManually() {
        assertTrue(DownloadFlowRules.offersChooseManually(DownloadEntryDecisionKind.OVER_LIMIT, true))
        assertTrue(DownloadFlowRules.offersChooseManually(DownloadEntryDecisionKind.RESOLUTION_MISSING, true))
        assertTrue(DownloadFlowRules.offersChooseManually(DownloadEntryDecisionKind.MANUAL_PICK, null))
    }

    @Test
    fun olderEntriesKeepChooseManuallyUnlessNothingUsableWasRecorded() {
        assertTrue(DownloadFlowRules.offersChooseManually(null, null))
        assertFalse(DownloadFlowRules.offersChooseManually(null, false))
    }

    @Test
    fun checkAgainIsOnlyForAnswersThatChangeByThemselves() {
        assertTrue(DownloadFlowRules.offersCheckAgain(DownloadEntryDecisionKind.NOTHING_CACHED))
        assertTrue(DownloadFlowRules.offersCheckAgain(DownloadEntryDecisionKind.NO_SOURCES))
        assertFalse(DownloadFlowRules.offersCheckAgain(DownloadEntryDecisionKind.OVER_LIMIT))
        assertFalse(DownloadFlowRules.offersCheckAgain(null))
    }

    // --- free space ---------------------------------------------------------------------------

    @Test
    fun aBatchThatFitsStarts() {
        assertEquals(DownloadFlowRules.FreeSpaceVerdict.Fits, DownloadFlowRules.freeSpace(listOf(2 * gb, 2 * gb), 10 * gb))
    }

    @Test
    fun unknownFreeSpaceNeverBlocks() {
        assertEquals(DownloadFlowRules.FreeSpaceVerdict.Fits, DownloadFlowRules.freeSpace(listOf(900 * gb), 0L))
        assertEquals(DownloadFlowRules.FreeSpaceVerdict.Fits, DownloadFlowRules.freeSpace(listOf(900 * gb), -1L))
    }

    @Test
    fun tooBigCountsWhatFitsInQueueOrder() {
        val verdict = DownloadFlowRules.freeSpace(listOf(3 * gb, 3 * gb, 3 * gb, 1 * gb), 7 * gb)
        assertIs<DownloadFlowRules.FreeSpaceVerdict.TooBig>(verdict)
        assertEquals(10 * gb, verdict.neededBytes)
        // In order: 3 + 3 fit, the third 3 does not - and the queue is not reordered to squeeze the 1 in.
        assertEquals(2, verdict.fitCount)
    }

    @Test
    fun unknownSizesCountAsNothing() {
        assertEquals(DownloadFlowRules.FreeSpaceVerdict.Fits, DownloadFlowRules.freeSpace(listOf(null, 4 * gb, null), 5 * gb))
    }

    // --- season chooser -----------------------------------------------------------------------

    private val seasons = listOf(
        DownloadFlowRules.SeasonChoice(season = 0, episodeCount = 3, unwatchedCount = 3),
        DownloadFlowRules.SeasonChoice(season = 1, episodeCount = 10, unwatchedCount = 0),
        DownloadFlowRules.SeasonChoice(season = 2, episodeCount = 10, unwatchedCount = 4),
        DownloadFlowRules.SeasonChoice(season = 3, episodeCount = 8, unwatchedCount = 8),
    )

    @Test
    fun allLeavesSpecialsOut() {
        assertEquals(setOf(1, 2, 3), DownloadFlowRules.allSeasons(seasons))
    }

    @Test
    fun unwatchedSelectsSeasonsWithSomethingLeft() {
        assertEquals(setOf(2, 3), DownloadFlowRules.unwatchedSeasons(seasons))
    }

    @Test
    fun aStartedShowOpensOnUnwatchedEpisodes() {
        assertEquals(setOf(2, 3) to true, DownloadFlowRules.defaultSeasonSelection(seasons))
    }

    @Test
    fun anUnstartedShowOpensOnEverything() {
        val fresh = seasons.map { it.copy(unwatchedCount = it.episodeCount) }
        assertEquals(setOf(1, 2, 3) to false, DownloadFlowRules.defaultSeasonSelection(fresh))
    }

    @Test
    fun episodeCountFollowsTheUnwatchedSwitch() {
        assertEquals(18, DownloadFlowRules.episodeCount(seasons, setOf(2, 3), unwatchedOnly = false))
        assertEquals(12, DownloadFlowRules.episodeCount(seasons, setOf(2, 3), unwatchedOnly = true))
    }

    // --- Assisted pre-selection and labels ---------------------------------------------------

    @Test
    fun assistedPreselectsThePreferredResolution() {
        assertEquals(1080, DownloadFlowRules.preselectedHeight(listOf(2160, 1080, 720), DownloadResolutionPreference.P1080))
    }

    @Test
    fun bestAvailablePreselectsTheHighest() {
        assertEquals(2160, DownloadFlowRules.preselectedHeight(listOf(720, 2160, 1080), DownloadResolutionPreference.BEST_AVAILABLE))
    }

    @Test
    fun aMissingPreferredResolutionPreselectsTheNearestBelowThenAbove() {
        assertEquals(720, DownloadFlowRules.preselectedHeight(listOf(2160, 720), DownloadResolutionPreference.P1080))
        assertEquals(2160, DownloadFlowRules.preselectedHeight(listOf(2160), DownloadResolutionPreference.P1080))
        assertEquals(null, DownloadFlowRules.preselectedHeight(emptyList(), DownloadResolutionPreference.P1080))
    }

    @Test
    fun labels() {
        assertEquals("4K", DownloadFlowRules.resolutionLabel(2160))
        assertEquals("1080p", DownloadFlowRules.resolutionLabel(1080))
        assertEquals("2.1 GB", DownloadFlowRules.sizeLabel(2_100_000_000L))
        assertEquals("2 GB", DownloadFlowRules.sizeLabel(2_000_000_000L))
        assertEquals("850 MB", DownloadFlowRules.sizeLabel(850_000_000L))
        assertEquals("124 GB", DownloadFlowRules.sizeLabel(123_600_000_000L))
    }
}
