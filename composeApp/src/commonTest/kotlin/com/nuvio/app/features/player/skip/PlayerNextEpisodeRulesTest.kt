package com.nuvio.app.features.player.skip

import com.nuvio.app.features.details.MetaVideo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerNextEpisodeRulesTest {
    @Test
    fun resolvesTheAdjacentEpisodeAcrossASeasonBoundary() {
        val episodes = listOf(
            MetaVideo(id = "s2e1", title = "Next season", season = 2, episode = 1),
            MetaVideo(id = "s1e2", title = "Current", season = 1, episode = 2),
            MetaVideo(id = "s1e1", title = "Previous", season = 1, episode = 1),
        )

        assertEquals(
            "s2e1",
            PlayerNextEpisodeRules.resolveNextEpisode(episodes, currentSeason = 1, currentEpisode = 2)?.id,
        )
    }

    @Test
    fun lastEpisodeHasNoNextEpisode() {
        val episodes = listOf(
            MetaVideo(id = "s1e1", title = "Only", season = 1, episode = 1),
        )

        assertNull(PlayerNextEpisodeRules.resolveNextEpisode(episodes, 1, 1))
    }

    @Test
    fun futureEpisodeIsNotPlayableYet() {
        assertFalse(PlayerNextEpisodeRules.hasEpisodeAired("2999-01-01"))
        assertTrue(PlayerNextEpisodeRules.hasEpisodeAired("2000-01-01"))
    }

    @Test
    fun thresholdUsesConfiguredFallbackWhenNoOutroExists() {
        assertFalse(
            PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
                positionMs = 96_000L,
                durationMs = 100_000L,
                skipIntervals = emptyList(),
                thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
                thresholdPercent = 97f,
                thresholdMinutesBeforeEnd = 2f,
            ),
        )
        assertTrue(
            PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
                positionMs = 97_000L,
                durationMs = 100_000L,
                skipIntervals = emptyList(),
                thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
                thresholdPercent = 97f,
                thresholdMinutesBeforeEnd = 2f,
            ),
        )
    }
}
