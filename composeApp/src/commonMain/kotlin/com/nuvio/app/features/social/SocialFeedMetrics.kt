package com.nuvio.app.features.social

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How wide the Social feed is, and what fits across it.
 *
 * ⚠ **This file exists because the column count and the width it was spent on came from two
 * different numbers.** `SocialScreen` chose the column count from `maxWidth - friends rail` - about
 * 1000dp on the window this was tested at, so three columns - and then handed those three columns
 * to a `LazyColumn` capped at `widthIn(max = 600.dp)`. Each card got roughly 180dp, the Watching Now
 * card's text column got about 60dp of that, and `PAUSED` and `Ask to join` came out one character
 * per line beside a card several times taller than it was wide. The screen still had a third of the
 * window standing empty next to it, which is what made it look like a sizing bug rather than an
 * arithmetic one.
 *
 * So the width and the columns are computed together, once, from the window, and both the screen
 * and the render harness read them from here. A test asserts the cards this produces are wide
 * enough to hold their own labels - see `SocialFeedMetricsTest`.
 */

/**
 * The dashboard runs edge to edge. It was capped at 1440dp and centred, which on a 1920px monitor
 * read as unexplained margins either side of the whole screen; wide windows now get more columns
 * instead (see the caps below).
 */
internal val SocialDashboardMaxWidth = Dp.Infinity

/** The feed's own `contentPadding`, on each side. Part of the arithmetic, so it is named here. */
internal val SocialFeedHorizontalPadding = 24.dp

/** The gap between cards in a grid row, matching `socialGridItems`. */
internal val SocialGridGap = 10.dp

/**
 * The width below which a card's own labels start to break, measured from the two that broke.
 *
 * Watching Now carries an identity block, a title, an episode line and a join action, so it needs
 * materially more room than a Friends' activity row - and it is the live,
 * actionable surface, which is the other reason it gets the bigger share.
 */
internal val SocialWatchingNowMinCardWidth = 340.dp
internal val SocialActivityMinCardWidth = 280.dp

/**
 * Column caps. Watching Now stays at two so a live card keeps a generous footprint; activity is
 * allowed three, past which the cards start reading as a catalogue grid again.
 */
internal const val SocialWatchingNowMaxColumns = 3
internal const val SocialActivityMaxColumns = 4

/** Everything the Social feed's layout needs, derived from the window in one place. */
internal data class SocialFeedMetrics(
    val feedWidth: Dp,
    val contentWidth: Dp,
    val watchingNowColumns: Int,
    val activityColumns: Int,
    val watchingNowCardWidth: Dp,
    val activityCardWidth: Dp,
    /**
     * The still's width inside each card.
     *
     * A one-column feed is the phone path and a card there is the full width of the screen, so the
     * desktop's larger still would take a third of it and put the artwork back in charge of the
     * hierarchy. Wide art belongs to the multi-column case.
     */
    val watchingNowArtworkWidth: Dp,
) {
    /** Below [SocialWatchingNowStackedBelow] the card puts its artwork above the text. */
    val watchingNowStacked: Boolean get() = watchingNowCardWidth < SocialWatchingNowStackedBelow
}

internal fun socialColumnsFor(contentWidth: Dp, minCardWidth: Dp, maxColumns: Int): Int =
    ((contentWidth + SocialGridGap) / (minCardWidth + SocialGridGap)).toInt().coerceIn(1, maxColumns)

internal fun socialCardWidthFor(contentWidth: Dp, columns: Int): Dp =
    (contentWidth - SocialGridGap * (columns - 1)) / columns

internal fun socialFeedMetrics(windowWidth: Dp, railVisible: Boolean): SocialFeedMetrics {
    // The dashboard itself is capped and centred, so a 2560dp window does not give the feed 2560dp
    // to divide - reading `maxWidth` straight was half of the original mismatch.
    val dashboardWidth = if (windowWidth < SocialDashboardMaxWidth) windowWidth else SocialDashboardMaxWidth
    val feedWidth = if (railVisible) dashboardWidth - SocialFriendsRailWidth else dashboardWidth
    val contentWidth = (feedWidth - SocialFeedHorizontalPadding * 2).coerceAtLeast(160.dp)
    val watchingNowColumns = socialColumnsFor(contentWidth, SocialWatchingNowMinCardWidth, SocialWatchingNowMaxColumns)
    val activityColumns = socialColumnsFor(contentWidth, SocialActivityMinCardWidth, SocialActivityMaxColumns)
    return SocialFeedMetrics(
        feedWidth = feedWidth,
        contentWidth = contentWidth,
        watchingNowColumns = watchingNowColumns,
        activityColumns = activityColumns,
        watchingNowCardWidth = socialCardWidthFor(contentWidth, watchingNowColumns),
        activityCardWidth = socialCardWidthFor(contentWidth, activityColumns),
        watchingNowArtworkWidth = if (watchingNowColumns > 1) {
            SocialWatchingNowArtworkWidthWide
        } else {
            SocialWatchingNowArtworkWidth
        },
    )
}
