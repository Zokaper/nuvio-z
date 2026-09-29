package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopNavigationTrackWidthTest {

    /** "Downloads" in `labelLarge` medium at 1.0 zoom measures about 70 dp; the rest are shorter. */
    private val downloadsDp = 71f

    @Test
    fun shortLabelsKeepUpstreamsWidth() {
        assertEquals(64f, desktopNavigationLabelAllowanceDp(40f))
        assertEquals(64f, desktopNavigationLabelAllowanceDp(68f))
    }

    @Test
    fun theWidestLabelSetsTheAllowance() {
        assertTrue(desktopNavigationLabelAllowanceDp(downloadsDp) > 64f)
    }

    @Test
    fun downloadsFitsAtNormalDesktopWidthsWithAndWithoutSocial() {
        // Home, Search, Library, Downloads, (Social), Settings - at 28 dp icons, 1280 dp and wider.
        listOf(5, 6).forEach { tabs ->
            listOf(1280f - 32f, 1600f - 32f, 3840f - 32f).forEach { width ->
                assertTrue(desktopNavigationLabelsFit(28f, downloadsDp, tabs, width), "$tabs tabs at $width")
            }
        }
    }

    @Test
    fun upstreamsFixedAllowanceWasTooNarrowForDownloads() {
        // The reported "Downl...": a 64 dp allowance leaves 72 dp for a label, and a larger UI zoom
        // or font scale pushes "Downloads" past it.
        val slot = 28f + 20f + 64f
        val room = slot - (16f + (28f - 10f) + 6f)
        assertTrue(room < downloadsDp + 2f)
    }

    @Test
    fun aLongerTranslationWidensTheBarUntilTheWindowRunsOut() {
        val long = 110f
        assertTrue(desktopNavigationLabelsFit(28f, long, 6, 1280f - 32f))
        assertFalse(desktopNavigationLabelsFit(28f, long, 6, 700f))
    }

    @Test
    fun collapsedLabelsTakeNoRoomAndTheWindowCapsTheTrack() {
        val allowance = desktopNavigationLabelAllowanceDp(downloadsDp)
        assertEquals((28f + 20f) * 6 + 8f, desktopNavigationTrackWidthDp(28f, allowance, 0f, 6, 2000f))
        assertEquals(500f, desktopNavigationTrackWidthDp(28f, allowance, 1f, 6, 500f))
    }
}
