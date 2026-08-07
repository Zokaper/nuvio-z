package com.nuvio.app.features.playback

import com.nuvio.app.features.downloads.CodecPreference
import com.nuvio.app.features.downloads.DynamicRangePolicy
import com.nuvio.app.features.downloads.SizePreference
import com.nuvio.app.features.downloads.SourceRanking
import com.nuvio.app.features.downloads.SourceRankingPreferences
import com.nuvio.app.features.downloads.VideoResolution
import kotlin.math.sqrt

/**
 * The quality choices for one title, derived from the sources that actually exist for it.
 *
 * This is the inversion of the old preset model. A [PlaybackQualityTier] was a budget
 * the catalogue was filtered to fit, so the sheet could offer "4K" for a title nobody has
 * ever released in 4K, and quote a bandwidth figure belonging to the preset rather than to
 * any file the user would receive. Here the catalogue comes first: sources are bucketed by
 * the resolution they claim, each bucket is split by what it really costs to stream, and a
 * bucket with nothing in it simply produces no row.
 *
 * Pure and repository-free, like [PlaybackModeRouter] and [AutoDownshiftDetector], so the
 * shipped code can be exercised outside Gradle.
 */
data class PlaybackQualityOption(
    /**
     * Stable across a refetch. The streamlined sheet round-trips the chosen id through
     * `rememberSaveable`, so it is built from resolution and variant - never from a
     * position in a list, which reorders whenever an addon answers in a different order.
     */
    val id: String,
    val resolution: VideoResolution?,
    val variant: Variant,
    /** Connection speed this option needs, headroom included. Null only for [Variant.BEST]. */
    val requiredMbps: Double?,
    /** The representative source's own bitrate, before headroom. Null when no size was known. */
    val representativeBitrateMbps: Double?,
    /** True when [requiredMbps] came from a nominal figure because no source reported a size. */
    val isEstimateApproximate: Boolean,
    /** Size of the source this option would play, when known. */
    val representativeSizeBytes: Long?,
    /** The whole bucket, best first, so the failure chain still has somewhere to go. */
    val candidates: List<PlaybackSourceCandidate>,
) {
    enum class Variant { BEST, HIGH, LOW, SINGLE }

    /** "4K", "1080p", "SD". The High/Low half of the label is localized by the caller. */
    val resolutionLabel: String
        get() = when (resolution) {
            VideoResolution.UHD_4320 -> "8K"
            VideoResolution.UHD_2160 -> "4K"
            VideoResolution.QHD_1440 -> "1440p"
            VideoResolution.FULL_HD_1080 -> "1080p"
            VideoResolution.HD_720 -> "720p"
            VideoResolution.SD -> "SD"
            null -> ""
        }
}

object PlaybackQualityOptions {

    /**
     * Share of the line a stream may occupy, and the only place it is applied on this path -
     * `sizeCapBytes` folded the same idea into a byte cap, and a build using both would
     * charge for it twice.
     *
     * The old tier value was 0.6, which demanded a 1.67x margin: a 19 Mbps 4K release read as
     * needing 31 Mbps and was refused on a connection comfortably streaming it. That margin
     * suits a live ladder with no buffer, not a VOD player that buffers seconds ahead and has
     * [AutoDownshiftDetector] behind it. A third over the file's own bitrate is the honest
     * number to quote and the one to judge by.
     */
    const val HEADROOM = 0.75

    /** A bucket splits only when its top source costs at least this much more than its cheapest. */
    private const val SPLIT_RATIO = 1.5

    fun build(
        candidates: List<PlaybackSourceCandidate>,
        context: PlaybackSelectionContext,
    ): List<PlaybackQualityOption> {
        if (candidates.isEmpty()) return emptyList()

        val measured = candidates.map { candidate ->
            val bitrate = bitrateMbps(candidate, context)
            MeasuredCandidate(
                candidate = candidate,
                bitrateMbps = bitrate,
                isPlausible = bitrate == null || bitrate <= bitrateCeilingMbps(candidate.facts.resolution),
            )
        }
        val buckets = measured
            .mapNotNull { entry -> bucketFor(entry)?.let { it to entry } }
            .groupBy({ it.first }, { it.second })

        val derived = buckets.entries
            .sortedByDescending { it.key.height }
            .flatMap { (resolution, entries) -> optionsForBucket(resolution, entries) }

        return listOf(bestAvailable(candidates, buckets.keys.maxByOrNull { it.height })) + derived
    }

