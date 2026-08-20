package com.nuvio.app.features.playback

import com.nuvio.app.features.downloads.AudioPreference
import com.nuvio.app.features.downloads.CodecPreference
import com.nuvio.app.features.downloads.DynamicRangePolicy
import com.nuvio.app.features.downloads.SizePreference
import com.nuvio.app.features.downloads.SourceRanking
import com.nuvio.app.features.downloads.SourceRankingPreferences
import com.nuvio.app.features.downloads.VideoResolution

/**
 * The quality choices for one title, derived from the sources that actually exist for it.
 *
 * This is the inversion of the old preset model. A quality *tier* was a budget
 * the catalogue was filtered to fit, so the sheet could offer "4K" for a title nobody has
 * ever released in 4K, and quote a bandwidth figure belonging to the preset rather than to
 * any file the user would receive. Here the catalogue comes first: sources are bucketed by
 * the resolution they claim, each bucket is split by what it really costs to stream, and a
 * bucket with nothing in it simply produces no row.
 *
 * The *bands* within a bucket are absolute rather than relative - see [Variant] and
 * `bandBoundariesMbps`. Deriving them from each title's own spread made every label a
 * statement about one catalogue and about nothing else.
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
    /**
     * Which band of its resolution this row is, on an **absolute** scale.
     *
     * [MAX] exists because "High" was the word for a remux. The bands used to be the bucket's
     * own bitrate spread cut into thirds, so the top row meant "the fattest release this
     * particular title happens to have" - an 88 GB 4K remux on one title and a 14 GB WEB-DL on
     * the next, under the same label. Nothing could be aimed at, and the honest report was that
     * people went to the source list instead.
     *
     * With fixed boundaries a band means the same class of file everywhere, and remux territory
     * gets its own name rather than colonising the one a user reaches for by default.
     */
    enum class Variant { BEST, MAX, HIGH, MID, LOW, SINGLE }

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

    /**
     * Where one resolution's bands begin, in the file's own megabits per second - **before**
     * [HEADROOM], because these describe releases rather than connections.
     *
     * Absolute, and that is the whole point. The relative split these replaced divided a
     * bucket's own spread into geometric thirds, so every label was a statement about the
     * catalogue for one title and about nothing else. A user who learned that "1080p Mid" was
     * about right for them learned nothing transferable: on the next title the same word meant
     * a different file, and on a title with a remux the top band meant 88 GB.
     *
     * The numbers are drawn from what the formats actually cost: roughly, WEB-DL sits at or
     * below [mid], a good Blu-ray encode between [mid] and [high], a heavy encode between
     * [high] and [max], and a remux above [max].
     */
    private data class BandBoundaries(val mid: Double, val high: Double, val max: Double)

    private fun bandBoundariesMbps(resolution: VideoResolution): BandBoundaries = when (resolution) {
        VideoResolution.UHD_4320 -> BandBoundaries(mid = 30.0, high = 70.0, max = 140.0)
        // A UHD Blu-ray remux runs 60-120 Mbps; a 4K WEB-DL is usually under 25.
        VideoResolution.UHD_2160 -> BandBoundaries(mid = 10.0, high = 25.0, max = 50.0)
        VideoResolution.QHD_1440 -> BandBoundaries(mid = 5.0, high = 12.0, max = 25.0)
        // A 1080p remux is 25-40 Mbps, a heavy Blu-ray encode 8-16, a WEB-DL 3-8.
        VideoResolution.FULL_HD_1080 -> BandBoundaries(mid = 3.0, high = 8.0, max = 16.0)
        VideoResolution.HD_720 -> BandBoundaries(mid = 1.5, high = 3.0, max = 6.0)
        VideoResolution.SD -> BandBoundaries(mid = 0.8, high = 1.6, max = 3.5)
    }

    fun build(
        candidates: List<PlaybackSourceCandidate>,
        context: PlaybackSelectionContext,
    ): List<PlaybackQualityOption> {
        if (candidates.isEmpty()) return emptyList()

        val allMeasured = candidates.map { candidate ->
            val bitrate = bitrateMbps(candidate, context)
            MeasuredCandidate(
                candidate = candidate,
                bitrateMbps = bitrate,
                isPlausible = bitrate == null || bitrate <= bitrateCeilingMbps(candidate.facts.resolution),
            )
        }
        // The user's own ceiling, applied here so **Best available honours it too**. That card
        // is the one most people tap and the one whose source can be the most expensive in the
        // catalogue; a ceiling it walked past would not be a ceiling.
        //
        // A source with no credible size is never excluded - there is no figure to judge it by,
        // and refusing what cannot be measured would quietly empty a catalogue on the addons
        // that report least.
        val ceiling = context.qualityCeilingMbps?.takeIf { it > 0.0 && it.isFinite() }
        val measured = if (ceiling == null) {
            allMeasured
        } else {
            // Falling back to the unfiltered set is deliberate: a preference must never become a
            // dead end. If nothing this title offers fits under the ceiling, the honest answer is
            // the catalogue as it is, not an empty sheet.
            allMeasured
                .filter { (it.credibleBitrateMbps ?: 0.0) <= ceiling }
                .ifEmpty { allMeasured }
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
     * height descending and bands Max to Low within each, and `groupBy` preserves the order
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
     * The option the user already chose for this show, or **null**.
     *
     * For `entry<StreamRoute>`, which uses it to skip the quality sheet entirely for the rest of
     * a sitting. That is a different job from [stickyAffordable]'s and needs the opposite
     * failure mode, which is the whole reason this exists rather than reusing it:
     *
     *  - [stickyAffordable] is a **tie-break** for the in-player next episode. When the band is
     *    unavailable it falls back to [highestAffordable], because nobody is there to answer a
     *    sheet mid-binge and any reasonable source beats stopping the binge.
     *  - This is a **decision to skip a question**. A fallback here would be silent
     *    substitution: the sheet does not appear, so there is nothing on screen for the user to
     *    disagree with, and an episode with no release in their band would play something they
     *    never picked while the app acted as though they had. Null means *ask*.
     *
     * Matched on [PlaybackQualityOption.id], so the variant counts too - someone who chose
     * "1080p Low" to stay inside a data cap has not chosen "1080p High". Best available is
     * matchable like any other row; it is a deliberate choice with a stable id.
     */
    fun rememberedOption(
        options: List<PlaybackQualityOption>,
        bandId: String?,
    ): PlaybackQualityOption? {
        if (bandId.isNullOrBlank()) return null
        return options.firstOrNull { it.id == bandId }
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
        isEstimateMeasured: Boolean = true,
    ): ConnectionFit? = connectionFit(option.requiredMbps, estimatedMbps, isEstimateMeasured)

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
        isEstimateMeasured: Boolean = true,
    ): ConnectionFit? {
        val required = requiredMbps?.takeIf { it > 0.0 } ?: return null
        val estimate = estimatedMbps?.takeIf { it > 0.0 } ?: return null
        return ConnectionFit(
            requiredMbps = required,
            estimatedMbps = estimate,
            loadFraction = (required / estimate).coerceIn(0.0, MAX_LOAD_FRACTION),
            // Two conditions, and both were missing.
            //
            // **The estimate has to be a measurement.** `required > estimate` was scored against
            // whatever `peek` returned, including the platform guess - 50 Mbps for any Wi-Fi -
            // so a connection nobody had measured still produced a red line under half the
            // catalogue. A meter drawn against a guess is fair enough; a verdict is not.
            //
            // **And it has to clear a margin.** `requiredMbps` is already the file's bitrate
            // plus a third, and the estimate underneath it is structurally a lower bound: no
            // signal feeding it can observe more throughput than it asked for. Warning the
            // instant those two cross meant flagging rows that play perfectly well, which is
            // what taught the user to ignore the warning - and a warning that is ignored is
            // worse than none, because it was right occasionally.
            isOverConnection = isEstimateMeasured && required > estimate * OVER_CONNECTION_MARGIN,
        )
    }

    /**
     * How far past the estimate an option must reach before the sheet says so.
     *
     * Not tuned to a stall threshold - nothing here can predict one. It is the width of the
     * band where the two figures are too close for the difference to mean anything.
     */
    const val OVER_CONNECTION_MARGIN = 1.15

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
        // Banded on measured sources alone. A source that reported no size has no figure to be
        // banded by, and 0.0 is not that figure - treating it as one would mint a "Low" row
        // whose only occupant is a file nobody knows the size of, quoting a nominal bitrate for
        // it. They join the lowest occupied band below instead, which is what the relative
        // split did and the reason it did it.
        val (sized, unsized) = ranked.partition { it.credibleBitrateMbps != null }

        val bounds = bandBoundariesMbps(resolution)
        // Still gated on two *measured* sources. One figure is not a comparison, and banding a
        // bucket where only one source reported a size would put a confident label on a row
        // whose neighbour is a guess.
        val splits = if (sized.size >= 2) {
            fun bandOf(entry: MeasuredCandidate): Double = entry.credibleBitrateMbps ?: 0.0
            val banded = listOf(
                PlaybackQualityOption.Variant.MAX to sized.filter { bandOf(it) >= bounds.max },
                PlaybackQualityOption.Variant.HIGH to
                    sized.filter { bandOf(it) >= bounds.high && bandOf(it) < bounds.max },
                PlaybackQualityOption.Variant.MID to
                    sized.filter { bandOf(it) >= bounds.mid && bandOf(it) < bounds.high },
                PlaybackQualityOption.Variant.LOW to sized.filter { bandOf(it) < bounds.mid },
            )
            // Onto the cheapest band that actually exists - `banded` runs dearest first, so that
            // is the last occupied one. A sizeless source cannot justify a dearer row.
            val cheapestOccupied = banded.indexOfLast { it.second.isNotEmpty() }
            banded.mapIndexed { index, (variant, own) ->
                variant to if (index == cheapestOccupied) own + unsized else own
            }
        } else {
            listOf(PlaybackQualityOption.Variant.SINGLE to ranked)
        }

        // An empty band produces no row, and a lone row labelled "1080p Mid" would be a
        // comparative label with nothing to compare against.
        //
        // ⚠ **Absolute boundaries make this load-bearing, where the relative ones made it a
        // formality.** The old split derived its boundaries from the bucket's own cheapest and
        // dearest, so the extreme bands were occupied by construction and only Mid could come
        // out empty. Fixed boundaries have no such guarantee: a title whose only 1080p releases
        // are 5 and 6 Mbps puts everything in Mid and must produce **one** unlabelled row, not
        // a "Mid" with nothing above or below it to mean anything against.
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
        //
        // **Under REQUIRE, language leads all three**, because a row is described by the source
        // it would actually open (`previewSelection`) and that function moves unwatchable
        // candidates to the back. Without the same rule here the card's caption would name a
        // release the selector had already stepped past - the sheet describing one file and
        // playing another.
        //
        // Under PREFER it must **not** lead: `SourceRanking.languageScore` is already inside the
        // comparator below, one key under resolution, which is what "ranked on, never excluded"
        // means. Promoting it here as well would make a right-language uncached source beat a
        // wrong-language cached one, which is a refusal wearing a preference's name.
        val preferences = preferencesFor(resolution, context)
        val excludesByLanguage = context.languageStrictness == LanguageStrictness.REQUIRE &&
            !context.preferredAudioLanguage.isNullOrBlank()
        return compareBy<MeasuredCandidate>(
            { excludesByLanguage && !SourceRanking.isLanguageWatchable(it.candidate.facts, preferences) },
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
            preferredAudioLanguage = context.preferredAudioLanguage
                ?.takeIf { context.languageStrictness != LanguageStrictness.OFF },
            secondaryAudioLanguage = context.secondaryAudioLanguage
                ?.takeIf { context.languageStrictness != LanguageStrictness.OFF },
            codecPreference = context.codecPreference,
            audioPreference = context.audioPreference,
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
