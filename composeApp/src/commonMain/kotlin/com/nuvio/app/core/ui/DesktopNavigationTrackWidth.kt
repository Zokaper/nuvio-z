package com.nuvio.app.core.ui

// No imports, and none may be added: group 3 of `scripts/run-pure-suites.sh` compiles this on its own.

/**
 * How wide the desktop top bar's jelly track is (Nuvio Z, setup polish / physical QA).
 *
 * Upstream sized each tab for a fixed **64 dp** label. Nuvio Z's bar carries Downloads and Social as
 * well, and "Downloads" in the bar's `labelLarge` needs about 70 dp - so it was cut to "Downl…" at
 * every normal window width, and a UI zoom or a longer translation made it worse. Every tab gets the
 * same width (the jelly pill slides between equal slots), so the widest label is what sets it.
 *
 * The slot is `icon + 20 + allowance * labelFraction`; what a label actually has inside it is the slot
 * minus the tab's own padding (8 + 8), the icon glyph (`iconSize - 10`) and the 6 dp gap - i.e.
 * `allowance + 8`. So the allowance is the widest label less 8, plus [LabelSlackDp] so a label that
 * measures a fraction over does not ellipsize on rounding, and never less than upstream's 64.
 */
fun desktopNavigationLabelAllowanceDp(widestLabelDp: Float): Float =
    maxOf(UpstreamLabelAllowanceDp, widestLabelDp - LabelRoomBeyondAllowanceDp + LabelSlackDp)

/** The whole track: equal slots plus the row's 4 dp inset each side, capped at what the window has. */
fun desktopNavigationTrackWidthDp(
    iconSizeDp: Float,
    labelAllowanceDp: Float,
    labelFraction: Float,
    itemCount: Int,
    maxWidthDp: Float,
): Float = ((iconSizeDp + 20f + labelAllowanceDp * labelFraction) * itemCount + 8f).coerceAtMost(maxWidthDp)

/** Whether every label fits at full size: false only when the window itself is too narrow. */
fun desktopNavigationLabelsFit(
    iconSizeDp: Float,
    widestLabelDp: Float,
    itemCount: Int,
    maxWidthDp: Float,
): Boolean {
    val allowance = desktopNavigationLabelAllowanceDp(widestLabelDp)
    val track = desktopNavigationTrackWidthDp(iconSizeDp, allowance, 1f, itemCount, maxWidthDp)
    val slot = (track - 8f) / itemCount
    return slot - (16f + (iconSizeDp - 10f) + 6f) >= widestLabelDp
}

private const val UpstreamLabelAllowanceDp = 64f
private const val LabelRoomBeyondAllowanceDp = 8f
private const val LabelSlackDp = 4f
