package com.nuvio.app.core.network

import com.nuvio.app.features.playback.PlaybackQualityTier
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class NetworkQualityRepositoryTest {
    @AfterTest
    fun tearDown() = NetworkQualityRepository.resetForTest()

    @Test
    fun platformDefaultResolvesConservatively() {
        val expected = when (NetworkQualityPlatform.current().connectionType) {
            NetworkConnectionType.OFFLINE -> PlaybackQualityTier.Data
            NetworkConnectionType.CELLULAR, NetworkConnectionType.UNKNOWN -> PlaybackQualityTier.Low
            NetworkConnectionType.WIFI -> PlaybackQualityTier.Standard
            NetworkConnectionType.ETHERNET -> PlaybackQualityTier.High
        }
        assertEquals(expected, NetworkQualityRepository.resolveTier(PlaybackQualityTier.BuiltIns))
    }

    @Test
    fun passiveSampleUpgradesResolvedTier() {
        NetworkQualityRepository.recordTransfer(bytes = 8_000_000, elapsedMs = 1_000)

        assertEquals(
            PlaybackQualityTier.Ultra,
            NetworkQualityRepository.resolveTier(PlaybackQualityTier.BuiltIns),
        )
        assertEquals(NetworkEstimateConfidence.PASSIVE, NetworkQualityRepository.current().confidence)
    }

    @Test
    fun providerEstimateDoesNotPoisonGenericNetworkEstimate() {
        NetworkQualityRepository.recordTransfer(bytes = 8_000_000, elapsedMs = 1_000)
        NetworkQualityRepository.recordTransfer(bytes = 500_000, elapsedMs = 4_000, providerId = "slow-host")

        val generic = NetworkQualityRepository.current().estimatedMbps
        val slowProvider = NetworkQualityRepository.current("slow-host").estimatedMbps

        assertNotEquals(generic, slowProvider)
        assertEquals(PlaybackQualityTier.Data, NetworkQualityRepository.resolveTier(PlaybackQualityTier.BuiltIns, "slow-host"))
        assertEquals(PlaybackQualityTier.Ultra, NetworkQualityRepository.resolveTier(PlaybackQualityTier.BuiltIns))
    }

    @Test
    fun tinySamplesAreIgnored() {
        NetworkQualityRepository.recordTransfer(bytes = 1_024, elapsedMs = 1_000)
        assertEquals(NetworkEstimateConfidence.PLATFORM_DEFAULT, NetworkQualityRepository.current().confidence)
    }

    @Test
    fun meteredChoiceIsSessionScoped() {
        assertNull(NetworkQualityRepository.meteredChoiceForCurrentNetwork())
        NetworkQualityRepository.rememberMeteredChoice(MeteredPlaybackChoice.CAPPED)
        assertEquals(MeteredPlaybackChoice.CAPPED, NetworkQualityRepository.meteredChoiceForCurrentNetwork())
    }
}
