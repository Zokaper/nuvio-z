package com.nuvio.app.features.social

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SocialRecoveryTest {

    private val enabled = SocialCapabilities(socialEnabled = true)
    private val healthy = SocialUiState(activeProfileId = "profile", capabilities = enabled)

    @Test
    fun aHealthySurfaceIsLeftAlone() {
        assertFalse(socialNeedsRecovery(healthy, capabilitiesLoaded = true, realtimeOpen = true))
    }

    @Test
    fun theSignInErrorOverACachedFeedIsRecovered() {
        // The reported state: the cached feed, "Offline", and the bridge's failure, left by an activation
        // that ran while the official session was not yet usable.
        val stuck = healthy.copy(isOfflineCache = true, errorMessage = "Sign in to your Nuvio account to use Social.")
        assertTrue(socialNeedsRecovery(stuck, capabilitiesLoaded = true, realtimeOpen = false))
    }

    @Test
    fun anyOneLeftoverIsEnough() {
        assertTrue(socialNeedsRecovery(healthy.copy(errorMessage = "JWT expired"), true, true))
        assertTrue(socialNeedsRecovery(healthy.copy(isOfflineCache = true), true, true))
        assertTrue(socialNeedsRecovery(healthy, capabilitiesLoaded = true, realtimeOpen = false))
    }

    @Test
    fun failedCapabilitiesAreRecoveredEvenThoughTheyReadAsSocialOff() {
        val fallback = SocialUiState(activeProfileId = "profile")
        assertTrue(socialNeedsRecovery(fallback, capabilitiesLoaded = false, realtimeOpen = false))
        assertFalse(
            socialNeedsRecovery(fallback, capabilitiesLoaded = true, realtimeOpen = false),
            "a backend that answered with social off has nothing to recover",
        )
    }

    @Test
    fun nothingIsRecoveredWithoutAProfileOrWhileLoading() {
        assertFalse(socialNeedsRecovery(SocialUiState(), capabilitiesLoaded = false, realtimeOpen = false))
        assertFalse(socialNeedsRecovery(healthy.copy(isLoading = true, isOfflineCache = true), true, false))
    }

    @Test
    fun aChannelBackFromAnOutageFetchesWhatItMissed() {
        // 2026-10-23: the invite was broadcast during the phone's 8 s socket outage and never replayed.
        fun SocialChannelLiveness.run(vararg seen: Pair<Boolean, Boolean>): List<Boolean> {
            var state = this
            return seen.map { (socket, channel) -> state.observe(socket, channel).also { state = it.first }.second }
        }
        // Opening: no fetch here, `openRealtime` does its own after subscribing.
        assertEquals(listOf(false, false, false), SocialChannelLiveness().run(false to false, true to false, true to true))
        // The socket drops and comes back with the channel rejoined: fetch once.
        assertEquals(listOf(false, false, true), SocialChannelLiveness().run(true to true, false to true, true to true))
        // The channel alone is refused and rejoins (the expired-token case).
        assertEquals(listOf(false, false, false, true), SocialChannelLiveness().run(true to true, true to false, false to false, true to true))
        // Staying live is not a return.
        assertEquals(listOf(false, false), SocialChannelLiveness().run(true to true, true to true))
    }
}
