package com.nuvio.app.features.player

import com.nuvio.app.features.playback.PlaybackMode
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerEpisodeModeRouterTest {
    @Test
    fun classicOpensTheSourceList() {
        assertEquals(PlayerEpisodeModeRoute.SOURCE_LIST, playerEpisodeModeRoute(PlaybackMode.CLASSIC))
    }

    @Test
    fun streamlinedAsksItsQualityQuestion() {
        // ⚠ Regression, hardware 2026-09-15. `c28493cc` routed desktop Streamlined to AUTO_PICK
        // because the Compose sheet cannot be drawn over the native player, and Next episode then
        // auto-selected in Streamlined exactly as it does in Instant. Desktop draws the Streamlined
        // rows natively now; the route is the mode's, on every platform.
        assertEquals(PlayerEpisodeModeRoute.QUALITY_SHEET, playerEpisodeModeRoute(PlaybackMode.STREAMLINED))
    }

    @Test
    fun instantAutoPicks() {
        assertEquals(PlayerEpisodeModeRoute.AUTO_PICK, playerEpisodeModeRoute(PlaybackMode.INSTANT))
    }

    @Test
    fun onlyInstantSelectsWithoutAsking() {
        PlaybackMode.entries.forEach { mode ->
            assertEquals(
                mode == PlaybackMode.INSTANT,
                playerEpisodeModeRoute(mode) == PlayerEpisodeModeRoute.AUTO_PICK,
                "mode=$mode",
            )
        }
    }
}
