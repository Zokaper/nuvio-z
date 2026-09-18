package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The ranking half of "Prefer built-in subtitles": a release-name hint, worth a tie-break and
 * nothing more, and exactly zero whenever the preference is off.
 */
class EmbeddedSubtitleRankingTest {

    private data class Candidate(val id: String, val facts: SourceFacts)

    private val webDl1080 = SourceFacts(resolution = VideoResolution.FULL_HD_1080, releaseQuality = "WEB-DL")

    @Test
    fun aNamedSubtitleHintBreaksATieInItsFavour() {
        val plain = Candidate("a-plain", webDl1080)
        val subbed = Candidate("b-subbed", webDl1080.copy(releaseSubtitleLanguages = setOf("en")))

        val ordered = listOf(plain, subbed).sortedWith(comparator(preferEmbedded = "en")).map { it.id }

        assertEquals(listOf("b-subbed", "a-plain"), ordered)
    }

    @Test
    fun theHintIsInvisibleWhenThePreferenceIsOff() {
        // Classic, manual picks and downloads all pass null. The stable url tie-break then decides,
        // exactly as it did before the preference existed.
        val plain = Candidate("a-plain", webDl1080)
        val subbed = Candidate("b-subbed", webDl1080.copy(releaseSubtitleLanguages = setOf("en")))

        val ordered = listOf(subbed, plain).sortedWith(comparator(preferEmbedded = null)).map { it.id }

        assertEquals(listOf("a-plain", "b-subbed"), ordered)
    }

    @Test
    fun aHintNeverBuysAResolutionTier() {
        val sharper = Candidate("sharper", SourceFacts(resolution = VideoResolution.UHD_2160, releaseQuality = "WEB-DL"))
        val subbed = Candidate(
            "subbed",
            webDl1080.copy(releaseSubtitleLanguages = setOf("en"), claimsMultiSubtitles = true),
        )

        val ordered = listOf(subbed, sharper).sortedWith(comparator(preferEmbedded = "en")).map { it.id }

        assertEquals(listOf("sharper", "subbed"), ordered)
    }

    @Test
    fun aHintNeverBuysALanguageTier() {
        val english = Candidate("english", webDl1080.copy(languages = setOf("en"), hasStructuredLanguages = true))
        val hindiSubbed = Candidate(
            "hindi-subbed",
            webDl1080.copy(languages = setOf("hi"), releaseSubtitleLanguages = setOf("en")),
        )

        val ordered = listOf(hindiSubbed, english)
            .sortedWith(comparator(preferEmbedded = "en", preferredAudio = "en"))
            .map { it.id }

        assertEquals(listOf("english", "hindi-subbed"), ordered)
    }

    @Test
    fun scoresNamedAboveUnnamedAboveNothing() {
        val preferences = SourceRankingPreferences(preferredEmbeddedSubtitleLanguage = "en")
        assertEquals(
            SourceRanking.EMBEDDED_SUBTITLES_NAMED,
            SourceRanking.embeddedSubtitleScore(SourceFacts(releaseSubtitleLanguages = setOf("en")), preferences),
        )
        assertEquals(
            SourceRanking.EMBEDDED_SUBTITLES_UNNAMED,
            SourceRanking.embeddedSubtitleScore(SourceFacts(claimsMultiSubtitles = true), preferences),
        )
        assertEquals(0, SourceRanking.embeddedSubtitleScore(SourceFacts(releaseSubtitleLanguages = setOf("fr")), preferences))
        // Sidecar subtitles are external by definition - they earn nothing here.
        assertEquals(0, SourceRanking.embeddedSubtitleScore(SourceFacts(subtitleLanguages = setOf("en")), preferences))
    }

    @Test
    fun burnedInSubtitlesAreNotATrack() {
        val preferences = SourceRankingPreferences(preferredEmbeddedSubtitleLanguage = "en")
        val hardSubbed = SourceFacts(releaseSubtitleLanguages = setOf("en"), isHardSubbed = true)
        assertEquals(0, SourceRanking.embeddedSubtitleScore(hardSubbed, preferences))
    }

    @Test
    fun aSubtitleOnlyReleaseClaimKeepsASourceWatchable() {
        // `VOSTFR` for a French-subtitle user: wrong audio, readable subtitles. The strict gate must
        // not throw it away, and after the audio/subtitle split the claim arrives here as a
        // subtitle language rather than as an audio one.
        val facts = SourceFacts(languages = setOf("it"), releaseSubtitleLanguages = setOf("fr"))
        val preferences = SourceRankingPreferences(preferredAudioLanguage = "fr")
        assertEquals(SourceRanking.SUBTITLES_ONLY, SourceRanking.languageScore(facts, preferences))
    }

    private fun comparator(preferEmbedded: String?, preferredAudio: String? = null) =
        SourceRanking.comparator<Candidate>(
            preferences = SourceRankingPreferences(
                preferredAudioLanguage = preferredAudio,
                preferredEmbeddedSubtitleLanguage = preferEmbedded,
            ),
            midRangeTarget = null,
            factsOf = Candidate::facts,
            isDirectOf = { true },
            addonOrderOf = { 0 },
            stableUrlOf = Candidate::id,
        )
}
