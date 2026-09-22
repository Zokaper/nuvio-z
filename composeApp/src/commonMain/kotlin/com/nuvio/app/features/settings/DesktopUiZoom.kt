package com.nuvio.app.features.settings

/**
 * How large the desktop interface is drawn, as a percentage of the size the app picks for itself.
 *
 * ## Why a percentage of automatic, and not an absolute scale
 *
 * `desktopUiScaleForWindow` already derives a scale from the window size, so the app is roughly
 * the right size on a laptop and on a 4K panel without anyone touching a setting. This value is a
 * multiplier **on top of that**, which means [Default] (100%) reads as "whatever the app decided"
 * on every machine.
 *
 * An absolute scale would not survive moving between displays: 100% would mean "small" on a 4K
 * monitor and "correct" on a 1280 window, so the same stored number would be wrong somewhere. It
 * is also why `Ctrl+0` can be described simply as "reset" - it returns to automatic rather than to
 * some fixed size the user has to know about.
 *
 * These are the browser zoom steps, deliberately: the interaction is `Ctrl` `+` / `-` / `0`, and
 * matching the ladder people already have in their fingers is worth more than a rounder set of
 * numbers.
 *
 * ⚠ **Device-local, never synced.** See the note in `ThemeSettingsStorage.desktop.kt` - a 4K
 * desktop and a laptop want different answers, so syncing this would guarantee one of them is
 * wrong.
 */
enum class DesktopUiZoom(
    /** The multiplier applied to the automatic scale, as a percentage. */
    val percent: Int,
) {
    P50(50),
    P67(67),
    P75(75),
    P90(90),
    P100(100),
    P110(110),
    P125(125),
    P150(150),
    P175(175),
    P200(200),
    ;

    /** The multiplier to apply to the automatic scale. */
    val factor: Float get() = percent / 100f

    /** Human-readable, and not a string resource: "125%" needs no translating. */
    val label: String get() = "$percent%"

    /** The next step up, or this one when already at the top. */
    fun zoomedIn(): DesktopUiZoom = entries.getOrElse(ordinal + 1) { this }

    /** The next step down, or this one when already at the bottom. */
    fun zoomedOut(): DesktopUiZoom = entries.getOrElse(ordinal - 1) { this }

    companion object {
        /** 100% - i.e. exactly what the automatic scale chose. */
        val Default = P100

        /**
         * Resolves a stored value, falling back to [Default].
         *
         * Stored **by percentage rather than by enum name**, so that adding or removing a step in
         * a later release cannot resolve to a different size than the user picked. A percentage
         * that no longer exists snaps to the nearest one that does, which is a better answer than
         * silently resetting somebody's zoom to 100%.
         */
        fun fromPercent(percent: Int?): DesktopUiZoom {
            if (percent == null) return Default
            return entries.minByOrNull { kotlin.math.abs(it.percent - percent) } ?: Default
        }
    }
}
