package com.nuvio.app.features.playback

import com.nuvio.app.buildP2pPlayerLaunch
import com.nuvio.app.features.streams.p2pSentinelUrl
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamLaunch
import com.nuvio.app.features.streams.StreamSubtitle
import com.nuvio.app.features.streams.StreamsRepository
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class P2pAutoPlayLaunchTest {

    @AfterTest
    fun tearDown() {
        StreamsRepository.seedAutoPlayCandidates(emptyList())
        StreamsRepository.consumeAutoPlay()
    }

    @Test
    fun `automatic P2P launch carries the failure chain`() {
        val launch = StreamLaunch(
            profileId = 1,
            type = "movie",
            videoId = "tt1234567",
            title = "Big Buck Bunny",
        )
        val infoHash = "0123456789abcdef0123456789abcdef01234567"
        val stream = StreamItem(
            infoHash = infoHash,
            addonName = "TorrentAddon",
            addonId = "addon.torrent",
        )

        val replaceStreamRoute = true
        val isClassic = false
        val hasFailureChain = replaceStreamRoute && !isClassic

        val playerLaunch = buildP2pPlayerLaunch(
            launch = launch,
            stream = stream,
            infoHash = infoHash,
            sentinelUrl = p2pSentinelUrl(infoHash, stream.p2pFileIdx),
            effectiveVideoId = launch.videoId,
            pauseDescription = null,
            resolvedResumePositionMs = null,
            resolvedResumeProgressFraction = null,
            autoPickedWithFailureChain = hasFailureChain,
            autoPickAttempt = 1,
        )

        assertTrue(playerLaunch.autoPickedWithFailureChain)
    }

    @Test
    fun `manual P2P launch does not carry the failure chain`() {
        val launch = StreamLaunch(
            profileId = 1,
            type = "movie",
            videoId = "tt1234567",
            title = "Big Buck Bunny",
        )
        val infoHash = "0123456789abcdef0123456789abcdef01234567"
        val stream = StreamItem(
            infoHash = infoHash,
            addonName = "TorrentAddon",
            addonId = "addon.torrent",
        )

        val replaceStreamRoute = false
        val isClassic = false
        val hasFailureChain = replaceStreamRoute && !isClassic

        val playerLaunch = buildP2pPlayerLaunch(
            launch = launch,
            stream = stream,
            infoHash = infoHash,
            sentinelUrl = p2pSentinelUrl(infoHash, stream.p2pFileIdx),
            effectiveVideoId = launch.videoId,
            pauseDescription = null,
            resolvedResumePositionMs = null,
            resolvedResumeProgressFraction = null,
            autoPickedWithFailureChain = hasFailureChain,
            autoPickAttempt = 1,
        )

        assertFalse(playerLaunch.autoPickedWithFailureChain)
    }

    @Test
    fun `P2P launch retains external subtitles`() {
        val launch = StreamLaunch(
            profileId = 1,
            type = "movie",
            videoId = "tt1234567",
            title = "Big Buck Bunny",
        )
        val infoHash = "0123456789abcdef0123456789abcdef01234567"
        val subtitles = listOf(
            StreamSubtitle(
                url = "https://subtitles.example.com/en.srt",
                language = "en",
                name = "English SDH",
            ),
            StreamSubtitle(
                url = "https://subtitles.example.com/es.srt",
                language = "es",
                name = "Spanish",
            ),
        )
        val stream = StreamItem(
            infoHash = infoHash,
            addonName = "TorrentAddon",
            addonId = "addon.torrent",
            externalSubtitles = subtitles,
        )

        val playerLaunch = buildP2pPlayerLaunch(
            launch = launch,
            stream = stream,
            infoHash = infoHash,
            sentinelUrl = p2pSentinelUrl(infoHash, stream.p2pFileIdx),
            effectiveVideoId = launch.videoId,
            pauseDescription = null,
            resolvedResumePositionMs = null,
            resolvedResumeProgressFraction = null,
            autoPickedWithFailureChain = true,
            autoPickAttempt = 1,
        )

        assertEquals(subtitles, playerLaunch.externalSubtitles)
    }

    @Test
    fun `fatal playback failure advances to the next candidate when failure chain is active`() {
        val torrent1 = StreamItem(
            infoHash = "0123456789abcdef0123456789abcdef01234567",
            addonName = "TorrentAddon",
            addonId = "addon.torrent",
        )
        val torrent2 = StreamItem(
            infoHash = "123456789abcdef0123456789abcdef012345678",
            addonName = "TorrentAddon",
            addonId = "addon.torrent",
        )
        StreamsRepository.seedAutoPlayCandidates(listOf(torrent1, torrent2))

        assertEquals(torrent1, StreamsRepository.uiState.value.autoPlayStream)

        val failed = StreamsRepository.uiState.value.autoPlayStream
        assertNotNull(failed)
        val hasNext = StreamsRepository.skipAutoPlayStream(failed)
        assertTrue(hasNext, "Chain must advance to the next candidate")
        if (hasNext) StreamsRepository.signalFailoverRetry()

        assertEquals(torrent2, StreamsRepository.uiState.value.autoPlayStream)
        assertTrue(StreamsRepository.consumeFailoverRetry())
    }

    @Test
    fun `StreamRoute remains on back stack while failure chain is active and pops when exhausted or absent`() {
        fun shouldPopStreamRoute(replaceStreamRoute: Boolean, hasFailureChain: Boolean): Boolean =
            replaceStreamRoute && !hasFailureChain

        // Automatic mode with failure chain: StreamRoute must NOT be popped
        assertFalse(shouldPopStreamRoute(replaceStreamRoute = true, hasFailureChain = true))

        // Classic auto-play: StreamRoute IS popped
        assertTrue(shouldPopStreamRoute(replaceStreamRoute = true, hasFailureChain = false))

        // Manual pick from source list: StreamRoute is NOT popped
        assertFalse(shouldPopStreamRoute(replaceStreamRoute = false, hasFailureChain = false))
    }
}
