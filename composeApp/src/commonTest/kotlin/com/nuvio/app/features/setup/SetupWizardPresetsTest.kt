package com.nuvio.app.features.setup

import com.nuvio.app.features.details.MetaEpisodeCardStyle
import com.nuvio.app.features.details.MetaScreenBackgroundMode
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three looks.
 *
 * A preset is only worth offering if picking it leaves a complete, coherent configuration -
 * and the way that fails in practice is not a crash but a copy-paste: two presets that differ
 * in the field someone remembered and agree in the three they did not.
 */
class SetupWizardPresetsTest {

    private val all = SetupPreset.entries.map { it.values }

    @Test
    fun thePresetsArePairwiseDistinct() {
        assertEquals(all.size, all.toSet().size, "two presets carry identical values")
    }

    @Test
    fun noFieldIsTheSameInAllThreePresets() {
        // A field every preset agrees on is a field the choice does not control. It belongs in
        // the defaults, not here - and if it is here, one of the presets is a copy-paste.
        fun <T> varies(name: String, selector: (SetupPresetValues) -> T) {
            assertTrue(
                all.map(selector).toSet().size > 1,
                "$name is identical across all presets, so choosing a look cannot change it",
            )
        }
        varies("catalogLandscapeMode") { it.catalogLandscapeMode }
        varies("hideLabels") { it.hideLabels }
        varies("posterWidthDp") { it.posterWidthDp }
        varies("posterCornerRadiusDp") { it.posterCornerRadiusDp }
        varies("heroEnabled") { it.heroEnabled }
        varies("continueWatchingStyle") { it.continueWatchingStyle }
        varies("detailsBackgroundMode") { it.detailsBackgroundMode }
        varies("episodeCardStyle") { it.episodeCardStyle }
        varies("detailsTabLayout") { it.detailsTabLayout }
    }

    @Test
    fun everyBackgroundModeIsReachableFromAPreset() {
        // The details background is the option the wizard most wants to demonstrate, so all
        // three of its values need a preset that shows them off.
        assertEquals(
            MetaScreenBackgroundMode.entries.toSet(),
            all.map { it.detailsBackgroundMode }.toSet(),
        )
    }

    @Test
    fun simpleStaysCloseToTheShippedDefaults() {
        // Simple has to be safe to pick without reading anything: posters, labels, no hero,
        // plain backgrounds. If this test starts failing, Simple has stopped being the safe one.
        val simple = SetupPreset.Simple.values
        assertEquals(false, simple.catalogLandscapeMode)
        assertEquals(false, simple.hideLabels)
        assertEquals(false, simple.heroEnabled)
        assertEquals(MetaScreenBackgroundMode.Normal, simple.detailsBackgroundMode)
        assertEquals(false, simple.detailsTabLayout)
        assertEquals(ContinueWatchingSectionStyle.Card, simple.continueWatchingStyle)
    }

    @Test
    fun cinematicIsTheOneThatShowsArtwork() {
        val cinematic = SetupPreset.Cinematic.values
        // Landscape cards are what put the logo overlay on screen, and the blurred backdrop is
        // what the sample title's background image exists to demonstrate.
        assertTrue(cinematic.catalogLandscapeMode)
        assertTrue(cinematic.heroEnabled)
        assertEquals(MetaScreenBackgroundMode.Cinematic, cinematic.detailsBackgroundMode)
        assertEquals(MetaEpisodeCardStyle.Horizontal, cinematic.episodeCardStyle)
    }

    @Test
    fun compactFitsMoreOnScreenThanTheOthers() {
        val compact = SetupPreset.Compact.values
        assertTrue(
            all.all { compact.posterWidthDp <= it.posterWidthDp },
            "Compact must not be wider than any other preset",
        )
        assertEquals(ContinueWatchingSectionStyle.Wide, compact.continueWatchingStyle)
    }

    @Test
    fun posterWidthsAreOfferedByTheSettingsPageToo() {
        // PosterCustomizationSettingsPage offers 104/112/120/126/134/140. A preset that writes
        // a width outside that set leaves the settings page with no chip selected, which reads
        // as the setting having been lost.
        val offered = setOf(104, 112, 120, 126, 134, 140)
        all.forEach { assertTrue(it.posterWidthDp in offered, "${it.posterWidthDp} is not an offered width") }
    }

    @Test
    fun posterRadiiAreOfferedByTheSettingsPageToo() {
        val offered = setOf(0, 4, 8, 12, 16)
        all.forEach { assertTrue(it.posterCornerRadiusDp in offered, "${it.posterCornerRadiusDp} is not an offered radius") }
    }

    // --- matchingSetupPreset ------------------------------------------------------------

    @Test
    fun eachPresetRecognisesItself() {
        SetupPreset.entries.forEach { preset ->
            assertEquals(preset, matchingSetupPreset(preset.values))
        }
    }

    @Test
    fun aHandTunedMixMatchesNothing() {
        // The case that matters: the user picked Cinematic, then turned the hero off two steps
        // later. Still claiming "Cinematic" over a layout they have since changed is worse than
        // claiming nothing.
        val tweaked = SetupPreset.Cinematic.values.copy(heroEnabled = false)
        assertNotEquals(SetupPreset.Cinematic.values, tweaked)
        assertNull(matchingSetupPreset(tweaked))
    }
}