    /**
     * The option Instant should play on a connection estimated at [estimatedMbps].
     *
     * Returns the highest-resolution option the line can sustain, and when it can sustain
     * none of them the cheapest one rather than null. Falling through to the source list
     * because every release is large would make Instant stop being instant on exactly the
     * titles where it is most useful; a stream that has to buffer is still better than a
     * mode that gives up.
     */
    fun highestAffordable(
        options: List<PlaybackQualityOption>,
        estimatedMbps: Double,
        maxHeight: Int? = null,
    ): PlaybackQualityOption? {
        val derived = options
            .filter { it.variant != PlaybackQualityOption.Variant.BEST }
            .filter { option -> maxHeight == null || (option.resolution?.height ?: 0) <= maxHeight }
        if (derived.isEmpty()) {
            // A ceiling nothing fits under is a refusal, not a suggestion. Falling back to
            // Best available here would hand a 4K remux to someone on mobile data who asked
            // to be capped at 720p - the source list is the honest answer instead.
            return if (maxHeight != null) null else options.firstOrNull()
        }
        val affordable = derived.filter { (it.requiredMbps ?: Double.MAX_VALUE) <= estimatedMbps }
        return affordable.maxWithOrNull(qualityOrder) ?: derived.minWithOrNull(costOrder)
    }

    private val qualityOrder = compareBy<PlaybackQualityOption>(
        { it.resolution?.height ?: 0 },
        { it.requiredMbps ?: 0.0 },
    )

    private val costOrder = compareBy<PlaybackQualityOption>(
        { it.requiredMbps ?: Double.MAX_VALUE },
        { it.resolution?.height ?: 0 },
    )

    private data class MeasuredCandidate(
        val candidate: PlaybackSourceCandidate,
        val bitrateMbps: Double?,
        /**
         * Whether the reported size can be a single episode or film at this resolution.
         *
         * An 85 GB "1080p" episode is not a very good 1080p encode - it is a season pack
         * whose torrent-level size covers a dozen files, or a folder size, or simply wrong.
         * Ranking sorts by size descending, so without this the largest number in the
         * catalogue heads the High row every time and the quoted bandwidth is fiction.
         */
        val isPlausible: Boolean,
    ) {
        val credibleBitrateMbps: Double? get() = bitrateMbps?.takeIf { isPlausible }
    }

    private fun optionsForBucket(
        resolution: VideoResolution,
        entries: List<MeasuredCandidate>,
    ): List<PlaybackQualityOption> {
        val ranked = entries.sortedWith(rankingFor(resolution))
        val measured = ranked.mapNotNull(MeasuredCandidate::credibleBitrateMbps)
        val cheapest = measured.minOrNull()
        val dearest = measured.maxOrNull()

        val splits = if (
            measured.size >= 2 && cheapest != null && dearest != null &&
            cheapest > 0.0 && dearest >= cheapest * SPLIT_RATIO
        ) {
            // Geometric midpoint, so a 4 / 12 Mbps pair splits where a user would split it
            // rather than wherever the arithmetic mean happens to land. Sources with no
            // credible size ride along with Low - they cannot justify the High row.
            val boundary = sqrt(cheapest * dearest)
            val high = ranked.filter { (it.credibleBitrateMbps ?: 0.0) >= boundary }
            val low = ranked.filter { (it.credibleBitrateMbps ?: 0.0) < boundary }
            listOf(PlaybackQualityOption.Variant.HIGH to high, PlaybackQualityOption.Variant.LOW to low)
        } else {
            listOf(PlaybackQualityOption.Variant.SINGLE to ranked)
        }

        return splits.mapNotNull { (variant, own) ->
            if (own.isEmpty()) return@mapNotNull null
            // The row is described by the best source it would actually start, and an
            // implausible size never gets to be that even when it ranks first.
            val representative = own.firstOrNull { it.credibleBitrateMbps != null } ?: own.first()
            val bitrate = representative.credibleBitrateMbps
            // Everything in the bucket stays reachable: the option's own sources first, the
            // rest of the bucket behind them, so a dead pick still has fallbacks.
            val ordered = own + ranked.filterNot { entry -> own.any { it === entry } }
            PlaybackQualityOption(
                id = "${resolution.height}_${variant.name.lowercase()}",
                resolution = resolution,
                variant = variant,
                requiredMbps = requiredMbps(bitrate ?: nominalBitrateMbps(resolution)),
                representativeBitrateMbps = bitrate,
                isEstimateApproximate = bitrate == null,
                representativeSizeBytes = representative.candidate.facts.sizeBytes
                    ?.takeIf { representative.isPlausible },
                candidates = ordered.map(MeasuredCandidate::candidate),
            )
        }
    }

