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
     * Instant is withdrawn until its selection has been watched working on a real device.
     *
     * The original reason - it picked a tier from a measured line and then had no ceiling to
     * hold it - **no longer applies**: options are derived from the catalogue by
     * [PlaybackQualityOptions] and `stickyAffordable` costs each one against the estimate. Nor
     * is the estimate a platform guess any more; `NetworkStrengthProbe` measures the host
     * before the first play and `NetworkThroughputMeter` keeps measuring during it.
     *
     * What is left is evidence. Instant's bounded failure chain, its metered consent and its
     * automatic downshift have all passed on tests alone and none has been seen on a device,
     * and `playbackAutoDownshift` is still default-off pending the measured buffer ceiling
     * `STATUS.md` asks for. Withdrawing it is not a decision about the idea; it is a decision
     * not to ship a mode nobody has watched behave.
     */
    val isSelectable: Boolean
        get() = this != INSTANT

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
         * The mode a profile stored on [INSTANT] behaves as while Instant is withdrawn.
         *
         * Streamlined, because it is the closest experience: the source is still chosen for
         * the user, they only add one tap for quality. Classic would take away the automatic
         * selection they opted into.
         *
         * Applied at **read** time, deliberately. Rewriting storage would forget the choice
         * for good, so re-enabling Instant later would silently leave those profiles behind;
         * this way the stored key is untouched and they come back on their own.
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
