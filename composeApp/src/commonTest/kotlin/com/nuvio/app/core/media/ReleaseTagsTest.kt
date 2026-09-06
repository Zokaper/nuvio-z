package com.nuvio.app.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The four parse faults this table was extracted to end, as named cases.
 *
 * Each of them shipped, each was invisible from the outside, and each made the auto-picker
 * disagree with the badges drawn beside the very same release.
 */
class ReleaseTagsTest {

    @Test
    fun hdr10PlusIsNotReadAsPlainHdr10() {
        val ranges = ReleaseTags.dynamicRanges(text = "Movie.2160p.UHD.BluRay.HDR10+.x265-GRP")

        assertTrue(ReleaseDynamicRange.HDR10_PLUS in ranges)
        // The old `\bhdr10\+?\b` backtracked to bare `hdr10` here, so HDR10+ scored as HDR10.
        assertFalse(ReleaseDynamicRange.HDR10 in ranges)
    }

    @Test
    fun hdr10PlusSpeltOutIsRecognisedAtAll() {
        // This used to yield an empty set, so an HDR10+ release read as SDR and was demoted
        // *below* a plain HDR one under PREFER_HDR - the reported ranking failure.
        assertEquals(
            setOf(ReleaseDynamicRange.HDR10_PLUS),
            ReleaseTags.dynamicRanges(text = "Movie.2160p.WEB-DL.HDR10Plus.DDP5.1-GRP"),
        )
    }

    @Test
    fun doviIsDolbyVision() {
        val ranges = ReleaseTags.dynamicRanges(text = "Movie.2160p.UHD.BluRay.DoVi.HDR.x265-GRP")

        assertTrue(ReleaseDynamicRange.DOLBY_VISION in ranges)
        assertTrue(ReleaseTags.claimsHdrFamily(ranges))
    }

    @Test
    fun camelotIsNotACamRip() {
        assertEquals("BLURAY", ReleaseTags.releaseQuality("Camelot.1967.1080p.BluRay.x264-GRP"))
        // And a real cam still is one.
        assertEquals("CAM", ReleaseTags.releaseQuality("Some.Movie.2026.CAM.x264-GRP"))
    }

    @Test
    fun bareDvIsTokenBoundedSoItDoesNotHitInsideAWord() {
        assertTrue(ReleaseDynamicRange.DOLBY_VISION in ReleaseTags.dynamicRanges(text = "Movie.2160p.DV.HDR"))
        assertFalse(
            ReleaseDynamicRange.DOLBY_VISION in ReleaseTags.dynamicRanges(text = "Advent.2020.1080p.WEB"),
        )
    }

    @Test
    fun sdrIsAPositiveClaimAndNotAnEmptySet() {
        assertEquals(setOf(ReleaseDynamicRange.SDR), ReleaseTags.dynamicRanges(text = "Movie.1080p.SDR.WEB-DL"))
        // Nothing claimed is not the same as SDR claimed, and both must stay distinguishable:
        // an emptiness test used to stand in for "no HDR" everywhere.
        assertEquals(emptySet<ReleaseDynamicRange>(), ReleaseTags.dynamicRanges(text = "Movie.1080p.WEB-DL"))
    }

    @Test
    fun structuredHdrFieldsAreMatchedExactly() {
        assertEquals(
            setOf(ReleaseDynamicRange.DOLBY_VISION, ReleaseDynamicRange.HDR10_PLUS),
            ReleaseTags.dynamicRanges(structuredValues = listOf("DV", "HDR10+")),
        )
    }

    @Test
    fun losslessAndImmersiveAudioAreDistinguished() {
        val codecs = ReleaseTags.audioCodecs(text = "Movie.2160p.Remux.TrueHD.7.1.Atmos-FGT")

        assertTrue(ReleaseAudioCodec.TRUEHD in codecs)
        assertTrue(ReleaseAudioCodec.ATMOS in codecs)
        assertTrue(codecs.any(ReleaseAudioCodec::isLossless))
        assertTrue(codecs.any(ReleaseAudioCodec::isImmersive))
    }

    @Test
    fun atmosAloneIsImmersiveButNotLossless() {
        // Atmos rides on TrueHD or on DD+, and a release that says only "Atmos" has not said
        // which. Calling it lossless would satisfy a requirement it may not meet.
        val codecs = ReleaseTags.audioCodecs(text = "Movie.2160p.WEB-DL.DDP5.1.Atmos-GRP")

        assertTrue(ReleaseAudioCodec.ATMOS in codecs)
        assertFalse(codecs.any(ReleaseAudioCodec::isLossless))
    }

