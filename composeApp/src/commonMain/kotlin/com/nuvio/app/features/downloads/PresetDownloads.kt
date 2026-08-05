package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

@Serializable
enum class CodecPreference { ANY, HEVC, AV1, AVC }

@Serializable
enum class DynamicRangePolicy { ANY, AVOID_HDR, PREFER_HDR, REQUIRE_HDR, REQUIRE_DOLBY_VISION }

/**
 * Which end of the size range to take among candidates that are otherwise equal.
 *
 * At a given resolution more bytes generally means a higher bitrate, so the
 * largest file still inside the cap is usually the best picture. Frugal presets
 * want the opposite.
 */
@Serializable
enum class SizePreference { LARGEST_UNDER_CAP, SMALLEST }

@Serializable
data class DownloadPreset(
    val id: String,
    val name: String,
    val targetResolution: VideoResolution,
    val gigabytesPerHourLimit: Double,
    val codecPreference: CodecPreference = CodecPreference.HEVC,
    val requirePreferredCodec: Boolean = false,
    val dynamicRangePolicy: DynamicRangePolicy = DynamicRangePolicy.ANY,
    val preferredAudioLanguage: String? = null,
    val requirePreferredAudioLanguage: Boolean = false,
    /**
     * Prefer sources the debrid provider already holds.
     *
     * An uncached source is not refused outright - it is sent to review instead of
     * being started automatically, because the provider answers an uncached
     * request with a placeholder video while it queues the real file.
     */
    val preferCachedSources: Boolean = true,
    val sizePreference: SizePreference = SizePreference.LARGEST_UNDER_CAP,
) {
    init {
        require(id.isNotBlank())
        require(gigabytesPerHourLimit > 0.0)
    }

    fun sizeCapBytes(runtimeMinutes: Int?, isEpisode: Boolean): Long {
        val minutes = runtimeMinutes?.takeIf { it > 0 } ?: if (isEpisode) 45 else 120
        return (gigabytesPerHourLimit * 1_000_000_000.0 * minutes / 60.0).roundToLong()
    }

    companion object {
        val Saver = DownloadPreset(
            id = "saver",
            name = "Saver",
            targetResolution = VideoResolution.HD_720,
            gigabytesPerHourLimit = 0.75,
            codecPreference = CodecPreference.HEVC,
            dynamicRangePolicy = DynamicRangePolicy.AVOID_HDR,
            // Saving space is the whole point of this preset.
            sizePreference = SizePreference.SMALLEST,
        )
        val Balanced = DownloadPreset(
            id = "balanced",
            name = "Balanced",
            targetResolution = VideoResolution.FULL_HD_1080,
            gigabytesPerHourLimit = 1.5,
            codecPreference = CodecPreference.HEVC,
        )
        /**
         * 4K at a bitrate that keeps a season to a sane size.
         *
         * Around 7 GB for an hour-long episode: comfortably above a 2160p web
         * encode, comfortably below a remux.
         */
        val UltraHdLow = DownloadPreset(
            id = "quality_4k_low",
            name = "4K Low",
            targetResolution = VideoResolution.UHD_2160,
            gigabytesPerHourLimit = 8.0,
            codecPreference = CodecPreference.HEVC,
            dynamicRangePolicy = DynamicRangePolicy.PREFER_HDR,
        )

        /** 4K with room for remux-grade sources - around 13 GB for an hour. */
        val UltraHdHigh = DownloadPreset(
            id = "quality_4k_high",
            name = "4K High",
            targetResolution = VideoResolution.UHD_2160,
            gigabytesPerHourLimit = 15.0,
            codecPreference = CodecPreference.HEVC,
            dynamicRangePolicy = DynamicRangePolicy.PREFER_HDR,
        )

        val BuiltIns = listOf(Saver, Balanced, UltraHdLow, UltraHdHigh)

        /**
         * Built-ins that no longer ship, kept only to be recognised on load.
         *
         * The old `Quality` preset asked for 2160p while capping at 4 GB/hour, a
         * combination no real 4K file meets - so it rejected every candidate it
         * was pointed at and reported that they all exceeded the cap. It is
         * replaced by the two tiers above rather than retuned, because the single
         * cap was the problem.
         */
        internal val RetiredBuiltIns = listOf(
            DownloadPreset(
                id = "quality",
                name = "Quality",
                targetResolution = VideoResolution.UHD_2160,
                gigabytesPerHourLimit = 4.0,
                codecPreference = CodecPreference.HEVC,
                dynamicRangePolicy = DynamicRangePolicy.PREFER_HDR,
            ),
        )
    }
}

