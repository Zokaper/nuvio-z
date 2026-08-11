package com.nuvio.app.core.network

import com.nuvio.app.features.downloads.VideoResolution
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
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
    fun aRealMeasurementCanLowerTheEstimateWhereASustainedBitrateCannot() {
        // The fault this whole change exists to fix. The platform default for Wi-Fi is 50, and
        // until now the only playback signal was monotonic - so a connection that had never
        // carried more than 8 Mbps kept being told it could carry fifty.
        NetworkQualityRepository.recordProbeResult(8.0)
        assertEquals(8.0, NetworkQualityRepository.current().estimatedMbps, 1e-9)

        // Blended, not replaced: one window is not the whole story either.
        NetworkQualityRepository.recordMeasuredThroughput(3.0)
        val blended = NetworkQualityRepository.current().estimatedMbps
        assertTrue(blended < 8.0, "a measurement must be able to lower the estimate, got $blended")
        assertTrue(blended > 3.0, "and must not throw the earlier one away, got $blended")
    }

    @Test
    fun aProbeIsReportedAsAProbe() {
        NetworkQualityRepository.recordProbeResult(42.0, providerId = "TorBox")

        // Normalized, so the sheet's "torbox" and a stream's "TorBox" are one host.
        val state = NetworkQualityRepository.current("torbox")
        assertEquals(NetworkEstimateConfidence.PROBED, state.confidence)
        assertTrue(state.isMeasured)
        assertEquals(42.0, state.estimatedMbps, 1e-9)
    }

    @Test
    fun anUnmeasuredConnectionIsNotMeasured() {
        // What the quality sheet reads to decide between a figure and "Checking your
        // connection" - a platform default must never be presented as an observation.
        assertFalse(NetworkQualityRepository.current().isMeasured)
    }

    @Test
    fun peekDoesNotPublish() {
        // The sheet derives its figure during composition from a flow it is also collecting.
        // A read that writes back would be a recomposition loop.
        NetworkQualityRepository.recordProbeResult(30.0, providerId = "fast-host")
        val published = NetworkQualityRepository.uiState.value

        NetworkQualityRepository.peek("some-other-host")

        assertEquals(published, NetworkQualityRepository.uiState.value)
    }

    @Test
    fun theAgeOfOneKeyIsNotTheAgeOfAnother() {
        // `peek` falls back from the host key to the line-wide one so the sheet always has a
        // figure. `estimateAgeMs` deliberately does not: answering "how old is the number you
        // would show" reported an unmeasured host as freshly measured, and it was never probed.
        NetworkQualityRepository.recordProbeResult(30.0)

        assertNotNull(NetworkQualityRepository.estimateAgeMs(null))
        assertNull(
            NetworkQualityRepository.estimateAgeMs("never-measured-host"),
            "an unmeasured host has no age of its own, however fresh the line is",
        )
        // The sheet still shows the line-wide figure for that host - only the probe's freshness
        // question is exact.
        assertEquals(30.0, NetworkQualityRepository.peek("never-measured-host").estimatedMbps, 1e-9)
    }

    @Test
    fun theProbeSkipsAFreshEstimateAndNotAStaleOne() {
        assertNull(NetworkQualityRepository.estimateAgeMs())

        NetworkQualityRepository.recordProbeResult(20.0)

        val age = assertNotNull(NetworkQualityRepository.estimateAgeMs())
        assertTrue(age < NetworkStrengthProbe.FRESH_ESTIMATE_MS, "a just-taken estimate is fresh")
    }

    @Test
    fun anEstimateFromLastWeekIsNotEvidenceAboutTonight() {
        var now = 1_000_000_000L
        NetworkQualityRepository.nowProvider = { now }
        NetworkQualityRepository.recordProbeResult(60.0)
        assertEquals(60.0, NetworkQualityRepository.current().estimatedMbps, 1e-9)

        // Six days on it still stands - a network the app has seen before is worth more than
        // a guess, even if nobody has measured it since.
        now += 6L * 24L * 60L * 60L * 1_000L
        assertEquals(60.0, NetworkQualityRepository.current().estimatedMbps, 1e-9)

        // Eight, and the identity behind it has probably outlived the network it named.
        now += 2L * 24L * 60L * 60L * 1_000L
        assertEquals(
            NetworkEstimateConfidence.PLATFORM_DEFAULT,
            NetworkQualityRepository.current().confidence,
        )
    }

    @Test
    fun aMeasurementSurvivesTheProcessAndComesBackAsCached() {
        // What makes a cold start something other than a guess. Everything measured yesterday
        // was thrown away on every launch, so the first play of every session ran on the
        // connection-type preset no matter how much the app had learned.
        var written: String? = null
        NetworkQualityRepository.saveJson = { written = it }
        NetworkQualityRepository.recordProbeResult(37.0, providerId = "torbox")
        val payload = assertNotNull(written, "a new key must be persisted immediately")

        // A fresh process: nothing in memory, the blob on disk.
        NetworkQualityRepository.resetForTest(restoredAlready = false)
        NetworkQualityRepository.loadJson = { payload }

        val state = NetworkQualityRepository.current("torbox")
        assertEquals(37.0, state.estimatedMbps, 1e-9)
        // Restored, so worth using but not evidence about the network in front of the user
        // right now - which is exactly the distinction the probe's freshness check reads.
        assertEquals(NetworkEstimateConfidence.CACHED, state.confidence)
        assertTrue(state.isMeasured)
    }

    @Test
    fun aCorruptOrEmptyBlobIsNotFatal() {
        NetworkQualityRepository.resetForTest(restoredAlready = false)
        NetworkQualityRepository.loadJson = { "not json at all" }

        assertEquals(
            NetworkEstimateConfidence.PLATFORM_DEFAULT,
            NetworkQualityRepository.current().confidence,
        )
    }

    @Test
    fun meteredChoiceIsSessionScoped() {
        assertNull(NetworkQualityRepository.meteredChoiceForCurrentNetwork())
        NetworkQualityRepository.rememberMeteredChoice(MeteredPlaybackChoice.CAPPED)
        assertEquals(MeteredPlaybackChoice.CAPPED, NetworkQualityRepository.meteredChoiceForCurrentNetwork())
    }
}
