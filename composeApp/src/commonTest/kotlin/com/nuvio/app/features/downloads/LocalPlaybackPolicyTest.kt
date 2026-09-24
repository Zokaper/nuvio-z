package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalPlaybackPolicyTest {
    private fun decide(
        manual: Boolean = false,
        completed: Boolean = false,
        file: Boolean = false,
        offline: Boolean = false,
    ) = LocalPlaybackPolicy.decide(
        manualSelection = manual,
        hasCompletedDownload = completed,
        localFileAvailable = file,
        offline = offline,
    )

    @Test
    fun aDownloadedEpisodePlaysLocallyOnlineAndOffline() {
        assertEquals(LocalPlaybackDecision.PlayLocal, decide(completed = true, file = true))
        assertEquals(LocalPlaybackDecision.PlayLocal, decide(completed = true, file = true, offline = true))
    }

    @Test
    fun offlineNeverReachesForTheNetwork() {
        for (manual in listOf(false, true)) {
            for (completed in listOf(false, true)) {
                for (file in listOf(false, true)) {
                    val decision = decide(manual = manual, completed = completed, file = file, offline = true)
                    assertTrue(
                        decision != LocalPlaybackDecision.OpenSources,
                        "offline manual=$manual completed=$completed file=$file opened the network list",
                    )
                }
            }
        }
    }

    @Test
    fun offlineWithoutADownloadSaysSo() {
        assertEquals(LocalPlaybackDecision.ExplainNotDownloadedOffline, decide(offline = true))
    }

    @Test
    fun offlineWithAMissingFileSaysTheFileIsMissingNotThatItWasNeverDownloaded() {
        assertEquals(
            LocalPlaybackDecision.ExplainFileMissing,
            decide(completed = true, file = false, offline = true),
        )
    }

    @Test
    fun choosingASourceOnlineOpensTheListEvenWhenDownloaded() {
        assertEquals(LocalPlaybackDecision.OpenSources, decide(manual = true, completed = true, file = true))
    }

    @Test
    fun choosingASourceOfflinePlaysTheOnlySourceThereIs() {
        assertEquals(
            LocalPlaybackDecision.PlayLocal,
            decide(manual = true, completed = true, file = true, offline = true),
        )
    }

    @Test
    fun localSourcesAreRecognisedOnEveryPlatformsForm() {
        assertTrue(LocalPlaybackPolicy.isLocalSource("file:/C:/Users/a/AppData/Roaming/Nuvio%20Z/downloads/x.mkv"))
        assertTrue(LocalPlaybackPolicy.isLocalSource("file:///data/user/0/com.nuvio.app/files/downloads/x.mp4"))
        assertTrue(LocalPlaybackPolicy.isLocalSource("/var/mobile/Containers/Data/Application/x/Documents/x.mkv"))
        assertTrue(LocalPlaybackPolicy.isLocalSource("C:\\Users\\a\\downloads\\x.mkv"))
        assertTrue(LocalPlaybackPolicy.isLocalSource("D:/media/x.mkv"))
    }

    @Test
    fun networkSourcesAreNotLocal() {
        kotlin.test.assertFalse(LocalPlaybackPolicy.isLocalSource("https://cdn.example/x.mkv"))
        kotlin.test.assertFalse(LocalPlaybackPolicy.isLocalSource("magnet:?xt=urn:btih:abc"))
        kotlin.test.assertFalse(LocalPlaybackPolicy.isLocalSource(""))
        kotlin.test.assertFalse(LocalPlaybackPolicy.isLocalSource(null))
    }

    @Test
    fun offlineRunContinuesThroughConsecutiveDownloadedEpisodes() {
        val run = LocalPlaybackPolicy.offlineEpisodeRun(
            downloaded = listOf(1 to 3, 1 to 1, 1 to 2, 1 to 4),
            currentSeason = 1,
            currentEpisode = 2,
        )
        assertEquals(listOf(1 to 1, 1 to 2, 1 to 3, 1 to 4), run)
    }

    @Test
    fun offlineRunStopsAtAGapInsteadOfSkippingAnEpisode() {
        val run = LocalPlaybackPolicy.offlineEpisodeRun(
            downloaded = listOf(1 to 1, 1 to 2, 1 to 4, 1 to 5),
            currentSeason = 1,
            currentEpisode = 1,
        )
        assertEquals(listOf(1 to 1, 1 to 2), run)
    }

    @Test
    fun offlineRunCrossesIntoTheNextSeasonAtItsEnd() {
        val run = LocalPlaybackPolicy.offlineEpisodeRun(
            downloaded = listOf(1 to 23, 1 to 24, 2 to 1, 2 to 2),
            currentSeason = 1,
            currentEpisode = 23,
        )
        assertEquals(listOf(1 to 23, 1 to 24, 2 to 1, 2 to 2), run)
    }

    @Test
    fun offlineRunDoesNotJumpSeasonsOverAGapInsideASeason() {
        val run = LocalPlaybackPolicy.offlineEpisodeRun(
            downloaded = listOf(1 to 1, 1 to 3, 2 to 1),
            currentSeason = 1,
            currentEpisode = 1,
        )
        assertEquals(listOf(1 to 1), run)
    }

    @Test
    fun onlineWithAMissingFileFallsBackToStreaming() {
        assertEquals(LocalPlaybackDecision.OpenSources, decide(completed = true, file = false))
    }
}
