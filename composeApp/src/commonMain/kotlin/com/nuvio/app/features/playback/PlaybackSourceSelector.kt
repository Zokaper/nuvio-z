package com.nuvio.app.features.playback

import com.nuvio.app.features.downloads.CodecPreference
import com.nuvio.app.features.downloads.DynamicRangePolicy
import com.nuvio.app.features.downloads.SizePreference
import com.nuvio.app.features.downloads.SourceFacts
import com.nuvio.app.features.downloads.SourceFactsExtractor
import com.nuvio.app.features.downloads.SourceRanking
import com.nuvio.app.features.downloads.SourceRankingPreferences
import com.nuvio.app.features.streams.StreamItem

data class PlaybackSourceCandidate(
    val stream: StreamItem,
    val facts: SourceFacts = SourceFactsExtractor.extract(stream),
    val addonOrder: Int = 0,
)

data class PlaybackSelectionContext(
    val runtimeMinutes: Int? = null,
    val isEpisode: Boolean,
    val allowTorrentSources: Boolean = false,
)

sealed interface PlaybackSelectionResult {
    data class Play(
        val stream: StreamItem,
        val fallbacks: List<StreamItem>,
    ) : PlaybackSelectionResult

    data class AskUncached(val stream: StreamItem) : PlaybackSelectionResult
    data class NeedsManual(val reason: String) : PlaybackSelectionResult
}

object PlaybackSourceSelector {
    const val MIN_HEALTHY_SEEDERS = 5

    fun select(
        candidates: List<PlaybackSourceCandidate>,
        tier: PlaybackQualityTier?,
        context: PlaybackSelectionContext,
    ): PlaybackSelectionResult {
        val cap = tier?.sizeCapBytes(context.runtimeMinutes, context.isEpisode)
        val withinTier = candidates.filter { candidate ->
            tier == null || candidate.facts.resolution?.height?.let {
                it <= tier.targetResolution.height
            } ?: true
        }.filter { candidate ->
            cap == null || candidate.facts.sizeBytes?.let { it <= cap } ?: true
        }.filter { candidate ->
            matchesRequirements(candidate.facts, tier)
        }

        val protocolEligible = withinTier.filter { candidate ->
            isPlaybackProtocolEligible(candidate, context.allowTorrentSources)
        }
        val ordered = protocolEligible.sortedWith(candidateComparator(tier))
        val playable = ordered.filterNot(::isUncachedDebrid)

        playable.firstOrNull()?.let { selected ->
            return PlaybackSelectionResult.Play(
                stream = selected.stream,
                fallbacks = playable.drop(1).map(PlaybackSourceCandidate::stream),
            )
        }
        ordered.firstOrNull(::isUncachedDebrid)?.let { uncached ->
            return PlaybackSelectionResult.AskUncached(uncached.stream)
        }
        return PlaybackSelectionResult.NeedsManual(
            if (withinTier.isEmpty()) "No source matched this quality tier"
            else "No source can be auto-played safely",
        )
    }

    private fun candidateComparator(tier: PlaybackQualityTier?): Comparator<PlaybackSourceCandidate> {
        val ranked = SourceRanking.comparator(
            preferences = SourceRankingPreferences(
                codecPreference = tier?.codecPreference ?: CodecPreference.ANY,
                dynamicRangePolicy = tier?.dynamicRangePolicy ?: DynamicRangePolicy.ANY,
                sizePreference = SizePreference.LARGEST_UNDER_CAP,
            ),
            midRangeTarget = null,
            factsOf = PlaybackSourceCandidate::facts,
            isDirectOf = { it.stream.playableDirectUrl != null },
            addonOrderOf = PlaybackSourceCandidate::addonOrder,
            stableUrlOf = { it.stream.playableDirectUrl.orEmpty() },
        )
        // P2P is deliberately behind every HTTP/debrid candidate even when explicitly enabled.
        return compareBy<PlaybackSourceCandidate> { it.stream.isTorrentStream }.then(ranked)
    }

    private fun matchesRequirements(facts: SourceFacts, tier: PlaybackQualityTier?): Boolean {
        tier ?: return true
        val hasHdr = facts.dynamicRange.isNotEmpty()
        return when (tier.dynamicRangePolicy) {
            DynamicRangePolicy.REQUIRE_HDR -> hasHdr
            DynamicRangePolicy.REQUIRE_DOLBY_VISION -> "DOLBY_VISION" in facts.dynamicRange
            else -> true
        }
    }

    private fun isPlaybackProtocolEligible(
        candidate: PlaybackSourceCandidate,
        allowTorrentSources: Boolean,
    ): Boolean {
        val stream = candidate.stream
        val directUrl = stream.playableDirectUrl?.trim()?.lowercase()
        if (directUrl != null && (directUrl.startsWith("http://") || directUrl.startsWith("https://"))) {
            return ".torrent" !in directUrl
        }
        if (stream.isDirectDebridStream) return true
        if (candidate.facts.isDebridReady == false && stream.p2pInfoHash != null) return true
        return allowTorrentSources && stream.isTorrentStream && stream.p2pInfoHash != null &&
            (candidate.facts.seeders ?: 0) >= MIN_HEALTHY_SEEDERS
    }

    private fun isUncachedDebrid(candidate: PlaybackSourceCandidate): Boolean =
        candidate.facts.isDebridReady == false &&
            (candidate.stream.isTorrentStream || candidate.stream.clientResolve != null)
}
