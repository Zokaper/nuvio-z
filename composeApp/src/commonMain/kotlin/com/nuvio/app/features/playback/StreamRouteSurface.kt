package com.nuvio.app.features.playback

// No imports, and none may be added. This file is pure by design so it can be compiled
// and run outside Gradle (`AGENTS.md`, "Verifying without Gradle", item 2) - which is the
// only way the route's covering rules get executed at all, since everything that used to
// decide them lived inside a Compose route entry that no test can reach.

/**
 * What `entry<StreamRoute>` puts in front of the user.
 *
 * That route stacks four things over one `StreamsScreen` - the opaque hand-off surface, the
 * quality sheet, the progress overlay, and the list itself - and until this existed each was
 * decided by its own inline expression over the same six flags. Nothing held the rule that
 * matters: **whatever is on top, the user must be able to act on it.** Three paths reached a
 * terminal state with none of the four conditions true, and the hand-off surface kept
 * painting over a screen nobody could see or leave.
 *
 * One function, so a new dead end is a failing test rather than a blank screen.
 */
enum class StreamRouteSurface {
    /** `StreamsScreen` uncovered: Classic, an explicit manual pick, or a spent automatic path. */
    SourceList,

    /** Streamlined's quality picker, over an opaque surface. */
    QualitySheet,

    /** [PlaybackProgressOverlay]: the automatic path is working and can still finish. */
    ProgressOverlay,

    /**
     * Opaque and empty. **Only ever between screens, never a resting state.**
     *
     * Either a hand-off to the player is in flight, or the route is popping itself out to the
     * details screen. `entry<StreamRoute>` owns that guarantee - this function cannot see a
     * navigation, so it cannot enforce it.
     */
    HandOff,
}

/**
 * Everything [streamRouteSurface] needs, gathered by the route.
 *
 * Plain data for the same reason [PlaybackProgressInputs] is: the whole table can be covered
 * without a Compose runtime, which is the only kind of test this route has ever had.
 */
data class StreamRouteSurfaceInputs(
    /** Classic never covers its list, in any state. */
    val isClassic: Boolean,
    /** `launch.manualSelection || launch.downloadIntent` - the user asked for the list. */
    val isManualLaunch: Boolean,
    /** Set by every path that gives up on choosing automatically. */
    val manualSourceListRequested: Boolean,
    /** `reuseNavigated || playbackHandedOff` - playback has been handed off at least once. */
    val hasNavigatedAway: Boolean,
    /** The route decision is `ShowQualitySheet`. */
    val isQualitySheetRoute: Boolean,
    val qualitySheetDismissed: Boolean,
    /** The route decision is `AutoPick`. */
    val isAutoPickRoute: Boolean,
    val isStreamlinedPlaybackStarting: Boolean,
    /** A dialog is up and needs an answer before anything else can happen. */
    val awaitingUserAnswer: Boolean,
)

/**
 * The one place that decides what covers `StreamsScreen`.
 *
 * The ordering is the argument:
 *
 * 1. **An uncovered list wins outright.** Classic, a manual launch and every bail-out are the
 *    cases where the list is the answer, and no later rule may cover it again. Every path that
 *    gives up on choosing automatically ends here, which is the "escape hatch" half of the
 *    rule: in Streamlined the list appears when the app could not choose, never otherwise.
 * 2. **Anything after a hand-off stays covered.** Between screens, in both directions: leaving
 *    for the player, and on the way back out to the details screen. It used to uncover the
 *    list on the way back, which was wrong twice over - it flashed a screen the user chose
 *    Streamlined to avoid, and `consumeAutoPlay` clears the request key, so `StreamsScreen`
 *    immediately re-fetched and the "source loading" screen sat there until a second Back.
 *    **The route must not rest here** - `entry<StreamRoute>` pops itself to details, and falls
 *    back to `manualSourceListRequested` if that pop no-ops.
 * 3. The sheet, while it is still the user's to answer.
 * 4. **A question uncovers the list too**, so dismissing the dialog leaves something usable
 *    behind it rather than the opaque surface.
 * 5. The overlay, while the automatic path can still finish.
 * 6. Hand-off, before a decision exists. The only legitimate blank frame there is.
 */
fun streamRouteSurface(inputs: StreamRouteSurfaceInputs): StreamRouteSurface = when {
    inputs.isClassic || inputs.isManualLaunch || inputs.manualSourceListRequested ->
        StreamRouteSurface.SourceList

    inputs.hasNavigatedAway -> StreamRouteSurface.HandOff

    inputs.isQualitySheetRoute && !inputs.qualitySheetDismissed ->
        StreamRouteSurface.QualitySheet

    inputs.awaitingUserAnswer -> StreamRouteSurface.SourceList

    inputs.isAutoPickRoute || inputs.isStreamlinedPlaybackStarting ->
        StreamRouteSurface.ProgressOverlay

    else -> StreamRouteSurface.HandOff
}
