package com.nuvio.app.features.playback

import com.nuvio.app.features.downloads.SourceFacts
import com.nuvio.app.features.downloads.VideoResolution
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsRepository
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Streamlined's failure chain, and the ordering that decides what it walks.
 *
 * Streamlined shipped without one: `completeStreamlinedOptionSelection` took
 * `PlaybackSelectionResult.Play.stream` and threw `fallbacks` away, so a provider answering
 * "not cached" at resolve time was the end of the road - while Instant, seeding the very same
 * chain, stepped past it. These cases pin the seam between the two halves of the fix: the
 * selector hands back a whole row, and the repository walks it.
 */
class StreamlinedFailureChainTest {

    @AfterTest
    fun tearDown() {
        StreamsRepository.consumeAutoPlay()
    }

    @Test
    fun `a chosen quality row seeds every source behind the winner`() {
        val row = row(cached("https://cdn.example/a.mkv"), cached("https://cdn.example/b.mkv"), cached("https://cdn.example/c.mkv"))
        val play = assertIs<PlaybackSelectionResult.Play>(PlaybackSourceSelector.select(row, CONTEXT))

        StreamsRepository.seedAutoPlayCandidates(listOf(play.stream) + play.fallbacks)

        assertEquals(play.stream, StreamsRepository.uiState.value.autoPlayStream)
        // The whole point: a resolve failure on the winner has somewhere to go.
        assertTrue(StreamsRepository.skipAutoPlayStream(play.stream))
        assertEquals(play.fallbacks.first(), StreamsRepository.uiState.value.autoPlayStream)
    }

    @Test
    fun `a chain that runs out says so rather than advancing to nothing`() {
        val row = row(cached("https://cdn.example/only.mkv"))
        val play = assertIs<PlaybackSelectionResult.Play>(PlaybackSourceSelector.select(row, CONTEXT))
        assertTrue(play.fallbacks.isEmpty())

        StreamsRepository.seedAutoPlayCandidates(listOf(play.stream))

        // False is what uncovers the source list in `App.kt`. Advancing to a null stream
        // instead would leave the progress overlay up over a playback that never starts.
        assertFalse(StreamsRepository.skipAutoPlayStream(play.stream))
        assertNull(StreamsRepository.uiState.value.autoPlayStream)
    }

    @Test
    fun `a streamlined chain survives the moment playback starts`() {
        val row = row(cached("https://cdn.example/a.mkv"), cached("https://cdn.example/b.mkv"))
        val play = assertIs<PlaybackSelectionResult.Play>(PlaybackSourceSelector.select(row, CONTEXT))
        StreamsRepository.seedAutoPlayCandidates(listOf(play.stream) + play.fallbacks)
        StreamsRepository.consumeAutoPlay()

        // A source that opens and dies a second in is the most common failure there is, and
        // Streamlined now reaches the same arm Instant does.
        assertTrue(StreamsRepository.failOverAfterPlaybackStarted())
        assertEquals(play.fallbacks.first(), StreamsRepository.uiState.value.autoPlayStream)
    }

    @Test
    fun `a known cached source leads an identical one of unknown state`() {
        // Same resolution, same size, same protocol: cache evidence is the only thing between
        // them, and without it the tie fell to the stable-url ordering - which here would put
        // the unknown one first.
        val unknown = PlaybackSourceCandidate(
            stream = StreamItem(name = "a", url = "https://cdn.example/a-unknown.mkv", addonName = "Addon", addonId = "addon"),
            facts = SourceFacts(resolution = VideoResolution.FULL_HD_1080, sizeBytes = SIZE),
        )
        val known = PlaybackSourceCandidate(
            stream = StreamItem(name = "z", url = "https://cdn.example/z-cached.mkv", addonName = "Addon", addonId = "addon"),
            facts = SourceFacts(
                resolution = VideoResolution.FULL_HD_1080,
                sizeBytes = SIZE,
                isDebridReady = true,
                debridService = "torbox",
            ),
        )

        val row = PlaybackQualityOptions.build(listOf(unknown, known), CONTEXT)
            .first { it.resolution == VideoResolution.FULL_HD_1080 }

        assertEquals(known.stream, row.candidates.first().stream)
    }

