package com.nuvio.app.features.player

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubtitleRenderGeometryTest {

    private fun near(expected: Float, actual: Float, message: String = "") =
        assertTrue(abs(expected - actual) < 0.01f, "$message expected $expected, was $actual")

    @Test
    fun theEngineMappingsAreTheOnesThatShipped() {
        // Android libmpv: 55 / 18 per sp, clamped 36..122.
        assertEquals(55, androidMpvSubtitleFontSize(18))
        assertEquals(36, androidMpvSubtitleFontSize(6))
        assertEquals(122, androidMpvSubtitleFontSize(40))
        assertEquals(3, androidMpvSubtitleOutlineSize(outlineEnabled = true, outlineWidth = 2))
        assertEquals(1, androidMpvSubtitleOutlineSize(outlineEnabled = true, outlineWidth = 0))
        assertEquals(0, androidMpvSubtitleOutlineSize(outlineEnabled = false, outlineWidth = 4))
        assertEquals(98, androidMpvSubtitlePosition(20))
        // iOS and desktop: x3 clamped 18..96, sub-pos 100 - offset / 2.
        near(54f, mpvSubtitleFontSize(18))
        near(18f, mpvSubtitleFontSize(4))
        near(96f, mpvSubtitleFontSize(40))
        assertEquals(90, mpvSubtitlePosition(20))
        assertEquals(100, mpvSubtitlePosition(0))
        // ExoPlayer: two thirds of 8% plus a capped offset.
        near(0.08f * 2f / 3f + 0.02f, exoSubtitleBottomPaddingFraction(20))
        near(0.08f * 2f / 3f + 0.2f, exoSubtitleBottomPaddingFraction(900))
    }

    @Test
    fun exoPlayerTextIsAFixedSpSizeWhateverTheFrame() {
        val small = subtitleFrameGeometry(SubtitleRenderer.ExoPlayer, 18, true, 2, 20, false, frameHeightDp = 200f)
        val large = subtitleFrameGeometry(SubtitleRenderer.ExoPlayer, 18, true, 2, 20, false, frameHeightDp = 800f)
        assertTrue(small.fontSizeIsSp)
        near(18f, small.fontSize)
        near(18f, large.fontSize)
        near(EXO_OUTLINE_STROKE_DP, small.outlineStrokeDp)
    }

    @Test
    fun mpvTextGrowsWithTheFrame() {
        val g = subtitleFrameGeometry(SubtitleRenderer.Mpv, 18, true, 2, 0, false, frameHeightDp = 360f)
        assertFalse(g.fontSizeIsSp)
        near(27f, g.fontSize, "54 scaled px on a 360 dp frame")
        near(11f, g.bottomDp, "the 22 px default margin at sub-pos 100")
        near(2f, g.outlineStrokeDp, "a 1 dp outline each side")
        val android = subtitleFrameGeometry(SubtitleRenderer.AndroidMpv, 18, true, 2, 0, false, frameHeightDp = 360f)
        near(27.5f, android.fontSize)
    }

    @Test
    fun raisingTheOffsetRaisesTheTextOnEveryRenderer() {
        SubtitleRenderer.entries.forEach { renderer ->
            val low = subtitleFrameGeometry(renderer, 18, true, 2, 0, false, 360f)
            val high = subtitleFrameGeometry(renderer, 18, true, 2, 100, false, 360f)
            assertTrue(high.bottomDp > low.bottomDp, "$renderer")
        }
    }

    @Test
    fun theBackgroundBoxFollowsEachRenderersRule() {
        // ExoPlayer draws the box and the outline together.
        val exo = subtitleFrameGeometry(SubtitleRenderer.ExoPlayer, 18, true, 2, 20, true, 360f)
        assertTrue(exo.boxed)
        assertTrue(exo.outlineStrokeDp > 0f)
        // Android libmpv: the outline wins; the box only without one.
        assertFalse(subtitleFrameGeometry(SubtitleRenderer.AndroidMpv, 18, true, 2, 20, true, 360f).boxed)
        assertTrue(subtitleFrameGeometry(SubtitleRenderer.AndroidMpv, 18, false, 2, 20, true, 360f).boxed)
        // iOS and desktop: any visible background selects the opaque box, which replaces the outline.
        val mpv = subtitleFrameGeometry(SubtitleRenderer.Mpv, 18, true, 2, 20, true, 360f)
        assertTrue(mpv.boxed)
        near(0f, mpv.outlineStrokeDp)
        // No background, no box anywhere.
        SubtitleRenderer.entries.forEach { renderer ->
            assertFalse(subtitleFrameGeometry(renderer, 18, true, 2, 20, false, 360f).boxed, "$renderer")
        }
    }
}
