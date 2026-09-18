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
        // would take a third of it back for the artwork.
        val phone = socialFeedMetrics(420.dp, railVisible = false)
        assertEquals(SocialWatchingNowArtworkWidth, phone.watchingNowArtworkWidth)
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
        assertTrue(socialFeedMetrics(360.dp, railVisible = false).watchingNowStacked)
    }
}
