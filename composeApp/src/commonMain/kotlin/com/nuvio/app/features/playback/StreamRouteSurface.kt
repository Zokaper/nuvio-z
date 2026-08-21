package com.nuvio.app.features.playback

// No imports, and none may be added. This file is pure by design so it can be compiled
// and run outside Gradle (`AGENTS.md`, "Verifying without Gradle", item 2) - which is the
// only way the route's covering rules get executed at all, since everything that used to
// decide them lived inside a Compose route entry that no test can reach.

/**
 * How many sources an automatic path may try before it hands the screen back.
 *
 * Lives here rather than beside the overlay that prints it because the overlay is a Compose
 * file, and a budget nothing can execute is a budget that drifts from the code spending it -
 * which is exactly what happened. `entry<StreamRoute>` seeded the *whole* ranked row while the
 * overlay coerced its display to this number, so a deep bucket ground through nine candidates
 * showing "Attempt 3 of 3": a progress figure that stops moving, which reads as a hang.
 *
 * `PlaybackProgress.MAX_ATTEMPTS` is this value; both names exist so the overlay can keep
 * talking about itself, and there is still only one number.
 */
const val PLAYBACK_MAX_ATTEMPTS: Int = 3

/**
 * The chain an automatic path walks: the winner, then as many fallbacks as the budget allows.
 *
 * Generic because this file may not import [com.nuvio.app.features.streams.StreamItem] - see
 * the note at the top - and because the rule is about counting, not about streams.
 *
 * Capping at the seed rather than at the walk is deliberate: `StreamsRepository` answers
 * "is there a next candidate?" and every bail-out in the route is written against that answer.
 * A budget enforced anywhere else would need a second way to say "spent", and two of those is
 * how one of them ends up not being checked.
 */
fun <T> playbackChain(winner: T, fallbacks: List<T>): List<T> =
    listOf(winner) + fallbacks.take(PLAYBACK_MAX_ATTEMPTS - 1)

/**
 * Whether the progress overlay should offer a way out yet.
 *
 * The overlay covers `StreamsScreen` completely and consumes pointer input, so until this
 * answers true the only exit is Back - which abandons the play rather than dropping to the
 * source list. That was survivable while the automatic path was fast and became a trap the
 * moment a debrid mint went slow: the user tapped a quality, got a spinner, and had no way to
 * say "just show me the list" without losing the tap.
 *
 * Not shown from the first frame, because the happy path resolves in well under a second and
 * an escape hatch offered before anything has gone wrong invites the user to leave a flow that
 * was about to work. Either signal opens it: a failure has been seen ([attempt] above 1), or
 * enough wall-clock has passed that the wait itself is the problem.
 */
fun shouldOfferManualEscape(attempt: Int, elapsedMs: Long): Boolean =
    attempt > 1 || elapsedMs >= MANUAL_ESCAPE_DELAY_MS

/**
 * How long a silent automatic start may run before it offers the source list.
 *
 * Five seconds is past the point where a working debrid mint has answered and well short of
 * [STREAMLINED_SELECTION_TIMEOUT_MS], which is the backstop for a wait nothing else bounds.
 * This is not that: it adds a choice, it never takes the wait away.
 */
const val MANUAL_ESCAPE_DELAY_MS: Long = 5_000L

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
    /**
     * A band chosen earlier in this sitting is going to answer the sheet's question.
     *
     * Set while the route is still waiting for the fetch to settle, so the sheet is never drawn
     * on a play that is not going to ask anything. Without it the user watched a skeleton grid
     * appear and vanish on every episode - a question flashed and withdrawn, which is worse
     * than either asking or not.
     *
     * Cleared by the route the moment `PlaybackQualityOptions.rememberedOption` answers null:
     * this episode has no release in that band, so the question is live again and the sheet is
     * the honest answer.
     */
    val hasRememberedBand: Boolean = false,
    /**
     * The route decision is `AutoPick` - Instant, which has no sheet to draw.
     *
     * This input was removed in `0.5.0-beta` with a note saying two flags meaning "the
     * automatic path is working" is how one of them ends up not being cleared. That note is
     * right and this is not one of those flags: it is a **route identity**, derived from
     * `playbackRouteDecision is AutoPick` exactly as [isQualitySheetRoute] is derived from
     * `ShowQualitySheet`. Nothing sets it and nothing clears it.
     *
     * The rule the note was actually protecting is honoured by
     * [isAutoPlaybackStarting] instead, which is one flag for both modes.
     */
    val isAutoPickRoute: Boolean = false,
    /**
     * A quality has been chosen - by the user, by a remembered band, or by the connection -
     * and the automatic path is running.
     *
     * **One flag for Streamlined and Instant**, deliberately. It was
     * `isStreamlinedPlaybackStarting` while Instant was withdrawn.
     */
    val isAutoPlaybackStarting: Boolean,
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
 * 3. **A remembered band answers the sheet's question**, so the overlay owns the screen instead
 *    and the sheet is never drawn on a play that is not going to ask. Above the sheet rather
 *    than below it because the sheet's own condition is still true here - the route is
 *    `ShowQualitySheet` and nothing has been dismissed - and drawing it for the frames before
 *    the auto-selection lands is a question flashed and withdrawn.
 * 4. **Instant covers the screen from the start**, because it has no sheet: its equivalent of
 *    the question is the overlay reporting on a decision being made. Without this rule an
 *    Instant play matched nothing and fell to rule 8 - an opaque, empty, pointer-consuming
 *    screen over a source list, which is the exact fault [streamRouteSurface] was written to
 *    kill. Above the dialog rule so the metered question is asked over the overlay rather than
 *    over the list Instant exists to avoid; below the bail-outs so every give-up still wins.
 * 5. The sheet, while it is still the user's to answer.
 * 6. **A question uncovers the list too**, so dismissing the dialog leaves something usable
 *    behind it rather than the opaque surface.
 * 7. The overlay, while the automatic path can still finish.
 * 8. Hand-off, before a decision exists. The only legitimate blank frame there is.
 */
fun streamRouteSurface(inputs: StreamRouteSurfaceInputs): StreamRouteSurface = when {
    inputs.isClassic || inputs.isManualLaunch || inputs.manualSourceListRequested ->
        StreamRouteSurface.SourceList

    inputs.hasNavigatedAway -> StreamRouteSurface.HandOff

    inputs.isQualitySheetRoute && inputs.hasRememberedBand && !inputs.qualitySheetDismissed ->
        StreamRouteSurface.ProgressOverlay

    inputs.isAutoPickRoute && !inputs.qualitySheetDismissed ->
        StreamRouteSurface.ProgressOverlay

    inputs.isQualitySheetRoute && !inputs.qualitySheetDismissed ->
        StreamRouteSurface.QualitySheet

    inputs.awaitingUserAnswer -> StreamRouteSurface.SourceList

    inputs.isAutoPlaybackStarting -> StreamRouteSurface.ProgressOverlay

    else -> StreamRouteSurface.HandOff
}
