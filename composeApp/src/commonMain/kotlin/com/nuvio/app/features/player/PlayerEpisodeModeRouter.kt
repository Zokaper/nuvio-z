package com.nuvio.app.features.player

import com.nuvio.app.features.playback.PlaybackMode

internal enum class PlayerEpisodeModeRoute {
    SOURCE_LIST,
    QUALITY_SHEET,
    AUTO_PICK,
}

/**
 * Keeps an episode chosen inside the player on the same playback path as a details-page play:
 * the mode's own question, and nothing else.
 *
 * Classic asks with the release list, Streamlined with its quality rows, Instant asks nothing.
 * This is `PlaybackModeRouter`'s answer for the same three modes, and the two must not disagree.
 *
 * ⚠ **Platform does not change the question, only how it is drawn.** Desktop hosts playback in a
 * native surface a Compose sheet cannot be raised above. That was first answered with Classic's list
 * (hardware Bug 3) and then, in `c28493cc`, with automatic selection - which is Instant's answer, and
 * on hardware read as "Next episode ignores my playback mode". Desktop now draws the Streamlined rows
 * in the native controls layer instead (`PlayerEpisodeQualityChooser.kt`).
 *
 * A Watch Together guest never reaches this: `ownsNextEpisode` hides and refuses the control, and the
 * party's content handoff moves the guest instead.
 */
internal fun playerEpisodeModeRoute(mode: PlaybackMode): PlayerEpisodeModeRoute = when (mode) {
    PlaybackMode.CLASSIC -> PlayerEpisodeModeRoute.SOURCE_LIST
    PlaybackMode.STREAMLINED -> PlayerEpisodeModeRoute.QUALITY_SHEET
    PlaybackMode.INSTANT -> PlayerEpisodeModeRoute.AUTO_PICK
}
