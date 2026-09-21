package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The window classifier, checked against the devices the layouts are written for.
 *
 * The band tests are the cheap half. The half that matters is [aLandscapePhoneIsShortAndWide] and
 * its neighbours: the whole reason this file exists is that a landscape phone was indistinguishable
 * from a small tablet under a width-only rule, and the lobby drew a portrait column into 411dp of
 * height because of it.
 */
class NuvioWindowClassTest {

    // --- width bands -------------------------------------------------------------------------

    @Test
    fun widthBandsSplitAtTheirBoundaries() {
        assertEquals(WindowWidthClass.Compact, windowWidthClassFor(599f))
        assertEquals(WindowWidthClass.Medium, windowWidthClassFor(600f))
        assertEquals(WindowWidthClass.Medium, windowWidthClassFor(839f))
        assertEquals(WindowWidthClass.Expanded, windowWidthClassFor(840f))
        assertEquals(WindowWidthClass.Expanded, windowWidthClassFor(1179f))
        assertEquals(WindowWidthClass.Wide, windowWidthClassFor(1180f))
    }

    @Test
    fun heightBandsSplitAtTheirBoundaries() {
        assertEquals(WindowHeightClass.Short, windowHeightClassFor(519f))
        assertEquals(WindowHeightClass.Regular, windowHeightClassFor(520f))
        assertEquals(WindowHeightClass.Regular, windowHeightClassFor(899f))
        assertEquals(WindowHeightClass.Tall, windowHeightClassFor(900f))
    }

    @Test
    fun aZeroSizedWindowClassifiesRatherThanThrowing() {
        // The first frame of a BoxWithConstraints can measure 0. It must classify as the smallest
        // thing, not as something that skips the phone branch.
        val first = NuvioWindowClass(0f, 0f)
        assertEquals(WindowWidthClass.Compact, first.widthClass)
        assertEquals(WindowHeightClass.Short, first.heightClass)
        assertFalse(first.isTwoPaneSurface)
    }

    // --- the devices the layouts are written for ---------------------------------------------

    /** Galaxy S25, portrait: 1080 x 2340 at density 2.625. */
    @Test
    fun aPortraitPhoneIsCompactAndTall() {
        val phone = NuvioWindowClass(411f, 891f)
        assertTrue(phone.isCompactWidth)
        assertFalse(phone.isShortSurface)
        assertFalse(phone.isShortWide)
        assertTrue(phone.isPhoneSurface)
        assertFalse(phone.isTwoPaneSurface)
    }

    /**
     * The same phone rotated: 891 x 411dp.
     *
     * ⚠ This is the case the old rules got wrong in both directions at once. It missed the lobby's
     * `wide` (900dp) by nine density-independent pixels, so it took the phone-portrait branch, and
     * it is nowhere near `twoPane` (1180dp), so nothing else could catch it. It has to be
     * recognisable as "wide enough to split, too short to stack" or there is no layout for it.
     */
    @Test
    fun aLandscapePhoneIsShortAndWide() {
        val phone = NuvioWindowClass(891f, 411f)
        assertFalse(phone.isCompactWidth)
        assertTrue(phone.isShortSurface)
        assertTrue(phone.isShortWide)
        assertTrue(phone.isPhoneSurface)
        assertFalse(phone.isTwoPaneSurface)
    }

    /**
     * A small tablet in landscape - 1280 x 800 - must NOT be Short.
     *
     * This is what pins [NuvioWindowBreakpoints.REGULAR_HEIGHT_DP] at 520 rather than at a rounder
     * 600 or 640: raising it would fold a perfectly tall tablet into the landscape-phone layout.
     */
    @Test
    fun aLandscapeTabletIsNotShort() {
        val tablet = NuvioWindowClass(1280f, 800f)
        assertFalse(tablet.isShortSurface)
        assertFalse(tablet.isShortWide)
        assertFalse(tablet.isPhoneSurface)
        assertTrue(tablet.isTwoPaneSurface)
    }

    @Test
    fun aPortraitTabletStacksAndDoesNotSplit() {
        val tablet = NuvioWindowClass(800f, 1280f)
        assertEquals(WindowWidthClass.Medium, tablet.widthClass)
        assertFalse(tablet.isPhoneSurface)
        assertFalse(tablet.isTwoPaneSurface)
    }

    @Test
    fun aDesktopWindowIsTwoPane() {
        assertTrue(NuvioWindowClass(1280f, 820f).isTwoPaneSurface)
        assertTrue(NuvioWindowClass(1920f, 1080f).isTwoPaneSurface)
    }

    /**
     * A desktop window dragged short keeps its width but loses the second pane.
     *
     * The two-pane branch pins an action bar under a scrolling column; at 400dp of height that
     * column has nothing left to scroll and the pane is worse than the single column.
     */
    @Test
    fun aVeryShortDesktopWindowGivesUpTheSecondPane() {
        val squashed = NuvioWindowClass(1600f, 400f)
        assertEquals(WindowWidthClass.Wide, squashed.widthClass)
        assertTrue(squashed.isShortSurface)
        assertTrue(squashed.isShortWide)
        assertFalse(squashed.isTwoPaneSurface)
    }

    /** Split-screen on a phone: narrow and short at once. */
    @Test
    fun splitScreenIsCompactAndShort() {
        val split = NuvioWindowClass(320f, 400f)
        assertTrue(split.isCompactWidth)
        assertTrue(split.isShortSurface)
        // Compact width wins: there is no room to put two regions side by side.
        assertFalse(split.isShortWide)
        assertTrue(split.isPhoneSurface)
    }

    // --- the contract other files depend on ---------------------------------------------------

    /**
     * `NuvioTokens.Breakpoint` derives from these, and `PartyTwoPaneMinWidth` was 1180dp before
     * this vocabulary existed. If either number moves, a desktop layout moves with it.
     */
    @Test
    fun theBreakpointsAreTheValuesTheRestOfTheAppWasBuiltOn() {
        assertEquals(600f, NuvioWindowBreakpoints.MEDIUM_WIDTH_DP)
        assertEquals(840f, NuvioWindowBreakpoints.EXPANDED_WIDTH_DP)
        assertEquals(1180f, NuvioWindowBreakpoints.WIDE_WIDTH_DP)
    }
}
