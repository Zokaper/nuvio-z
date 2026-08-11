package com.nuvio.app.features.playback

import com.nuvio.app.features.downloads.CodecPreference
import com.nuvio.app.features.downloads.DynamicRangePolicy
import com.nuvio.app.features.downloads.SizePreference
import com.nuvio.app.features.downloads.SourceRanking
import com.nuvio.app.features.downloads.SourceRankingPreferences
import com.nuvio.app.features.downloads.VideoResolution
import kotlin.math.pow
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
    enum class Variant { BEST, HIGH, MID, LOW, SINGLE }

    /** "4K", "1080p", "SD". The High/Low half of the label is localized by the caller. */
    val resolutionLabel: String
        get() = resolution.qualityLabel
}

/**
 * One resolution and every band offered at it, which is the shape the sheet draws.
 *
 * [PlaybackQualityOptions.build] already emits its options in this order; a group is that
 * hierarchy made explicit rather than a re-derivation of it. Keeping the grouping here rather
 * than in the Compose layer is the same reasoning the rest of this file is built on - it can
 * be exercised outside Gradle, and two renderers cannot disagree about what a resolution
 * offers.
 *
 * [resolution] is null only for the Best available group, which claims no resolution and is
 * always alone.
 */
data class PlaybackQualityGroup(
    val resolution: VideoResolution?,
    val options: List<PlaybackQualityOption>,
) {
    val resolutionLabel: String
        get() = resolution.qualityLabel
}

/**
 * The user-facing name for a resolution, shared by the quality rows and by anything that has
 * only [SourceFacts] to go on - such as reporting which source Instant actually opened, which
 * is not always the one it first chose.
 */
