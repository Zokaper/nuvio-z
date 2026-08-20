package com.nuvio.app.features.downloads

import com.nuvio.app.core.media.ReleaseTags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceRankingTest {
    private data class Candidate(
        val id: String,
        val facts: SourceFacts,
        val direct: Boolean = true,
        val order: Int = 0,
    )

    @Test
    fun qualityKeysOutrankCacheAndDirectness() {
        val high = Candidate(
            "high",
            SourceFacts(resolution = VideoResolution.FULL_HD_1080, isDebridReady = false),
            direct = false,
        )
        val low = Candidate(
            "low",
            SourceFacts(resolution = VideoResolution.HD_720, isDebridReady = true),
        )

        assertEquals(listOf("high", "low"), listOf(low, high).sortedWith(comparator()).map { it.id })
    }

    @Test
    fun deterministicTiesUseAddonOrderThenUrl() {
        val facts = SourceFacts(resolution = VideoResolution.FULL_HD_1080, sizeBytes = 1_000)
        val later = Candidate("z", facts, order = 2)
        val firstB = Candidate("b", facts, order = 1)
        val firstA = Candidate("a", facts, order = 1)

        assertEquals(
            listOf("a", "b", "z"),
            listOf(later, firstB, firstA).sortedWith(comparator()).map { it.id },
        )
    }

    /**
     * The reported failure, with the release names it was reported against.
     *
     * *"if I wanted lossless audio plus HDR10, the current preferences might serve me that 88gb
     * one which has no lossless audio."* - and it did. The 95 GB IMAX remux won the HDR key,
     * which sat above everything else that mattered, then won again on size; audio was not
     * parsed at all, so "lossless" never entered the comparison. With the four middle keys
     * added rather than chained, satisfying **both** beats satisfying either.
     */
    @Test
    fun losslessPlusHdrBeatsTheBiggerHdrOnlyRemux() {
        val imaxRemux = Candidate(
            "imax",
            facts(
                "Movie.2026.IMAX.2160p.UHDRemux.HYBRID.HDR.DV.x265.DDP5.1-GRP",
                sizeBytes = 95_000_000_000L,
            ),
        )
        val losslessRemux = Candidate(
            "fgt",
            facts(
                "Movie.2026.2160p.UHD.BluRay.REMUX.HDR10.TrueHD.7.1.Atmos.x265-FGT",
                sizeBytes = 76_000_000_000L,
            ),
        )

        val ordered = listOf(imaxRemux, losslessRemux)
            .sortedWith(
                comparator(
                    SourceRankingPreferences(
                        dynamicRangePolicy = DynamicRangePolicy.PREFER_HDR,
                        audioPreference = AudioPreference.PREFER_LOSSLESS,
                    ),
                ),
            )
            .map { it.id }

        assertEquals(listOf("fgt", "imax"), ordered)
    }

    @Test
    fun unstatedAudioOutranksKnownLossyButNotLossless() {
        val unstated = facts("Movie.2026.1080p.WEB-DL.x264-GRP")
        val lossy = facts("Movie.2026.1080p.WEB-DL.AAC.2.0.x264-GRP")
        val lossless = facts("Movie.2026.1080p.BluRay.FLAC.5.1.x264-GRP")
        val preference = AudioPreference.PREFER_LOSSLESS

        // Release names carry audio only sometimes, so silence must not read as "no lossless
        // track" - that would demote most WEB-DLs for a user who asked for one.
        assertTrue(SourceRanking.audioScore(lossless, preference) > SourceRanking.audioScore(unstated, preference))
        assertTrue(SourceRanking.audioScore(unstated, preference) > SourceRanking.audioScore(lossy, preference))
    }

    @Test
    fun anUnmetRequirementDemotesRatherThanExcludes() {
        val withoutLossless = facts("Movie.2026.1080p.WEB-DL.AAC.2.0-GRP")

        assertEquals(
            SourceRanking.UNSATISFIED_REQUIREMENT,
            SourceRanking.audioScore(withoutLossless, AudioPreference.REQUIRE_LOSSLESS),
        )
        // Still ranked, still reachable through the failure chain - the language gate's rule.
        val ordered = listOf(
            Candidate("lossy", withoutLossless),
            Candidate("lossless", facts("Movie.2026.1080p.BluRay.DTS-HD.MA.5.1-GRP")),
        ).sortedWith(comparator(SourceRankingPreferences(audioPreference = AudioPreference.REQUIRE_LOSSLESS)))

        assertEquals(listOf("lossless", "lossy"), ordered.map { it.id })
    }

    @Test
    fun anyAudioPreferenceScoresEveryCandidateTheSame() {
        val lossless = facts("Movie.2026.1080p.BluRay.TrueHD.7.1-GRP")
        val lossy = facts("Movie.2026.1080p.WEB-DL.AAC.2.0-GRP")

        assertEquals(0, SourceRanking.audioScore(lossless, AudioPreference.ANY))
        assertEquals(0, SourceRanking.audioScore(lossy, AudioPreference.ANY))
        assertEquals(0, SourceRanking.channelScore(lossless, AudioPreference.ANY))
    }

    @Test
    fun requireHdrIsSatisfiedByHdr10PlusAndRefusedBySdr() {
        val hdr10Plus = facts("Movie.2026.2160p.WEB-DL.HDR10Plus-GRP")
        val sdr = facts("Movie.2026.2160p.WEB-DL.SDR-GRP")

        assertEquals(6, SourceRanking.dynamicRangeScore(hdr10Plus, DynamicRangePolicy.REQUIRE_HDR))
        // ⚠ `dynamicRange.isNotEmpty()` used to answer this, and an SDR-tagged release now
        // *has* a member, so the emptiness test would have said yes.
        assertEquals(
            SourceRanking.UNSATISFIED_REQUIREMENT,
            SourceRanking.dynamicRangeScore(sdr, DynamicRangePolicy.REQUIRE_HDR),
        )
    }

    private fun facts(releaseName: String, sizeBytes: Long? = null): SourceFacts =
        SourceFacts(
            resolution = VideoResolution.UHD_2160.takeIf { "2160p" in releaseName }
                ?: VideoResolution.FULL_HD_1080,
            sizeBytes = sizeBytes,
            dynamicRange = ReleaseTags.dynamicRanges(text = releaseName).mapTo(mutableSetOf()) { it.name },
            audioCodecs = ReleaseTags.audioCodecs(text = releaseName).mapTo(mutableSetOf()) { it.name },
            audioChannels = ReleaseTags.channelCount(ReleaseTags.audioChannels(text = releaseName)),
            releaseQuality = ReleaseTags.releaseQuality(releaseName),
        )

    private fun comparator(preferences: SourceRankingPreferences = SourceRankingPreferences()) =
        SourceRanking.comparator<Candidate>(
            preferences = preferences,
            midRangeTarget = null,
            factsOf = Candidate::facts,
            isDirectOf = Candidate::direct,
            addonOrderOf = Candidate::order,
            stableUrlOf = Candidate::id,
        )
}
