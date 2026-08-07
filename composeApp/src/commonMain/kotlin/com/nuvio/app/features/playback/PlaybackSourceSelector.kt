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
        val matchingQuality = candidates.filter { candidate ->
            tier == null || candidate.facts.resolution?.height?.let {
                it <= tier.targetResolution.height
            } ?: true
        }.filter { candidate ->
            matchesRequirements(candidate.facts, tier)
        }
        val withinCap = matchingQuality.filter { candidate ->
            cap == null || candidate.facts.sizeBytes?.let { it <= cap } ?: true
        }
        val protocolEligibleWithinCap = withinCap.filter { candidate ->
            isPlaybackProtocolEligible(candidate, context.allowTorrentSources)
        }
        // A catalog is not guaranteed to contain a release beneath our preferred bandwidth
        // budget. Streamlined must still streamline: if quality-compatible sources exist,
        // choose the smallest safe overflow instead of abandoning the user on the Classic
        // list. The cap remains the primary path and retains its largest-under-cap ranking.
        val orderedWithinCap = protocolEligibleWithinCap.sortedWith(candidateComparator(tier))
        val orderedOverflow = matchingQuality
            .filter { it !in withinCap }
            .filter { candidate ->
                isPlaybackProtocolEligible(candidate, context.allowTorrentSources)
            }
            .sortedWith(overCapComparator(tier))
        val playableWithinCap = orderedWithinCap.filterNot(::isUncachedDebrid)
        playableWithinCap.firstOrNull()?.let { selected ->
            return PlaybackSelectionResult.Play(
                stream = selected.stream,
                fallbacks = playableWithinCap.drop(1).map(PlaybackSourceCandidate::stream),
            )
        }
        val playableOverflow = orderedOverflow.filterNot(::isUncachedDebrid)
        playableOverflow.firstOrNull()?.let { selected ->
            return PlaybackSelectionResult.Play(
                stream = selected.stream,
                fallbacks = playableOverflow.drop(1).map(PlaybackSourceCandidate::stream),
            )
        }
        (orderedWithinCap + orderedOverflow).firstOrNull(::isUncachedDebrid)?.let { uncached ->
            return PlaybackSelectionResult.AskUncached(uncached.stream)
        }
        return PlaybackSelectionResult.NeedsManual(
            if (matchingQuality.isEmpty()) "No source matched this quality tier"
            else "No source can be auto-played safely",
        )
    }

    private fun overCapComparator(tier: PlaybackQualityTier?): Comparator<PlaybackSourceCandidate> =
        compareBy<PlaybackSourceCandidate> { it.facts.sizeBytes ?: Long.MAX_VALUE }
            .then(candidateComparator(tier))

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
        // Cache state is not a transport. Torrentio/AIOStreams commonly return only an
        // infohash and ask the client to mint the debrid URL. A known-cached item in that
        // shape used to fall through to the raw-torrent gate while an uncached one was
        // admitted, which inverted the safe behaviour and broke Streamlined entirely.
        if (
            stream.p2pInfoHash != null &&
            (candidate.facts.isDebridReady != null || isDebridBacked(candidate) || stream.isAddonDebridCandidate)
        ) return true
        return allowTorrentSources && stream.isTorrentStream && stream.p2pInfoHash != null &&
            (candidate.facts.seeders ?: 0) >= MIN_HEALTHY_SEEDERS
    }

    /**
     * Whether this candidate must not be auto-played because the provider may still be
     * preparing it.
     *
     * **Unknown is not cached.** An uncached debrid request answers with the provider's
     * placeholder video - a two-minute "being prepared" slate - and auto-playing one is
     * indistinguishable from the app being broken. Requiring *positive* evidence of a cached
     * copy is the only safe default, because a debrid addon that advertises its cache state
     * only in the display name leaves [SourceFacts.isDebridReady] null rather than false.
     *
     * Scoped to debrid-backed candidates on purpose. Plugin scrapers and plain direct links
     * legitimately have no cache state at all, and treating their null as "not ready" would
     * empty the candidate set and turn Instant into a mode that never plays anything.
     */
    private fun isUncachedDebrid(candidate: PlaybackSourceCandidate): Boolean {
        if (candidate.facts.isDebridReady == false) {
            return candidate.stream.isTorrentStream || candidate.stream.clientResolve != null ||
                isDebridBacked(candidate)
        }
        return candidate.facts.isDebridReady == null && isDebridBacked(candidate)
    }

    /** Positive evidence that a debrid provider stands behind this candidate. */
    private fun isDebridBacked(candidate: PlaybackSourceCandidate): Boolean =
        candidate.facts.debridService != null ||
            candidate.stream.clientResolve != null ||
            candidate.stream.isDirectDebridStream
}
