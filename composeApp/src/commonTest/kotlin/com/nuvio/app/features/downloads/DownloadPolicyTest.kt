package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadPolicyTest {
    @Test
    fun anUnansweredModeIsDerivedFromPlaybackMode() {
        val policy = DownloadPolicy(mode = null)
        assertEquals(DownloadMode.MANUAL, policy.effectiveMode(PlaybackModeForDownloads.CLASSIC))
        assertEquals(DownloadMode.ASSISTED, policy.effectiveMode(PlaybackModeForDownloads.STREAMLINED))
        assertEquals(DownloadMode.AUTOMATIC, policy.effectiveMode(PlaybackModeForDownloads.INSTANT))
    }

    @Test
    fun anAnsweredModeIgnoresPlaybackMode() {
        val policy = DownloadPolicy(mode = DownloadMode.ASSISTED)
        PlaybackModeForDownloads.entries.forEach {
            assertEquals(DownloadMode.ASSISTED, policy.effectiveMode(it))
        }
    }

    @Test
    fun theDefaultsAreTheOnesDecided() {
        val policy = DownloadPolicy()
        assertEquals(DownloadPickRule.BEST_THAT_FITS, policy.pickRule)
        assertEquals(DownloadRange.SAME_AS_PLAYBACK, policy.range)
        assertEquals(DownloadResolutionFallback.ASK, policy.resolutionFallback)
    }

    @Test
    fun sizeLevelsGrowWithTheLevelAtEveryResolution() {
        listOf(480, 720, 1080, 1440, 2160, 4320).forEach { height ->
            val values = listOf(DownloadSizeLevel.SMALL, DownloadSizeLevel.MEDIUM, DownloadSizeLevel.LARGE, DownloadSizeLevel.HUGE)
                .map { DownloadSizeLevels.gigabytesPerHour(it, height)!! }
            assertEquals(values.sorted(), values, "levels out of order at ${height}p")
            assertEquals(values.distinct(), values)
        }
    }

    @Test
    fun theSameLevelIsLargerAtAHigherResolution() {
        DownloadSizeLevel.entries.filter { it != DownloadSizeLevel.ANY }.forEach { level ->
            val p1080 = DownloadSizeLevels.gigabytesPerHour(level, 1080)!!
            val p2160 = DownloadSizeLevels.gigabytesPerHour(level, 2160)!!
            assertTrue(p2160 > p1080, "$level is not larger at 4K")
        }
    }

    @Test
    fun sizeFiguresReadAsAPersonWouldSayThem() {
        assertEquals("0.4", downloadSizeFigure(0.4))
        assertEquals("1", downloadSizeFigure(1.0))
        assertEquals("1.5", downloadSizeFigure(1.5))
        assertEquals("0.1", downloadSizeFigure(0.01), "never 0")
        assertEquals("1", downloadSizeFigure(3.0 / 3.0))
        assertEquals("12", downloadSizeFigure(12.4))
        assertEquals("18", downloadSizeFigure(18.0))
    }

    @Test
    fun anySizeHasNoLimit() {
        assertNull(DownloadSizeLevels.limitBytes(DownloadSizeLevel.ANY, 2160, 60, isEpisode = true))
    }

    @Test
    fun anUnknownRuntimeAssumesTheOldPresetDefaults() {
        val episode = DownloadSizeLevels.limitBytes(DownloadSizeLevel.MEDIUM, 1080, null, isEpisode = true)!!
        val film = DownloadSizeLevels.limitBytes(DownloadSizeLevel.MEDIUM, 1080, null, isEpisode = false)!!
        assertEquals(2_250_000_000L, episode) // 3 GB/h x 45 min
        assertEquals(6_000_000_000L, film) // 3 GB/h x 120 min
    }

    @Test
    fun anUnlistedResolutionUsesTheNearestRow() {
        assertEquals(
            DownloadSizeLevels.gigabytesPerHour(DownloadSizeLevel.MEDIUM, 1080),
            DownloadSizeLevels.gigabytesPerHour(DownloadSizeLevel.MEDIUM, 1000),
        )
    }

    @Test
    fun automaticNeverAsksBeforeStartingASingleItemOrASeason() {
        assertEquals(DownloadEntryRoute.START_NOW, DownloadEntryRouter.route(DownloadMode.AUTOMATIC, DownloadRequestScope.SINGLE))
        assertEquals(DownloadEntryRoute.START_NOW, DownloadEntryRouter.route(DownloadMode.AUTOMATIC, DownloadRequestScope.SEASON))
        assertEquals(
            DownloadEntryRoute.SEASON_CHOOSER_THEN_START,
            DownloadEntryRouter.route(DownloadMode.AUTOMATIC, DownloadRequestScope.WHOLE_SHOW),
        )
    }

    @Test
    fun assistedAlwaysShowsTheResolutionRows() {
        assertEquals(DownloadEntryRoute.ASSISTED_SHEET, DownloadEntryRouter.route(DownloadMode.ASSISTED, DownloadRequestScope.SINGLE))
        assertEquals(DownloadEntryRoute.ASSISTED_SHEET, DownloadEntryRouter.route(DownloadMode.ASSISTED, DownloadRequestScope.SEASON))
        assertEquals(
            DownloadEntryRoute.SEASON_CHOOSER_THEN_ASSISTED,
            DownloadEntryRouter.route(DownloadMode.ASSISTED, DownloadRequestScope.WHOLE_SHOW),
        )
    }

    @Test
    fun manualNeverRoutesToAnythingButADownloadSurface() {
        assertEquals(DownloadEntryRoute.MANUAL_SOURCE_LIST, DownloadEntryRouter.route(DownloadMode.MANUAL, DownloadRequestScope.SINGLE))
        assertEquals(DownloadEntryRoute.MANUAL_CHOOSE_SOURCES, DownloadEntryRouter.route(DownloadMode.MANUAL, DownloadRequestScope.SEASON))
        assertEquals(
            DownloadEntryRoute.SEASON_CHOOSER_THEN_MANUAL,
            DownloadEntryRouter.route(DownloadMode.MANUAL, DownloadRequestScope.WHOLE_SHOW),
        )
    }

    // --- Assisted "Choose now": an estimate before any source is found ------------------------

    @Test
    fun anEstimateRunsFromTheLevelBelowToTheLevelItself() {
        // 22 episodes x 45 min = 16.5 h; 1080p Standard runs from Small (1.2) to 3 GB/h.
        val range = DownloadSizeLevels.estimateBytes(DownloadSizeLevel.MEDIUM, 1080, List(22) { 45 })!!
        assertEquals(19_800_000_000L, range.first)
        assertEquals(49_500_000_000L, range.last)
    }

    @Test
    fun unknownRuntimesCountAsTheKnownAverage() {
        val range = DownloadSizeLevels.estimateBytes(DownloadSizeLevel.SMALL, 1080, listOf(60, null))!!
        // Two hours at Small (0.6-1.2 GB/h at 1080p).
        assertEquals(1_200_000_000L, range.first)
        assertEquals(2_400_000_000L, range.last)
    }

    @Test
    fun noKnownRuntimeOrNoLimitMeansNoEstimate() {
        assertNull(DownloadSizeLevels.estimateBytes(DownloadSizeLevel.MEDIUM, 1080, listOf(null, null)))
        assertNull(DownloadSizeLevels.estimateBytes(DownloadSizeLevel.ANY, 1080, listOf(45)))
    }
}
