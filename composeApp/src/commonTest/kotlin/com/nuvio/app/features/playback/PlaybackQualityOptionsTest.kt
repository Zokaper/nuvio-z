package com.nuvio.app.features.playback

import com.nuvio.app.features.downloads.DynamicRangePolicy
import com.nuvio.app.features.downloads.SourceFacts
import com.nuvio.app.features.downloads.VideoResolution
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackQualityOptionsTest {

    @Test
    fun offersBandsPerResolutionPlusBestAvailable() {
        // At the 60-minute default: 133 / 26.7 Mbps at 4K, 20 / 6.7 at 1080p, 4.4 at 720p.
        // Each lands in the band its *bitrate* names, not in a third of this title's spread.
        val options = build(
            candidate("4k-remux", VideoResolution.UHD_2160, gigabytes = 60.0),
            candidate("4k-web", VideoResolution.UHD_2160, gigabytes = 12.0),
            candidate("1080-big", VideoResolution.FULL_HD_1080, gigabytes = 9.0),
            candidate("1080-small", VideoResolution.FULL_HD_1080, gigabytes = 3.0),
            candidate("720", VideoResolution.HD_720, gigabytes = 2.0),
        )

        assertEquals(
            listOf("best", "2160_max", "2160_high", "1080_max", "1080_mid", "720_single"),
            options.map { it.id },
        )
    }

    @Test
    fun theSameFileGetsTheSameBandWhateverElseTheTitleOffers() {
        // The complaint absolute bands exist for, stated as a test. A 20 Mbps 4K release is a
        // mid-weight file whether it is the largest thing this title has or the smallest, and
        // under the relative split it was whichever of those the catalogue made it: the top
        // band beside a 6.7 Mbps encode, the bottom band beside a 66 Mbps remux. Same file,
        // same connection, opposite words - which is why nobody could aim at a band.
        fun subject() = candidate("subject", VideoResolution.UHD_2160, gigabytes = 9.0)
        fun bandOfSubject(vararg others: PlaybackSourceCandidate) =
            build(subject(), *others)
                .filter { it.resolution != null }
                .single { row -> row.candidates.any { it.stream.name == "subject" && row.candidates.first() === it } }
                .variant

        assertEquals(
            PlaybackQualityOption.Variant.MID,
            bandOfSubject(candidate("small", VideoResolution.UHD_2160, gigabytes = 3.0)),
        )
        assertEquals(
            PlaybackQualityOption.Variant.MID,
            bandOfSubject(
                candidate("remux", VideoResolution.UHD_2160, gigabytes = 30.0),
                candidate("heavy", VideoResolution.UHD_2160, gigabytes = 14.0),
            ),
        )
    }

    @Test
    fun aWideSpreadFillsSeveralBands() {
        // 2 / 4 / 9 GB an hour is 4.4 / 8.9 / 20 Mbps at 1080p, which crosses both the 8 and
        // the 16 boundary - three real bands, each naming a class of file rather than a third
        // of this title's spread.
        val options = build(
            candidate("big", VideoResolution.FULL_HD_1080, gigabytes = 9.0),
            candidate("middling", VideoResolution.FULL_HD_1080, gigabytes = 4.0),
            candidate("lean", VideoResolution.FULL_HD_1080, gigabytes = 2.0),
        )

        assertEquals(
            listOf("best", "1080_max", "1080_high", "1080_mid"),
            options.map { it.id },
        )
        assertEquals("big", options[1].candidates.first().stream.name)
        assertEquals("middling", options[2].candidates.first().stream.name)
        assertEquals("lean", options[3].candidates.first().stream.name)
    }

    @Test
    fun twoSourcesInsideOneBandAreOneRow() {
        // 4 / 7 GB an hour is 8.9 / 15.6 Mbps - both a good 1080p Blu-ray encode, both inside
        // the same band. The relative split called these "High" and "Low"; that was a label
        // manufactured from a 1.75x gap, and on the next title the same two words meant a
        // 4x one. One row, and the dearer source leads it.
        val options = build(
            candidate("big", VideoResolution.FULL_HD_1080, gigabytes = 7.0),
            candidate("lean", VideoResolution.FULL_HD_1080, gigabytes = 4.0),
        )

        assertEquals(listOf("best", "1080_single"), options.map { it.id })
        assertEquals("big", options[1].candidates.first().stream.name)
        assertTrue(options[1].candidates.any { it.stream.name == "lean" })
    }

    @Test
    fun aBandedBucketNeverProducesExactlyOneRow() {
        // The invariant the collapse guard exists for, and absolute boundaries make it
        // load-bearing where the relative ones made it a formality. The old split derived its
        // boundaries from the bucket's own extremes, so the top and bottom bands were occupied
        // by construction. Fixed boundaries have no such guarantee - everything here lands in
        // Max - and a lone row reading "1080p Max" would be a comparison with nothing to
        // compare against.
        val options = build(
            candidate("top", VideoResolution.FULL_HD_1080, gigabytes = 20.0),
            candidate("also-top", VideoResolution.FULL_HD_1080, gigabytes = 19.0),
        )
        val banded = options.filter { it.resolution != null }

        assertEquals(1, banded.size)
        assertEquals(PlaybackQualityOption.Variant.SINGLE, banded.single().variant)
    }

    @Test
    fun sourcesWithNoCredibleSizeStillRideTheCheapestRow() {
        // A source that reports no size has no figure to be banded by, so it joins the
        // cheapest band that exists rather than inventing a place for itself. Treating its
        // absent bitrate as 0.0 would mint a "Low" row whose only occupant is a file nobody
        // knows the size of, quoting a nominal figure for it.
        val options = build(
            candidate("big", VideoResolution.FULL_HD_1080, gigabytes = 9.0),
            candidate("middling", VideoResolution.FULL_HD_1080, gigabytes = 4.0),
            candidate("lean", VideoResolution.FULL_HD_1080, gigabytes = 2.0),
            candidate("sizeless", VideoResolution.FULL_HD_1080, gigabytes = null),
        )
        val cheapest = options.last()

        assertEquals(PlaybackQualityOption.Variant.MID, cheapest.variant)
        assertTrue(cheapest.candidates.any { it.stream.name == "sizeless" })
        // And nowhere else - it must not head a row of its own.
        assertTrue(options.none { it.variant == PlaybackQualityOption.Variant.LOW })
    }

    @Test
    fun anUnmeasuredConnectionDrawsAMeterButNeverAVerdict() {
        // `defaultMbps` returns 50 for any Wi-Fi and nothing had measured it, yet the sheet
        // scored "May be more than your connection carries" against that guess - a red line
        // under half the catalogue on the strength of a link type.
        val fit = PlaybackQualityOptions.connectionFit(
            requiredMbps = 80.0,
            estimatedMbps = 50.0,
            isEstimateMeasured = false,
        )

        assertNotNull(fit)
        assertFalse(fit.isOverConnection)
        // The meter is still drawn - a rough baseline is useful to compare rows against.
        assertEquals(1.6, fit.loadFraction, 0.001)
    }

    @Test
    fun aRowThatOnlyJustExceedsTheEstimateIsNotFlagged() {
        // `requiredMbps` already carries a third of headroom over the file's own bitrate, and
        // the estimate under it is a lower bound - nothing feeding it can observe more
        // throughput than it asked for. Warning the instant the two crossed flagged rows that
        // play perfectly well, which is what taught the user to ignore the warning.
        val justOver = PlaybackQualityOptions.connectionFit(requiredMbps = 52.0, estimatedMbps = 50.0)
        val clearlyOver = PlaybackQualityOptions.connectionFit(requiredMbps = 90.0, estimatedMbps = 50.0)

        assertFalse(assertNotNull(justOver).isOverConnection)
        assertTrue(assertNotNull(clearlyOver).isOverConnection)
    }

    @Test
    fun aQualityCeilingRemovesWhatItRefusesFromEveryRowIncludingBest() {
        // Best available is the card most people tap and the one whose source can be the most
        // expensive in the catalogue. A ceiling it walked past would not be a ceiling.
        val options = PlaybackQualityOptions.build(
            listOf(
                candidate("remux", VideoResolution.UHD_2160, gigabytes = 30.0),
                candidate("web", VideoResolution.UHD_2160, gigabytes = 9.0),
            ),
            PlaybackSelectionContext(runtimeMinutes = 60, isEpisode = false, qualityCeilingMbps = 40.0),
        )

        assertTrue(options.all { row -> row.candidates.none { it.stream.name == "remux" } })
        assertEquals("web", options.first().candidates.first().stream.name)
    }

    @Test
    fun aQualityCeilingNothingFitsUnderIsIgnoredRatherThanEmptyingTheSheet() {
        // A preference must never become a dead end. If this title has nothing under the
        // ceiling, the honest answer is the catalogue as it is - not an empty sheet and a trip
        // to the source list, which is the outcome the whole mode exists to avoid.
        val options = PlaybackQualityOptions.build(
            listOf(candidate("only-remux", VideoResolution.UHD_2160, gigabytes = 30.0)),
            PlaybackSelectionContext(runtimeMinutes = 60, isEpisode = false, qualityCeilingMbps = 5.0),
        )

        assertEquals("only-remux", options.first().candidates.single().stream.name)
    }

    @Test
    fun aQualityCeilingNeverJudgesASourceThatReportedNoSize() {
        // There is no figure to judge it by, and refusing what cannot be measured would quietly
        // empty a catalogue on the addons that report least.
        val options = PlaybackQualityOptions.build(
            listOf(
                candidate("sizeless", VideoResolution.FULL_HD_1080, gigabytes = null),
                candidate("big", VideoResolution.FULL_HD_1080, gigabytes = 12.0),
            ),
            PlaybackSelectionContext(runtimeMinutes = 60, isEpisode = false, qualityCeilingMbps = 10.0),
        )

        assertTrue(options.any { row -> row.candidates.any { it.stream.name == "sizeless" } })
        assertTrue(options.all { row -> row.candidates.none { it.stream.name == "big" } })
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
        // 9 GB over 60 minutes = 20 Mbps of file; at 75% headroom the line needs 26.7.
        val options = build(candidate("movie", VideoResolution.FULL_HD_1080, gigabytes = 9.0), runtimeMinutes = 60)
        val row = options.single { it.resolution == VideoResolution.FULL_HD_1080 }

        assertEquals(20.0, row.representativeBitrateMbps!!, 0.1)
        assertEquals(26.7, row.requiredMbps!!, 0.2)
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
    fun anEightKLabelOnAThousandEightyPBitrateIsBucketedAsFourK() {
        // The reported sheet: "8K · HDR · 18 GB · Needs 33 Mb/s". 18 GB over ~100 minutes is
        // 24 Mb/s - a 1080p-grade bitrate wearing an 8K label - and the old floor of 8.0 waved
        // it straight through, because nothing legitimately reaches 8K by accident and the
        // check was therefore inert for the one resolution that needed it.
        val options = build(
            candidate("fake-8k", VideoResolution.UHD_4320, gigabytes = 18.0, runtimeMinutes = 100),
            runtimeMinutes = 100,
        )

        assertTrue(options.none { it.resolution == VideoResolution.UHD_4320 })
    }

    @Test
    fun aDemotedEightKIsCalledFourKEverywhere() {
        // Bucketing the source correctly is not enough on its own. `SourceRanking`'s leading key
        // is resolution height descending, so while `facts.resolution` still read UHD_4320 the
        // fake kept the head of Best available and the caption kept saying 8K.
        val options = build(
            candidate("fake-8k", VideoResolution.UHD_4320, gigabytes = 18.0, runtimeMinutes = 100),
            runtimeMinutes = 100,
        )

        assertTrue(
            options.flatMap { it.candidates }.none { it.facts.resolution == VideoResolution.UHD_4320 },
            "the demotion has to reach facts, or ranking and captions contradict the row",
        )
    }

    @Test
    fun theBestAvailableCardIsNotHeadedByAnUpscale() {
        // The screenshot, exactly: an 18 GB "8K" upscale beside a genuine 61 GB 4K remux. Once
        // both read as 4K the resolution key ties and `LARGEST_UNDER_CAP` decides on size, which
        // is the honest answer. Best available is the card most people tap and the one Instant
        // plays, so this is the assertion that matters most.
        val options = build(
            candidate("fake-8k", VideoResolution.UHD_4320, gigabytes = 18.0, runtimeMinutes = 100),
            candidate("real-remux", VideoResolution.UHD_2160, gigabytes = 61.0, runtimeMinutes = 100),
            runtimeMinutes = 100,
        )

        val best = options.first { it.variant == PlaybackQualityOption.Variant.BEST }
        assertEquals("real-remux", best.candidates.first().stream.name)
        // Still reachable behind it - a demotion is not a deletion.
        assertTrue(best.candidates.any { it.stream.name == "fake-8k" })
    }

    @Test
    fun aSourceThatStatedNoResolutionIsNeverRelabelled() {
        // The guard on the rewrite. `effectiveResolution` *infers* a resolution for a source that
        // stated none, capped at 1080p, so the sheet has a row to put it on - but that is a guess,
        // not a correction, and writing it into facts breaks two things at once. `SourceRanking`
        // sorts an unstated resolution at the bottom, so relabelling it to 1080p promotes it over
        // genuinely-labelled 720p releases. And `requiredMbpsFor` tests the bitrate against the
        // ceiling for whatever `facts.resolution` says: 80 Mb/s passes the 150 Mb/s ceiling for
        // an unknown resolution and fails the 50 Mb/s one for 1080p, so the source would head a
        // row and then quote no bandwidth and draw no meter at all.
        val unlabelled = candidate("unlabelled", null, gigabytes = 60.0, runtimeMinutes = 100)
        val options = build(unlabelled, runtimeMinutes = 100)

        val carried = options.flatMap { it.candidates }.first { it.stream.name == "unlabelled" }
        assertNull(carried.facts.resolution, "an inference must not be written back as a fact")
        assertNotNull(
            PlaybackQualityOptions.requiredMbpsFor(
                carried,
                PlaybackSelectionContext(runtimeMinutes = 100, isEpisode = false),
            ),
            "a relabelled source would fail its own plausibility ceiling and quote nothing",
        )
    }

    @Test
    fun aGenuineEightKKeepsItsLabel() {
        // Demote-only, from the other direction. 60 GB over 100 minutes is 80 Mb/s, which is
        // what 8K actually costs, so nothing here is contradicted.
        val options = build(
            candidate("8k-remux", VideoResolution.UHD_4320, gigabytes = 60.0, runtimeMinutes = 100),
            runtimeMinutes = 100,
        )

        assertTrue(options.any { it.resolution == VideoResolution.UHD_4320 })
        assertTrue(
            options.flatMap { it.candidates }.all { it.facts.resolution == VideoResolution.UHD_4320 },
        )
    }

    @Test
    fun aLargeReleaseIsNeverPromotedAboveWhatItClaims() {
        // The guard only demotes. A bloated 1080p remux is still a 1080p file.
        val options = build(candidate("remux", VideoResolution.FULL_HD_1080, gigabytes = 40.0))

        assertEquals(VideoResolution.FULL_HD_1080, options.last().resolution)
    }

    @Test
    fun aSeasonPackNeverHeadsTheHighRow() {
        // The reported Daredevil case: an 85 GB "1080p episode" is a season pack whose
        // torrent-level size covers a dozen files. Ranking sorts by size descending, so
        // without a plausibility ceiling that number heads 1080p High every time and the
        // quoted bandwidth is fiction.
        val pack = candidate("season-pack", VideoResolution.FULL_HD_1080, gigabytes = 85.0, runtimeMinutes = 50)
        val real = candidate("episode", VideoResolution.FULL_HD_1080, gigabytes = 4.0, runtimeMinutes = 50)
        val lean = candidate("web", VideoResolution.FULL_HD_1080, gigabytes = 1.5, runtimeMinutes = 50)

        val high = build(pack, real, lean, runtimeMinutes = 50)
            .single { it.variant == PlaybackQualityOption.Variant.HIGH }

        assertEquals("episode", high.candidates.first().stream.name)
        // 4 GB over 50 minutes is 10.7 Mbps of file, so 14.2 of line - not 302.
        assertEquals(14.2, high.requiredMbps!!, 0.3)
        // Still reachable as a fallback - a pack often resolves to the right file.
        assertEquals("season-pack", high.candidates.last().stream.name)
    }

    @Test
    fun aBucketOfNothingButImplausibleSizesFallsBackToAnApproximateEstimate() {
        val options = build(
            candidate("pack-a", VideoResolution.FULL_HD_1080, gigabytes = 85.0, runtimeMinutes = 50),
            candidate("pack-b", VideoResolution.FULL_HD_1080, gigabytes = 60.0, runtimeMinutes = 50),
            runtimeMinutes = 50,
        )
        val row = options.single { it.resolution == VideoResolution.FULL_HD_1080 }

        assertTrue(row.isEstimateApproximate)
        assertNull(row.representativeSizeBytes)
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

        // 60 GB/h needs 178 Mbps, 9 GB/h needs 27, 3 GB/h needs 9.
        assertEquals("1080_max", PlaybackQualityOptions.highestAffordable(options, 40.0)?.id)
        assertEquals("1080_mid", PlaybackQualityOptions.highestAffordable(options, 12.0)?.id)
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
        // 133 and 26.7 Mbps at 4K, so Max and High.
        val cheaper = options.single { it.variant == PlaybackQualityOption.Variant.HIGH }

        assertEquals(2, cheaper.candidates.size)
        assertEquals("4k-web", cheaper.candidates.first().stream.name)
    }

    @Test
    fun aPinnedResolutionSurvivesAnEstimateThatWouldNowReachHigher() {
        // The churn this exists to stop: episode 1 played 1080p, then a minute of clean
        // playback ratcheted the estimate up, and episode 2 silently became 4K.
        val options = build(
            candidate("4k", VideoResolution.UHD_2160, gigabytes = 20.0),
            candidate("1080", VideoResolution.FULL_HD_1080, gigabytes = 4.0),
            runtimeMinutes = 60,
        )

        assertEquals("2160_single", PlaybackQualityOptions.highestAffordable(options, 500.0)?.id)
        assertEquals(
            "1080_single",
            PlaybackQualityOptions.stickyAffordable(options, pinnedHeight = 1080, estimatedMbps = 500.0)?.id,
        )
    }

    @Test
    fun aPinForAResolutionThisEpisodeDoesNotHaveIsIgnored() {
        val options = build(
            candidate("1080", VideoResolution.FULL_HD_1080, gigabytes = 4.0),
            candidate("720", VideoResolution.HD_720, gigabytes = 2.0),
            runtimeMinutes = 60,
        )

        assertEquals(
            "1080_single",
            PlaybackQualityOptions.stickyAffordable(options, pinnedHeight = 2160, estimatedMbps = 500.0)?.id,
        )
    }

    @Test
    fun aPinIsDroppedRatherThanStalling() {
        // Holding 4K on a connection that can no longer carry it trades churn for buffering,
        // which is the worse of the two.
        val options = build(
            candidate("4k", VideoResolution.UHD_2160, gigabytes = 60.0),
            candidate("720", VideoResolution.HD_720, gigabytes = 2.0),
            runtimeMinutes = 60,
        )

        assertEquals(
            "720_single",
            PlaybackQualityOptions.stickyAffordable(options, pinnedHeight = 2160, estimatedMbps = 12.0)?.id,
        )
    }

    @Test
    fun aPinNeverOverridesTheMeteredCap() {
        val options = build(
            candidate("4k", VideoResolution.UHD_2160, gigabytes = 20.0),
            candidate("720", VideoResolution.HD_720, gigabytes = 2.0),
            runtimeMinutes = 60,
        )

        val picked = PlaybackQualityOptions.stickyAffordable(
            options = options,
            pinnedHeight = 2160,
            estimatedMbps = 500.0,
            maxHeight = 720,
        )
        assertEquals(VideoResolution.HD_720, picked?.resolution)
    }

    @Test
    fun aPinDoesNotResurrectACapNothingFitsUnder() {
        // The refusal guard from `highestAffordable` has to survive the sticky path, or a pin
        // becomes a way to hand a 4K remux to a capped mobile connection.
        val options = build(
            candidate("4k", VideoResolution.UHD_2160, gigabytes = 20.0),
            candidate("1080", VideoResolution.FULL_HD_1080, gigabytes = 4.0),
            runtimeMinutes = 60,
        )

        assertNull(
            PlaybackQualityOptions.stickyAffordable(
                options = options,
                pinnedHeight = 2160,
                estimatedMbps = 500.0,
                maxHeight = 720,
            ),
        )
    }

    @Test
    fun noPinBehavesExactlyLikeHighestAffordable() {
        val options = build(
            candidate("4k", VideoResolution.UHD_2160, gigabytes = 20.0),
            candidate("1080", VideoResolution.FULL_HD_1080, gigabytes = 4.0),
            runtimeMinutes = 60,
        )

        assertEquals(
            PlaybackQualityOptions.highestAffordable(options, 500.0)?.id,
            PlaybackQualityOptions.stickyAffordable(options, pinnedHeight = null, estimatedMbps = 500.0)?.id,
        )
    }

    @Test
    fun aRememberedBandIsMatchedExactly() {
        val options = build(
            candidate("4k", VideoResolution.UHD_2160, gigabytes = 20.0),
            candidate("1080", VideoResolution.FULL_HD_1080, gigabytes = 4.0),
            runtimeMinutes = 60,
        )

        assertEquals(
            "1080_single",
            PlaybackQualityOptions.rememberedOption(options, bandId = "1080_single")?.id,
        )
    }

    @Test
    fun aRememberedBandThisEpisodeLacksAsksRatherThanSubstituting() {
        // The divergence from `stickyAffordable`, and the reason this function exists. That one
        // is a tie-break for the in-player next episode, where nobody is there to answer a sheet
        // and any reasonable source beats stopping - so it falls back. This one decides whether
        // to *skip a question*, and a fallback would be silent substitution: the sheet never
        // appears, so there is nothing on screen for the user to disagree with. Null means ask.
        val options = build(
            candidate("1080", VideoResolution.FULL_HD_1080, gigabytes = 4.0),
            candidate("720", VideoResolution.HD_720, gigabytes = 2.0),
            runtimeMinutes = 60,
        )

        assertNull(PlaybackQualityOptions.rememberedOption(options, bandId = "2160_single"))
        // Same inputs, the other function: it substitutes, on purpose.
        assertNotNull(
            PlaybackQualityOptions.stickyAffordable(options, pinnedHeight = 2160, estimatedMbps = 500.0),
        )
    }

    @Test
    fun aRememberedBandDistinguishesTheVariantNotJustTheResolution() {
        // Someone who chose "1080p Mid" to stay inside a data cap has not chosen "1080p Max".
        // Matching on height alone would hand them the row they were avoiding, silently.
        val options = build(
            candidate("1080-big", VideoResolution.FULL_HD_1080, gigabytes = 12.0),
            candidate("1080-small", VideoResolution.FULL_HD_1080, gigabytes = 2.0),
            runtimeMinutes = 60,
        )
        val low = options.first { it.variant == PlaybackQualityOption.Variant.MID }
        val high = options.first { it.variant == PlaybackQualityOption.Variant.MAX }
        assertTrue(low.id != high.id)

        assertEquals(low.id, PlaybackQualityOptions.rememberedOption(options, bandId = low.id)?.id)
        assertEquals(high.id, PlaybackQualityOptions.rememberedOption(options, bandId = high.id)?.id)
    }

    @Test
    fun noRememberedBandMeansNoAnswer() {
        val options = build(
            candidate("1080", VideoResolution.FULL_HD_1080, gigabytes = 4.0),
            runtimeMinutes = 60,
        )

        assertNull(PlaybackQualityOptions.rememberedOption(options, bandId = null))
        assertNull(PlaybackQualityOptions.rememberedOption(options, bandId = "  "))
        assertNull(PlaybackQualityOptions.rememberedOption(emptyList(), bandId = "1080_single"))
    }

    @Test
    fun anUnmeasuredConnectionGetsNoFit() {
        // No estimate means the sheet has nothing to compare against, and a meter drawn from
        // nothing implies a measurement that was never taken. A zero is the same case: it is
        // what an unmeasured network reports, not a line that carries nothing.
        val option = option(requiredMbps = 20.0)

        assertNull(PlaybackQualityOptions.connectionFit(option, estimatedMbps = null))
        assertNull(PlaybackQualityOptions.connectionFit(option, estimatedMbps = 0.0))
    }

    @Test
    fun bestAvailableGetsNoFit() {
        // Best available deliberately carries no `requiredMbps` - it is whatever ranks first,
        // and quoting a bandwidth for it would be quoting a source it may not open.
        assertNull(
            PlaybackQualityOptions.connectionFit(option(requiredMbps = null), estimatedMbps = 50.0),
        )
    }

    @Test
    fun anOptionUnderTheEstimateIsNotOverTheConnection() {
        val fit = PlaybackQualityOptions.connectionFit(option(requiredMbps = 12.0), estimatedMbps = 24.0)

        assertNotNull(fit)
        assertEquals(0.5, fit.loadFraction, 1e-9)
        assertFalse(fit.isOverConnection)
    }

    @Test
    fun anOptionExactlyAtTheEstimateIsNotOverIt() {
        // The boundary case. `requiredMbps` already carries HEADROOM, so an option costing
        // exactly what the line is thought to carry is the case that headroom exists to cover -
        // warning about it would flag the very thing the estimate says is fine.
        val fit = PlaybackQualityOptions.connectionFit(option(requiredMbps = 24.0), estimatedMbps = 24.0)

        assertNotNull(fit)
        assertEquals(1.0, fit.loadFraction, 1e-9)
        assertFalse(fit.isOverConnection)
    }

    @Test
    fun anAbsurdRatioIsCappedForDisplayButStillReadsAsOver() {
        // A 200 Mbps season pack against an 8 Mbps line is 25x. The meter caps so that every
        // ordinary option stays visible on the same scale; the warning is computed from the
        // real numbers and must not cap with it.
        val fit = PlaybackQualityOptions.connectionFit(option(requiredMbps = 200.0), estimatedMbps = 8.0)

        assertNotNull(fit)
        assertEquals(PlaybackQualityOptions.MAX_LOAD_FRACTION, fit.loadFraction, 1e-9)
        assertTrue(fit.isOverConnection)
        assertEquals(200.0, fit.requiredMbps, 1e-9)
        assertEquals(8.0, fit.estimatedMbps, 1e-9)
    }

    @Test
    fun groupsBandsUnderTheirResolutionHighestFirst() {
        val groups = PlaybackQualityOptions.group(
            build(
                candidate("4k-remux", VideoResolution.UHD_2160, gigabytes = 60.0),
                candidate("4k-web", VideoResolution.UHD_2160, gigabytes = 12.0),
                candidate("1080-big", VideoResolution.FULL_HD_1080, gigabytes = 9.0),
                candidate("1080-mid", VideoResolution.FULL_HD_1080, gigabytes = 4.0),
                candidate("1080-small", VideoResolution.FULL_HD_1080, gigabytes = 2.0),
                candidate("720", VideoResolution.HD_720, gigabytes = 2.0),
            ),
        )

        assertEquals(
            listOf(
                null,
                VideoResolution.UHD_2160,
                VideoResolution.FULL_HD_1080,
                VideoResolution.HD_720,
            ),
            groups.map { it.resolution },
        )
        assertEquals(
            listOf(
                listOf("best"),
                listOf("2160_max", "2160_high"),
                listOf("1080_max", "1080_high", "1080_mid"),
                listOf("720_single"),
            ),
            groups.map { group -> group.options.map { it.id } },
        )
    }

    @Test
    fun bestAvailableIsAGroupOfItsOwn() {
        // It claims no resolution, so grouping by resolution would put it in the same null
        // bucket as anything else that ever does - rendering them as bands of each other.
        val groups = PlaybackQualityOptions.group(
            build(candidate("only", VideoResolution.FULL_HD_1080, gigabytes = 4.0)),
        )

        assertEquals(PlaybackQualityOption.Variant.BEST, groups.first().options.single().variant)
        assertNull(groups.first().resolution)
        assertEquals("", groups.first().resolutionLabel)
    }

    @Test
    fun aResolutionWithOneSourceIsAGroupOfOneBand() {
        // Variant.SINGLE has no band word, so the card is a badge and one row. It must not
        // collapse into the resolution above it.
        val groups = PlaybackQualityOptions.group(
            build(
                candidate("1080", VideoResolution.FULL_HD_1080, gigabytes = 4.0),
                candidate("720", VideoResolution.HD_720, gigabytes = 2.0),
            ),
        )

        val single = groups.single { it.resolution == VideoResolution.HD_720 }
        assertEquals(PlaybackQualityOption.Variant.SINGLE, single.options.single().variant)
        assertEquals("720p", single.resolutionLabel)
    }

    @Test
    fun groupingPreservesEveryOptionAndInventsNone() {
        val options = build(
            candidate("4k", VideoResolution.UHD_2160, gigabytes = 30.0),
            candidate("1080-big", VideoResolution.FULL_HD_1080, gigabytes = 9.0),
            candidate("1080-mid", VideoResolution.FULL_HD_1080, gigabytes = 4.0),
            candidate("1080-small", VideoResolution.FULL_HD_1080, gigabytes = 2.0),
        )

        assertEquals(options, PlaybackQualityOptions.group(options).flatMap { it.options })
    }

    @Test
    fun everyGroupHasADistinctResolutionLabel() {
        // The sheet keys its grid on this label. A collision is a duplicate-key crash in
        // LazyVerticalGrid, not a visual glitch. `qualityLabel` returns "" for null, which is
        // why exactly one group is blank and why the sheet substitutes a constant for it.
        val groups = PlaybackQualityOptions.group(
            build(
                candidate("4k", VideoResolution.UHD_2160, gigabytes = 30.0),
                candidate("1440", VideoResolution.QHD_1440, gigabytes = 12.0),
                candidate("1080", VideoResolution.FULL_HD_1080, gigabytes = 6.0),
                candidate("720", VideoResolution.HD_720, gigabytes = 2.0),
                candidate("sd", VideoResolution.SD, gigabytes = 1.0),
            ),
        )
        val labels = groups.map { it.resolutionLabel }

        assertEquals(labels.size, labels.distinct().size)
        assertEquals(1, labels.count { it.isBlank() })
    }

    @Test
    fun nothingToOfferGroupsToNothing() {
        assertEquals(emptyList<PlaybackQualityGroup>(), PlaybackQualityOptions.group(emptyList()))
    }

    private fun option(requiredMbps: Double?) = PlaybackQualityOption(
        id = "test",
        resolution = VideoResolution.FULL_HD_1080,
        variant = PlaybackQualityOption.Variant.SINGLE,
        requiredMbps = requiredMbps,
        representativeBitrateMbps = null,
        isEstimateApproximate = false,
        representativeSizeBytes = null,
        candidates = emptyList(),
    )

    private fun build(
        vararg candidates: PlaybackSourceCandidate,
        runtimeMinutes: Int? = 60,
    ): List<PlaybackQualityOption> = PlaybackQualityOptions.build(
        candidates.toList(),
        PlaybackSelectionContext(runtimeMinutes = runtimeMinutes, isEpisode = false),
    )

    @Test
    fun bestAvailableDoesNotLeadWithASeasonPack() {
        // The defect `0.4.9-beta` fixed for the banded rows and never applied to this card.
        // `SourceRanking` sorts size descending, so the largest number in the catalogue headed
        // Best available - and 85 GB for one 1080p episode is a season pack, a folder size, or
        // simply wrong. It is still reachable, it just never leads.
        val options = build(
            candidate("season-pack", VideoResolution.FULL_HD_1080, gigabytes = 85.0),
            candidate("real-release", VideoResolution.FULL_HD_1080, gigabytes = 9.0),
        )

        val best = options.first { it.variant == PlaybackQualityOption.Variant.BEST }
        assertEquals("real-release", best.candidates.first().stream.name)
        assertTrue(best.candidates.any { it.stream.name == "season-pack" })
    }

    @Test
    fun bestAvailableFailedQuietlyWhenItLedWithAPack() {
        // Why the ordering mattered more than it looks: `requiredMbpsFor` returns null above
        // the plausibility ceiling, so a card led by a season pack quoted no bandwidth and
        // drew no connection meter. It went silent rather than warning - the ceiling was
        // protecting the label while the pick walked past it.
        val context = PlaybackSelectionContext(isEpisode = true)
        val pack = candidate("season-pack", VideoResolution.FULL_HD_1080, gigabytes = 85.0)
        val real = candidate("real-release", VideoResolution.FULL_HD_1080, gigabytes = 9.0)

        assertNull(PlaybackQualityOptions.requiredMbpsFor(pack, context))
        assertNotNull(PlaybackQualityOptions.requiredMbpsFor(real, context))
    }

    @Test
    fun bestAvailablePrefersEvidenceOfACachedCopy() {
        // Third key, below plausibility and torrent-ness. Two equally plausible releases: the
        // one the provider has confirmed leads, because the alternative is the user reading
        // "not cached" at resolve time on the card they were most likely to tap.
        val options = build(
            candidate(
                "hoped-for",
                VideoResolution.FULL_HD_1080,
                gigabytes = 9.0,
                debridService = "torbox",
            ),
            candidate(
                "known-cached",
                VideoResolution.FULL_HD_1080,
                gigabytes = 9.0,
                debridService = "torbox",
                isDebridReady = true,
            ),
        )

        val best = options.first { it.variant == PlaybackQualityOption.Variant.BEST }
        assertEquals("known-cached", best.candidates.first().stream.name)
    }

    @Test
    fun bestAvailableKeepsTorrentsBehindEverythingElse() {
        // A raw torrent is behind every HTTP and debrid candidate even when it is the largest
        // and the protocol gate would let it through. Best available had no such rule.
        val options = build(
            candidate("torrent", VideoResolution.UHD_2160, gigabytes = 40.0, infoHash = "abc123"),
            candidate("http", VideoResolution.UHD_2160, gigabytes = 20.0),
        )

        val best = options.first { it.variant == PlaybackQualityOption.Variant.BEST }
        assertEquals("http", best.candidates.first().stream.name)
    }

    @Test
    fun anExplicitDynamicRangeChoiceBeatsTheByResolutionDefault() {
        // 1080p defaults to ANY, so an SDR and an HDR release tie there and fall through to
        // size. Asking for HDR has to break that tie - it did not, because `preferencesFor`
        // hardcoded ANY and never saw the setting at all.
        val hdr = candidate("hdr", VideoResolution.FULL_HD_1080, gigabytes = 6.0, hdr = true)
        val sdr = candidate("sdr", VideoResolution.FULL_HD_1080, gigabytes = 9.0)

        val ignored = PlaybackQualityOptions.build(
            listOf(sdr, hdr),
            PlaybackSelectionContext(isEpisode = true),
        ).first { it.variant == PlaybackQualityOption.Variant.BEST }
        assertEquals("sdr", ignored.candidates.first().stream.name)

        val honoured = PlaybackQualityOptions.build(
            listOf(sdr, hdr),
            PlaybackSelectionContext(
                isEpisode = true,
                dynamicRangePolicy = DynamicRangePolicy.PREFER_HDR,
            ),
        ).first { it.variant == PlaybackQualityOption.Variant.BEST }
        assertEquals("hdr", honoured.candidates.first().stream.name)
    }

    @Test
    fun anyMeansNoOpinionRatherThanPreferNothing() {
        // The distinction the composition rule turns on. Left at ANY, the SD row must still
        // avoid HDR and the 4K row must still seek it out - flattening every row to "no
        // preference" would be a silent behaviour change for every user who never opens the
        // setting, which is almost all of them.
        val hdr = candidate("hdr", VideoResolution.UHD_2160, gigabytes = 20.0, hdr = true)
        val sdr = candidate("sdr", VideoResolution.UHD_2160, gigabytes = 20.0)

        val best = PlaybackQualityOptions.build(
            listOf(sdr, hdr),
            PlaybackSelectionContext(isEpisode = true),
        ).first { it.variant == PlaybackQualityOption.Variant.BEST }
        assertEquals("hdr", best.candidates.first().stream.name)
    }

    @Test
    fun aPreferredAudioLanguageLeadsItsRow() {
        // Never populated before 0.5.0-beta, so this preference worked for downloads and did
        // nothing for playback.
        val english = candidate("english", VideoResolution.FULL_HD_1080, gigabytes = 6.0, languages = setOf("EN"))
        val other = candidate("other", VideoResolution.FULL_HD_1080, gigabytes = 9.0, languages = setOf("DE"))

        val best = PlaybackQualityOptions.build(
            listOf(other, english),
            PlaybackSelectionContext(isEpisode = true, preferredAudioLanguage = "en"),
        ).first { it.variant == PlaybackQualityOption.Variant.BEST }
        assertEquals("english", best.candidates.first().stream.name)
    }

    private fun candidate(
        name: String,
        resolution: VideoResolution?,
        gigabytes: Double?,
        runtimeMinutes: Int? = null,
        infoHash: String? = null,
        debridService: String? = null,
        isDebridReady: Boolean? = null,
        hdr: Boolean = false,
        languages: Set<String> = emptySet(),
    ) = PlaybackSourceCandidate(
        stream = StreamItem(
            name = name,
            url = if (infoHash == null) "https://cdn.example/$name.mkv" else null,
            infoHash = infoHash,
            addonName = "addon",
            addonId = "addon",
        ),
        facts = SourceFacts(
            resolution = resolution,
            sizeBytes = gigabytes?.let { (it * 1_000_000_000.0).toLong() },
            durationSeconds = runtimeMinutes?.let { it * 60L },
            debridService = debridService,
            isDebridReady = isDebridReady,
            dynamicRange = if (hdr) setOf("HDR10") else emptySet(),
            languages = languages,
        ),
    )
}