/**
 * Reconciles a stored preset list with the built-ins this build ships.
 *
 * Presets are persisted, so an install that already exists keeps whatever it
 * saved and would never see a newly added built-in. Merging on load is what makes
 * a new tier reach anyone but a fresh install.
 *
 * A retired built-in is dropped only when it still matches its old default
 * exactly. Untouched, it was never a choice anyone made and keeping it would
 * leave the broken preset in the list; edited, it reflects a decision and stays.
 */
internal fun mergeStoredPresets(stored: List<DownloadPreset>): List<DownloadPreset> {
    val retained = stored.filterNot { preset -> preset in DownloadPreset.RetiredBuiltIns }
    val knownIds = retained.mapTo(mutableSetOf()) { it.id }
    return retained + DownloadPreset.BuiltIns.filterNot { it.id in knownIds }
}

@Serializable
data class AddonSourceKey(
    val manifestId: String,
    val manifestUrl: String,
) {
    val stableValue: String
        get() = "$manifestId|$manifestUrl"
}

@Serializable
data class DownloadSourcePolicy(
    /**
     * Null means all enabled addons. A non-null set is an explicit allowlist,
     * including an empty set.
     */
    val allowedAddons: Set<AddonSourceKey>? = null,
    /**
     * Presence of an AIO key means nested providers are restricted. Unknown
     * providers cannot pass a restricted AIO instance.
     */
    val allowedAioProviders: Map<AddonSourceKey, Set<String>> = emptyMap(),
    val aioOverrides: Set<AddonSourceKey> = emptySet(),
    val discoveredAioProviders: Map<AddonSourceKey, Set<String>> = emptyMap(),
) {
    fun allowsAddon(key: AddonSourceKey): Boolean = allowedAddons?.contains(key) != false

    fun allowsResult(key: AddonSourceKey, facts: SourceFacts): Boolean {
        if (!allowsAddon(key)) return false
        val restriction = allowedAioProviders[key] ?: return true
        val identities = listOfNotNull(facts.providerId, facts.providerName)
            .map { it.trim().lowercase() }
            .filter(String::isNotEmpty)
        return identities.any(restriction.map { it.trim().lowercase() }.toSet()::contains)
    }

    fun snapshot(): DownloadSourcePolicy = copy(
        allowedAddons = allowedAddons?.toSet(),
        allowedAioProviders = allowedAioProviders.mapValues { it.value.toSet() }.toMap(),
        aioOverrides = aioOverrides.toSet(),
        discoveredAioProviders = discoveredAioProviders.mapValues { it.value.toSet() }.toMap(),
    )
}

data class DownloadSourceCandidate(
    val stream: StreamItem,
    val addonKey: AddonSourceKey,
    val facts: SourceFacts,
    /** URL after any direct-debrid resolution. */
    val resolvedUrl: String? = stream.playableDirectUrl,
    val addonOrder: Int = 0,
)

@Serializable
sealed class SourceSelectionResult {
    @Serializable
    @SerialName("selected")
    data class Selected(
        val streamUrl: String,
        val facts: SourceFacts,
        val addonKey: AddonSourceKey,
        val calculatedCapBytes: Long,
    ) : SourceSelectionResult()

    @Serializable
    @SerialName("approval_needed")
    data class ApprovalNeeded(
        val streamUrl: String,
        val facts: SourceFacts,
        val addonKey: AddonSourceKey,
        val calculatedCapBytes: Long,
        val reason: String,
    ) : SourceSelectionResult()

    @Serializable
    @SerialName("no_match")
    data class NoMatch(val reason: String) : SourceSelectionResult()
}

object PresetSourceSelector {
    fun select(
        candidates: List<DownloadSourceCandidate>,
        preset: DownloadPreset,
        policy: DownloadSourcePolicy,
        runtimeMinutes: Int?,
        isEpisode: Boolean,
    ): SourceSelectionResult {
        val cap = preset.sizeCapBytes(runtimeMinutes, isEpisode)
        val eligible = candidates
            .asSequence()
            .filter { policy.allowsResult(it.addonKey, it.facts) }
            .filter { isAutomaticProtocol(it.resolvedUrl) }
            .filter { candidate ->
                candidate.facts.resolution?.height?.let { it <= preset.targetResolution.height } ?: true
            }
            .filter { matchesRequirements(it.facts, preset) }
            .sortedWith(candidateComparator(preset))
            .toList()

        eligible.firstOrNull { candidate ->
            val size = candidate.facts.sizeBytes
            size != null && size <= cap && !candidate.facts.hasConflictingHardMetadata &&
                candidate.facts.resolution != null
        }?.let { candidate ->
            // An uncached debrid source is not started automatically: the provider
            // answers with a "download queued" placeholder while it fetches the real
            // file, so this goes to review rather than silently waiting. A null
            // readiness means direct HTTP or an addon that does not say, which is
            // left alone.
            return if (preset.preferCachedSources && candidate.facts.isDebridReady == false) {
                candidate.approval(cap, "Source is not cached yet")
            } else {
                candidate.selected(cap)
            }
        }

        eligible.firstOrNull { candidate ->
            val size = candidate.facts.sizeBytes
            size == null || candidate.facts.hasConflictingHardMetadata ||
                candidate.facts.resolution == null
        }?.let { candidate ->
            val reason = when {
                candidate.facts.hasConflictingHardMetadata -> "Conflicting source metadata"
                candidate.facts.sizeBytes == null -> "Source size is unknown"
                else -> "Source resolution is unknown"
            }
            return candidate.approval(cap, reason)
        }

        return SourceSelectionResult.NoMatch(
            if (eligible.isEmpty()) "No automatic-download source matched the preset and source policy"
            else "All matching sources exceed the calculated size cap",
        )
    }

