package com.nuvio.app.features.player

// No imports, and none may be added: group 3 of `scripts/run-pure-suites.sh` compiles this file on
// its own, and the setup previews are only honest if this is the one place the numbers live.

/**
 * How a [SubtitleStyleState] becomes pixels, per renderer - **the one calculation** the players and
 * Advanced Setup's subtitle preview share (setup polish, physical QA).
 *
 * ## Why this exists
 *
 * The preview used to scale the stored size by a guessed 0.62 against a frame half a phone wide.
 * The renderers do not work that way, and they do not even agree with each other:
 *
 * - **ExoPlayer** (Android's default engine; `Auto` starts on it) draws `fontSizeSp` as a fixed
 *   **sp** size - independent of the video height - with a 2 dp outline stroke, a background box
 *   padded by an eighth of the font size, and a bottom padding that is a *fraction of the view*.
 * - **libmpv** (iOS, desktop, and Android's `libmpv` engine) sizes in *scaled pixels* against a
 *   720-line frame, so the text grows with the screen, and moves by `sub-pos` percent of the height.
 *   Android and iOS/desktop map the same stored values with different factors.
 *
 * So the same "18" is about 5% of a landscape phone's height on ExoPlayer and about 7.5% on mpv.
 * The preview asks this file for the renderer the device will really use; the engines ask it for the
 * values they hand to the renderer. Change a factor here and both move together.
 */
enum class SubtitleRenderer {
    /** Android's ExoPlayer `SubtitleView` (`PlayerEngine.android.kt`, `applySubtitleStyle`). */
    ExoPlayer,

    /** Android's libmpv engine. */
    AndroidMpv,

    /** iOS (`MPVPlayerBridge.swift`) and desktop (`NativePlayerController`): the same mapping. */
    Mpv,
}

/** mpv's `sub-font-size`, `sub-outline-size` and `sub-margin-y` are in pixels of a 720-line frame. */
internal const val MPV_SCALED_FRAME_HEIGHT: Float = 720f

/** mpv's default `sub-margin-y`, in scaled pixels: the gap below a subtitle at `sub-pos` 100. */
internal const val MPV_DEFAULT_SUB_MARGIN_Y: Float = 22f

internal const val ANDROID_MPV_SUBTITLE_FONT_SIZE_SCALE: Double = 55.0 / 18.0
internal const val ANDROID_MPV_SUBTITLE_FONT_SIZE_MIN: Int = 36
internal const val ANDROID_MPV_SUBTITLE_FONT_SIZE_MAX: Int = 122
internal const val ANDROID_MPV_SUBTITLE_OUTLINE_SIZE_SCALE: Double = 1.5

/** ExoPlayer's `SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION`. */
internal const val EXO_DEFAULT_BOTTOM_PADDING_FRACTION: Float = 0.08f

/** ExoPlayer's `SubtitlePainter`: outline stroke 2 dp, box corners 2 dp, box padding 0.125 x size. */
internal const val EXO_OUTLINE_STROKE_DP: Float = 2f
internal const val EXO_BOX_CORNER_DP: Float = 2f
internal const val EXO_BOX_PADDING_RATIO: Float = 0.125f

/** Android libmpv `sub-font-size`. */
internal fun androidMpvSubtitleFontSize(fontSizeSp: Int): Int =
    (fontSizeSp * ANDROID_MPV_SUBTITLE_FONT_SIZE_SCALE).toInt().coerceIn(
        ANDROID_MPV_SUBTITLE_FONT_SIZE_MIN,
        ANDROID_MPV_SUBTITLE_FONT_SIZE_MAX,
    )

/** Android libmpv `sub-outline-size` / `sub-border-size`. */
internal fun androidMpvSubtitleOutlineSize(outlineEnabled: Boolean, outlineWidth: Int): Int =
    if (!outlineEnabled) 0 else (outlineWidth * ANDROID_MPV_SUBTITLE_OUTLINE_SIZE_SCALE).toInt().coerceAtLeast(1)

/** Android libmpv `sub-pos`. */
internal fun androidMpvSubtitlePosition(bottomOffset: Int): Int = (100 - bottomOffset / 10).coerceIn(0, 100)

/** iOS and desktop `sub-font-size`. */
internal fun mpvSubtitleFontSize(fontSizeSp: Int): Float = (fontSizeSp * 3f).coerceIn(18f, 96f)

/** iOS and desktop `sub-pos`. */
internal fun mpvSubtitlePosition(bottomOffset: Int): Int = (100 - (bottomOffset / 2)).coerceIn(0, 150)

/** iOS and desktop `sub-outline-size`: the stored width itself, in scaled pixels. */
internal fun mpvSubtitleOutlineSize(outlineEnabled: Boolean, outlineWidth: Int): Float =
    if (outlineEnabled) outlineWidth.toFloat() else 0f

