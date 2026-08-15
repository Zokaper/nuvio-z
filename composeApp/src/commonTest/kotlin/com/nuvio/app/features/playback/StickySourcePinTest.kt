package com.nuvio.app.features.playback

import com.nuvio.app.features.downloads.VideoResolution
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [StickySourcePin]'s matching rules.
 *
 * The pin is **not wired to anything** as of 0.5.0-beta - the router arm that consumed it was
 * withdrawn because it could only be created from the long-press escape hatch and, once
 * created, silently stopped the quality sheet appearing with no way to see or clear it. The
 * type and these cases are kept deliberately: the matching logic is sound and the feature is
 * deferred pending a surfaced version, not rejected. Deleting them would mean re-deriving
 * descending-strictness matching from scratch.
 *
 * This class also used to cover `PlaybackQualityTier`, which was removed in the same release -
 * quality options are derived from the catalogue by [PlaybackQualityOptions] instead.
 */
class StickySourcePinTest {

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
