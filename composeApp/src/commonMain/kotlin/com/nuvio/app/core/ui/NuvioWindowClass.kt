package com.nuvio.app.core.ui

/**
 * What size of window we are drawing into, as two independent questions.
 *
 * Every responsive decision in this app used to be a bare `maxWidth >= N.dp` written inside the
 * screen that needed it, with each screen choosing its own N: 1040 in the Social tab, 900 and 1180
 * in the Watch Together lobby, 1024 and 1280 in the tokens nobody read. Two consequences, and both
 * shipped:
 *
 * 1. **Nothing anywhere asked how tall the window was.** A landscape phone is about 891 x 411dp -
 *    wide enough to pass for a small tablet and far too short to hold a portrait column - so the
 *    lobby drew its phone-portrait composition into it and spent the entire viewport on a header
 *    and a hero, with the action bar below the fold. A width-only vocabulary cannot express the one
 *    fact that mattered.
 * 2. **Neighbouring screens disagreed about the same window.** At 891dp the lobby's `wide` was
 *    false by 9dp while its `twoPane` was false by 289, and nothing said which of the two a phone
 *    in landscape was supposed to be.
 *
 * ## Import-free on purpose
 *
 * This file names no Compose type - the classes below take plain dp **as `Float`** - so it compiles
 * outside Gradle and is covered by group 2 of `scripts/run-pure-suites.sh`. It is the same argument
 * `core/media/ReleaseTags.kt` and `core/language/LanguageCodes.kt` carry: a rule that decides what
 * the user sees should be executable by a test that needs no emulator, no Skia and no toolchain.
 * The Compose-facing half - the `Dp` overload and the `CompositionLocal` - lives next door in
 * `NuvioWindowClassCompose.kt`, which may import freely.
 *
 * ⚠ **These constants are the single source of truth for the app's breakpoints.**
 * `NuvioTokens.Breakpoint` reads them rather than repeating them; do not write a second copy of any
 * number here into a screen.
 */
object NuvioWindowBreakpoints {
    /** Below this a window holds one column of phone-sized content. */
    const val MEDIUM_WIDTH_DP = 600f

    /** At or above this a window can hold a content column beside something else. */
    const val EXPANDED_WIDTH_DP = 840f

    /**
     * At or above this the Watch Together lobby splits into two panes.
     *
     * Deliberately the value `PartyTwoPaneMinWidth` already carried, so adopting this vocabulary
     * cannot move the desktop layout by a pixel.
     */
    const val WIDE_WIDTH_DP = 1180f

    /**
     * Below this a window cannot hold a stacked phone composition, whatever its width.
     *
     * 520 rather than a rounder number because it has to separate two real devices: a landscape
     * phone is ~411dp tall and must be [WindowHeightClass.Short]; a small tablet in landscape is
     * ~600dp tall and must not be.
     */
    const val REGULAR_HEIGHT_DP = 520f

    /** At or above this there is room for a hero, a rail and a roster without anything folding. */
    const val TALL_HEIGHT_DP = 900f
}

/** How much horizontal room there is, in the four bands the layouts actually branch on. */
enum class WindowWidthClass { Compact, Medium, Expanded, Wide }

/** How much vertical room there is. [Short] is, in practice, "a phone held sideways". */
enum class WindowHeightClass { Short, Regular, Tall }

fun windowWidthClassFor(widthDp: Float): WindowWidthClass = when {
    widthDp >= NuvioWindowBreakpoints.WIDE_WIDTH_DP -> WindowWidthClass.Wide
    widthDp >= NuvioWindowBreakpoints.EXPANDED_WIDTH_DP -> WindowWidthClass.Expanded
    widthDp >= NuvioWindowBreakpoints.MEDIUM_WIDTH_DP -> WindowWidthClass.Medium
    else -> WindowWidthClass.Compact
}

fun windowHeightClassFor(heightDp: Float): WindowHeightClass = when {
    heightDp >= NuvioWindowBreakpoints.TALL_HEIGHT_DP -> WindowHeightClass.Tall
    heightDp >= NuvioWindowBreakpoints.REGULAR_HEIGHT_DP -> WindowHeightClass.Regular
    else -> WindowHeightClass.Short
}

/**
 * The window, classified. Construct it from a `BoxWithConstraints` with `nuvioWindowClass()`.
 *
 * The derived properties below are the vocabulary screens should branch on. Prefer naming the
 * *situation* ([isShortSurface]) over the measurement, so a layout reads as the case it handles.
 */
data class NuvioWindowClass(
    val widthDp: Float,
    val heightDp: Float,
) {
    val widthClass: WindowWidthClass = windowWidthClassFor(widthDp)
    val heightClass: WindowHeightClass = windowHeightClassFor(heightDp)

    /** One phone-width column. The portrait phone case. */
    val isCompactWidth: Boolean get() = widthClass == WindowWidthClass.Compact

    /** Too short to stack. The landscape phone case, and split-screen. */
    val isShortSurface: Boolean get() = heightClass == WindowHeightClass.Short

    /**
     * Wide enough to put two regions side by side, but too short to stack them.
     *
     * This is the case that had no name before and therefore no layout: a landscape phone. It is
     * the signal the lobby's two-region composition keys on.
     */
    val isShortWide: Boolean
        get() = isShortSurface && widthClass != WindowWidthClass.Compact

    /**
     * Either dimension is phone-sized, so phone densities and phone type sizes apply.
     *
     * Note this is true for a landscape phone as well as a portrait one, which is the point: a
     * 891 x 411dp window is not a small desktop and must not be given desktop card heights.
     */
    val isPhoneSurface: Boolean get() = isCompactWidth || isShortSurface

    /** The lobby's desktop two-pane layout. Unchanged from `PartyTwoPaneMinWidth`. */
    val isTwoPaneSurface: Boolean
        get() = widthClass == WindowWidthClass.Wide && !isShortSurface
}
