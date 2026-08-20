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
        assertEquals(setOf("pt-br"), releaseLanguagesIn("Movie.2024.1080p.LEGENDADO.WEB-DL.mkv").codes)
    }

    @Test
    fun readsSceneWordsThatNameAMarket() {
        assertEquals(setOf("fr"), releaseLanguagesIn("Movie.2024.1080p.VOSTFR.WEB-DL.mkv").codes)
        assertEquals(setOf("fr"), releaseLanguagesIn("Movie.2024.1080p.TRUEFRENCH.BluRay.mkv").codes)
        assertEquals(setOf("pl"), releaseLanguagesIn("Movie.2024.1080p.LEKTOR.PL.WEB-DL.mkv").codes)
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
}
