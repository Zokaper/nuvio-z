package com.nuvio.app.features.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which modes may be chosen, and what happens to a profile already on one that may not.
 *
 * `0.4.0-beta` shipped a stale "Not ready yet" caption because two files described the modes
 * independently. The machinery that produced it was deleted rather than fixed, so this is new
 * construction - and the point of these cases is that there is exactly **one** predicate.
 */
class PlaybackModeAvailabilityTest {

    @Test
    fun `Instant is withdrawn and the other two are not`() {
        assertFalse(PlaybackMode.INSTANT.isSelectable)
        assertTrue(PlaybackMode.CLASSIC.isSelectable)
        assertTrue(PlaybackMode.STREAMLINED.isSelectable)
    }

    @Test
    fun `a profile stored on Instant behaves as Streamlined`() {
        // Streamlined, not Classic: the source is still chosen for them, they only add one
        // tap for quality. Classic would take away the automatic selection they opted into.
        assertEquals(
            PlaybackMode.STREAMLINED,
            PlaybackMode.coerceSelectable(PlaybackMode.fromStorage("INSTANT")),
        )
    }

    @Test
    fun `coercing leaves the stored value alone`() {
        // The coercion is a read-time view. `fromStorage` must keep answering INSTANT, or the
        // choice is forgotten for good and re-enabling the mode silently leaves those
        // profiles behind.
        assertEquals(PlaybackMode.INSTANT, PlaybackMode.fromStorage("INSTANT"))
    }

    @Test
    fun `a selectable mode passes through untouched`() {
        PlaybackMode.entries.filter { it.isSelectable }.forEach { mode ->
            assertEquals(mode, PlaybackMode.coerceSelectable(mode))
        }
    }

    @Test
    fun `the mode Instant is coerced to is itself selectable`() {
        // Guards the obvious foot-gun in withdrawing a second mode later: a coercion target
        // that is itself unavailable would leave a profile with nothing it can be.
        assertTrue(PlaybackMode.coerceSelectable(PlaybackMode.INSTANT).isSelectable)
    }
}
