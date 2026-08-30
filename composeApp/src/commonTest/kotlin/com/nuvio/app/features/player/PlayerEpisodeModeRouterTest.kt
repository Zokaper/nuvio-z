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
    fun streamlinedOpensTheQualitySheet() {
        assertEquals(PlayerEpisodeModeRoute.QUALITY_SHEET, playerEpisodeModeRoute(PlaybackMode.STREAMLINED))
    }

    @Test
    fun instantAutoPicks() {
        assertEquals(PlayerEpisodeModeRoute.AUTO_PICK, playerEpisodeModeRoute(PlaybackMode.INSTANT))
    }
}
