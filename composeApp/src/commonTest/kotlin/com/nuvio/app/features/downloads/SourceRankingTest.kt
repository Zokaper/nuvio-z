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

    @Test
    fun anUnrecognisedDynamicRangeStringIsNotAnHdrClaim() {
        // `normalizeDynamicRange` keeps whatever it does not recognise, uppercased, so an addon
        // sending `hdr: ["None"]` produced {"NONE"} - not SDR, and so read as HDR by a `!= SDR`
        // test. A release saying plainly it has no HDR was admitted to a REQUIRE_HDR preset and
        // penalised under AVOID_HDR, while PREFER_HDR scored the same file 0 because it resolves
        // the name first. The two gates have to agree about one file.
        val declaredNone = SourceFacts(dynamicRange = mutableSetOf("NONE"))

        assertEquals(
            SourceRanking.UNSATISFIED_REQUIREMENT,
            SourceRanking.dynamicRangeScore(declaredNone, DynamicRangePolicy.REQUIRE_HDR),
        )
        assertEquals(6, SourceRanking.dynamicRangeScore(declaredNone, DynamicRangePolicy.AVOID_HDR))
        assertEquals(0, SourceRanking.dynamicRangeScore(declaredNone, DynamicRangePolicy.PREFER_HDR))
    }

    @Test
    fun dolbyVisionStillSatisfiesRequireHdr() {
        // `claimsHdr` is deliberately wider than `ReleaseTags.claimsHdrFamily`, which excludes
        // DV for the badge row. Resolving names must not have narrowed it.
        val dolbyVision = facts("Movie.2026.2160p.WEB-DL.DV-GRP")

        assertTrue(SourceRanking.claimsHdr(dolbyVision))
        assertEquals(6, SourceRanking.dynamicRangeScore(dolbyVision, DynamicRangePolicy.REQUIRE_HDR))
    }

    @Test
    fun caseA_8kAiUpscaleVs4kNativeOn4kDisplay_4kNativeWins() {
        val prefs = SourceRankingPreferences(displayMaxHeight = 2160)
        val eightKAi = Candidate("8k_ai", facts("Movie.8K.AI.Upscale.HEVC-GRP"))
        val fourKNative = Candidate("4k_native", facts("Movie.2160p.4K.WEB-DL.x265-GRP"))

        val ordered = listOf(eightKAi, fourKNative).sortedWith(comparator(prefs)).map { it.id }
        assertEquals(listOf("4k_native", "8k_ai"), ordered)
    }

    @Test
    fun caseB_8kNativeVs4kNativeOn4kDisplay_resolutionTierTied_qualityModelDecides() {
        val prefs = SourceRankingPreferences(displayMaxHeight = 2160)
        val eightKNative = facts("Movie.4320p.8K.UHD.BluRay.x265-GRP")
        val fourKNative = facts("Movie.2160p.4K.UHD.BluRay.x265-GRP")

        assertEquals(5, SourceRanking.resolutionTier(eightKNative, prefs))
        assertEquals(5, SourceRanking.resolutionTier(fourKNative, prefs))

        // When 4K has higher quality (Remux vs Web-DL), 4K wins
        val fourKRemux = Candidate("4k_remux", facts("Movie.2160p.4K.Remux.x265-GRP"))
        val eightKWeb = Candidate("8k_web", facts("Movie.4320p.8K.WEB-DL.x265-GRP"))
        val ordered1 = listOf(eightKWeb, fourKRemux).sortedWith(comparator(prefs)).map { it.id }
        assertEquals(listOf("4k_remux", "8k_web"), ordered1)

        // When 8K has higher quality (Remux vs Web-DL), 8K wins
        val eightKRemux = Candidate("8k_remux", facts("Movie.4320p.8K.Remux.x265-GRP"))
        val fourKWeb = Candidate("4k_web", facts("Movie.2160p.4K.WEB-DL.x265-GRP"))
        val ordered2 = listOf(fourKWeb, eightKRemux).sortedWith(comparator(prefs)).map { it.id }
        assertEquals(listOf("8k_remux", "4k_web"), ordered2)
    }

    @Test
    fun caseC_8kAiUpscaleVs4kNativeOn8kDisplay_4kNativePreferred() {
        val prefs = SourceRankingPreferences(displayMaxHeight = 4320)
        val eightKAi = Candidate("8k_ai", facts("Movie.8K.AI.Upscaled.HEVC-GRP"))
        val fourKRemux = Candidate("4k_remux", facts("Movie.2160p.4K.Remux.x265-GRP"))

        val ordered = listOf(eightKAi, fourKRemux).sortedWith(comparator(prefs)).map { it.id }
        assertEquals(listOf("4k_remux", "8k_ai"), ordered)
    }

    @Test
    fun caseD_8kNativeVs4kNativeOn8kDisplay_8kNativeWins() {
        val prefs = SourceRankingPreferences(displayMaxHeight = 4320)
        val eightKNative = Candidate("8k_native", facts("Movie.4320p.8K.UHD.BluRay.x265-GRP"))
        val fourKNative = Candidate("4k_native", facts("Movie.2160p.4K.UHD.BluRay.x265-GRP"))

        assertEquals(6, SourceRanking.resolutionTier(eightKNative.facts, prefs))
        assertEquals(5, SourceRanking.resolutionTier(fourKNative.facts, prefs))

        val ordered = listOf(fourKNative, eightKNative).sortedWith(comparator(prefs)).map { it.id }
        assertEquals(listOf("8k_native", "4k_native"), ordered)
    }

    @Test
    fun caseE_4kAiUpscaleVs1080pNativeOn4kDisplay_penaltiesAppliedAndBalanced() {
        val prefs = SourceRankingPreferences(displayMaxHeight = 2160)
        val native4k = facts("Movie.2160p.4K.WEB-DL.x265-GRP")
        val ai4k = facts("Movie.2160p.4K.Topaz.AI.Upscale.x265-GRP")

        // AI penalty is applied
        assertEquals(SourceRanking.AI_UPSCALE_PENALTY, SourceRanking.aiUpscaleScore(ai4k))
        assertEquals(0, SourceRanking.aiUpscaleScore(native4k))
        assertTrue(SourceRanking.mediaScore(native4k, prefs) > SourceRanking.mediaScore(ai4k, prefs))

        // Same resolution: native always beats AI upscale
        val cNative4k = Candidate("4k_clean", native4k)
        val cAi4k = Candidate("4k_ai", ai4k)
        assertEquals(listOf("4k_clean", "4k_ai"), listOf(cAi4k, cNative4k).sortedWith(comparator(prefs)).map { it.id })

        // At 1080p: clean release beats 1080p AI upscale
        val cNative1080 = Candidate("1080_clean", facts("Movie.1080p.WEB-DL.x264-GRP"))
        val cAi1080 = Candidate("1080_ai", facts("Movie.1080p.AI.Upscale.x264-GRP"))
        assertEquals(listOf("1080_clean", "1080_ai"), listOf(cAi1080, cNative1080).sortedWith(comparator(prefs)).map { it.id })
    }

    @Test
    fun caseF_theatricalCaptureVsStandardReleases_standardAlwaysWins() {
        val cam = Candidate("cam", facts("Movie.2024.1080p.CAM.x264-GRP"))
        val ts = Candidate("ts", facts("Movie.2024.1080p.TELESYNC.x264-GRP"))
        val sd = Candidate("sd", facts("Movie.2024.480p.SD.x264-GRP"))
        val hd1080 = Candidate("1080p", facts("Movie.2024.1080p.WEB-DL.x264-GRP"))
        val uhd4k = Candidate("4k", facts("Movie.2024.2160p.WEB-DL.x265-GRP"))

        val prefs = SourceRankingPreferences()
        assertEquals(SourceRanking.THEATRICAL_CAPTURE_TIER, SourceRanking.resolutionTier(cam.facts, prefs))
        assertEquals(SourceRanking.THEATRICAL_CAPTURE_TIER, SourceRanking.resolutionTier(ts.facts, prefs))

        val ordered = listOf(cam, ts, sd, hd1080, uhd4k).sortedWith(comparator(prefs)).map { it.id }
        assertEquals(listOf("4k", "1080p", "sd"), ordered.take(3))
        // Verify every standard release beats CAM/TS
        assertTrue(ordered.indexOf("sd") < ordered.indexOf("cam"))
        assertTrue(ordered.indexOf("sd") < ordered.indexOf("ts"))
        assertTrue(ordered.indexOf("1080p") < ordered.indexOf("cam"))
        assertTrue(ordered.indexOf("4k") < ordered.indexOf("cam"))
    }

    @Test
    fun caseG_theatricalCaptureOnlyAvailable_camIsSelected() {
        val cam = Candidate("cam", facts("Movie.2024.1080p.CAM.x264-GRP"))
        val ts = Candidate("ts", facts("Movie.2024.720p.TELESYNC.x264-GRP"))

        val ordered = listOf(ts, cam).sortedWith(comparator()).map { it.id }
        // Both are theatrical captures, comparator successfully sorts without error or dropping
        assertEquals(2, ordered.size)
        assertTrue(ordered.contains("cam"))
        assertTrue(ordered.contains("ts"))
    }

    private fun facts(releaseName: String, sizeBytes: Long? = null, isAiUpscaled: Boolean? = null): SourceFacts =
        SourceFacts(
            resolution = when {
                "4320p" in releaseName || "8K" in releaseName || "8k" in releaseName -> VideoResolution.UHD_4320
                "2160p" in releaseName || "4K" in releaseName -> VideoResolution.UHD_2160
                "720p" in releaseName -> VideoResolution.HD_720
                "480p" in releaseName || "SD" in releaseName -> VideoResolution.SD
                else -> VideoResolution.FULL_HD_1080
            },
            sizeBytes = sizeBytes,
            dynamicRange = ReleaseTags.dynamicRanges(text = releaseName).mapTo(mutableSetOf()) { it.name },
            audioCodecs = ReleaseTags.audioCodecs(text = releaseName).mapTo(mutableSetOf()) { it.name },
            audioChannels = ReleaseTags.channelCount(ReleaseTags.audioChannels(text = releaseName)),
            releaseQuality = ReleaseTags.releaseQuality(releaseName),
            isAiUpscaled = isAiUpscaled ?: ReleaseTags.isAiUpscaled(releaseName),
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
