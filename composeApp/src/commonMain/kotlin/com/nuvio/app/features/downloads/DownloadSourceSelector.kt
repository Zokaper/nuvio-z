package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamDebridCacheState

/**
 * Picks a download source under a [DownloadPolicy]. Replaces `PresetSourceSelector` (Phase 9).
 *
 * The ordering is the shared [SourceRanking] comparator - resolution, then language, then
 * dynamic range and media, then cached, then the size rule - so "which file wins among those
 * that fit" is decided *after* range and language, as decided. Only the gates are this file's.
 *
 * ## Cache evidence (plan §4.2a)
 * Nuvio has no way to ask a debrid service to cache something and wait for it: a stream marked
 * not-cached short-circuits in the resolver, and Real-Debrid's resolver deletes what it added
 * when a resolve fails. So a candidate is **usable** only with positive evidence of a cached copy
 * or as a plain HTTP file with no cache state. `Unknown` debrid candidates are not usable for
 * Automatic or Assisted (the S10 rule), and [DownloadDecisionReason.NothingCached] means zero
 * usable candidates at all - never "some exist but break the rules".
 */
object DownloadSourceSelector {

    data class Context(
        val policy: DownloadPolicy,
        val runtimeMinutes: Int?,
        val isEpisode: Boolean,
        /** Language (and anything else not download-specific) from the shared playback prefs. */
        val rankingPreferences: SourceRankingPreferences,
        /** The playback dynamic-range policy, for [DownloadRange.SAME_AS_PLAYBACK]. */
        val playbackDynamicRange: DynamicRangePolicy,
        val addonFilter: DownloadSourcePolicy,
    )

    fun select(candidates: List<DownloadSourceCandidate>, context: Context): DownloadDecision {
        if (candidates.isEmpty()) return DownloadDecision.NeedsDecision(DownloadDecisionReason.NoSources)
        val allowed = candidates.filter {
            context.addonFilter.allowsResult(it.addonKey, it.facts) && isAutomaticCandidate(it)
        }
        if (allowed.isEmpty()) return DownloadDecision.NeedsDecision(DownloadDecisionReason.NoSources)
        val usable = allowed.filter { cacheEvidence(it) != DownloadCacheEvidence.NOT_USABLE }
        if (usable.isEmpty()) return DownloadDecision.NeedsDecision(DownloadDecisionReason.NothingCached)

        val byHeight = usable.groupBy { it.facts.resolution?.height }
        val heights = byHeight.keys.filterNotNull().sortedDescending()
        val policy = context.policy
        val preferred = policy.preferredResolution

        if (preferred == DownloadResolutionPreference.BEST_AVAILABLE) {
            // The highest resolution with something inside the size level; if none fits
            // anywhere, the smallest over-limit file overall is what the user is asked about.
            for (height in heights) {
                pickWithinLimit(byHeight.getValue(height), height, context)?.let { return it }
            }
            return overLimit(usable, context)
        }

        val target = preferred.height
        val atTarget = byHeight[target]
        if (!atTarget.isNullOrEmpty()) {
            return pickWithinLimit(atTarget, target, context) ?: overLimit(atTarget, context)
        }

        val lower = heights.filter { it < target }.maxOrNull()
        val higher = heights.filter { it > target }.minOrNull()
        val fallbackHeight = when (policy.resolutionFallback) {
            DownloadResolutionFallback.LOWER -> lower
            DownloadResolutionFallback.HIGHER -> higher
            DownloadResolutionFallback.ASK -> null
        }
        if (fallbackHeight == null) {
            return DownloadDecision.NeedsDecision(
                DownloadDecisionReason.ResolutionMissing(
                    preferredHeight = target,
                    nearestLowerHeight = lower,
                    nearestHigherHeight = higher,
                ),
            )
        }
        // The size level carries to the new resolution: Medium at 4K is Medium-at-4K.
        val atFallback = byHeight.getValue(fallbackHeight)
        return pickWithinLimit(atFallback, fallbackHeight, context) ?: overLimit(atFallback, context)
    }

