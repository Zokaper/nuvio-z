package com.nuvio.app.features.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackEntranceMotionTest {

    @Test
    fun endpointsAtZero() {
        assertEquals(0f, PlaybackEntranceMotion.scrimAlpha(0f))
        assertEquals(0f, PlaybackEntranceMotion.panelProgress(0f))
        assertEquals(0f, PlaybackEntranceMotion.panelAlpha(0f))
        assertEquals(PlaybackEntranceMotion.PANEL_START_SCALE, PlaybackEntranceMotion.panelScale(0f))
        assertEquals(PlaybackEntranceMotion.PANEL_RISE_DP, PlaybackEntranceMotion.panelRiseDp(0f))
    }

    @Test
    fun endpointsAtOne() {
        assertEquals(1f, PlaybackEntranceMotion.scrimAlpha(1f))
        assertEquals(1f, PlaybackEntranceMotion.panelProgress(1f))
        assertEquals(1f, PlaybackEntranceMotion.panelAlpha(1f))
        assertEquals(1f, PlaybackEntranceMotion.panelScale(1f))
        assertEquals(0f, PlaybackEntranceMotion.panelRiseDp(1f))
    }

    @Test
    fun clampingOutsideZeroAndOne() {
        assertEquals(0f, PlaybackEntranceMotion.scrimAlpha(-0.5f))
        assertEquals(0f, PlaybackEntranceMotion.panelProgress(-0.5f))
        assertEquals(0f, PlaybackEntranceMotion.panelAlpha(-0.5f))
        assertEquals(PlaybackEntranceMotion.PANEL_START_SCALE, PlaybackEntranceMotion.panelScale(-0.5f))
        assertEquals(PlaybackEntranceMotion.PANEL_RISE_DP, PlaybackEntranceMotion.panelRiseDp(-0.5f))

        assertEquals(1f, PlaybackEntranceMotion.scrimAlpha(1.5f))
        assertEquals(1f, PlaybackEntranceMotion.panelProgress(1.5f))
        assertEquals(1f, PlaybackEntranceMotion.panelAlpha(1.5f))
        assertEquals(1f, PlaybackEntranceMotion.panelScale(1.5f))
        assertEquals(0f, PlaybackEntranceMotion.panelRiseDp(1.5f))
    }

    @Test
    fun panelWaitsUntilPanelStartFraction() {
        assertEquals(0f, PlaybackEntranceMotion.panelProgress(PlaybackEntranceMotion.PANEL_START_FRACTION))
        assertEquals(0f, PlaybackEntranceMotion.panelAlpha(PlaybackEntranceMotion.PANEL_START_FRACTION))
        assertEquals(0f, PlaybackEntranceMotion.panelProgress(PlaybackEntranceMotion.PANEL_START_FRACTION / 2f))
    }

    @Test
    fun scrimCompletesByScrimEndFraction() {
        assertEquals(1f, PlaybackEntranceMotion.scrimAlpha(PlaybackEntranceMotion.SCRIM_END_FRACTION))
        assertEquals(1f, PlaybackEntranceMotion.scrimAlpha(PlaybackEntranceMotion.SCRIM_END_FRACTION + 0.1f))
    }

    @Test
    fun monotonicAcrossSampledSweep() {
        var prevScrim = -1f
        var prevPanel = -1f
        var prevScale = -1f
        var prevRise = Float.MAX_VALUE

        for (step in 0..100) {
            val p = step / 100f
            val scrim = PlaybackEntranceMotion.scrimAlpha(p)
            val panel = PlaybackEntranceMotion.panelProgress(p)
            val scale = PlaybackEntranceMotion.panelScale(p)
            val rise = PlaybackEntranceMotion.panelRiseDp(p)

            assertTrue(scrim >= prevScrim, "scrimAlpha should be non-decreasing at p=$p")
            assertTrue(panel >= prevPanel, "panelProgress should be non-decreasing at p=$p")
            assertTrue(scale >= prevScale, "panelScale should be non-decreasing at p=$p")
            assertTrue(rise <= prevRise, "panelRiseDp should be non-increasing at p=$p")

            prevScrim = scrim
            prevPanel = panel
            prevScale = scale
            prevRise = rise
        }
    }
}
