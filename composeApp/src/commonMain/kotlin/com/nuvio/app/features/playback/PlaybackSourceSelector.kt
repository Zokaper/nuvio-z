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

    /**
     * Plays the chosen quality option.
     *
     * The option already *is* a ranked group of real sources, so there is nothing left to
     * filter on quality here - no resolution ceiling, no byte cap, no overflow tier. What
     * survives is the part that was never about quality: which protocols are safe to start
     * unattended, and which debrid links might still be a "preparing" placeholder.
     */
    fun select(
        option: PlaybackQualityOption,
        context: PlaybackSelectionContext,
    ): PlaybackSelectionResult = select(option.candidates, context)

    /**
     * Plays the best of [candidates] with no quality constraint.
     *
     * Used by Best available and by the sticky-pin path, which has already decided *which*
     * release it wants and only needs the safety gates applied.
     */
    fun select(
        candidates: List<PlaybackSourceCandidate>,
        context: PlaybackSelectionContext,
    ): PlaybackSelectionResult {
        val eligible = candidates.filter { candidate ->
            isPlaybackProtocolEligible(candidate, context.allowTorrentSources)
        }
        val playable = eligible.filterNot(::isUncachedDebrid)
        playable.firstOrNull()?.let { selected ->
            return PlaybackSelectionResult.Play(
                stream = selected.stream,
                fallbacks = playable.drop(1).map(PlaybackSourceCandidate::stream),
            )
        }
        eligible.firstOrNull(::isUncachedDebrid)?.let { uncached ->
            return PlaybackSelectionResult.AskUncached(uncached.stream)
        }
        return PlaybackSelectionResult.NeedsManual(
            if (candidates.isEmpty()) "No source matched this quality"
            else "No source can be auto-played safely",
        )
    }

    /**
     * The shared ordering, for callers that hold a bare candidate list rather than an option.
     *
     * P2P is deliberately behind every HTTP/debrid candidate even when explicitly enabled.
     */
    fun rank(candidates: List<PlaybackSourceCandidate>): List<PlaybackSourceCandidate> {
        val ranked = SourceRanking.comparator(
            preferences = SourceRankingPreferences(
                codecPreference = CodecPreference.ANY,
                dynamicRangePolicy = DynamicRangePolicy.ANY,
                sizePreference = SizePreference.LARGEST_UNDER_CAP,
            ),
            midRangeTarget = null,
            factsOf = PlaybackSourceCandidate::facts,
            isDirectOf = { it.stream.playableDirectUrl != null },
            addonOrderOf = PlaybackSourceCandidate::addonOrder,
            stableUrlOf = { it.stream.playableDirectUrl.orEmpty() },
        )
        return candidates.sortedWith(
            compareBy<PlaybackSourceCandidate> { it.stream.isTorrentStream }.then(ranked),
        )
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

/**
 * Whether the stream request has settled enough to decide on.
 *
 * Firing on the first quiet moment picks from a half-filled list; never firing leaves the
 * quality sheet spinning with every row disabled and only the dismiss button working. The
 * third clause is what closes that second failure: a fetch can finish with streams present
 * that all fail the protocol or cache gates, and `toEmptyStateReason` deliberately reports
 * no empty state in that case - so without it, "settled but nothing is selectable" waited
 * forever for a signal that was never coming.
 */
internal fun isStreamlinedSelectionReady(
    requestToken: String?,
    expectedRequestToken: String,
    isAnyLoading: Boolean,
    candidateCount: Int,
    hasTerminalEmptyState: Boolean,
    hasStreams: Boolean = false,
): Boolean =
    requestToken == expectedRequestToken &&
        !isAnyLoading &&
        (candidateCount > 0 || hasTerminalEmptyState || hasStreams)
