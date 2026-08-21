package com.nuvio.app.features.playback

import kotlinx.serialization.Serializable

/**
 * How much of the source decision the app makes on the user's behalf.
 *
 * The mode is global and chosen once, but it is never a trap: a per-play override
 * (long-press on mobile, right-click on desktop) always reaches the Classic source
 * list, and the player keeps a "Change source" action in every mode.
 */
@Serializable
enum class PlaybackMode {
    /** Today's flow. The user picks the source, and nothing is scored for them. */
    CLASSIC,

    /** The user picks a quality tier; [PlaybackSourceSelector] picks the source. */
    STREAMLINED,

    /** Tier and source both come from the measured network connection. */
    INSTANT,
    ;

    /**
     * Whether this mode may be chosen right now.
     *
     * **The only availability test in the codebase.** Nothing else may ask `== INSTANT` to
     * decide whether a mode can be picked: `0.4.0-beta` shipped a stale "Not ready yet"
     * caption precisely because two files described the modes independently, and the
     * machinery that produced it was deleted in `0.4.1-beta` rather than fixed.
     *
     * **All three modes ship.** Kept as a property rather than deleted because it is the
     * mechanism a withdrawal uses, and it has been used twice: `0.4.10-beta` withheld Instant
     * here, and `0.5.0-beta` deleted its route paths on top of that.
     *
     * Instant came back once every reason it was pulled had been answered, and each was
     * answered by work done for Streamlined rather than for Instant:
     *
     *  - the estimate was a platform guess, and then a *mean* that under-read worse the faster
     *    the line was. `core/network/ThroughputWindow.kt` reports a sustained windowed rate,
     *    every `httpMeasureThroughput` actual feeds it, and a probe that cannot measure now
     *    says so in a log instead of failing silently;
     *  - the figure moved while it was being read. The route waits for the probe to settle
     *    before it decides, on the same signal the quality sheet waits on;
     *  - there was no ceiling to hold what it picked. `playback_quality_ceiling_mbps` is
     *    applied in [PlaybackQualityOptions.build] before bucketing, so even Best available
     *    honours it, and the bands are absolute rather than one title's own spread;
     *  - a dead source dead-ended the mode. The capped failure chain, the overlay that names
     *    the dead source, `shouldOfferManualEscape` and `giveUpToSourceList` are all shared.
     *
     * ⚠ **Automatic downshift is withheld separately, by `AUTO_DOWNSHIFT_AVAILABLE` in
     * [AutoDownshiftDetector].** It used to be gated on `INSTANT.isSelectable` and would have
     * ridden back in on this line for free, having never once run on a device.
     */
    val isSelectable: Boolean
        get() = true

    companion object {
        /**
         * Existing installs must land on [CLASSIC].
         *
         * The first-launch selector is shown to everyone, including people who have
         * been using the app for months, and it is pre-selected to Classic so that
         * dismissing it changes nothing about how their app behaves.
         */
        val Default = CLASSIC

        fun fromStorage(value: String?): PlaybackMode =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: Default

        /**
         * The mode a profile behaves as while its stored choice is withdrawn.
         *
         * **The identity today**, because every mode is selectable. It is kept because the
         * read-time shape is the load-bearing part, and it just proved itself: a profile that
         * chose Instant before `0.4.10-beta` withheld it was read as [STREAMLINED] for two
         * releases with its stored key untouched, and came back to Instant on its own the
         * moment `isSelectable` said yes. Rewriting storage would have forgotten those
         * choices for good.
         *
         * Streamlined is the coercion target, not Classic, for the same reason: the source is
         * still chosen for the user and they only add one tap for quality, where Classic would
         * take away the automatic selection they opted into.
         */
        fun coerceSelectable(mode: PlaybackMode): PlaybackMode =
            if (mode.isSelectable) mode else STREAMLINED
    }
}

/**
 * A release the user pinned, so the rest of a season plays the same thing.
 *
 * Stored through `BingeGroupCacheRepository` rather than a store of its own: that
 * repository already means "remember the release for this content", and two mechanisms
 * for one idea can disagree.
 *
 * Matching is by descending strictness - see [matches]. A pin that matches nothing is
 * silently ignored rather than blocking playback, because a season routinely contains
 * one episode the pinned group never released.
 */
@Serializable
data class StickySourcePin(
    val releaseGroup: String? = null,
    val bingeGroup: String? = null,
    val addonId: String? = null,
    val providerId: String? = null,
    val resolutionHeight: Int? = null,
) {
    /** True when this pin carries nothing to match on, in which case it must be ignored. */
    val isEmpty: Boolean
        get() = releaseGroup.isNullOrBlank() && bingeGroup.isNullOrBlank() &&
            addonId.isNullOrBlank() && providerId.isNullOrBlank()

    /**
     * Strength of the match, or null for no match at all.
     *
     * Higher is better, and callers should take the best-scoring candidate rather than
     * the first: a season pack and a single episode from the same group both match on
     * [releaseGroup], and the one that also matches the resolution is the right one.
     */
    fun matchStrength(
        candidateReleaseGroup: String?,
        candidateBingeGroup: String?,
        candidateAddonId: String?,
        candidateProviderId: String?,
        candidateResolutionHeight: Int?,
    ): Int? {
        if (isEmpty) return null
        var score = 0
        if (!releaseGroup.isNullOrBlank()) {
            if (!releaseGroup.equals(candidateReleaseGroup?.trim(), ignoreCase = true)) return null
            score += 8
        } else if (!bingeGroup.isNullOrBlank()) {
            if (!bingeGroup.equals(candidateBingeGroup?.trim(), ignoreCase = true)) return null
            score += 4
        }
        if (!addonId.isNullOrBlank()) {
            if (releaseGroup.isNullOrBlank() && bingeGroup.isNullOrBlank() &&
                !addonId.equals(candidateAddonId?.trim(), ignoreCase = true)
            ) return null
            if (addonId.equals(candidateAddonId?.trim(), ignoreCase = true)) score += 2
        }
        if (!providerId.isNullOrBlank()) {
            if (releaseGroup.isNullOrBlank() && bingeGroup.isNullOrBlank() &&
                !providerId.equals(candidateProviderId?.trim(), ignoreCase = true)
            ) return null
            if (providerId.equals(candidateProviderId?.trim(), ignoreCase = true)) score += 2
        }
        if (resolutionHeight != null) {
            if (releaseGroup.isNullOrBlank() && bingeGroup.isNullOrBlank() &&
                resolutionHeight != candidateResolutionHeight
            ) return null
            if (resolutionHeight == candidateResolutionHeight) score += 1
        }
        return score.takeIf { it > 0 }
    }
}
