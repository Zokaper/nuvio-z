package com.nuvio.app.features.player

import com.nuvio.app.features.playback.PlaybackMode

internal enum class PlayerEpisodeModeRoute {
    SOURCE_LIST,
    QUALITY_SHEET,
    AUTO_PICK,
}

/** Keeps an episode chosen inside the player on the same playback path as a details-page play. */
internal fun playerEpisodeModeRoute(mode: PlaybackMode): PlayerEpisodeModeRoute = when (mode) {
    PlaybackMode.CLASSIC -> PlayerEpisodeModeRoute.SOURCE_LIST
    PlaybackMode.STREAMLINED -> PlayerEpisodeModeRoute.QUALITY_SHEET
    PlaybackMode.INSTANT -> PlayerEpisodeModeRoute.AUTO_PICK
}