    @Test
    fun dtsHdMasterAudioIsLossless() {
        val codecs = ReleaseTags.audioCodecs(text = "Movie.1080p.BluRay.DTS-HD.MA.5.1-GRP")

        assertTrue(ReleaseAudioCodec.DTS_HD_MA in codecs)
        assertTrue(codecs.any(ReleaseAudioCodec::isLossless))
    }

    @Test
    fun channelsAreReadAsACount() {
        assertEquals(8, ReleaseTags.channelCount(ReleaseTags.audioChannels(text = "TrueHD.7.1.Atmos")))
        assertEquals(6, ReleaseTags.channelCount(ReleaseTags.audioChannels(text = "DDP5.1")))
        assertEquals(2, ReleaseTags.channelCount(ReleaseTags.audioChannels(text = "AAC.2.0")))
        assertEquals(null, ReleaseTags.channelCount(ReleaseTags.audioChannels(text = "Movie.1080p.WEB-DL")))
    }

    @Test
    fun structuredChannelFieldsAreRead() {
        // `parsed.channels` has been decoded off the wire since StreamParser was written and
        // read by nothing at all until the picker learnt about audio.
        assertEquals(6, ReleaseTags.channelCount(ReleaseTags.audioChannels(structuredValues = listOf("5.1"))))
    }

    @Test
    fun bestDynamicRangeTakesTheStrongestClaim() {
        assertEquals(
            ReleaseDynamicRange.DOLBY_VISION,
            ReleaseTags.bestDynamicRange(setOf(ReleaseDynamicRange.HDR10, ReleaseDynamicRange.DOLBY_VISION)),
        )
        assertEquals(null, ReleaseTags.bestDynamicRange(emptySet()))
    }

    @Test
    fun detectsAiUpscaledReleasesFromText() {
        assertTrue(ReleaseTags.isAiUpscaled("Movie.2024.4320p.8K.AI.Upscale.HEVC-GRP"))
        assertTrue(ReleaseTags.isAiUpscaled("Movie.2024.8K.AI-Upscaled.DDP5.1-GRP"))
        assertTrue(ReleaseTags.isAiUpscaled("Movie.1080p.Topaz.AI.Enhanced.x265"))
        assertTrue(ReleaseTags.isAiUpscaled("Movie.2160p.Upscaled.HDR.HEVC-GRP"))
        assertTrue(ReleaseTags.isAiUpscaled("Classic.Film.1980.AI-Remastered.4K"))
        assertTrue(ReleaseTags.isAiUpscaled("movie.2024.8k.ai.upscaled.hevc"))
        assertTrue(ReleaseTags.isAiUpscaled("Some.Show.S01E01.4K.Topaz.x265"))

        // Negative cases: native releases without AI claims
        assertFalse(ReleaseTags.isAiUpscaled("Movie.2024.4320p.8K.UHD.BluRay.x265-GRP"))
        assertFalse(ReleaseTags.isAiUpscaled("Movie.2024.2160p.4K.Remux.TrueHD.Atmos-GRP"))
        assertFalse(ReleaseTags.isAiUpscaled("The.Daily.Show.2024.1080p.WEB-DL.x264"))
        assertFalse(ReleaseTags.isAiUpscaled("Captain.America.2011.1080p.BluRay.x264"))
        assertFalse(ReleaseTags.isAiUpscaled("Official.Trailer.1080p.H264"))
        assertFalse(ReleaseTags.isAiUpscaled(""))
    }

    @Test
    fun detectsTheatricalCaptures() {
        assertTrue(ReleaseTags.isTheatricalCapture("CAM"))
        assertTrue(ReleaseTags.isTheatricalCapture("HDCAM"))
        assertTrue(ReleaseTags.isTheatricalCapture("CAMRip"))
        assertTrue(ReleaseTags.isTheatricalCapture("TS"))
        assertTrue(ReleaseTags.isTheatricalCapture("Telesync"))
        assertTrue(ReleaseTags.isTheatricalCapture("TC"))
        assertTrue(ReleaseTags.isTheatricalCapture("Telecine"))
        assertTrue(ReleaseTags.isTheatricalCapture("hdcam"))
        assertTrue(ReleaseTags.isTheatricalCapture("telesync"))

        // Negative cases
        assertFalse(ReleaseTags.isTheatricalCapture("BLURAY"))
        assertFalse(ReleaseTags.isTheatricalCapture("REMUX"))
        assertFalse(ReleaseTags.isTheatricalCapture("WEB-DL"))
        assertFalse(ReleaseTags.isTheatricalCapture("WEBRIP"))
        assertFalse(ReleaseTags.isTheatricalCapture("HDTV"))
        assertFalse(ReleaseTags.isTheatricalCapture(null))
    }
}
