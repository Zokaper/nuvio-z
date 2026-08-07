package com.nuvio.app.features.playback

import com.nuvio.app.features.downloads.SourceFacts
import com.nuvio.app.features.downloads.VideoResolution
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackQualityOptionsTest {

    @Test
    fun offersHighAndLowPerResolutionPlusBestAvailable() {
        val options = build(
            candidate("4k-remux", VideoResolution.UHD_2160, gigabytes = 60.0),
            candidate("4k-web", VideoResolution.UHD_2160, gigabytes = 12.0),
            candidate("1080-big", VideoResolution.FULL_HD_1080, gigabytes = 9.0),
            candidate("1080-small", VideoResolution.FULL_HD_1080, gigabytes = 3.0),
            candidate("720", VideoResolution.HD_720, gigabytes = 2.0),
        )

        assertEquals(
            listOf("best", "2160_high", "2160_low", "1080_high", "1080_low", "720_single"),
            options.map { it.id },
        )
    }

    @Test
    fun aQualityWithNoSourcesHasNoRow() {
        val options = build(
            candidate("1080", VideoResolution.FULL_HD_1080, gigabytes = 4.0),
            candidate("720", VideoResolution.HD_720, gigabytes = 2.0),
        )

        assertTrue(options.none { it.resolution == VideoResolution.UHD_2160 })
    }

    @Test
    fun aSingleSourceBucketIsOnePlainRow() {
        val options = build(candidate("only-4k", VideoResolution.UHD_2160, gigabytes = 20.0))
        val row = options.single { it.resolution == VideoResolution.UHD_2160 }

        assertEquals(PlaybackQualityOption.Variant.SINGLE, row.variant)
        assertEquals("4K", row.resolutionLabel)
    }

    @Test
    fun nearlyIdenticalSourcesDoNotSplit() {
        // Two 1080p releases within a few percent of each other are not a High and a Low;
        // offering them as one would be a distinction the user cannot act on.
        val options = build(
            candidate("a", VideoResolution.FULL_HD_1080, gigabytes = 4.0),
            candidate("b", VideoResolution.FULL_HD_1080, gigabytes = 4.2),
        )

        assertEquals(PlaybackQualityOption.Variant.SINGLE, options.last().variant)
    }

    @Test
    fun requiredSpeedIsTheFileBitratePlusHeadroom() {
        // 9 GB over 60 minutes = 20 Mbps of file; at 60% headroom the line needs 33.3.
        val options = build(candidate("movie", VideoResolution.FULL_HD_1080, gigabytes = 9.0), runtimeMinutes = 60)
        val row = options.single { it.resolution == VideoResolution.FULL_HD_1080 }

        assertEquals(20.0, row.representativeBitrateMbps!!, 0.1)
        assertEquals(33.3, row.requiredMbps!!, 0.2)
    }

    @Test
    fun aBucketWithNoSizesStillRendersButIsMarkedApproximate() {
        val options = build(candidate("sizeless", VideoResolution.FULL_HD_1080, gigabytes = null))
        val row = options.single { it.resolution == VideoResolution.FULL_HD_1080 }

        assertTrue(row.isEstimateApproximate)
        assertNull(row.representativeBitrateMbps)
        assertNotNull(row.requiredMbps)
    }

    @Test
    fun perSourceDurationBeatsTheTitleRuntime() {
        // An extended cut divided by the theatrical runtime reads as a higher bitrate than
        // it is, so the file's own duration wins when an addon reports one.
        val candidate = PlaybackSourceCandidate(
            stream = StreamItem(name = "extended", url = "https://cdn.example/a.mkv", addonName = "a", addonId = "a"),
            facts = SourceFacts(
                resolution = VideoResolution.FULL_HD_1080,
                sizeBytes = 9_000_000_000,
                durationSeconds = 7_200,
            ),
        )
        val options = PlaybackQualityOptions.build(
            listOf(candidate),
            PlaybackSelectionContext(runtimeMinutes = 30, isEpisode = false),
        )

        // 9 GB over two hours, not over the half hour the title claims.
        assertEquals(10.0, options.last().representativeBitrateMbps!!, 0.1)
    }

    @Test
    fun aMislabelledResolutionIsBucketedByWhatItCosts() {
        // `parseResolution` reads a bare "uhd" out of a display name, so an addon that titles
        // every entry "UHD Streams" used to merely fail a filter. Now it would mint a visible
        // 4K row that plays a 720p file.
        val options = build(
            candidate("fake-4k", VideoResolution.UHD_2160, gigabytes = 0.8, runtimeMinutes = 45),
            runtimeMinutes = 45,
        )

        assertTrue(options.none { it.resolution == VideoResolution.UHD_2160 })
    }

    @Test
    fun aLargeReleaseIsNeverPromotedAboveWhatItClaims() {
        // The guard only demotes. A bloated 1080p remux is still a 1080p file.
        val options = build(candidate("remux", VideoResolution.FULL_HD_1080, gigabytes = 40.0))

        assertEquals(VideoResolution.FULL_HD_1080, options.last().resolution)
    }

    @Test
    fun idsAreStableWhenAddonsAnswerInADifferentOrder() {
        val a = candidate("a", VideoResolution.UHD_2160, gigabytes = 60.0)
        val b = candidate("b", VideoResolution.UHD_2160, gigabytes = 12.0)
        val c = candidate("c", VideoResolution.HD_720, gigabytes = 2.0)

        assertEquals(build(a, b, c).map { it.id }, build(c, b, a).map { it.id })
    }

    @Test
    fun instantTakesTheHighestOptionTheLineCanCarry() {
        val options = build(
            candidate("4k", VideoResolution.UHD_2160, gigabytes = 60.0),
            candidate("1080-big", VideoResolution.FULL_HD_1080, gigabytes = 9.0),
            candidate("1080-small", VideoResolution.FULL_HD_1080, gigabytes = 3.0),
            runtimeMinutes = 60,
        )

        // 60 GB/h needs 222 Mbps, 9 GB/h needs 33, 3 GB/h needs 11.
        assertEquals("1080_high", PlaybackQualityOptions.highestAffordable(options, 50.0)?.id)
        assertEquals("1080_low", PlaybackQualityOptions.highestAffordable(options, 15.0)?.id)
        assertEquals("2160_single", PlaybackQualityOptions.highestAffordable(options, 500.0)?.id)
    }

    @Test
    fun anUnaffordableCatalogueStillPlaysSomething() {
        // Falling through to the source list because every release is large would make
        // Instant stop being instant on exactly the titles where it is most useful.
        val options = build(candidate("4k", VideoResolution.UHD_2160, gigabytes = 60.0), runtimeMinutes = 60)

        assertEquals("2160_single", PlaybackQualityOptions.highestAffordable(options, 1.0)?.id)
    }

    @Test
    fun theMeteredCapIsAResolutionCeiling() {
        val options = build(
            candidate("4k", VideoResolution.UHD_2160, gigabytes = 20.0),
            candidate("720", VideoResolution.HD_720, gigabytes = 2.0),
            runtimeMinutes = 60,
        )

        val picked = PlaybackQualityOptions.highestAffordable(options, 500.0, maxHeight = 720)
        assertEquals(VideoResolution.HD_720, picked?.resolution)
    }

    @Test
    fun aCapNothingFitsUnderRefusesRatherThanIgnoringIt() {
        // Best available is ordered resolution-descending, so falling back to it here would
        // hand a 4K remux to someone on mobile data who asked to be capped at 720p.
        val options = build(
            candidate("4k", VideoResolution.UHD_2160, gigabytes = 20.0),
            candidate("1080", VideoResolution.FULL_HD_1080, gigabytes = 4.0),
            runtimeMinutes = 60,
        )

        assertNull(PlaybackQualityOptions.highestAffordable(options, 500.0, maxHeight = 720))
    }

    @Test
    fun everyOptionCarriesTheWholeBucketAsFallbacks() {
        val options = build(
            candidate("4k-remux", VideoResolution.UHD_2160, gigabytes = 60.0),
            candidate("4k-web", VideoResolution.UHD_2160, gigabytes = 12.0),
        )
        val low = options.single { it.variant == PlaybackQualityOption.Variant.LOW }

        assertEquals(2, low.candidates.size)
        assertEquals("4k-web", low.candidates.first().stream.name)
    }

    private fun build(
        vararg candidates: PlaybackSourceCandidate,
        runtimeMinutes: Int? = 60,
    ): List<PlaybackQualityOption> = PlaybackQualityOptions.build(
        candidates.toList(),
        PlaybackSelectionContext(runtimeMinutes = runtimeMinutes, isEpisode = false),
    )

    private fun candidate(
        name: String,
        resolution: VideoResolution,
        gigabytes: Double?,
        runtimeMinutes: Int? = null,
    ) = PlaybackSourceCandidate(
        stream = StreamItem(
            name = name,
            url = "https://cdn.example/$name.mkv",
            addonName = "addon",
            addonId = "addon",
        ),
        facts = SourceFacts(
            resolution = resolution,
            sizeBytes = gigabytes?.let { (it * 1_000_000_000.0).toLong() },
            durationSeconds = runtimeMinutes?.let { it * 60L },
        ),
    )
}
