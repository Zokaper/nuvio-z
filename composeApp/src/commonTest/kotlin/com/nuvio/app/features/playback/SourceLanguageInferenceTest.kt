package com.nuvio.app.features.playback

import com.nuvio.app.core.language.releaseLanguageEvidenceIn
import com.nuvio.app.features.downloads.SourceFacts
import com.nuvio.app.features.playback.SourceLanguageInference.Basis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Structured metadata, then release tokens, then the title's own original language, then unknown -
 * and never "English because nothing was said".
 */
class SourceLanguageInferenceTest {

    /** What the extractor produces from a release name alone, minus the structured fields. */
    private fun fromReleaseName(name: String): SourceFacts {
        val evidence = releaseLanguageEvidenceIn(name)
        return SourceFacts(
            languages = evidence.audio.codes,
            isMultiLanguage = evidence.audio.isMulti,
            releaseSubtitleLanguages = evidence.subtitles.codes,
            claimsMultiSubtitles = evidence.subtitles.isMulti,
            claimsDubbedAudio = evidence.isDubbed,
            isHardSubbed = evidence.isHardSubbed,
        )
    }

    @Test
    fun englishOriginalWithNoEngTokenReadsEnglish() {
        val audio = SourceLanguageInference.audio(
            fromReleaseName("Heat.1995.2160p.UHD.BluRay.REMUX.HDR.TrueHD.7.1-FGT.mkv"),
            contentOriginalLanguage = "en",
        )
        assertEquals(setOf("en"), audio.codes)
        assertEquals(Basis.CONTENT_ORIGINAL, audio.basis)
    }

    @Test
    fun theSameReleaseWithNoContentLanguageStaysUnknown() {
        // Not "English by default": with nothing to go on, the slot says nothing.
        val audio = SourceLanguageInference.audio(
            fromReleaseName("Heat.1995.2160p.UHD.BluRay.REMUX-FGT.mkv"),
            contentOriginalLanguage = null,
        )
        assertFalse(audio.isKnown)
        assertEquals(Basis.UNKNOWN, audio.basis)
    }

    @Test
    fun foreignOriginalWithNoTokenReadsItsOwnLanguageNotEnglish() {
        val audio = SourceLanguageInference.audio(
            fromReleaseName("[SubsPlease] Frieren - 12 (1080p) [ABCD1234].mkv"),
            contentOriginalLanguage = "ja",
        )
        assertEquals(setOf("ja"), audio.codes)
    }

    @Test
    fun explicitForeignAudioBeatsTheContentLanguage() {
        val audio = SourceLanguageInference.audio(
            fromReleaseName("Heat.1995.1080p.BluRay.ITA.AC3-GRP.mkv"),
            contentOriginalLanguage = "en",
        )
        assertEquals(setOf("it"), audio.codes)
        assertEquals(Basis.RELEASE_NAME, audio.basis)
    }

    @Test
    fun dualAudioNamesBothLanguages() {
        val audio = SourceLanguageInference.audio(
            fromReleaseName("Movie.2024.1080p.WEB-DL.Dual.Audio.ENG.HIN.mkv"),
            contentOriginalLanguage = "hi",
        )
        assertEquals(setOf("en", "hi"), audio.codes)
        assertTrue(audio.isMulti)
    }

    @Test
    fun multiWithoutNamesStaysMultiAndIsNotFilledIn() {
        val audio = SourceLanguageInference.audio(
            fromReleaseName("Movie.2024.2160p.MULTi.REMUX.mkv"),
            contentOriginalLanguage = "fr",
        )
        assertTrue(audio.isMulti)
        assertTrue(audio.codes.isEmpty())
    }

    @Test
    fun subtitleOnlyTokensDoNotBecomeAudio() {
        val facts = fromReleaseName("Heat.1995.1080p.WEB.VOSTFR.mkv")
        val inferred = SourceLanguageInference.infer(facts, contentOriginalLanguage = "en")

        assertEquals(setOf("en"), inferred.audio.codes)
        assertEquals(setOf("fr"), inferred.subtitles.codes)
        assertEquals(Basis.RELEASE_NAME, inferred.subtitles.basis)
    }

    @Test
    fun engSubsIsASubtitleClaim() {
        val facts = fromReleaseName("Movie.2024.1080p.WEB-DL.HIN.ENG.SUBS.mkv")
        val inferred = SourceLanguageInference.infer(facts, contentOriginalLanguage = null)

        assertEquals(setOf("hi"), inferred.audio.codes)
        assertEquals(setOf("en"), inferred.subtitles.codes)
    }

    @Test
    fun aDubbedMarkerContradictsTheOriginalLanguage() {
        val audio = SourceLanguageInference.audio(
            fromReleaseName("Frieren.S01E12.1080p.WEB.DUBBED.mkv"),
            contentOriginalLanguage = "ja",
        )
        assertFalse(audio.isKnown)
    }

    @Test
    fun structuredMetadataOverridesWeakerInference() {
        // Structured says Hindi. The content language says English. Structured wins, and says so.
        val facts = SourceFacts(languages = setOf("hi"), hasStructuredLanguages = true)
        val audio = SourceLanguageInference.audio(facts, contentOriginalLanguage = "en")

        assertEquals(setOf("hi"), audio.codes)
        assertEquals(Basis.STRUCTURED, audio.basis)
    }

    @Test
    fun conflictingReleaseAndContentMetadataFollowTheRelease() {
        // A Japanese title released with an English track named in the file: the file is the
        // better witness for what this particular source carries.
        val audio = SourceLanguageInference.audio(
            fromReleaseName("Your.Name.2016.1080p.BluRay.ENG.mkv"),
            contentOriginalLanguage = "ja",
        )
        assertEquals(setOf("en"), audio.codes)
    }

    @Test
    fun sidecarSubtitlesAreStructured() {
        val subtitles = SourceLanguageInference.subtitles(SourceFacts(subtitleLanguages = setOf("en")))
        assertEquals(Basis.STRUCTURED, subtitles.basis)
    }

    @Test
    fun subtitlesNeverFallBackToTheContentLanguage() {
        val inferred = SourceLanguageInference.infer(SourceFacts(), contentOriginalLanguage = "en")
        assertFalse(inferred.subtitles.isKnown)
    }

    @Test
    fun contentLanguageCodesAreNormalizedConservatively() {
        assertEquals("en", SourceLanguageInference.contentLanguageCode("en"))
        assertEquals("en", SourceLanguageInference.contentLanguageCode("English"))
        assertEquals("en", SourceLanguageInference.contentLanguageCode("English, Spanish"))
        assertEquals("ja", SourceLanguageInference.contentLanguageCode("jpn"))
        assertNull(SourceLanguageInference.contentLanguageCode(""))
        assertNull(SourceLanguageInference.contentLanguageCode("xx"))
        assertNull(SourceLanguageInference.contentLanguageCode("No Language"))
    }

    @Test
    fun theBandPrintsTheInferredPair() {
        val names = mapOf("en" to "English", "fr" to "French")
        val label = PlaybackLoadingFacts.languagePairLabel(
            fromReleaseName("Heat.1995.1080p.WEB.VOSTFR.mkv"),
            contentLanguage = "en",
        ) { names.getValue(it) }

        assertEquals("English / French", label)
    }
}
