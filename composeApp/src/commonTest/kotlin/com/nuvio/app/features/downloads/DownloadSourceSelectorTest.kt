package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadSourceSelectorTest {
    private val addon = AddonSourceKey("a", "https://a/manifest.json")
    private val gb = 1_000_000_000L

    private fun candidate(
        name: String,
        resolution: VideoResolution?,
        size: Long?,
        cached: Boolean? = null,
        debrid: Boolean = false,
        hdr: Boolean = false,
    ): DownloadSourceCandidate {
        val url = "https://a/$name.mkv"
        val stream = StreamItem(url = url, addonName = "a", addonId = "a")
        return DownloadSourceCandidate(
            stream = stream,
            addonKey = addon,
            facts = SourceFacts(
                resolution = resolution,
                sizeBytes = size,
                reportedSizes = listOfNotNull(size),
                isDebridReady = cached,
                dynamicRange = if (hdr) setOf("HDR10") else emptySet(),
            ),
            resolvedUrl = if (debrid) null else url,
            sourceOrigin = if (debrid) DownloadSourceOrigin(stream) else null,
        )
    }

    private fun context(policy: DownloadPolicy, runtime: Int? = 60, playbackRange: DynamicRangePolicy = DynamicRangePolicy.ANY) =
        DownloadSourceSelector.Context(
            policy = policy,
            runtimeMinutes = runtime,
            isEpisode = true,
            rankingPreferences = SourceRankingPreferences(),
            playbackDynamicRange = playbackRange,
            addonFilter = DownloadSourcePolicy(),
        )

    private fun picked(decision: DownloadDecision): String {
        assertIs<DownloadDecision.Pick>(decision)
        return decision.candidate.resolvedUrl ?: decision.candidate.stream.url.orEmpty()
    }

    // Medium at 1080p is 2 GB/h: a 60-minute episode may be up to 2 GB.

    @Test
    fun theBestFileUnderTheLimitAtThePreferredResolutionWins() {
        val decision = DownloadSourceSelector.select(
            listOf(
                candidate("small", VideoResolution.FULL_HD_1080, 1 * gb),
                candidate("fits", VideoResolution.FULL_HD_1080, 19 * gb / 10),
                candidate("remux", VideoResolution.FULL_HD_1080, 12 * gb),
                candidate("uhd", VideoResolution.UHD_2160, 7 * gb),
            ),
            context(DownloadPolicy()),
        )
        assertEquals("https://a/fits.mkv", picked(decision))
    }

    @Test
    fun smallestThatFitsTakesTheSmallest() {
        val decision = DownloadSourceSelector.select(
            listOf(
                candidate("small", VideoResolution.FULL_HD_1080, 1 * gb),
                candidate("fits", VideoResolution.FULL_HD_1080, 19 * gb / 10),
            ),
            context(DownloadPolicy(pickRule = DownloadPickRule.SMALLEST_THAT_FITS)),
        )
        assertEquals("https://a/small.mkv", picked(decision))
    }

    @Test
    fun everythingOverTheLimitAsksAndQuotesTheSmallest() {
        val decision = DownloadSourceSelector.select(
            listOf(
                candidate("big", VideoResolution.FULL_HD_1080, 9 * gb),
                candidate("bigger", VideoResolution.FULL_HD_1080, 12 * gb),
            ),
            context(DownloadPolicy()),
        )
        assertIs<DownloadDecision.NeedsDecision>(decision)
        val reason = decision.reason
        assertIs<DownloadDecisionReason.OverLimit>(reason)
        assertEquals(9 * gb, reason.smallestBytes)
    }

    @Test
    fun anySizeNeverAsksAboutSize() {
        val decision = DownloadSourceSelector.select(
            listOf(candidate("remux", VideoResolution.FULL_HD_1080, 40 * gb)),
            context(DownloadPolicy(sizeLevel = DownloadSizeLevel.ANY)),
        )
        assertEquals("https://a/remux.mkv", picked(decision))
    }

    @Test
    fun aMissingResolutionAsksByDefaultAndNamesBothNeighbours() {
        val decision = DownloadSourceSelector.select(
            listOf(
                candidate("hd", VideoResolution.HD_720, 1 * gb),
                candidate("uhd", VideoResolution.UHD_2160, 5 * gb),
            ),
            context(DownloadPolicy()),
        )
        assertIs<DownloadDecision.NeedsDecision>(decision)
        assertEquals(
            DownloadDecisionReason.ResolutionMissing(preferredHeight = 1080, nearestLowerHeight = 720, nearestHigherHeight = 2160),
            decision.reason,
        )
    }

    @Test
    fun fallingLowerTakesTheNearestLowerResolution() {
        val decision = DownloadSourceSelector.select(
            listOf(
                candidate("sd", VideoResolution.SD, 1 * gb / 2),
                candidate("hd", VideoResolution.HD_720, 1 * gb),
                candidate("uhd", VideoResolution.UHD_2160, 5 * gb),
            ),
            context(DownloadPolicy(resolutionFallback = DownloadResolutionFallback.LOWER)),
        )
        assertEquals("https://a/hd.mkv", picked(decision))
    }

    @Test
    fun fallingHigherCarriesTheSizeLevelToTheNewResolution() {
        // Medium at 4K is 8 GB/h, so a 5 GB 4K file fits a 60-minute episode.
        val decision = DownloadSourceSelector.select(
            listOf(candidate("uhd", VideoResolution.UHD_2160, 5 * gb)),
            context(DownloadPolicy(resolutionFallback = DownloadResolutionFallback.HIGHER)),
        )
        assertEquals("https://a/uhd.mkv", picked(decision))
    }

    @Test
    fun bestAvailableTakesTheHighestResolutionThatFits() {
        val decision = DownloadSourceSelector.select(
            listOf(
                candidate("uhd-remux", VideoResolution.UHD_2160, 60 * gb),
                candidate("fhd", VideoResolution.FULL_HD_1080, 19 * gb / 10),
                candidate("hd", VideoResolution.HD_720, 9 * gb / 10),
            ),
            context(DownloadPolicy(preferredResolution = DownloadResolutionPreference.BEST_AVAILABLE)),
        )
        assertEquals("https://a/fhd.mkv", picked(decision))
    }

    @Test
    fun anUncachedDebridSourceIsSkippedSilentlyForACachedOne() {
        val decision = DownloadSourceSelector.select(
            listOf(
                candidate("uncached", VideoResolution.FULL_HD_1080, 19 * gb / 10, cached = false, debrid = true),
                candidate("cached", VideoResolution.FULL_HD_1080, 15 * gb / 10, cached = true, debrid = true),
            ),
            context(DownloadPolicy()),
        )
        assertIs<DownloadDecision.Pick>(decision)
        assertEquals(true, decision.candidate.facts.isDebridReady)
    }

    @Test
    fun nothingCachedMeansZeroUsableSourcesNotSomeBreakingTheRules() {
        val allUncached = DownloadSourceSelector.select(
            listOf(
                candidate("u1", VideoResolution.FULL_HD_1080, 1 * gb, cached = false, debrid = true),
                candidate("u2", VideoResolution.FULL_HD_1080, 1 * gb, cached = null, debrid = true),
            ),
            context(DownloadPolicy()),
        )
        assertEquals(DownloadDecision.NeedsDecision(DownloadDecisionReason.NothingCached), allUncached)

        val cachedButTooBig = DownloadSourceSelector.select(
            listOf(
                candidate("u1", VideoResolution.FULL_HD_1080, 1 * gb, cached = false, debrid = true),
                candidate("big", VideoResolution.FULL_HD_1080, 9 * gb, cached = true, debrid = true),
            ),
            context(DownloadPolicy()),
        )
        assertIs<DownloadDecision.NeedsDecision>(cachedButTooBig)
        assertIs<DownloadDecisionReason.OverLimit>(cachedButTooBig.reason)
    }

    @Test
    fun anUnknownDebridCacheStateIsNotUsable() {
        assertEquals(
            DownloadCacheEvidence.NOT_USABLE,
            DownloadSourceSelector.cacheEvidence(candidate("x", VideoResolution.FULL_HD_1080, gb, cached = null, debrid = true)),
        )
        assertEquals(
            DownloadCacheEvidence.PLAIN_HTTP,
            DownloadSourceSelector.cacheEvidence(candidate("x", VideoResolution.FULL_HD_1080, gb)),
        )
    }

    @Test
    fun rangeRanksBeforeTheSizeRule() {
        val candidates = listOf(
            candidate("sdr-bigger", VideoResolution.FULL_HD_1080, 19 * gb / 10),
            candidate("hdr-smaller", VideoResolution.FULL_HD_1080, 12 * gb / 10, hdr = true),
        )
        val preferHdr = DownloadSourceSelector.select(candidates, context(DownloadPolicy(range = DownloadRange.PREFER_HDR)))
        assertEquals("https://a/hdr-smaller.mkv", picked(preferHdr))
        val sameAsPlaybackAvoiding = DownloadSourceSelector.select(
            candidates,
            context(DownloadPolicy(), playbackRange = DynamicRangePolicy.AVOID_HDR),
        )
        assertEquals("https://a/sdr-bigger.mkv", picked(sameAsPlaybackAvoiding))
    }

    @Test
    fun noSourcesAtAllIsItsOwnReason() {
        assertEquals(
            DownloadDecision.NeedsDecision(DownloadDecisionReason.NoSources),
            DownloadSourceSelector.select(emptyList(), context(DownloadPolicy())),
        )
    }

    @Test
    fun assistedOffersOneRowPerResolutionAndFlagsARowWhereNothingFits() {
        val options = DownloadSourceSelector.assistedOptions(
            listOf(
                candidate("uhd-remux", VideoResolution.UHD_2160, 60 * gb),
                candidate("uhd-bigger-remux", VideoResolution.UHD_2160, 80 * gb),
                candidate("fhd-a", VideoResolution.FULL_HD_1080, 19 * gb / 10),
                candidate("fhd-b", VideoResolution.FULL_HD_1080, 1 * gb),
                candidate("hd", VideoResolution.HD_720, 9 * gb / 10),
            ),
            context(DownloadPolicy()),
        )
        assertEquals(listOf(2160, 1080, 720), options.map { it.height })
        val uhd = options.first()
        assertTrue(uhd.overLimit)
        assertEquals(60 * gb, uhd.candidate.facts.sizeBytes)
        assertEquals(19 * gb / 10, options[1].candidate.facts.sizeBytes)
    }

    @Test
    fun aSeasonRowTotalsItsEpisodesAndListsTheOnesMissingThatResolution() {
        val context = context(DownloadPolicy())
        val byEpisode = mapOf(
            1 to DownloadSourceSelector.assistedOptions(listOf(candidate("e1", VideoResolution.FULL_HD_1080, gb)), context),
            2 to DownloadSourceSelector.assistedOptions(listOf(candidate("e2", VideoResolution.FULL_HD_1080, gb)), context),
            3 to DownloadSourceSelector.assistedOptions(listOf(candidate("e3", VideoResolution.HD_720, gb / 2)), context),
        )
        val rows = DownloadSourceSelector.seasonRows(byEpisode)
        val fhd = rows.first { it.height == 1080 }
        assertEquals(2 * gb, fhd.totalBytes)
        assertEquals(listOf(3), fhd.missingEpisodes)
        assertNull(rows.firstOrNull { it.height == 2160 })
    }
}
