package com.nuvio.app.features.playback

import com.nuvio.app.features.setup.PlaybackSetupVariant
import com.nuvio.app.features.setup.playbackSetupVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The other half of the setup wizard's playback branch.
 *
 * `playbackSetupVariant` takes a mode **name** because `SetupWizardSteps.kt` is import-free and
 * has to compile outside Gradle - which means the pure suite can only ever assert the three
 * names somebody typed into it. This file is where the enum and the rule meet: it walks the
 * real [PlaybackMode] entries, so **adding a mode without giving it a variant fails here**
 * rather than in front of a user standing on a step the wizard cannot fill.
 *
 * That failure mode is not hypothetical. `0.4.0-beta` shipped a stale "Not ready yet" caption on
 * Instant precisely because two files described the modes independently and only one was fixed.
 */
class PlaybackSetupVariantCoverageTest {

    @Test
    fun everyPlaybackModeHasADeliberateVariant() {
        val expected = mapOf(
            PlaybackMode.CLASSIC to PlaybackSetupVariant.None,
            PlaybackMode.STREAMLINED to PlaybackSetupVariant.QualityBand,
            PlaybackMode.INSTANT to PlaybackSetupVariant.AutomaticBand,
        )
        assertEquals(
            PlaybackMode.entries.toSet(),
            expected.keys,
            "a PlaybackMode was added or removed without deciding what the wizard should ask for it",
        )
        expected.forEach { (mode, variant) ->
            assertEquals(variant, playbackSetupVariant(mode.name), mode.name)
        }
    }

    @Test
    fun everySelectableModeIsReachableFromTheWizard() {
        // The wizard draws every mode and enables it from `isSelectable` alone. If a mode is
        // selectable it must have a variant that is at least defined - None is a legitimate
        // answer (Classic), an undefined one is not.
        PlaybackMode.entries.filter { it.isSelectable }.forEach { mode ->
            assertTrue(
                playbackSetupVariant(mode.name) in PlaybackSetupVariant.entries,
                "${mode.name} is selectable but has no defined setup variant",
            )
        }
    }

    @Test
    fun aModeNameStorageWouldRejectAsksNothing() {
        // `PlaybackMode.fromStorage` resolves an unrecognised stored value to CLASSIC, and
        // CLASSIC asks nothing. The wizard must agree with storage rather than guess.
        val unknown = "A_MODE_THAT_NEVER_SHIPPED"
        assertEquals(PlaybackMode.Default, PlaybackMode.fromStorage(unknown))
        assertEquals(PlaybackSetupVariant.None, playbackSetupVariant(unknown))
    }
}
