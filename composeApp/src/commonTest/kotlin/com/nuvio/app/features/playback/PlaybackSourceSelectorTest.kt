package com.nuvio.app.features.playback

import com.nuvio.app.features.downloads.SourceFacts
import com.nuvio.app.features.downloads.SourceFactsExtractor
import com.nuvio.app.features.downloads.VideoResolution
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PlaybackSourceSelectorTest {
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
    fun bandwidthCapRejectsOversizedSourceAndKeepsOrderedFallbacks() {
        val oversized = candidate("https://cdn.example/4k.mkv", VideoResolution.UHD_2160, size = 9_000_000_000)
        val best = candidate("https://cdn.example/1080.mkv", VideoResolution.FULL_HD_1080, size = 2_000_000_000)
        val fallback = candidate("https://cdn.example/720.mkv", VideoResolution.HD_720, size = 1_000_000_000)
        val tier = PlaybackQualityTier(
            id = "test",
            name = "Test",
            targetResolution = VideoResolution.UHD_2160,
            megabitsPerSecond = 10.0,
        )

        val result = assertIs<PlaybackSelectionResult.Play>(
            PlaybackSourceSelector.select(
                listOf(oversized, fallback, best),
                tier,
                PlaybackSelectionContext(runtimeMinutes = 45, isEpisode = true),
            ),
        )
        assertEquals(best.stream, result.stream)
        assertEquals(listOf(fallback.stream), result.fallbacks)
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
        candidates.toList(),
        PlaybackQualityTier.Ultra,
        PlaybackSelectionContext(isEpisode = true, allowTorrentSources = allowTorrents),
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
    }
}
