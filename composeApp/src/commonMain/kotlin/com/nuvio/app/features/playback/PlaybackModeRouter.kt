package com.nuvio.app.features.playback

/**
 * Which branch of the stream route runs, and why.
 *
 * [reason] exists so the decision can be logged and asserted on without reading it back
 * out of navigation state - the whole point of hoisting this out of `entry<StreamRoute>`
 * is that the ordering becomes something a test can pin down.
 */
sealed class PlaybackRouteDecision {
    abstract val reason: String

    /** Show the full source list. Classic, and every per-play override. */
    data class ShowSourceList(override val reason: String) : PlaybackRouteDecision()

    /** Play a completed local download without touching the network. */
    data class PlayLocalDownload(override val reason: String) : PlaybackRouteDecision()

    /** A pinned release matched; play it and skip the quality sheet. */
    data class PlayStickyPin(override val reason: String) : PlaybackRouteDecision()

    /** A cached link for this exact video is still valid; reuse it. */
    data class ReuseLastLink(override val reason: String) : PlaybackRouteDecision()

    /** Streamlined: ask which quality, then auto-pick within it. */
    data class ShowQualitySheet(override val reason: String) : PlaybackRouteDecision()

    /** Instant: resolve a tier from the connection and auto-pick. */
    data class AutoPick(override val reason: String) : PlaybackRouteDecision()
}

/**
 * Everything the decision depends on, gathered by the caller.
 *
 * Deliberately plain data: no repositories, no Compose state, no suspend. The route entry
 * gathers these and this object decides, so the ordering below is the only place the
 * precedence exists and a test can cover all of it.
 */
data class PlaybackRouteInputs(
    val mode: PlaybackMode,
    /** `StreamLaunch.manualSelection` - the long-press / right-click / "Select source" path. */
    val manualSelection: Boolean,
    val hasCompletedLocalDownload: Boolean,
    /** A pin exists for this series+season *and* at least one candidate matches it. */
    val hasMatchingStickyPin: Boolean,
    val reuseLastLinkEnabled: Boolean,
    val hasValidCachedLink: Boolean,
)

/**
 * The single source of truth for which selection mechanism wins.
 *
 * Two mechanisms already ran here before playback modes existed, and the live ordering
 * they established is preserved rather than replaced:
 *
 *  - `manualSelection` gates the completed-download shortcut (`App.kt`, in
 *    `launchPlaybackWithDownloadPreference`);
 *  - the reuse-last-link effect is itself gated on `!launch.manualSelection` and fires
 *    *before* auto-play evaluation.
 *
 * So the order is `manualSelection` > local download > sticky pin > reuse-last-link > mode.
 * The sticky pin is inserted above reuse-last-link on purpose: without that, a Streamlined
 * user with reuse-last-link on would never see the quality sheet, because the reuse branch
 * would answer first for every episode they had already watched.
 *
 * `streamAutoPlayMode` (MANUAL / FIRST_STREAM / REGEX_MATCH) is **not** an input here. It
 * stays a Classic-only setting; letting it run alongside [PlaybackSourceSelector] would put
 * two pickers on the same candidate set with no rule about which is right.
 */
object PlaybackModeRouter {

    fun decide(inputs: PlaybackRouteInputs): PlaybackRouteDecision = when {
        inputs.manualSelection ->
            PlaybackRouteDecision.ShowSourceList("manual selection requested")

        inputs.hasCompletedLocalDownload ->
            PlaybackRouteDecision.PlayLocalDownload("a completed download exists on this device")

        // Only Streamlined pins. Classic never auto-picks, and Instant answers to the
        // connection rather than to a release the user liked three episodes ago.
        inputs.mode == PlaybackMode.STREAMLINED && inputs.hasMatchingStickyPin ->
            PlaybackRouteDecision.PlayStickyPin("a pinned release matched this episode")

        inputs.reuseLastLinkEnabled && inputs.hasValidCachedLink ->
            PlaybackRouteDecision.ReuseLastLink("a cached link for this video is still valid")

        inputs.mode == PlaybackMode.STREAMLINED ->
            PlaybackRouteDecision.ShowQualitySheet("streamlined mode")

        inputs.mode == PlaybackMode.INSTANT ->
            PlaybackRouteDecision.AutoPick("instant mode")

        else -> PlaybackRouteDecision.ShowSourceList("classic mode")
    }
}
