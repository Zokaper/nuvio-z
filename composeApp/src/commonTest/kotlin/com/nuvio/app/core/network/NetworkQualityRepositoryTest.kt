package com.nuvio.app.core.network

import com.nuvio.app.features.downloads.VideoResolution
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class NetworkQualityRepositoryTest {
    @AfterTest
    fun tearDown() = NetworkQualityRepository.resetForTest()

    @Test
    fun anUnmeasuredConnectionNeverStartsA4kDownload() {
        // resolutionForEstimate feeds Instant's no-dialog download, which never asks. Erring
        // high is right for streaming and wrong for disk, so 2160 needs a real measurement.
        val expected = when (NetworkQualityPlatform.current().connectionType) {
            NetworkConnectionType.OFFLINE -> VideoResolution.SD
            else -> VideoResolution.FULL_HD_1080
        }
        assertEquals(expected, NetworkQualityRepository.resolutionForEstimate())
    }

    @Test
    fun passiveSampleUpgradesTheEstimate() {
        NetworkQualityRepository.recordTransfer(bytes = 80_000_000, elapsedMs = 1_000)

        assertEquals(VideoResolution.UHD_2160, NetworkQualityRepository.resolutionForEstimate())
        assertEquals(NetworkEstimateConfidence.PASSIVE, NetworkQualityRepository.current().confidence)
    }

    @Test
    fun providerEstimateDoesNotPoisonGenericNetworkEstimate() {
        NetworkQualityRepository.recordTransfer(bytes = 80_000_000, elapsedMs = 1_000)
        NetworkQualityRepository.recordTransfer(bytes = 500_000, elapsedMs = 4_000, providerId = "slow-host")

        val generic = NetworkQualityRepository.current().estimatedMbps
        val slowProvider = NetworkQualityRepository.current("slow-host").estimatedMbps

        assertNotEquals(generic, slowProvider)
        assertEquals(VideoResolution.SD, NetworkQualityRepository.resolutionForEstimate("slow-host"))
        assertEquals(VideoResolution.UHD_2160, NetworkQualityRepository.resolutionForEstimate())
    }

    @Test
    fun aPlaybackSampleRaisesTheEstimateButNeverLowersIt() {
        // A stream arrives at the file's own bitrate and no faster, so a clean playback is a
        // lower bound. Smoothing it in would drag the estimate down towards whatever the user
        // last watched, and Instant would lose the top qualities the more it was used.
        NetworkQualityRepository.recordSustainedBitrate(40.0)
        assertEquals(40.0, NetworkQualityRepository.current().estimatedMbps)

        NetworkQualityRepository.recordSustainedBitrate(5.0)
        assertEquals(40.0, NetworkQualityRepository.current().estimatedMbps)

        NetworkQualityRepository.recordSustainedBitrate(60.0)
        assertEquals(60.0, NetworkQualityRepository.current().estimatedMbps)
    }

    @Test
    fun anArmedObservationCountsOnlyOnceConfirmed() {
        NetworkQualityRepository.notePlaybackBitrate(45.0)
        assertEquals(NetworkEstimateConfidence.PLATFORM_DEFAULT, NetworkQualityRepository.current().confidence)

        NetworkQualityRepository.cancelPlaybackObservation()
        NetworkQualityRepository.confirmPlaybackBitrate()
        assertEquals(NetworkEstimateConfidence.PLATFORM_DEFAULT, NetworkQualityRepository.current().confidence)

        NetworkQualityRepository.notePlaybackBitrate(45.0)
        NetworkQualityRepository.confirmPlaybackBitrate()
        assertEquals(45.0, NetworkQualityRepository.current().estimatedMbps)
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
