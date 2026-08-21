package com.nuvio.app.features.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which modes may be chosen, and what happens to a profile already on one that may not.
 *
 * `0.4.0-beta` shipped a stale "Not ready yet" caption because two files described the modes
 * independently. The machinery that produced it was deleted rather than fixed, so this is new
 * construction - and the point of these cases is that there is exactly **one** predicate.
 *
 * These cases were written to pin Instant's withdrawal and they inverted when it came back.
 * That is the tripwire working: re-enabling a mode is a deliberate change to this file, not
 * something a boolean flip does quietly on the way past.
 */
class PlaybackModeAvailabilityTest {

    @Test
    fun `all three modes ship`() {
        PlaybackMode.entries.forEach { mode ->
            assertTrue(mode.isSelectable, "$mode should be selectable")
        }
    }

    @Test
    fun `a profile stored on Instant is on Instant again`() {
        // The whole point of coercing at read time rather than rewriting storage: a profile
        // that chose Instant before `0.4.10-beta` withheld it was shown Streamlined for two
        // releases with its stored key untouched, and comes back on its own now.
        assertEquals(
            PlaybackMode.INSTANT,
            PlaybackMode.coerceSelectable(PlaybackMode.fromStorage("INSTANT")),
        )
    }

    @Test
    fun `coercing leaves the stored value alone`() {
        // `fromStorage` must keep answering INSTANT whether or not it is selectable, or the
        // choice is forgotten for good and the next withdrawal leaves those profiles behind.
        assertEquals(PlaybackMode.INSTANT, PlaybackMode.fromStorage("INSTANT"))
    }

    @Test
    fun `a selectable mode passes through untouched`() {
        PlaybackMode.entries.filter { it.isSelectable }.forEach { mode ->
            assertEquals(mode, PlaybackMode.coerceSelectable(mode))
        }
    }

    @Test
    fun `the coercion target is itself selectable`() {
        // Guards the obvious foot-gun in withdrawing a mode later: a coercion target that is
        // itself unavailable would leave a profile with nothing it can be.
        assertTrue(PlaybackMode.coerceSelectable(PlaybackMode.INSTANT).isSelectable)
    }
}