    /**
     * Assisted: one row per available resolution, each the single best match to the policy at
     * that resolution. A resolution where nothing fits the size level still gets a row - the
     * closest file above the limit, flagged - because Assisted is the user choosing.
     */
    fun assistedOptions(candidates: List<DownloadSourceCandidate>, context: Context): List<AssistedOption> {
        val usable = candidates.filter {
            context.addonFilter.allowsResult(it.addonKey, it.facts) &&
                isAutomaticCandidate(it) &&
                cacheEvidence(it) != DownloadCacheEvidence.NOT_USABLE
        }
        return usable.groupBy { it.facts.resolution?.height }
            .filterKeys { it != null }
            .map { (height, group) ->
                height!!
                val limit = limitFor(height, context)
                val fitting = group.filter { fits(it, limit) }
                if (fitting.isNotEmpty()) {
                    AssistedOption(height, rank(fitting, limit, context).first(), overLimit = false)
                } else {
                    val smallest = group.filter { it.facts.sizeBytes != null }.minByOrNull { it.facts.sizeBytes!! }
                        ?: rank(group, limit, context).first()
                    AssistedOption(height, smallest, overLimit = true)
                }
            }
            .sortedByDescending { it.height }
    }

    /**
     * Assisted for a season: per resolution, the total across the episodes that have it, and the
     * episodes that do not (those become one grouped attention card if that row is chosen).
     */
    fun <K> seasonRows(optionsByEpisode: Map<K, List<AssistedOption>>): List<AssistedSeasonRow<K>> {
        val heights = optionsByEpisode.values.flatten().map { it.height }.distinct().sortedDescending()
        return heights.map { height ->
            val chosen = optionsByEpisode.mapNotNull { (episode, options) ->
                options.firstOrNull { it.height == height }?.let { episode to it }
            }.toMap()
            AssistedSeasonRow(
                height = height,
                optionByEpisode = chosen,
                totalBytes = chosen.values.sumOf { it.candidate.facts.sizeBytes ?: 0L },
                unknownSizeCount = chosen.values.count { it.candidate.facts.sizeBytes == null },
                missingEpisodes = optionsByEpisode.keys.filter { it !in chosen },
            )
        }
    }

    fun cacheEvidence(candidate: DownloadSourceCandidate): DownloadCacheEvidence {
        // The service's own answer, from the local cache check discovery runs, beats anything
        // the addon's text claims.
        when (candidate.stream.debridCacheStatus?.state) {
            StreamDebridCacheState.CACHED -> return DownloadCacheEvidence.CACHED
            StreamDebridCacheState.NOT_CACHED -> return DownloadCacheEvidence.NOT_USABLE
            else -> Unit
        }
        val ready = candidate.facts.isDebridReady
        val debridBacked = candidate.sourceOrigin != null
        return when {
            ready == true -> DownloadCacheEvidence.CACHED
            ready == false -> DownloadCacheEvidence.NOT_USABLE
            // A debrid source nobody vouched for: Unknown is not cached (S10).
            debridBacked -> DownloadCacheEvidence.NOT_USABLE
            else -> DownloadCacheEvidence.PLAIN_HTTP
        }
    }

    /** At least one candidate Automatic or Assisted could download, whatever its size. */
    fun hasUsable(candidates: List<DownloadSourceCandidate>, context: Context): Boolean =
        candidates.any {
            context.addonFilter.allowsResult(it.addonKey, it.facts) &&
                isAutomaticCandidate(it) &&
                cacheEvidence(it) != DownloadCacheEvidence.NOT_USABLE
        }

    /**
     * Known not to be cached: the Manual list shows these disabled. `Unknown` stays selectable
     * there - the realizer checks it at slot start (plan section 4.2a).
     */
    /** [isKnownNotCached] for a row of the download source list. */
    fun isKnownNotCached(stream: com.nuvio.app.features.streams.StreamItem): Boolean =
        stream.debridCacheStatus?.state == StreamDebridCacheState.NOT_CACHED ||
            SourceFactsExtractor.extract(stream).isDebridReady == false

    fun isKnownNotCached(candidate: DownloadSourceCandidate): Boolean =
        candidate.stream.debridCacheStatus?.state == StreamDebridCacheState.NOT_CACHED ||
            candidate.facts.isDebridReady == false

    /**
     * The best source at exactly [height] under the policy's size level, or the smallest above it
     * - used where the user named the resolution (Use nearest, an Assisted row).
     */
    fun bestAt(candidates: List<DownloadSourceCandidate>, height: Int, context: Context): DownloadDecision? {
        val group = candidates.filter {
            context.addonFilter.allowsResult(it.addonKey, it.facts) &&
                isAutomaticCandidate(it) &&
                cacheEvidence(it) != DownloadCacheEvidence.NOT_USABLE &&
                it.facts.resolution?.height == height
        }
        if (group.isEmpty()) return null
        return pickWithinLimit(group, height, context) ?: overLimit(group, context)
    }

    fun limitAt(height: Int, context: Context): Long? = limitFor(height, context)

    private fun pickWithinLimit(
        group: List<DownloadSourceCandidate>,
        height: Int,
        context: Context,
    ): DownloadDecision.Pick? {
        val limit = limitFor(height, context)
        val fitting = group.filter { fits(it, limit) }
        if (fitting.isEmpty()) return null
        return DownloadDecision.Pick(rank(fitting, limit, context).first(), limit)
    }

