package com.nuvio.app.features.playback

/**
 * The entrance a play gets between the tap and the surface that answers it.
 *
 * ⚠ **One entrance per play, and it belongs to the surface that arrives - never to the
 * navigator.** `entry<PlayerRoute>` already carries that rule for the hand-off; this is the
 * same rule at the other end. A navigator crossfade moves the *background*, and the background
 * on this path is the same artwork on both sides, so crossfading it only dips the brightness.
 * What actually pops is the content over it: the quality panel and the loading band arriving at
 * full strength on frame one.
 *
 * Pure and primitive-typed so `scripts/run-pure-suites.sh` can execute the curves. The figures
 * are constants rather than parameters for the same reason the loading screen's are: there is
 * one entrance in this flow and a second set of numbers is how the two drift.
 */
object PlaybackEntranceMotion {

    /** Long enough to read as arriving, short enough not to delay a fast start. */
    const val DURATION_MS: Int = 260

    /** The scrim is fully up by here, so the content settles onto a ground that has arrived. */
    const val SCRIM_END_FRACTION: Float = 0.55f

    /** The content waits this far into the entrance. */
    const val PANEL_START_FRACTION: Float = 0.25f

    const val PANEL_RISE_DP: Float = 10f
    const val PANEL_START_SCALE: Float = 0.985f

    fun scrimAlpha(progress: Float): Float =
        (progress.coerceIn(0f, 1f) / SCRIM_END_FRACTION).coerceIn(0f, 1f)

    fun panelProgress(progress: Float): Float =
        ((progress.coerceIn(0f, 1f) - PANEL_START_FRACTION) / (1f - PANEL_START_FRACTION))
            .coerceIn(0f, 1f)

    fun panelAlpha(progress: Float): Float = panelProgress(progress)

    fun panelScale(progress: Float): Float =
        PANEL_START_SCALE + (1f - PANEL_START_SCALE) * panelProgress(progress)

    /** Falls to zero as the panel settles. Dp, applied by the caller against its density. */
    fun panelRiseDp(progress: Float): Float = PANEL_RISE_DP * (1f - panelProgress(progress))
}
