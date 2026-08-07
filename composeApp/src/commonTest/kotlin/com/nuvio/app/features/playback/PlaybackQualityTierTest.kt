package com.nuvio.app.features.playback

import com.nuvio.app.features.downloads.VideoResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackQualityTierTest {

    @Test
    fun sizeCapSpendsOnlyTheHeadroomShareOfTheLine() {
        // 10 Mbps at 60% for one hour: 10 * 0.6 / 8 = 0.75 MB/s * 3600 = 2.7 GB.
        val tier = PlaybackQualityTier.Standard.copy(megabitsPerSecond = 10.0)
        assertEquals(2_700_000_000L, tier.sizeCapBytes(runtimeMinutes = 60, isEpisode = true))
    }

    @Test
    fun sizeCapUsesTheSameRuntimeFallbacksAsDownloadPresets() {
        val tier = PlaybackQualityTier.Standard.copy(megabitsPerSecond = 10.0)
        val episode = tier.sizeCapBytes(runtimeMinutes = null, isEpisode = true)
        val movie = tier.sizeCapBytes(runtimeMinutes = null, isEpisode = false)
        assertEquals(tier.sizeCapBytes(45, isEpisode = true), episode)
        assertEquals(tier.sizeCapBytes(120, isEpisode = false), movie)
        // A zero or negative runtime is not a runtime.
        assertEquals(episode, tier.sizeCapBytes(runtimeMinutes = 0, isEpisode = true))
    }

    @Test
    fun aFasterTierNeverAcceptsASmallerFile() {
        val caps = PlaybackQualityTier.BuiltIns.map { it.sizeCapBytes(45, isEpisode = true) }
        assertEquals(caps.sortedBy { it }, caps, "BuiltIns must stay ascending by bandwidth")
    }

    @Test
    fun mergeAppendsBuiltInsAnExistingInstallHasNeverSeen() {
        val stored = listOf(PlaybackQualityTier.Standard)
        val merged = PlaybackQualityTier.mergeStoredTiers(stored)
        assertEquals(PlaybackQualityTier.BuiltIns.size, merged.size)
        assertTrue(merged.containsAll(PlaybackQualityTier.BuiltIns.filter { it.id != "tier_1080" }))
    }

    @Test
    fun mergeNeverRewritesAnEditedTier() {
        val edited = PlaybackQualityTier.Standard.copy(megabitsPerSecond = 30.0)
        val merged = PlaybackQualityTier.mergeStoredTiers(listOf(edited))
        assertEquals(30.0, merged.first { it.id == edited.id }.megabitsPerSecond)
    }

    @Test
    fun mergeIsIdempotent() {
        val once = PlaybackQualityTier.mergeStoredTiers(emptyList())
        assertEquals(once, PlaybackQualityTier.mergeStoredTiers(once))
    }

    @Test
    fun anEmptyPinMatchesNothing() {
        assertTrue(StickySourcePin().isEmpty)
        assertNull(
            StickySourcePin().matchStrength("GROUP", "binge", "addon:x", "torbox", 1080),
        )
    }

    @Test
    fun releaseGroupMustMatchWhenThePinNamesOne() {
        val pin = StickySourcePin(releaseGroup = "NTb")
        assertNotNull(pin.matchStrength("ntb", null, null, null, null))
        assertNull(pin.matchStrength("FLUX", null, null, null, null))
        assertNull(pin.matchStrength(null, null, null, null, null))
    }

    @Test
    fun releaseGroupRemainsTheIdentityWhenBingeMetadataChanges() {
        val pin = StickySourcePin(releaseGroup = "NTb", bingeGroup = "episode-1")
        assertNotNull(pin.matchStrength("NTb", "episode-2", "addon", "provider", 1080))
    }

    @Test
    fun bingeGroupCarriesThePinWhenNoReleaseGroupWasParsed() {
        val pin = StickySourcePin(bingeGroup = "torrentio|1080p|hevc")
        assertNotNull(pin.matchStrength(null, "torrentio|1080p|hevc", null, null, null))
        assertNull(pin.matchStrength(null, "torrentio|720p|hevc", null, null, null))
    }

    @Test
    fun theBetterMatchScoresHigherSoCallersCanRankRatherThanTakeTheFirst() {
        val pin = StickySourcePin(
            releaseGroup = "NTb",
            addonId = "addon:torrentio",
            providerId = "torbox",
            resolutionHeight = VideoResolution.FULL_HD_1080.height,
        )
        val exact = pin.matchStrength("NTb", null, "addon:torrentio", "torbox", 1080)
        val groupOnly = pin.matchStrength("NTb", null, "addon:other", "premiumize", 720)
        assertNotNull(exact)
        assertNotNull(groupOnly)
        assertTrue(exact > groupOnly, "exact=$exact groupOnly=$groupOnly")
    }

    /** A season routinely contains one episode the pinned group never released. */
    @Test
    fun aPinThatMatchesNothingIsIgnoredRatherThanBlocking() {
        val pin = StickySourcePin(releaseGroup = "NTb")
        assertNull(pin.matchStrength("FLUX", "other", "addon:other", "torbox", 1080))
    }
}
