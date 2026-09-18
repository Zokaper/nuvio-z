package com.nuvio.app.core.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanguageCodesTest {

    @Test
    fun readsThreeLetterCodesTheOldSevenLanguageTableMissed() {
        // `SourceFacts.LANGUAGE_TOKENS` knew en/ar/es/fr/de/ja/ko and nothing else, so a Hindi,
        // Italian or Russian release declared no language at all - and a preference cannot
        // reject what it cannot see.
        assertEquals(setOf("hi"), releaseLanguagesIn("Movie.2024.1080p.HIN.WEB-DL.mkv").codes)
        assertEquals(setOf("it"), releaseLanguagesIn("Movie.2024.1080p.ITA.BluRay.mkv").codes)
        assertEquals(setOf("ru"), releaseLanguagesIn("Movie.2024.1080p.RUS.WEB-DL.mkv").codes)
        assertEquals(setOf("ta"), releaseLanguagesIn("Movie.2024.1080p.TAM.WEB-DL.mkv").codes)
    }

    @Test
    fun multiAndDualAreMarkersNotLanguages() {
        // The whole reason a strict preference is survivable. A MULTI release almost always
        // carries the user's language; excluding it would throw away the best sources on the
        // titles most likely to have them.
        val multi = releaseLanguagesIn("Movie.2024.2160p.MULTi.REMUX.mkv")
        assertTrue(multi.isMulti)
        assertTrue(multi.codes.isEmpty())

        assertTrue(releaseLanguagesIn("Show.S01E01.1080p.DUAL.AUDIO.WEB-DL.mkv").isMulti)
        assertTrue(releaseLanguagesIn("Show.S01E01.1080p.Dual-Audio.mkv").isMulti)
        assertFalse(releaseLanguagesIn("Movie.2024.1080p.WEB-DL.mkv").isMulti)
    }

    @Test
    fun readsFlagEmojiBecauseThatIsHowTorrentioLabelsAudio() {
        // There was no regional-indicator handling anywhere in either repository, so every
        // flag-labelled release read as declaring nothing.
        assertEquals(setOf("en"), releaseLanguagesIn("🇬🇧 Movie 2160p").codes)
        assertEquals(setOf("ja"), releaseLanguagesIn("🇯🇵 Anime S01E01").codes)
        assertEquals(
            setOf("en", "hi"),
            releaseLanguagesIn("🇬🇧🇮🇳 Movie").codes,
        )
    }

    @Test
    fun doesNotReadATitleWordAsALanguage() {
        // ⚠ The reason two-letter codes are refused. `DebridStreamPresentation.hasToken` scans
        // for bare `it`, `de` and `la`, so it has been reading "IT Chapter Two" as Italian and
        // any group with LA in it as Latino. A misread language is worse than none: it decides
        // whether a source is offered.
        assertTrue(releaseLanguagesIn("IT.Chapter.Two.2019.2160p.BluRay.mkv").codes.isEmpty())
        assertTrue(releaseLanguagesIn("De.Palma.2015.1080p.WEB-DL.mkv").codes.isEmpty())
        assertTrue(releaseLanguagesIn("La.La.Land.2016.1080p.BluRay-LA.mkv").codes.isEmpty())
    }

    @Test
    fun doesNotFindALanguageInsideALongerWord() {
        // `ara` inside Sahara, `ita` inside Capitals, `por` inside Portal.
        assertTrue(releaseLanguagesIn("Sahara.2005.1080p.BluRay.mkv").codes.isEmpty())
        assertTrue(releaseLanguagesIn("Capitals.S01.1080p.WEB.mkv").codes.isEmpty())
        assertTrue(releaseLanguagesIn("Portal.2024.1080p.WEB.mkv").codes.isEmpty())
    }

    @Test
    fun keepsTheTwoSpanishesAndTheTwoPortuguesesApart() {
        assertEquals(setOf("es-419"), releaseLanguagesIn("Movie.2024.1080p.LATINO.WEB-DL.mkv").codes)
        assertEquals(setOf("es"), releaseLanguagesIn("Movie.2024.1080p.CASTELLANO.WEB-DL.mkv").codes)
        assertEquals(setOf("pt-br"), releaseLanguagesIn("Movie.2024.1080p.DUBLADO.WEB-DL.mkv").codes)
    }

    @Test
    fun readsSceneWordsThatNameAMarket() {
        assertEquals(setOf("fr"), releaseLanguagesIn("Movie.2024.1080p.TRUEFRENCH.BluRay.mkv").codes)
        assertEquals(setOf("pl"), releaseLanguagesIn("Movie.2024.1080p.LEKTOR.PL.WEB-DL.mkv").codes)
    }

    @Test
    fun subtitleSceneWordsAreSubtitlesNotAudio() {
        // `VOSTFR` is original audio with French subtitles and `LEGENDADO` is original audio with
        // Brazilian subtitles. Both used to be read as the audio language.
        val vostfr = releaseLanguageEvidenceIn("Movie.2024.1080p.VOSTFR.WEB-DL.mkv")
        assertTrue(vostfr.audio.codes.isEmpty())
        assertEquals(setOf("fr"), vostfr.subtitles.codes)

        val legendado = releaseLanguageEvidenceIn("Movie.2024.1080p.LEGENDADO.WEB-DL.mkv")
        assertTrue(legendado.audio.codes.isEmpty())
        assertEquals(setOf("pt-br"), legendado.subtitles.codes)
    }

    @Test
    fun aLanguageNextToSubsIsASubtitleLanguage() {
        val engSubs = releaseLanguageEvidenceIn("Movie.2024.1080p.WEB-DL.HINDI.ENG.SUBS.mkv")
        assertEquals(setOf("hi"), engSubs.audio.codes)
        assertEquals(setOf("en"), engSubs.subtitles.codes)

        // The Italian convention puts the language after the word.
        val subIta = releaseLanguageEvidenceIn("Film.2024.1080p.WEB-DL.ENG.AC3.SUB.ITA.mkv")
        assertEquals(setOf("en"), subIta.audio.codes)
        assertEquals(setOf("it"), subIta.subtitles.codes)

        assertEquals(setOf("en"), releaseLanguageEvidenceIn("Movie.2024.720p.HDRip.Hindi.ESub.mkv").subtitles.codes)
        assertEquals(setOf("en"), releaseLanguageEvidenceIn("Show.S01E01.1080p.EngSub.mkv").subtitles.codes)
    }

    @Test
    fun multiSubsIsNotMultiAudio() {
        val evidence = releaseLanguageEvidenceIn("Movie.2024.2160p.ITA.MultiSubs.mkv")
        assertFalse(evidence.audio.isMulti)
        assertEquals(setOf("it"), evidence.audio.codes)
        assertTrue(evidence.subtitles.isMulti)

        val spaced = releaseLanguageEvidenceIn("Movie.2024.2160p.MULTi.SUBS.mkv")
        assertFalse(spaced.audio.isMulti)
        assertTrue(spaced.subtitles.isMulti)
    }

    @Test
    fun dubbedAndHardSubbedAreMarkers() {
        assertTrue(releaseLanguageEvidenceIn("Anime.S01E01.1080p.DUBBED.WEB.mkv").isDubbed)
        assertTrue(releaseLanguageEvidenceIn("Movie.2024.720p.HC.HDRip.mkv").isHardSubbed)
        assertFalse(releaseLanguageEvidenceIn("Movie.2024.1080p.WEB-DL.mkv").isDubbed)
    }

    @Test
    fun aTitleWordThatMerelyStartsWithSubIsNotASubtitleClaim() {
        val evidence = releaseLanguageEvidenceIn("Submarine.2010.1080p.BluRay.mkv")
        assertTrue(evidence.subtitles.isEmpty)
        assertTrue(releaseLanguageEvidenceIn("[SubsPlease] Frieren - 12 (1080p).mkv").subtitles.isEmpty)
    }

    @Test
    fun collectsEveryLanguageAReleaseNames() {
        assertEquals(
            setOf("en", "fr", "de"),
            releaseLanguagesIn("Movie.2024.2160p.ENG.FRE.GER.REMUX.mkv").codes,
        )
    }

    @Test
    fun structuredValuesStillNormalizeThroughTheAliasTable() {
        // The other half: `AioParsedFile.languages` carries tagged values, where a short code
        // means what it says and `normalizeLanguageCode` is the right reader.
        assertEquals("en", normalizeLanguageCode("eng"))
        assertEquals("ja", normalizeLanguageCode("jpn"))
        assertEquals("pt-br", normalizeLanguageCode("Brazilian Portuguese"))
        assertEquals("es-419", normalizeLanguageCode("Latino"))
    }

    @Test
    fun matchingIsTolerantOfTheRegionSuffix() {
        assertTrue(languageMatchesPreference("pt-BR", "pt"))
        assertTrue(languageMatchesPreference("eng", "en"))
        assertFalse(languageMatchesPreference("hi", "en"))
    }

    @Test
    fun trackerReleaseGroupsDoNotInferRussianFromRutrackerOrRutor() {
        val rutracker = releaseLanguagesIn(
            "Bugonia.2025.Hybrid.UHD.EUR.BluRay.Remux.2160p.DV.HDR.HEVC.TrueHD.Atmos.7.1-RUTRACKER.mkv",
        )
        assertFalse("ru" in rutracker.codes)

        val rutor = releaseLanguagesIn("Movie.2024.1080p.WEB-DL-RUTOR.mkv")
        assertFalse("ru" in rutor.codes)
    }

    @Test
    fun legitimateRussianReleaseTokensAreRecognizedAcrossDelimiters() {
        assertEquals(setOf("ru"), releaseLanguagesIn("Movie.2024.1080p.RU.audio.mkv").codes)
        assertEquals(setOf("ru"), releaseLanguagesIn("Movie.2024.1080p.RU-Audio.mkv").codes)
        assertEquals(setOf("ru"), releaseLanguagesIn("Movie.2024.1080p.RU_Audio.mkv").codes)
        assertEquals(setOf("ru"), releaseLanguagesIn("Movie 2024 1080p RU Audio mkv").codes)
        assertEquals(setOf("ru"), releaseLanguagesIn("Movie.2024.1080p.RUS.mkv").codes)
        assertEquals(setOf("ru"), releaseLanguagesIn("Movie.2024.1080p.Russian.mkv").codes)
    }
}
