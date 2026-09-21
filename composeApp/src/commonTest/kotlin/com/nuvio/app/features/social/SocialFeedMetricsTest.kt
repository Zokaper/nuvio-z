package com.nuvio.app.features.social

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The arithmetic that collapsed a live card to 180dp.
 *
 * The column count and the width it was spent on came from two different numbers, and the only
 * thing that noticed was a physical screenshot. These cases say, in units, what the screenshot
 * said: a card is never narrower than the labels it has to hold.
 */
class SocialFeedMetricsTest {

    private val widths = listOf(420, 1040, 1280, 1440, 2560, 3840)

    @Test
    fun theColumnsAreSpentOnTheWidthTheyWereChosenFrom() {
        // The regression in one assertion: three activity columns divided into a 600dp-capped list
        // is 177dp a card. Every card must be at least the width its column count was chosen from.
        widths.forEach { width ->
            listOf(true, false).forEach { rail ->
                val metrics = socialFeedMetrics(width.dp, rail)
                if (metrics.watchingNowColumns > 1) {
                    assertTrue(
                        metrics.watchingNowCardWidth >= SocialWatchingNowMinCardWidth,
                        "watching now at ${width}dp rail=$rail is ${metrics.watchingNowCardWidth}",
                    )
                }
                if (metrics.activityColumns > 1) {
                    assertTrue(
                        metrics.activityCardWidth >= SocialActivityMinCardWidth,
                        "activity at ${width}dp rail=$rail is ${metrics.activityCardWidth}",
                    )
                }
                assertTrue(metrics.watchingNowCardWidth <= metrics.contentWidth)
                assertTrue(metrics.activityCardWidth <= metrics.contentWidth)
            }
        }
    }

    @Test
    fun aWideWindowSpendsTheWholeWidth() {
        // The dashboard is no longer capped: a 1440dp cap left bare margins on a 1920px monitor.
        val wide = socialFeedMetrics(1920.dp, railVisible = true)
        assertEquals(1920.dp - SocialFriendsRailWidth, wide.feedWidth)
    }

    @Test
    fun theRailIsSubtractedOnlyWhenItIsOnScreen() {
        val withRail = socialFeedMetrics(1280.dp, railVisible = true)
        val without = socialFeedMetrics(1280.dp, railVisible = false)
        assertEquals(SocialFriendsRailWidth, without.feedWidth - withRail.feedWidth)
    }

    @Test
    fun aPhoneWidthCollapsesToOneColumnRatherThanToNone() {
        val phone = socialFeedMetrics(420.dp, railVisible = false)
        assertEquals(1, phone.watchingNowColumns)
        assertEquals(1, phone.activityColumns)
        assertEquals(phone.contentWidth, phone.watchingNowCardWidth)
    }

    @Test
    fun aSingleColumnKeepsTheCompactArtwork() {
        // One column is the phone path, where a card is the whole screen and the desktop still
        // would take a third of it back for the artwork. A phone goes further: its card is a row.
        val phone = socialFeedMetrics(420.dp, railVisible = false)
        assertEquals(SocialWatchingNowArtworkWidthCompact, phone.watchingNowArtworkWidth)
        val tablet = socialFeedMetrics(800.dp, railVisible = false, windowHeight = 1280.dp)
        assertEquals(SocialWatchingNowArtworkWidth, tablet.watchingNowArtworkWidth)
        val desktop = socialFeedMetrics(1440.dp, railVisible = true)
        assertEquals(SocialWatchingNowArtworkWidthWide, desktop.watchingNowArtworkWidth)
    }

    @Test
    fun watchingNowKeepsTheBiggerFootprintOfTheTwo() {
        // Live and actionable outranks ambient history. If this ever inverts, the hierarchy the
        // redesign exists to establish has gone with it.
        val metrics = socialFeedMetrics(1440.dp, railVisible = true)
        assertTrue(metrics.watchingNowCardWidth > metrics.activityCardWidth)
        assertTrue(metrics.watchingNowColumns <= metrics.activityColumns)
    }

    @Test
    fun homeShelfRowsStayReadableAndStaySubordinate() {
        // Recently Watched was once demoted too far: 260dp with 92dp of artwork left "The D…". The
        // guard is the width left for the title at the narrowest row, and the height, which is what
        // holds the hierarchy under Continue Watching.
        val posterGapAndEnd = FriendActivityPosterWidth + 10.dp + 8.dp
        listOf(400.dp, 1280.dp, 2560.dp).forEach { window ->
            val text = friendActivityRowWidth(window) - posterGapAndEnd
            assertTrue(text >= 190.dp, "activity text column at $window is $text")
        }
        val cardPaddingAndGap = 32.dp
        val watchingText = SocialWatchingNowCardWidth - SocialWatchingNowArtworkWidth - cardPaddingAndGap
        assertTrue(watchingText >= 190.dp, "watching now text column is $watchingText")
        assertTrue(FriendActivityRowHeight < SocialWatchingNowCardHeight)
        assertTrue(!socialFeedMetrics(1440.dp, railVisible = true).watchingNowStacked)
        // The stacked card was the full-width still that made one friend a screen of feed; a phone
        // never gets it.
        assertTrue(!socialFeedMetrics(360.dp, railVisible = false).watchingNowStacked)
    }

    @Test
    fun densityFollowsTheWindowsShapeNotItsWidthAlone() {
        val portrait = socialFeedMetrics(411.dp, railVisible = false, windowHeight = 914.dp)
        val landscape = socialFeedMetrics(891.dp, railVisible = false, windowHeight = 411.dp)
        val tablet = socialFeedMetrics(800.dp, railVisible = false, windowHeight = 1280.dp)
        val desktop = socialFeedMetrics(1280.dp, railVisible = true, windowHeight = 820.dp)
        assertTrue(portrait.phone)
        assertTrue(landscape.phone, "a landscape phone is phone density however wide it is")
        assertTrue(!tablet.phone)
        assertTrue(!desktop.phone)
        // A landscape phone still gets the width as columns - the grid was already right.
        assertTrue(landscape.watchingNowColumns >= 2)
        assertEquals(SocialFeedHorizontalPadding, desktop.horizontalPadding)
        assertEquals(portrait.contentWidth, 411.dp - SocialFeedHorizontalPaddingPhone * 2)
    }
}