    private fun matchesRequirements(facts: SourceFacts, preset: DownloadPreset): Boolean {
        val preferredLanguage = preset.preferredAudioLanguage?.trim()?.uppercase()
        if (preset.requirePreferredAudioLanguage &&
            (preferredLanguage == null || preferredLanguage !in facts.languages)
        ) return false

        if (preset.requirePreferredCodec && preset.codecPreference != CodecPreference.ANY &&
            facts.codec != preset.codecPreference.name
        ) return false

        val hasHdr = facts.dynamicRange.isNotEmpty()
        return when (preset.dynamicRangePolicy) {
            DynamicRangePolicy.REQUIRE_HDR -> hasHdr
            DynamicRangePolicy.REQUIRE_DOLBY_VISION -> "DOLBY_VISION" in facts.dynamicRange
            else -> true
        }
    }

    private fun candidateComparator(preset: DownloadPreset): Comparator<DownloadSourceCandidate> =
        compareByDescending<DownloadSourceCandidate> {
            it.facts.resolution?.height ?: Int.MIN_VALUE
        }.thenByDescending {
            val preferred = preset.preferredAudioLanguage?.trim()?.uppercase()
            preferred != null && preferred in it.facts.languages
        }.thenByDescending {
            when (preset.dynamicRangePolicy) {
                DynamicRangePolicy.AVOID_HDR -> it.facts.dynamicRange.isEmpty()
                DynamicRangePolicy.PREFER_HDR -> it.facts.dynamicRange.isNotEmpty()
                else -> true
            }
        }.thenByDescending {
            preset.codecPreference == CodecPreference.ANY ||
                it.facts.codec == preset.codecPreference.name
        }.thenByDescending {
            releaseQualityScore(it.facts.releaseQuality)
        }.thenByDescending {
            // Below every quality key on purpose: a cached source should decide
            // between otherwise equal candidates, never cost a resolution tier.
            it.facts.isDebridReady == true
        }.thenByDescending {
            it.stream.playableDirectUrl != null
        }.let { comparator ->
            when (preset.sizePreference) {
                // select() takes the first candidate that fits the cap, so ordering
                // largest-first makes that the largest one under the cap.
                SizePreference.LARGEST_UNDER_CAP -> comparator.thenByDescending {
                    it.facts.sizeBytes ?: Long.MIN_VALUE
                }
                SizePreference.SMALLEST -> comparator.thenBy {
                    it.facts.sizeBytes ?: Long.MAX_VALUE
                }
            }
        }.thenBy {
            it.addonOrder
        }.thenBy {
            it.resolvedUrl.orEmpty()
        }

    private fun DownloadSourceCandidate.selected(cap: Long) =
        SourceSelectionResult.Selected(
            streamUrl = requireNotNull(resolvedUrl),
            facts = facts,
            addonKey = addonKey,
            calculatedCapBytes = cap,
        )

    private fun DownloadSourceCandidate.approval(cap: Long, reason: String) =
        SourceSelectionResult.ApprovalNeeded(
            streamUrl = requireNotNull(resolvedUrl),
            facts = facts,
            addonKey = addonKey,
            calculatedCapBytes = cap,
            reason = reason,
        )

    private fun isAutomaticProtocol(url: String?): Boolean {
        val normalized = url?.trim()?.lowercase() ?: return false
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) return false
        return ".m3u8" !in normalized && ".mpd" !in normalized && ".torrent" !in normalized
    }

    private fun releaseQualityScore(value: String?): Int {
        val normalized = value?.uppercase().orEmpty()
        return when {
            "REMUX" in normalized -> 6
            "BLURAY" in normalized || "BLU-RAY" in normalized -> 5
            "WEB-DL" in normalized -> 4
            "WEBRIP" in normalized -> 3
            "HDTV" in normalized -> 2
            "CAM" in normalized -> 0
            else -> 1
        }
    }
}