/** ExoPlayer's bottom padding, as a fraction of the view height. */
internal fun exoSubtitleBottomPaddingFraction(bottomOffset: Int): Float {
    val base = EXO_DEFAULT_BOTTOM_PADDING_FRACTION * 2f / 3f
    val offset = (bottomOffset / 1000f).coerceIn(0f, 0.2f)
    return (base + offset).coerceIn(0f, 0.4f)
}

/**
 * Where and how big one line of subtitle text is drawn in a frame [frameHeightDp] tall.
 *
 * @property fontSize the text size. In **sp** when [fontSizeIsSp] (ExoPlayer honours the system font
 *   scale); otherwise in **dp** (mpv sizes against the frame and ignores it).
 * @property bottomDp the gap between the frame's bottom edge and the bottom of the text block.
 * @property outlineStrokeDp the stroke drawn centred on the glyph edge (0 = no outline).
 * @property boxed whether a background box is drawn behind the text.
 * @property boxPaddingHorizontalDp / [boxPaddingVerticalDp] how far that box extends past the glyphs.
 */
data class SubtitleFrameGeometry(
    val fontSize: Float,
    val fontSizeIsSp: Boolean,
    val bottomDp: Float,
    val outlineStrokeDp: Float,
    val boxed: Boolean,
    val boxPaddingHorizontalDp: Float,
    val boxPaddingVerticalDp: Float,
    val boxCornerDp: Float,
)

fun subtitleFrameGeometry(
    renderer: SubtitleRenderer,
    fontSizeSp: Int,
    outlineEnabled: Boolean,
    outlineWidth: Int,
    bottomOffset: Int,
    backgroundVisible: Boolean,
    frameHeightDp: Float,
): SubtitleFrameGeometry {
    val perScaledPixel = frameHeightDp / MPV_SCALED_FRAME_HEIGHT
    return when (renderer) {
        SubtitleRenderer.ExoPlayer -> SubtitleFrameGeometry(
            fontSize = fontSizeSp.toFloat(),
            fontSizeIsSp = true,
            bottomDp = frameHeightDp * exoSubtitleBottomPaddingFraction(bottomOffset),
            outlineStrokeDp = if (outlineEnabled) EXO_OUTLINE_STROKE_DP else 0f,
            boxed = backgroundVisible,
            boxPaddingHorizontalDp = fontSizeSp * EXO_BOX_PADDING_RATIO,
            boxPaddingVerticalDp = 0f,
            boxCornerDp = EXO_BOX_CORNER_DP,
        )
        SubtitleRenderer.AndroidMpv -> {
            val outline = androidMpvSubtitleOutlineSize(outlineEnabled, outlineWidth) * perScaledPixel
            // `toMpvSubtitleBorderStyle`: outline wins; the opaque box only without one.
            val boxed = !outlineEnabled && backgroundVisible
            mpvGeometry(
                fontScaledPx = androidMpvSubtitleFontSize(fontSizeSp).toFloat(),
                subPos = androidMpvSubtitlePosition(bottomOffset),
                outlineDp = if (boxed) 0f else outline,
                boxed = boxed,
                boxPaddingDp = androidMpvSubtitleOutlineSize(true, outlineWidth) * perScaledPixel,
                frameHeightDp = frameHeightDp,
            )
        }
        SubtitleRenderer.Mpv -> {
            val outline = mpvSubtitleOutlineSize(outlineEnabled, outlineWidth) * perScaledPixel
            // `MPVPlayerBridge.swift`: any visible background colour selects the opaque box.
            val boxed = backgroundVisible
            mpvGeometry(
                fontScaledPx = mpvSubtitleFontSize(fontSizeSp),
                subPos = mpvSubtitlePosition(bottomOffset),
                outlineDp = if (boxed) 0f else outline,
                boxed = boxed,
                boxPaddingDp = maxOf(outline, perScaledPixel),
                frameHeightDp = frameHeightDp,
            )
        }
    }
}

private fun mpvGeometry(
    fontScaledPx: Float,
    subPos: Int,
    outlineDp: Float,
    boxed: Boolean,
    boxPaddingDp: Float,
    frameHeightDp: Float,
): SubtitleFrameGeometry {
    val perScaledPixel = frameHeightDp / MPV_SCALED_FRAME_HEIGHT
    // `sub-pos` 100 sits the text `sub-margin-y` above the bottom; lower values lift it by that many
    // percent of the frame.
    val lift = ((100 - subPos).coerceAtLeast(0) / 100f) * frameHeightDp
    return SubtitleFrameGeometry(
        fontSize = fontScaledPx * perScaledPixel,
        fontSizeIsSp = false,
        bottomDp = MPV_DEFAULT_SUB_MARGIN_Y * perScaledPixel + lift,
        // libass grows the outline outward by its size; a centred stroke needs twice that.
        outlineStrokeDp = outlineDp * 2f,
        boxed = boxed,
        boxPaddingHorizontalDp = boxPaddingDp,
        boxPaddingVerticalDp = boxPaddingDp,
        boxCornerDp = 0f,
    )
}
