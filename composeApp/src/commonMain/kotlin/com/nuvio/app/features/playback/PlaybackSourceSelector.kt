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
     * The candidate [select] would start for [option], without starting it.
     *
     * The sheet describes each row by the source that will actually open, and that is not
     * `option.candidates.first()`: the protocol and cache gates below can skip several
     * candidates before landing on one. Describing the first entry would name a release the
     * user never receives, which is the same class of untruth as quoting a season pack's
     * bandwidth for a row.
     */
    fun previewSelection(
        option: PlaybackQualityOption,
        context: PlaybackSelectionContext,
    ): PlaybackSourceCandidate? = option.candidates
        .filter { isPlaybackProtocolEligible(it, context.allowTorrentSources) }
        .let { eligible -> eligible.firstOrNull { !isUncachedDebrid(it) } ?: eligible.firstOrNull() }

    /**
     * A short human description of a source: `1080p · WEB-DL · TorBox`.
     *
     * Used by the quality sheet to say what a row would open and by the failure chain to say
     * what it just gave up on. Both need the same words, so there is one function.
     */
    fun describe(facts: SourceFacts?): String = listOfNotNull(
        facts?.resolution.qualityLabel.takeIf { it.isNotBlank() },
        facts?.releaseQuality?.takeIf { it.isNotBlank() },
        (facts?.debridService ?: facts?.providerName)?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

    /**
     * The same words without the resolution: `WEB-DL · DV · TorBox`.
     *
     * For callers that have already said which resolution this is - the quality sheet puts it
     * in a badge - where repeating it would be noise. Dynamic range is not in that badge and
     * is the one thing here the user chooses between two otherwise identical 4K releases on.
     */
    fun describeRelease(facts: SourceFacts?): String = listOfNotNull(
        facts?.releaseQuality?.takeIf { it.isNotBlank() },
        dynamicRangeLabel(facts),
        (facts?.debridService ?: facts?.providerName)?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

    /**
     * What the file *is*: `4K · DV · 18.2 GB`.
     *
     * For the Best available card, which has no resolution badge above it and quotes no
     * bandwidth of its own, so [describeRelease] was left carrying the whole card - and
     * `WEB-DL · TorBox` tells a user nothing about what they are about to receive. Which
     * protocol a release was ripped from and which host is serving it are the least useful
     * facts on hand; resolution, dynamic range and size are the ones being chosen between.
     *
     * Every part is omitted when unknown rather than placeholdered. A source that reported no
     * size reads `4K · DV`, because a card admitting it does not know is worth more than one
     * printing a figure it invented - the whole reason this sheet stopped quoting presets.
     */
    fun describeBestRelease(
        facts: SourceFacts?,
        // Passed in rather than called: `formatFileSize` reaches the generated resource
        // bundle, and this file's whole point is that it compiles and runs without Compose.
        formatSize: (Long) -> String,
    ): String = listOfNotNull(
        facts?.resolution.qualityLabel.takeIf { it.isNotBlank() },
        dynamicRangeLabel(facts),
        facts?.sizeBytes?.takeIf { it > 0L }?.let(formatSize),
    ).joinToString(" · ")

    /**
     * One dynamic-range word, best first, or null.
     *
     * Never a list. `SourceFacts.dynamicRange` is a set and a Dolby Vision release routinely
     * carries an HDR10 base layer too, so joining it would spend a 280 dp single-line row on
     * `DV · HDR10` - two ways of saying the same file is the good one.
     */
    fun dynamicRangeLabel(facts: SourceFacts?): String? {
        val ranges = facts?.dynamicRange.orEmpty()
        return when {
            "DOLBY_VISION" in ranges -> "DV"
            "HDR10" in ranges -> "HDR10"
            "HDR" in ranges -> "HDR"
            "HLG" in ranges -> "HLG"
            else -> null
        }
    }

    /**
     * The host worth measuring the connection against for [option], if there is one.
     *
     * Deliberately built from [previewSelection] rather than from the whole bucket: the probe
     * should pull bytes from the host that will actually serve this card, and it must not mint
     * a debrid link to find one - a candidate still needing `clientResolve` has no URL here and
     * the probe falls back to a neutral endpoint.
     */
    fun probeTarget(
        option: PlaybackQualityOption,
        context: PlaybackSelectionContext,
    ): ProbeTarget? {
        val candidate = previewSelection(option, context) ?: return null
        return ProbeTarget(
            url = candidate.stream.playableDirectUrl,
            headers = candidate.stream.behaviorHints.proxyHeaders?.request.orEmpty(),
            providerId = candidate.facts.debridService ?: candidate.facts.providerId,
        )
    }

    data class ProbeTarget(
        /** Null when the source still needs resolving; the caller measures a neutral endpoint. */
        val url: String?,
        val headers: Map<String, String>,
        val providerId: String?,
    )

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