    private fun overLimit(group: List<DownloadSourceCandidate>, context: Context): DownloadDecision {
        val sized = group.filter { it.facts.sizeBytes != null }
        val smallest = sized.minByOrNull { it.facts.sizeBytes!! }
        return DownloadDecision.NeedsDecision(
            DownloadDecisionReason.OverLimit(
                smallest = smallest ?: rank(group, null, context).first(),
                smallestBytes = smallest?.facts?.sizeBytes,
            ),
        )
    }

    private fun fits(candidate: DownloadSourceCandidate, limit: Long?): Boolean {
        if (candidate.facts.hasConflictingHardMetadata) return false
        if (limit == null) return true
        val size = candidate.facts.sizeBytes ?: return false
        return size <= limit
    }

    private fun limitFor(height: Int, context: Context): Long? = DownloadSizeLevels.limitBytes(
        level = context.policy.sizeLevel,
        resolutionHeight = height,
        runtimeMinutes = context.runtimeMinutes,
        isEpisode = context.isEpisode,
    )

    private fun rank(
        group: List<DownloadSourceCandidate>,
        limit: Long?,
        context: Context,
    ): List<DownloadSourceCandidate> {
        val preferences = context.rankingPreferences.copy(
            dynamicRangePolicy = when (context.policy.range) {
                DownloadRange.SAME_AS_PLAYBACK -> context.playbackDynamicRange
                DownloadRange.PREFER_HDR -> DynamicRangePolicy.PREFER_HDR
                DownloadRange.AVOID_HDR -> DynamicRangePolicy.AVOID_HDR
                DownloadRange.ANY -> DynamicRangePolicy.ANY
            },
            sizePreference = when (context.policy.pickRule) {
                DownloadPickRule.BEST_THAT_FITS -> SizePreference.LARGEST_UNDER_CAP
                DownloadPickRule.BALANCED -> SizePreference.MID_RANGE
                DownloadPickRule.SMALLEST_THAT_FITS -> SizePreference.SMALLEST
            },
        )
        return group.sortedWith(
            SourceRanking.comparator(
                preferences = preferences,
                midRangeTarget = limit?.let { SourceRanking.midRangeTarget(group.map { it.facts }, it) },
                factsOf = DownloadSourceCandidate::facts,
                isDirectOf = { it.stream.playableDirectUrl != null },
                addonOrderOf = DownloadSourceCandidate::addonOrder,
                stableUrlOf = { it.resolvedUrl ?: it.sourceOrigin?.stream?.streamLabel.orEmpty() },
            ),
        )
    }

    private fun isAutomaticCandidate(candidate: DownloadSourceCandidate): Boolean {
        if (candidate.sourceOrigin != null) return true
        val normalized = candidate.resolvedUrl?.trim()?.lowercase() ?: return false
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) return false
        return ".m3u8" !in normalized && ".mpd" !in normalized && ".torrent" !in normalized
    }
}

enum class DownloadCacheEvidence { CACHED, PLAIN_HTTP, NOT_USABLE }

sealed interface DownloadDecision {
    data class Pick(val candidate: DownloadSourceCandidate, val limitBytes: Long?) : DownloadDecision
    data class NeedsDecision(val reason: DownloadDecisionReason) : DownloadDecision
}

sealed interface DownloadDecisionReason {
    /** Every usable source at the resolution is above the size level (or its size is unknown). */
    data class OverLimit(val smallest: DownloadSourceCandidate, val smallestBytes: Long?) : DownloadDecisionReason

    /** Nothing at the preferred resolution, and the fallback preference is "Ask me". */
    data class ResolutionMissing(
        val preferredHeight: Int,
        val nearestLowerHeight: Int?,
        val nearestHigherHeight: Int?,
    ) : DownloadDecisionReason

    /** Sources exist, but none is cached on the debrid service (or known to be a plain file). */
    data object NothingCached : DownloadDecisionReason

    /** No source at all, or none the addon filter allows for downloading. */
    data object NoSources : DownloadDecisionReason
}

data class AssistedOption(
    val height: Int,
    val candidate: DownloadSourceCandidate,
    /** True when nothing at this resolution fits the size level and this is the closest above it. */
    val overLimit: Boolean,
)

data class AssistedSeasonRow<K>(
    val height: Int,
    val optionByEpisode: Map<K, AssistedOption>,
    val totalBytes: Long,
    val unknownSizeCount: Int,
    val missingEpisodes: List<K>,
)
