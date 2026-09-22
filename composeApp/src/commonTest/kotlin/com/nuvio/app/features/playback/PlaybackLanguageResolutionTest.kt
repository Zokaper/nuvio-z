package com.nuvio.app.features.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The sentinel resolver, and the defect it closes.
 *
 * `preferredAudioLanguage` ships as the sentinel `device`, and the source picker used to discard
 * every sentinel one call before ranking read it. The visible result was that
 * `LanguageStrictness.REQUIRE` - the shipped default - did nothing whatsoever for any profile that
 * had never opened the language dialog, which is almost all of them. The first two tests below are
 * that regression; the rest pin the rules that keep the fix from overcorrecting into a guess.
 */
class PlaybackLanguageResolutionTest {

    private fun resolve(
        audio: String = "device",
        secondaryAudio: String? = null,
        subtitle: String = "none",
        secondarySubtitle: String? = null,
        device: List<String> = listOf("en"),
        original: String? = null,
    ) = resolveRankableLanguages(
        preferredAudio = audio,
        secondaryAudio = secondaryAudio,
        preferredSubtitle = subtitle,
        secondarySubtitle = secondarySubtitle,
        deviceLanguages = device,
        contentOriginalLanguage = original,
    )

    // --- The regression ------------------------------------------------------------------

    @Test
    fun deviceSentinelResolvesToTheFirstDeviceLanguage() {
        assertEquals("en", resolve(audio = "device", device = listOf("en", "fr")).audio)
    }

    @Test
    fun deviceSentinelWithNoDeviceLanguagesIsNoOpinionRatherThanAGuess() {
        assertNull(resolve(audio = "device", device = emptyList()).audio)
    }

    // --- The anime case ------------------------------------------------------------------

    @Test
    fun originalAudioWithASubtitleLanguageResolvesToBoth() {
        val resolved = resolve(audio = "original", subtitle = "en", original = "ja")
        assertEquals("ja", resolved.audio)
        assertEquals("en", resolved.subtitle)
    }

    /**
     * ⚠ The ranker must not fall back to the device list here, although the *player* does.
     *
     * The player can see the file's actual tracks and is choosing among things that exist. The
     * ranker is choosing which file to open, so a fallback would have it prefer English releases
     * for a user who explicitly asked for whatever the original language was - the opposite of the
     * request, made silently.
     */
    @Test
    fun originalAudioOnATitleOfUnknownLanguageIsNoOpinion() {
        assertNull(resolve(audio = "original", original = null, device = listOf("en")).audio)
    }

    // --- Sentinels that name no language --------------------------------------------------

    @Test
    fun defaultNoneAndForcedAllResolveToNull() {
        assertNull(resolve(audio = "default").audio)
        assertNull(resolve(subtitle = "none").subtitle)
        assertNull(resolve(subtitle = "forced").subtitle)
    }

    @Test
    fun aDeviceListLeadingWithASentinelSkipsItRatherThanAdoptingIt() {
        assertEquals("fr", resolve(audio = "device", device = listOf("default", "fr")).audio)
    }

    // --- Concrete codes ---------------------------------------------------------------------

    @Test
    fun concreteCodesPassThroughNormalized() {
        assertEquals("en", resolve(audio = "eng").audio)
        assertEquals("pt-br", resolve(subtitle = "pob").subtitle)
        assertEquals("es-419", resolve(subtitle = "es-LA").subtitle)
    }

    @Test
    fun secondariesFollowExactlyTheSameRules() {
        val resolved = resolve(
            secondaryAudio = "original",
            secondarySubtitle = "device",
            device = listOf("de"),
            original = "ja",
        )
        assertEquals("ja", resolved.secondaryAudio)
        assertEquals("de", resolved.secondarySubtitle)
    }

    @Test
    fun blankAndUnsetValuesAreNoOpinion() {
        val resolved = resolve(audio = "  ", secondaryAudio = null, subtitle = "", secondarySubtitle = null)
        assertNull(resolved.audio)
        assertNull(resolved.secondaryAudio)
        assertNull(resolved.subtitle)
        assertNull(resolved.secondarySubtitle)
    }

    // --- The one-shot migration ------------------------------------------------------------

    @Test
    fun migrationSettlesTheDeviceSentinelIntoARealCode() {
        assertEquals(
            "fr",
            migratedPreferredAudioLanguage(false, "device", listOf("fr", "en")),
        )
    }

    @Test
    fun migrationTreatsAnUnsetValueAsTheSentinel() {
        assertEquals("en", migratedPreferredAudioLanguage(false, null, listOf("en")))
        assertEquals("en", migratedPreferredAudioLanguage(false, "", listOf("en")))
    }

    @Test
    fun migrationLeavesADeliberateChoiceAlone() {
        assertNull(migratedPreferredAudioLanguage(false, "ja", listOf("en")))
        assertNull(migratedPreferredAudioLanguage(false, "original", listOf("en")))
        assertNull(migratedPreferredAudioLanguage(false, "default", listOf("en")))
    }

    @Test
    fun migrationIsANoOpOnceItHasRun() {
        assertNull(migratedPreferredAudioLanguage(true, "device", listOf("en")))
    }

    /**
     * The device reporting nothing is not a reason to invent a language. The caller still records
     * the migration as done - see `settleDeviceAudioLanguageSentinel` - because re-running it every
     * launch would reach this same answer every time.
     */
    @Test
    fun migrationWithNoDeviceLanguagesWritesNothing() {
        assertNull(migratedPreferredAudioLanguage(false, "device", emptyList()))
    }
}
