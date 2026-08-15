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

    /**
     * A stable name for this branch, for saving the decision across a route leaving composition.
     *
     * `NavDisplay` composes only the top entry, so a mode with a failure chain - which
     * deliberately keeps `StreamRoute` on the back stack while the player is open - loses every
     * plain `remember` the moment it hands off. Re-running [PlaybackModeRouter.decide] on the
     * way back is **not** a substitute: by then the play just wrote a reuse-last-link entry, so
     * the same inputs answer [ReuseLastLink] where they first answered [AutoPick], and Instant's
     * retry chain is gated on that answer. The decision has to be carried, not re-derived.
     */
    abstract val key: String

    /** Show the full source list. Classic, and every per-play override. */
    data class ShowSourceList(override val reason: String) : PlaybackRouteDecision() {
        override val key: String get() = KEY
        companion object { const val KEY = "source_list" }
    }

    /** Play a completed local download without touching the network. */
    data class PlayLocalDownload(override val reason: String) : PlaybackRouteDecision() {
        override val key: String get() = KEY
        companion object { const val KEY = "local_download" }
    }

    /** A cached link for this exact video is still valid; reuse it. */
    data class ReuseLastLink(override val reason: String) : PlaybackRouteDecision() {
        override val key: String get() = KEY
        companion object { const val KEY = "reuse_last_link" }
    }

    /**
     * Streamlined: ask which quality, then auto-pick within it.
     *
     * **Nothing consumes this yet.** The quality sheet lands in Phase 2; until then the
     * route entry treats it as [ShowSourceList], which is what keeps Phase 1 behaviour
     * neutral. It is modelled now so the precedence order can be settled and tested in
     * one place rather than twice.
     */
    data class ShowQualitySheet(override val reason: String) : PlaybackRouteDecision() {
        override val key: String get() = KEY
        companion object { const val KEY = "quality_sheet" }
    }

    /**
     * Instant: resolve a tier from the connection and auto-pick.
     *
     * **Nothing consumes this yet** - see [ShowQualitySheet]. Lands in Phase 3, once
     * network quality estimation exists.
     */
    data class AutoPick(override val reason: String) : PlaybackRouteDecision() {
        override val key: String get() = KEY
        companion object { const val KEY = "auto_pick" }
    }

    companion object {
        /**
         * Rebuilds a decision from [key] and [reason], for restoring one that outlived its
         * composition. Unknown keys answer null rather than guessing a branch - a wrong answer
         * here silently changes which selection mechanism runs.
         */
        fun fromKey(key: String?, reason: String): PlaybackRouteDecision? = when (key) {
            ShowSourceList.KEY -> ShowSourceList(reason)
            PlayLocalDownload.KEY -> PlayLocalDownload(reason)
            ReuseLastLink.KEY -> ReuseLastLink(reason)
            ShowQualitySheet.KEY -> ShowQualitySheet(reason)
            AutoPick.KEY -> AutoPick(reason)
            else -> null
        }
    }
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
 * So the order is `manualSelection` > local download > reuse-last-link > mode.
 *
 * A sticky-pin rule used to sit above reuse-last-link, so that a release the user pinned for
 * a season beat a cached link. It was withdrawn in `0.5.0-beta`: the pin could only be
 * created from the long-press escape hatch, so the ordinary Streamlined flow never made one,
 * and once made it silently stopped the quality sheet appearing for that season with nothing
 * in the UI to say why or to clear it. [StickySourcePin] is kept for when it is surfaced
 * properly - this is a product deferral, not a rejection of the idea.
 *
 * Reuse-last-link therefore now answers first for an episode the user has already watched,
 * and Streamlined says so rather than skipping its sheet silently - see the toast raised
 * beside [ReuseLastLink] in `entry<StreamRoute>`.
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

        inputs.reuseLastLinkEnabled && inputs.hasValidCachedLink ->
            PlaybackRouteDecision.ReuseLastLink("a cached link for this video is still valid")

        inputs.mode == PlaybackMode.STREAMLINED ->
            PlaybackRouteDecision.ShowQualitySheet("streamlined mode")

        inputs.mode == PlaybackMode.INSTANT ->
            PlaybackRouteDecision.AutoPick("instant mode")

        else -> PlaybackRouteDecision.ShowSourceList("classic mode")
    }
}