    private fun bestAvailable(
        candidates: List<PlaybackSourceCandidate>,
        topResolution: VideoResolution?,
    ): PlaybackQualityOption = PlaybackQualityOption(
        id = BEST_ID,
        resolution = null,
        variant = PlaybackQualityOption.Variant.BEST,
        requiredMbps = null,
        representativeBitrateMbps = null,
        isEstimateApproximate = false,
        representativeSizeBytes = null,
        candidates = candidates.sortedWith(
            SourceRanking.comparator(
                preferences = preferencesFor(topResolution),
                midRangeTarget = null,
                factsOf = PlaybackSourceCandidate::facts,
                isDirectOf = { it.stream.playableDirectUrl != null },
                addonOrderOf = PlaybackSourceCandidate::addonOrder,
                stableUrlOf = { it.stream.playableDirectUrl.orEmpty() },
            ),
        ),
    )

    private fun rankingFor(resolution: VideoResolution): Comparator<MeasuredCandidate> {
        val ranked: Comparator<MeasuredCandidate> = SourceRanking.comparator(
            preferences = preferencesFor(resolution),
            midRangeTarget = null,
            factsOf = { entry: MeasuredCandidate -> entry.candidate.facts },
            isDirectOf = { it.candidate.stream.playableDirectUrl != null },
            addonOrderOf = { it.candidate.addonOrder },
            stableUrlOf = { it.candidate.stream.playableDirectUrl.orEmpty() },
        )
        // Implausible sizes sort last within their own row. They stay reachable - a season
        // pack often still resolves to the right file - but they never lead.
        return compareBy<MeasuredCandidate>({ !it.isPlausible }, { it.candidate.stream.isTorrentStream })
            .then(ranked)
    }

    /**
     * HDR policy follows the resolution, never the option's rank.
     *
     * Attaching it to rank - "the cheapest row avoids HDR" - would demote a perfectly good
     * HDR 1080p release on any title whose cheapest row happens to be 1080p Low. Someone
     * choosing a 4K row wants the full picture; someone on the SD row is economizing.
     */
    private fun preferencesFor(resolution: VideoResolution?): SourceRankingPreferences =
        SourceRankingPreferences(
            codecPreference = CodecPreference.ANY,
            dynamicRangePolicy = when (resolution) {
                VideoResolution.UHD_4320, VideoResolution.UHD_2160 -> DynamicRangePolicy.PREFER_HDR
                VideoResolution.SD -> DynamicRangePolicy.AVOID_HDR
                else -> DynamicRangePolicy.ANY
            },
            sizePreference = SizePreference.LARGEST_UNDER_CAP,
        )

    /**
     * Which row this source belongs on.
     *
     * The claimed resolution leads, but it is not trusted blindly. `parseResolution` reads a
     * bare `uhd` or `hd` out of a stream's display name, so an addon that titles every entry
     * "UHD Streams" used to merely fail a filter and now would mint a visible 4K row that
     * plays a 720p file. A source whose bitrate is far below the floor for what it claims is
     * therefore demoted to the resolution its bitrate supports - **demoted only**, because a
     * bloated 1080p remux is still a 1080p file however many bytes it spends.
     *
     * Returns null when neither a resolution nor a size is known. Such a source cannot honestly
     * head any row, but it remains reachable through Best available.
     */
    private fun bucketFor(entry: MeasuredCandidate): VideoResolution? {
        val claimed = entry.candidate.facts.resolution
        val bitrate = entry.bitrateMbps
        if (claimed == null) {
            // Never invent 4K from a big file alone; an unlabelled source tops out at 1080p.
            return bitrate?.let { supportedResolution(it, ceiling = VideoResolution.FULL_HD_1080) }
        }
        if (bitrate == null) return claimed
        val floor = bitrateFloorMbps(claimed)
        if (bitrate >= floor) return claimed
        return supportedResolution(bitrate, ceiling = claimed)
    }

