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

    /** Opaque and empty. Only ever a hand-off, never a resting state. */
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
    /** This route is on top. False while a hand-off to the player is in flight. */
    val isRouteCurrent: Boolean,
    /** `reuseNavigated || playbackHandedOff` - playback has been handed off at least once. */
    val hasNavigatedAway: Boolean,
    /** `autoPlayStream != null` - a candidate is still queued to try. */
    val hasArmedFailureChain: Boolean,
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
 *    cases where the list is the answer, and no later rule may cover it again.
 * 2. **Coming back from the player uncovers.** This is the defect the whole function exists
 *    for. A mode with a failure chain deliberately leaves `StreamRoute` on the back stack, so
 *    the system Back gesture pops the player straight onto it - and the route's
 *    `playbackRouteDecision` is rebuilt from scratch, because `NavDisplay` composes only the
 *    top entry. Sheet gone, overlay suppressed by the hand-off flag, opaque surface still
 *    painting: a blank screen the user could neither read nor leave. Gated on the route being
 *    *current* so it does not flash the list on the way out, and on the chain being spent so
 *    a genuine retry still gets its overlay.
 * 3. The sheet, while it is still the user's to answer.
 * 4. **A question uncovers the list too**, so dismissing the dialog leaves something usable
 *    behind it rather than the opaque surface.
 * 5. Hand-off, while leaving.
 * 6. The overlay, while the automatic path can still finish.
 * 7. Hand-off, before a decision exists. The only legitimate blank frame there is.
 */
fun streamRouteSurface(inputs: StreamRouteSurfaceInputs): StreamRouteSurface = when {
    inputs.isClassic || inputs.isManualLaunch || inputs.manualSourceListRequested ->
        StreamRouteSurface.SourceList

    inputs.hasNavigatedAway && inputs.isRouteCurrent && !inputs.hasArmedFailureChain ->
        StreamRouteSurface.SourceList

    inputs.isQualitySheetRoute && !inputs.qualitySheetDismissed && !inputs.hasNavigatedAway ->
        StreamRouteSurface.QualitySheet

    inputs.awaitingUserAnswer -> StreamRouteSurface.SourceList

    inputs.hasNavigatedAway -> StreamRouteSurface.HandOff

    inputs.isAutoPickRoute || inputs.isStreamlinedPlaybackStarting ->
        StreamRouteSurface.ProgressOverlay

    else -> StreamRouteSurface.HandOff
}
