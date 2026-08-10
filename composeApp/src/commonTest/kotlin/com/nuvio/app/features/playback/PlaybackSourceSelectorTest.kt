package com.nuvio.app.features.playback

import com.nuvio.app.features.downloads.SourceFacts
import com.nuvio.app.features.downloads.SourceFactsExtractor
import com.nuvio.app.features.downloads.VideoResolution
import com.nuvio.app.features.streams.AioParsedFile
import com.nuvio.app.features.streams.AioStreamData
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackSourceSelectorTest {
    @Test
    fun streamlinedWaitsThroughTransientEmptyRequestState() {
        assertFalse(
            isStreamlinedSelectionReady(
                requestToken = "episode",
                expectedRequestToken = "episode",
                isAnyLoading = false,
                candidateCount = 0,
                hasTerminalEmptyState = false,
            ),
        )
        assertTrue(
            isStreamlinedSelectionReady(
                requestToken = "episode",
                expectedRequestToken = "episode",
                isAnyLoading = false,
                candidateCount = 3,
                hasTerminalEmptyState = false,
            ),
        )
    }

    @Test
    fun settledWithStreamsButNoCandidatesIsTerminal() {
        // The stuck sheet: a fetch finishes with streams present that all fail the protocol
        // or cache gates. `toEmptyStateReason` reports no empty state in that case, so
        // without the streams clause this waited forever with every row disabled.
        assertTrue(
            isStreamlinedSelectionReady(
                requestToken = "episode",
                expectedRequestToken = "episode",
                isAnyLoading = false,
                candidateCount = 0,
                hasTerminalEmptyState = false,
                hasStreams = true,
            ),
        )
    }

    @Test
    fun allowsHttpHlsAndDashButRejectsTorrentFiles() {
        val result = select(
            candidate("https://cdn.example/video.torrent", resolution = VideoResolution.UHD_2160),
            candidate("https://cdn.example/master.m3u8", resolution = VideoResolution.FULL_HD_1080),
            candidate("https://cdn.example/manifest.mpd", resolution = VideoResolution.HD_720),
        )

        assertEquals("https://cdn.example/master.m3u8", assertIs<PlaybackSelectionResult.Play>(result).stream.url)
    }

    @Test
    fun uncachedDebridIsOfferedOnlyWhenNothingPlayableExists() {
        val uncached = candidate(
            url = null,
            resolution = VideoResolution.FULL_HD_1080,
            isDebridReady = false,
            infoHash = HASH,
        )
        assertIs<PlaybackSelectionResult.AskUncached>(select(uncached))

        val direct = candidate("https://cdn.example/video.mkv", VideoResolution.HD_720)
        assertEquals(direct.stream, assertIs<PlaybackSelectionResult.Play>(select(uncached, direct)).stream)
    }

    @Test
    fun torrentRequiresToggleAndHealthyKnownSeederCount() {
        val healthy = candidate(null, VideoResolution.FULL_HD_1080, seeders = 20, infoHash = HASH)
        assertIs<PlaybackSelectionResult.NeedsManual>(select(healthy))
        assertIs<PlaybackSelectionResult.Play>(select(healthy, allowTorrents = true))

        val unknown = candidate(null, VideoResolution.FULL_HD_1080, infoHash = HASH)
        assertIs<PlaybackSelectionResult.NeedsManual>(select(unknown, allowTorrents = true))
    }

    @Test
    fun cachedDebridInfoHashIsPlayableWithoutRawTorrentOptIn() {
        val cached = candidate(
            url = null,
            resolution = VideoResolution.FULL_HD_1080,
            isDebridReady = true,
            infoHash = HASH,
            debridService = "realdebrid",
        )

        assertEquals(cached.stream, assertIs<PlaybackSelectionResult.Play>(select(cached)).stream)
    }

    @Test
    fun unknownDebridInfoHashIsOfferedInsteadOfRejectedAsATorrent() {
        val unknown = candidate(
            url = null,
            resolution = VideoResolution.FULL_HD_1080,
            infoHash = HASH,
            debridService = "realdebrid",
        )

        assertIs<PlaybackSelectionResult.AskUncached>(select(unknown))
    }

    @Test
    fun aCachedAioInfoHashCatalogIsPlayableEndToEnd() {
        // The shape that broke Streamlined entirely in 0.4.2-beta: AIOStreams returns only an
        // infohash and advertises the cache state in the display name.
        fun aio(size: Long) = PlaybackSourceCandidate(
            stream = StreamItem(
                name = "[TB ⚡] Comet 2160p",
                description = "WEB-DL HEVC HDR",
                infoHash = HASH,
                addonName = "AIOStreams | ElfHosted",
                addonId = "addon:aiostreams",
                streamData = AioStreamData(
                    size = size,
                    debridService = "torbox",
                    debridCached = true,
                    parsedFile = AioParsedFile(resolution = "2160p", codec = "HEVC", hdr = listOf("HDR")),
                ),
            ),
        )

        val largest = aio(9_130_000_000)
        val context = PlaybackSelectionContext(runtimeMinutes = 55, isEpisode = true)
        val options = PlaybackQualityOptions.build(
            listOf(largest, aio(6_990_000_000), aio(6_470_000_000)),
            context,
        )

        // 15.7-22.1 Mbps across the three: one 4K row, not a High and a Low. A split the
        // user cannot act on is worse than no split.
        val row = options.single { it.resolution == VideoResolution.UHD_2160 }
        assertEquals(PlaybackQualityOption.Variant.SINGLE, row.variant)

        val result = assertIs<PlaybackSelectionResult.Play>(PlaybackSourceSelector.select(row, context))
        assertEquals(largest.stream, result.stream)
        assertEquals(2, result.fallbacks.size)
    }

    @Test
    fun aDebridSourceOfUnknownCacheStateIsNeverAutoPlayed() {
        // The 0.4.1-beta field failure: an ElfHosted/AIOStreams link whose cache state lives
        // only in the display name, so `debridCached` was absent and isDebridReady was null.
        // Auto-play treated unknown as fine and started the provider's placeholder video.
        val unknown = candidate(
            "https://cdn.example/unknown.mkv",
            VideoResolution.FULL_HD_1080,
            debridService = "realdebrid",
        )
        assertIs<PlaybackSelectionResult.AskUncached>(select(unknown))
    }

    @Test
    fun anUnknownDebridSourceStillLosesToAKnownCachedOne() {
        val unknown = candidate(
            "https://cdn.example/unknown.mkv",
            VideoResolution.UHD_2160,
            debridService = "realdebrid",
        )
        val cached = candidate(
            "https://cdn.example/cached.mkv",
            VideoResolution.HD_720,
            isDebridReady = true,
            debridService = "realdebrid",
        )
        assertEquals(cached.stream, assertIs<PlaybackSelectionResult.Play>(select(unknown, cached)).stream)
    }

    @Test
    fun aNonDebridSourceWithNoCacheStateStillPlays() {
        // The regression guard for over-applying the fail-safe: plugin scrapers and plain
        // direct links have no cache state at all, and gating on null globally would empty
        // the candidate set and leave Instant unable to play anything.
        val plugin = candidate("https://cdn.example/plugin.mkv", VideoResolution.FULL_HD_1080)
        assertEquals(plugin.stream, assertIs<PlaybackSelectionResult.Play>(select(plugin)).stream)
    }

    @Test
    fun theFailureChainCarriesNoUncachedCandidates() {
        // fallbacks is what Instant's retry walks; a placeholder on attempt two is the same
        // bug one retry later.
        val best = candidate("https://cdn.example/a.mkv", VideoResolution.FULL_HD_1080, isDebridReady = true, debridService = "rd")
        val unknown = candidate("https://cdn.example/b.mkv", VideoResolution.HD_720, debridService = "rd")
        val alsoGood = candidate("https://cdn.example/c.mkv", VideoResolution.HD_720, isDebridReady = true, debridService = "rd")

        val result = assertIs<PlaybackSelectionResult.Play>(select(best, unknown, alsoGood))
        assertEquals(listOf(alsoGood.stream), result.fallbacks)
    }

    @Test
    fun theHourglassMarkerIsReadAsNotCached() {
        assertEquals(false, SourceFactsExtractor.parseDebridCacheMarker("⏳ 1080p WEB-DL"))
        assertEquals(true, SourceFactsExtractor.parseDebridCacheMarker("⚡ 1080p WEB-DL"))
        assertEquals(null, SourceFactsExtractor.parseDebridCacheMarker("1080p WEB-DL"))
        // "not cached" must not be read as "cached".
        assertEquals(false, SourceFactsExtractor.parseDebridCacheMarker("Not Cached - 1080p"))
    }

    private fun select(
        vararg candidates: PlaybackSourceCandidate,
        allowTorrents: Boolean = false,
    ) = PlaybackSourceSelector.select(
        PlaybackSourceSelector.rank(candidates.toList()),
        PlaybackSelectionContext(isEpisode = true, allowTorrentSources = allowTorrents),
    )

    @Test
    fun bestAvailableNamesTheFileNotTheProtocol() {
        // The whole point of the Best available card's line: `WEB-DL · TorBox` told the user
        // which rip it came from and which host serves it, neither of which is what they are
        // choosing between.
        val facts = SourceFacts(
            resolution = VideoResolution.UHD_2160,
            sizeBytes = 18_200_000_000L,
            dynamicRange = setOf("DOLBY_VISION", "HDR10"),
            releaseQuality = "WEB-DL",
            debridService = "TorBox",
        )

        assertEquals(
            "4K · DV · 18.2 GB",
            PlaybackSourceSelector.describeBestRelease(facts) { "18.2 GB" },
        )
        // One word, never a list: a Dolby Vision release routinely carries an HDR10 base layer.
        assertEquals("DV", PlaybackSourceSelector.dynamicRangeLabel(facts))
        // The caption keeps the provider and gains the range; the resolution stays out of it
        // because the badge above already carries it on every card that has one.
        assertEquals("WEB-DL · DV · TorBox", PlaybackSourceSelector.describeRelease(facts))
    }

    @Test
    fun anUnknownFieldIsOmittedRatherThanPlaceholdered() {
        val noSize = SourceFacts(resolution = VideoResolution.UHD_2160, dynamicRange = setOf("HDR"))
        assertEquals("4K · HDR", PlaybackSourceSelector.describeBestRelease(noSize) { "never called" })

        val bare = SourceFacts(resolution = VideoResolution.FULL_HD_1080)
        assertEquals("1080p", PlaybackSourceSelector.describeBestRelease(bare) { "never called" })
        assertEquals("", PlaybackSourceSelector.describeBestRelease(null) { "never called" })
    }

    @Test
    fun dynamicRangeLabelPrefersTheBetterFormat() {
        fun label(vararg ranges: String) =
            PlaybackSourceSelector.dynamicRangeLabel(SourceFacts(dynamicRange = ranges.toSet()))

        assertEquals("DV", label("HLG", "HDR10", "DOLBY_VISION"))
        assertEquals("HDR10", label("HDR", "HDR10"))
        assertEquals("HDR", label("HDR"))
        assertEquals("HLG", label("HLG"))
        assertNull(label())
    }

    @Test
    fun theProbeMeasuresTheSourceThatWouldOpenNotTheFirstCandidate() {
        // Same rule the description line follows: an uncached debrid entry at the head of the
        // bucket is skipped, so measuring its host would measure one the user never reaches.
        val uncached = candidate(
            "https://slow.example/uncached.mkv",
            resolution = VideoResolution.UHD_2160,
            isDebridReady = false,
            debridService = "TorBox",
        )
        val playable = candidate(
            "https://fast.example/ready.mkv",
            resolution = VideoResolution.FULL_HD_1080,
            debridService = "Real-Debrid",
        )
        val option = option(uncached, playable)

        val target = PlaybackSourceSelector.probeTarget(option, CONTEXT)

        assertEquals("https://fast.example/ready.mkv", target?.url)
        assertEquals("Real-Debrid", target?.providerId)
    }

    @Test
    fun aSourceStillNeedingResolvingOffersNoUrlToProbe() {
        // No debrid link is ever minted to run a measurement, so a candidate without a direct
        // URL yields none and the probe falls back to a neutral endpoint.
        val option = option(candidate(url = null, resolution = VideoResolution.UHD_2160, infoHash = HASH))

        val target = PlaybackSourceSelector.probeTarget(option, CONTEXT.copy(allowTorrentSources = true))

        assertNull(target?.url)
    }

    private fun option(vararg candidates: PlaybackSourceCandidate) = PlaybackQualityOption(
        id = "test",
        resolution = null,
        variant = PlaybackQualityOption.Variant.BEST,
        requiredMbps = null,
        representativeBitrateMbps = null,
        isEstimateApproximate = false,
        representativeSizeBytes = null,
        candidates = candidates.toList(),
    )

    private fun candidate(
        url: String?,
        resolution: VideoResolution,
        size: Long? = 1_000_000_000,
        seeders: Int? = null,
        isDebridReady: Boolean? = null,
        infoHash: String? = null,
        debridService: String? = null,
    ) = PlaybackSourceCandidate(
        stream = StreamItem(
            name = url ?: "torrent",
            url = url,
            infoHash = infoHash,
            addonName = "Addon",
            addonId = "addon",
        ),
        facts = SourceFacts(
            resolution = resolution,
            sizeBytes = size,
            seeders = seeders,
            isDebridReady = isDebridReady,
            debridService = debridService,
        ),
    )

    private companion object {
        const val HASH = "0123456789012345678901234567890123456789"
        val CONTEXT = PlaybackSelectionContext(runtimeMinutes = 55, isEpisode = true)
    }
}