val VideoResolution?.qualityLabel: String
    get() = when (this) {
        VideoResolution.UHD_4320 -> "8K"
        VideoResolution.UHD_2160 -> "4K"
        VideoResolution.QHD_1440 -> "1440p"
        VideoResolution.FULL_HD_1080 -> "1080p"
        VideoResolution.HD_720 -> "720p"
        VideoResolution.SD -> "SD"
        null -> ""
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

    /**
     * Spread at which two rows become three.
     *
     * `SPLIT_RATIO` squared, so the reasoning is the same one applied twice: a band is worth
     * offering when the thing above it costs half again as much. A 4 / 9 Mbps bucket splits
     * in two; a 4 / 18 one has room for a middle a user can actually aim at.
     */
    private const val THREE_WAY_RATIO = SPLIT_RATIO * SPLIT_RATIO

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
            .flatMap { (resolution, entries) -> optionsForBucket(resolution, entries, context) }

        return listOf(
            bestAvailable(measured, buckets.keys.maxByOrNull { it.height }, context),
        ) + derived
    }

    /**
     * [build]'s output as one entry per resolution, which is how the sheet draws it.
     *
     * A user chooses a resolution first and a band second, so "1080p High", "1080p Mid" and
     * "1080p Low" are one decision, not three - the flat list made them three peers of each
     * other and of every other resolution, and said "1080p" three times to say it once.
     *
     * **Order is [build]'s, not re-derived here.** That function already sorts buckets by
     * height descending and bands High to Low within each, and `groupBy` preserves the order
     * keys are first seen in. Re-sorting would be a second opinion on an ordering that is
     * already decided, and the two would drift.
     *
     * [PlaybackQualityOption.Variant.BEST] is pulled out rather than grouped: it claims no
     * resolution, so every future variant that does the same would otherwise land in one
     * shared null bucket and render as bands of each other.
     */
    fun group(options: List<PlaybackQualityOption>): List<PlaybackQualityGroup> {
        val (best, banded) = options.partition {
            it.variant == PlaybackQualityOption.Variant.BEST
        }
        return best.map { PlaybackQualityGroup(it.resolution, listOf(it)) } +
            banded.groupBy { it.resolution }
                .map { (resolution, bucket) -> PlaybackQualityGroup(resolution, bucket) }
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

    /**
     * [highestAffordable], but preferring the resolution Instant already settled on for this
     * series in this sitting.
     *
     * The complaint this answers: two taps that look identical to the user - same show, same
     * connection, next episode - can land on different resolutions, because the derived rows
     * come from *this* episode's catalogue and the bandwidth estimate ratchets upward as you
     * watch. Neither is a bug, and both read as a roulette wheel.
     *
     * Three things it deliberately will not do:
     *  - override a metered cap ([maxHeight]), which is a refusal and outranks a preference;
     *  - hold a resolution the estimate can no longer carry, which would trade churn for stalls;
     *  - invent a row - if this episode has no release at the pinned height, the pin simply
     *    does not apply and the normal answer stands.
     *
     * So it is a tie-break towards stability, never a ceiling and never a floor.
     */
    fun stickyAffordable(
        options: List<PlaybackQualityOption>,
        pinnedHeight: Int?,
        estimatedMbps: Double,
        maxHeight: Int? = null,
    ): PlaybackQualityOption? {
        val fallback = highestAffordable(
            options = options,
            estimatedMbps = estimatedMbps,
            maxHeight = maxHeight,
        )
        if (pinnedHeight == null || fallback == null) return fallback
        if (maxHeight != null && pinnedHeight > maxHeight) return fallback
        return options
            .filter { it.variant != PlaybackQualityOption.Variant.BEST }
            .filter { it.resolution?.height == pinnedHeight }
            .filter { (it.requiredMbps ?: Double.MAX_VALUE) <= estimatedMbps }
            .maxWithOrNull(qualityOrder)
            ?: fallback
    }

    /**
     * Where one option sits against the connection estimate.
     *
     * Null whenever either figure is unknown - Best available carries no `requiredMbps`, and a
     * connection nothing has measured yet carries no estimate. A null fit means the quality
     * sheet says nothing about the connection for that option, which is the honest answer;
     * drawing an empty meter would imply a measurement that does not exist.
     *
     * This is the single source of both the warning and the meter beside it. They were two
     * expressions of the same comparison in different files, which is how a bar and a sentence
     * come to disagree.
     */
    data class ConnectionFit(
        val requiredMbps: Double,
        val estimatedMbps: Double,
        /**
         * `required / estimate`, capped at [MAX_LOAD_FRACTION] for display. Above 1.0 the
         * option asks for more than the line is thought to carry.
         *
         * The cap is a drawing concern only: a 200 Mbps season pack against an 8 Mbps estimate
         * is 25x, and a meter that honoured that would need a scale on which every ordinary
         * option is invisible. [isOverConnection] is computed from the real numbers.
         */
        val loadFraction: Double,
        val isOverConnection: Boolean,
    )

    /** Ratio beyond which the meter stops growing. See [ConnectionFit.loadFraction]. */
    const val MAX_LOAD_FRACTION = 2.0

    fun connectionFit(
        option: PlaybackQualityOption,
        estimatedMbps: Double?,
    ): ConnectionFit? = connectionFit(option.requiredMbps, estimatedMbps)

    /**
     * The same comparison for a cost that did not come from an option's own bucket.
     *
     * Best available needs it: its `requiredMbps` is null by construction, so it had no meter
     * and no over-connection warning at all - on the card most likely to be tapped, and the one
     * whose source can be the most expensive in the catalogue. Its figure comes from
     * [requiredMbpsFor] on the source that would actually open.
     */
    fun connectionFit(
        requiredMbps: Double?,
        estimatedMbps: Double?,
    ): ConnectionFit? {
        val required = requiredMbps?.takeIf { it > 0.0 } ?: return null
        val estimate = estimatedMbps?.takeIf { it > 0.0 } ?: return null
        return ConnectionFit(
            requiredMbps = required,
            estimatedMbps = estimate,
            loadFraction = (required / estimate).coerceIn(0.0, MAX_LOAD_FRACTION),
            // Strictly greater: an option that costs exactly what the line carries is the
            // boundary case the headroom in `requiredMbps` already exists to cover, and
            // warning about it would flag the very thing the estimate says is fine.
            isOverConnection = required > estimate,
        )
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
        context: PlaybackSelectionContext,
    ): List<PlaybackQualityOption> {
        val ranked = entries.sortedWith(rankingFor(resolution, context))
        val measured = ranked.mapNotNull(MeasuredCandidate::credibleBitrateMbps)
        val cheapest = measured.minOrNull()
        val dearest = measured.maxOrNull()

        // Sources with no credible size ride along with the cheapest band throughout - they
        // cannot justify a dearer row.
        fun bandOf(entry: MeasuredCandidate): Double = entry.credibleBitrateMbps ?: 0.0
        val spread = if (cheapest != null && dearest != null && cheapest > 0.0) {
            dearest / cheapest
        } else {
            1.0
        }

        val splits = if (measured.size >= 2 && cheapest != null && spread >= THREE_WAY_RATIO) {
            // Two geometric boundaries at the thirds, extending the midpoint reasoning rather
            // than replacing it: the bands are equal *multiples* of each other, which is how
            // bitrate differences are actually felt.
            val lower = cheapest * spread.pow(1.0 / 3.0)
            val upper = cheapest * spread.pow(2.0 / 3.0)
            listOf(
                PlaybackQualityOption.Variant.HIGH to ranked.filter { bandOf(it) >= upper },
                PlaybackQualityOption.Variant.MID to ranked.filter { bandOf(it) >= lower && bandOf(it) < upper },
                PlaybackQualityOption.Variant.LOW to ranked.filter { bandOf(it) < lower },
            )
        } else if (measured.size >= 2 && cheapest != null && dearest != null && spread >= SPLIT_RATIO) {
            // Geometric midpoint, so a 4 / 12 Mbps pair splits where a user would split it
            // rather than wherever the arithmetic mean happens to land.
            val boundary = sqrt(cheapest * dearest)
            listOf(
                PlaybackQualityOption.Variant.HIGH to ranked.filter { bandOf(it) >= boundary },
                PlaybackQualityOption.Variant.LOW to ranked.filter { bandOf(it) < boundary },
            )
        } else {
            listOf(PlaybackQualityOption.Variant.SINGLE to ranked)
        }

        // An empty band produces no row, and a lone row labelled "1080p Mid" would be a
        // comparative label with nothing to compare against. The boundaries make that
        // unreachable - the cheapest source always falls below `lower` and the dearest always
        // reaches `upper`, so High and Low are both occupied whenever a split happens at all,
        // and only Mid can come out empty. This is the guard for someone moving those
        // boundaries later, not a hole being closed: see the test that pins the invariant.
        val resolved = if (splits.count { it.second.isNotEmpty() } < 2) {
            listOf(PlaybackQualityOption.Variant.SINGLE to ranked)
        } else {
            splits
        }

        return resolved.mapNotNull { (variant, own) ->
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

    /**
     * The card at the top of the sheet, and the one most people tap.
     *
     * **Ranked by exactly the same rules as every other row.** It used to sort with a bare
     * `SourceRanking.comparator` and skip all three of [rankingFor]'s leading keys, so the card
     * that claims to be the best available was the one place the catalogue's worst traps still
     * led: `LARGEST_UNDER_CAP` sorts size descending, so an 85 GB season pack advertised as one
     * file headed it every time - the precise defect `0.4.9-beta` fixed for the banded rows and
     * never applied here. A torrent could lead it, and so could a debrid source nobody had
     * evidence was cached.
     *
     * It failed quietly, too: [requiredMbpsFor] returns null above the plausibility ceiling, so
     * the season-pack case showed no bandwidth figure and no connection meter rather than a
     * warning. The ceiling was protecting the label while the pick walked straight past it.
     */
    private fun bestAvailable(
        measured: List<MeasuredCandidate>,
        topResolution: VideoResolution?,
        context: PlaybackSelectionContext,
    ): PlaybackQualityOption = PlaybackQualityOption(
        id = BEST_ID,
        resolution = null,
        variant = PlaybackQualityOption.Variant.BEST,
        requiredMbps = null,
        representativeBitrateMbps = null,
        isEstimateApproximate = false,
        representativeSizeBytes = null,
        candidates = measured.sortedWith(rankingFor(topResolution, context))
            .map(MeasuredCandidate::candidate),
    )

    private fun rankingFor(
        resolution: VideoResolution?,
        context: PlaybackSelectionContext,
    ): Comparator<MeasuredCandidate> {
        val ranked: Comparator<MeasuredCandidate> = SourceRanking.comparator(
            preferences = preferencesFor(resolution, context),
            midRangeTarget = null,
            factsOf = { entry: MeasuredCandidate -> entry.candidate.facts },
            isDirectOf = { it.candidate.stream.playableDirectUrl != null },
            addonOrderOf = { it.candidate.addonOrder },
            stableUrlOf = { it.candidate.stream.playableDirectUrl.orEmpty() },
        )
        // Implausible sizes sort last within their own row. They stay reachable - a season
        // pack often still resolves to the right file - but they never lead.
        //
        // Cache evidence is the *third* key, deliberately. A source known to be cached should
        // lead an equally plausible one whose state is only hoped for, because the alternative
        // is the provider answering "not cached" at resolve time and the user reading an error.
        // But promoting it above plausibility would let an implausible cached season pack head
        // the row again, and it would not show: the displayed bitrate and size come from
        // `credibleBitrateMbps`, so only what actually *plays* would regress.
        return compareBy<MeasuredCandidate>(
            { !it.isPlausible },
            { it.candidate.stream.isTorrentStream },
            { it.candidate.facts.isDebridReady != true },
        ).then(ranked)
    }

    /**
     * HDR policy follows the resolution, never the option's rank.
     *
     * Attaching it to rank - "the cheapest row avoids HDR" - would demote a perfectly good
     * HDR 1080p release on any title whose cheapest row happens to be 1080p Low. Someone
     * choosing a 4K row wants the full picture; someone on the SD row is economizing.
     *
     * **The user's choice composes with that rather than replacing it.** The by-resolution
     * default is a guess about what someone probably wants; an explicit setting is not a
     * guess, so it wins wherever one exists - but leaving it set to `ANY` must keep the
     * resolution-shaped behaviour rather than flattening every row to no preference at all.
     * That distinction is why this takes the whole context: `ANY` means "no opinion, use the
     * default", not "prefer nothing".
     */
    private fun preferencesFor(
        resolution: VideoResolution?,
        context: PlaybackSelectionContext,
    ): SourceRankingPreferences =
        SourceRankingPreferences(
            preferredAudioLanguage = context.preferredAudioLanguage,
            codecPreference = context.codecPreference,
            dynamicRangePolicy = context.dynamicRangePolicy.takeIf {
                it != DynamicRangePolicy.ANY
            } ?: when (resolution) {
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
     * What one concrete source needs off the connection, headroom included.
     *
     * [PlaybackQualityOption.requiredMbps] answers this for a bucket, but Best available has no
     * bucket to answer for - it spans the whole catalogue, so its `requiredMbps` is null by
     * construction and its card quoted no figure at all. The source it would *open* has a real
     * bitrate, and that is the honest number for that card.
     *
     * Null when the size is unknown, and also when the implied bitrate is beyond what the
     * resolution can plausibly be - a season pack advertised as one file would otherwise have
     * the card demanding hundreds of Mbps. [build] applies the same ceiling for the same reason.
     */
    fun requiredMbpsFor(
        candidate: PlaybackSourceCandidate,
        context: PlaybackSelectionContext,
    ): Double? {
        val bitrate = bitrateMbps(candidate, context) ?: return null
        if (bitrate > bitrateCeilingMbps(candidate.facts.resolution)) return null
        return requiredMbps(bitrate)
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
