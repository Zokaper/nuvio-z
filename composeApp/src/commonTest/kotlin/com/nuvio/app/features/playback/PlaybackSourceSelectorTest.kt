package com.nuvio.app.features.playback

import com.nuvio.app.features.downloads.SourceFacts
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
        ),
    )

    private companion object {
        const val HASH = "0123456789012345678901234567890123456789"
    }
}
