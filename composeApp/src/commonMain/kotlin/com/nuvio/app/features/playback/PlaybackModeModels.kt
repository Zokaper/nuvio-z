package com.nuvio.app.features.playback

import com.nuvio.app.features.downloads.CodecPreference
import com.nuvio.app.features.downloads.DynamicRangePolicy
import com.nuvio.app.features.downloads.VideoResolution
import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

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
    }
}

/**
 * A playback quality target: a resolution ceiling plus a *bandwidth* ceiling.
 *
 * Deliberately not a [com.nuvio.app.features.downloads.DownloadPreset]. A preset caps
 * bytes on disk (GB per hour of runtime); a tier caps bytes per second off the network.
 * They look alike and mean different things, and sharing one type would mean a user who
 * shrank a preset to fit a season on their phone silently degraded their streaming too.
 *
 * What the two *do* share is ranking - see [com.nuvio.app.features.downloads.SourceRanking].
 *
 * **Dormant.** Nothing reads a tier to choose a source any more: quality options are derived
 * from the catalogue instead - see [PlaybackQualityOptions]. The type, its storage key and
 * its sync entries are kept only so an existing install's stored blob stays readable and the
 * `syncKeysToClear` contract is not disturbed mid-change; an incomplete edit to that key set
 * is what wiped the playback settings in `0.4.0-beta`. Remove it in its own commit.
 */
@Serializable
data class PlaybackQualityTier(
    val id: String,
    val name: String,
    val targetResolution: VideoResolution,
    /**
     * Sustained throughput this tier assumes, in megabits per second.
     *
     * [PlaybackSourceSelector] spends only a fraction of it - see [HEADROOM] - because a
     * source picked to exactly saturate the line stalls the moment anything else on the
     * network wants bandwidth.
     */
    val megabitsPerSecond: Double,
    val codecPreference: CodecPreference = CodecPreference.ANY,
    val dynamicRangePolicy: DynamicRangePolicy = DynamicRangePolicy.ANY,
) {
    init {
        require(id.isNotBlank())
        require(megabitsPerSecond > 0.0)
    }

    /**
     * The largest file this tier will accept for a title of the given runtime.
     *
     * Mirrors `DownloadPreset.sizeCapBytes` including its runtime fallbacks, so the two
     * pickers disagree about *what* they cap and never about how long a thing is.
     */
    fun sizeCapBytes(runtimeMinutes: Int?, isEpisode: Boolean): Long {
        val minutes = runtimeMinutes?.takeIf { it > 0 } ?: if (isEpisode) 45 else 120
        val bytesPerSecond = megabitsPerSecond * HEADROOM * 1_000_000.0 / 8.0
        return (bytesPerSecond * minutes * 60.0).roundToLong()
    }

    companion object {
        /**
         * Share of the estimated line rate a source may occupy.
         *
         * The remaining 40% is what absorbs a co-tenant on the Wi-Fi, a debrid host
         * having a slow minute, and the fact that an average bitrate is an average -
         * a high-motion scene can run well above it. This headroom is the main reason
         * automatic downshifting should rarely need to fire.
         */
        const val HEADROOM = 0.6

        val Data = PlaybackQualityTier(
            id = "tier_data",
            name = "Data saver",
            targetResolution = VideoResolution.SD,
            megabitsPerSecond = 1.0,
            dynamicRangePolicy = DynamicRangePolicy.AVOID_HDR,
        )
        val Low = PlaybackQualityTier(
            id = "tier_720",
            name = "720p",
            targetResolution = VideoResolution.HD_720,
            megabitsPerSecond = 2.0,
        )
        val Standard = PlaybackQualityTier(
            id = "tier_1080",
            name = "1080p",
            targetResolution = VideoResolution.FULL_HD_1080,
            megabitsPerSecond = 3.0,
        )
        val High = PlaybackQualityTier(
            id = "tier_1080_high",
            name = "1080p High",
            targetResolution = VideoResolution.FULL_HD_1080,
            megabitsPerSecond = 7.0,
        )
        val Ultra = PlaybackQualityTier(
            id = "tier_2160",
            name = "4K",
            targetResolution = VideoResolution.UHD_2160,
            megabitsPerSecond = 22.0,
            dynamicRangePolicy = DynamicRangePolicy.PREFER_HDR,
        )

        /** Ascending by bandwidth. Order is relied on when resolving a measurement to a tier. */
        val BuiltIns = listOf(Data, Low, Standard, High, Ultra)

        /** Defaults shipped in 0.4.3-beta, used only to migrate untouched stored tiers. */
        private val PreviousBuiltIns = listOf(
            Data.copy(megabitsPerSecond = 2.0),
            Low.copy(megabitsPerSecond = 5.0),
            Standard.copy(megabitsPerSecond = 12.0),
            High.copy(megabitsPerSecond = 25.0),
            Ultra.copy(megabitsPerSecond = 55.0),
        ).associateBy(PlaybackQualityTier::id)

        /**
         * Built-ins that no longer ship, kept only to be recognised on load.
         *
         * Empty today. Anything removed from [BuiltIns] in future must be listed here or
         * it lingers on existing installs forever - the same trap the retired `quality`
         * download preset fell into.
         */
        internal val RetiredBuiltIns = emptyList<PlaybackQualityTier>()

        /**
         * Reconciles a stored tier list with the built-ins this build ships.
         *
         * Tiers are persisted once edited, so a newly added built-in would otherwise reach
         * only fresh installs. A retired built-in is dropped only while it still matches
         * its old default exactly: edited, it reflects a decision someone made and stays.
         *
         * Deliberately the same shape as `mergeStoredPresets`.
         */
        fun mergeStoredTiers(stored: List<PlaybackQualityTier>): List<PlaybackQualityTier> {
            val retained = stored.filterNot { it in RetiredBuiltIns }.map { tier ->
                val previousDefault = PreviousBuiltIns[tier.id]
                if (tier == previousDefault) BuiltIns.first { it.id == tier.id } else tier
            }
            val knownIds = retained.mapTo(mutableSetOf()) { it.id }
            return retained + BuiltIns.filterNot { it.id in knownIds }
        }
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