    @Test
    fun `an implausible size never leads its row even when it is cached`() {
        // Plausibility stays the *first* ranking key. Promoting cache evidence above it would
        // let an 80 GB season pack head the row again - and it would not show, because the
        // displayed bitrate and size come from `credibleBitrateMbps`, so only what actually
        // plays would regress.
        val pack = PlaybackSourceCandidate(
            stream = StreamItem(name = "pack", url = "https://cdn.example/season-pack.mkv", addonName = "Addon", addonId = "addon"),
            facts = SourceFacts(
                resolution = VideoResolution.FULL_HD_1080,
                sizeBytes = 80_000_000_000,
                isDebridReady = true,
                debridService = "torbox",
            ),
        )
        val episode = cached("https://cdn.example/episode.mkv")

        val row = PlaybackQualityOptions.build(listOf(pack, episode), CONTEXT)
            .first { it.resolution == VideoResolution.FULL_HD_1080 }

        assertEquals(episode.stream, row.candidates.first().stream)
        assertEquals(
            episode.stream,
            assertIs<PlaybackSelectionResult.Play>(PlaybackSourceSelector.select(row, CONTEXT)).stream,
        )
        // Still reachable behind the winner - a season pack often does resolve to the right file.
        assertTrue(row.candidates.any { it.stream == pack.stream })
    }

    @Test
    fun `the sheet previews the source the row would actually open`() {
        // `candidates.first()` is the wrong thing to describe: the protocol and cache gates
        // can skip it, and naming a release the user never receives is the same class of
        // untruth as quoting a season pack's bandwidth for a row.
        val row = row(cached("https://cdn.example/a.mkv"), cached("https://cdn.example/b.mkv"))
        val play = assertIs<PlaybackSelectionResult.Play>(PlaybackSourceSelector.select(row, CONTEXT))

        assertEquals(play.stream, PlaybackSourceSelector.previewSelection(row, CONTEXT)?.stream)
    }

    @Test
    fun `a row with nothing playable still describes what it would offer`() {
        // Every candidate is an uncached debrid link, so `select` asks rather than plays. The
        // sheet still has to say something about the row.
        val uncached = PlaybackSourceCandidate(
            stream = StreamItem(name = "u", url = "https://cdn.example/u.mkv", addonName = "Addon", addonId = "addon"),
            facts = SourceFacts(resolution = VideoResolution.FULL_HD_1080, sizeBytes = SIZE, debridService = "realdebrid"),
        )
        val row = PlaybackQualityOptions.build(listOf(uncached), CONTEXT)
            .first { it.resolution == VideoResolution.FULL_HD_1080 }

        assertIs<PlaybackSelectionResult.AskUncached>(PlaybackSourceSelector.select(row, CONTEXT))
        assertEquals(uncached.stream, PlaybackSourceSelector.previewSelection(row, CONTEXT)?.stream)
    }

    @Test
    fun `a source is described by resolution, release and provider`() {
        assertEquals(
            "1080p · WEB-DL · TorBox",
            PlaybackSourceSelector.describe(
                SourceFacts(
                    resolution = VideoResolution.FULL_HD_1080,
                    releaseQuality = "WEB-DL",
                    debridService = "TorBox",
                ),
            ),
        )
        // Nothing known is an empty string, not "null" - the caller falls back to the stream
        // label rather than showing a word the user cannot act on.
        assertEquals("", PlaybackSourceSelector.describe(null))
    }

    private fun row(vararg candidates: PlaybackSourceCandidate): PlaybackQualityOption =
        PlaybackQualityOptions.build(candidates.toList(), CONTEXT)
            .first { it.resolution == VideoResolution.FULL_HD_1080 }

    private fun cached(url: String) = PlaybackSourceCandidate(
        stream = StreamItem(name = url, url = url, addonName = "Addon", addonId = "addon"),
        facts = SourceFacts(
            resolution = VideoResolution.FULL_HD_1080,
            sizeBytes = SIZE,
            isDebridReady = true,
            debridService = "torbox",
        ),
    )

    private companion object {
        const val SIZE = 3_000_000_000L
        val CONTEXT = PlaybackSelectionContext(runtimeMinutes = 45, isEpisode = true)
    }
}