    private fun supportedResolution(bitrateMbps: Double, ceiling: VideoResolution): VideoResolution =
        VideoResolution.entries
            .filter { it.height <= ceiling.height }
            .sortedByDescending { it.height }
            .firstOrNull { bitrateMbps >= bitrateFloorMbps(it) }
            ?: VideoResolution.SD

    /** What this source costs to stream, in megabits per second, or null when its size is unknown. */
    fun bitrateMbps(
        candidate: PlaybackSourceCandidate,
        context: PlaybackSelectionContext,
    ): Double? {
        val bytes = candidate.facts.sizeBytes?.takeIf { it > 0L } ?: return null
        val seconds = durationSeconds(candidate, context)
        if (seconds <= 0L) return null
        return bytes.toDouble() * 8.0 / seconds.toDouble() / 1_000_000.0
    }

    /**
     * How long this source runs, most specific first.
     *
     * The per-source figure is the only one that describes the actual file. The title-level
     * runtime is absent entirely on the Continue Watching path, which is why the shared
     * 45/120 fallback still has to exist.
     */
    private fun durationSeconds(
        candidate: PlaybackSourceCandidate,
        context: PlaybackSelectionContext,
    ): Long {
        candidate.facts.durationSeconds?.takeIf { it > 0L }?.let { return it }
        val minutes = context.runtimeMinutes?.takeIf { it > 0 }
            ?: if (context.isEpisode) FALLBACK_EPISODE_MINUTES else FALLBACK_MOVIE_MINUTES
        return minutes.toLong() * 60L
    }

    private fun requiredMbps(bitrateMbps: Double): Double = bitrateMbps / HEADROOM

    /** Used only when a whole bucket reports no sizes at all; the row is marked approximate. */
    private fun nominalBitrateMbps(resolution: VideoResolution): Double = when (resolution) {
        VideoResolution.UHD_4320 -> 40.0
        VideoResolution.UHD_2160 -> 18.0
        VideoResolution.QHD_1440 -> 10.0
        VideoResolution.FULL_HD_1080 -> 6.0
        VideoResolution.HD_720 -> 3.0
        VideoResolution.SD -> 1.5
    }

    /**
     * Above this, the reported size cannot be one episode or film at this resolution.
     *
     * Set above a studio remux and well below a season pack: a 1080p remux peaks around
     * 40 Mbps, while an eight-episode pack advertised as one 1080p "source" lands in the
     * hundreds. Erring high is right here - a real release wrongly called implausible loses
     * its place at the head of a row, which is worse than letting a slightly fat encode lead.
     */
    private fun bitrateCeilingMbps(resolution: VideoResolution?): Double = when (resolution) {
        VideoResolution.UHD_4320 -> 300.0
        // Comfortably past the ~128 Mbps UHD Blu-ray maximum, so a genuine remux still leads.
        VideoResolution.UHD_2160 -> 150.0
        VideoResolution.QHD_1440 -> 80.0
        // Blu-ray tops out near 40 Mbps at 1080p; a season pack lands in the hundreds.
        VideoResolution.FULL_HD_1080 -> 50.0
        VideoResolution.HD_720 -> 20.0
        VideoResolution.SD -> 10.0
        null -> 150.0
    }

    /** Deliberately low - this only has to catch a mislabel, not police efficient encodes. */
    private fun bitrateFloorMbps(resolution: VideoResolution): Double = when (resolution) {
        VideoResolution.UHD_4320 -> 8.0
        VideoResolution.UHD_2160 -> 3.0
        VideoResolution.QHD_1440 -> 2.5
        VideoResolution.FULL_HD_1080 -> 1.2
        VideoResolution.HD_720 -> 0.5
        VideoResolution.SD -> 0.0
    }

    const val BEST_ID = "best"

    private const val FALLBACK_EPISODE_MINUTES = 45
    private const val FALLBACK_MOVIE_MINUTES = 120
}
