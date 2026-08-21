package com.nuvio.app.features.streams

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Instant's failure chain across the moment playback starts.
 *
 * The chain is consumed on the first frame - it has to be, or backing out of the player would
 * relaunch it - which used to mean a source that played for a second and then died looked
 * identical to an exhausted chain, and the user was dropped on the details screen with ranked
 * candidates still untried. These cases pin the distinction.
 */
class AutoPlayFailoverTest {

    @AfterTest
    fun tearDown() {
        // An **empty seed**, not a bare `consumeAutoPlay`. Retiring is what carries a chain
        // across the moment playback starts, so it no longer doubles as a reset - and a case
        // that left one retained would otherwise hand it to the next one. `seedAutoPlayCandidates`
        // is the call that retires a previous chain for good, which is exactly what a teardown
        // wants. See `a second start does not discard the retained chain`.
        StreamsRepository.seedAutoPlayCandidates(emptyList())
        StreamsRepository.consumeAutoPlay()
    }

    @Test
    fun `failing after playback started advances to the next candidate`() {
        StreamsRepository.seedAutoPlayCandidates(listOf(stream("a"), stream("b"), stream("c")))
        StreamsRepository.consumeAutoPlay()
        assertNull(StreamsRepository.uiState.value.autoPlayStream)

        assertTrue(StreamsRepository.failOverAfterPlaybackStarted())
        assertEquals("b", StreamsRepository.uiState.value.autoPlayStream?.url)
        assertEquals(listOf("b", "c"), StreamsRepository.uiState.value.autoPlayCandidates.map { it.url })
    }

    @Test
    fun `failing on the last candidate reports the chain exhausted`() {
        StreamsRepository.seedAutoPlayCandidates(listOf(stream("only")))
        StreamsRepository.consumeAutoPlay()

        assertFalse(StreamsRepository.failOverAfterPlaybackStarted())
        assertNull(StreamsRepository.uiState.value.autoPlayStream)
    }

    @Test
    fun `failover is single-shot within one play`() {
        StreamsRepository.seedAutoPlayCandidates(listOf(stream("a"), stream("b")))
        StreamsRepository.consumeAutoPlay()

        assertTrue(StreamsRepository.failOverAfterPlaybackStarted())
        // The replacement is live now, so a second failure must go through the normal skip path
        // rather than re-arming a chain whose candidates have already been through the player.
        assertFalse(StreamsRepository.failOverAfterPlaybackStarted())
    }

    @Test
    fun `a fresh chain cannot fail over to the previous content's candidates`() {
        StreamsRepository.seedAutoPlayCandidates(listOf(stream("old-a"), stream("old-b")))
        StreamsRepository.consumeAutoPlay()

        StreamsRepository.seedAutoPlayCandidates(listOf(stream("new-a")))
        StreamsRepository.consumeAutoPlay()

        assertFalse(StreamsRepository.failOverAfterPlaybackStarted())
    }

    /**
     * The reported fault: "No safe automatic source matched" over a chain that was never spent.
     *
     * `onPlaybackStarted` fires on **every** not-playing -> playing transition, so a pause and a
     * resume - or a rebuffer the engine reports as a stop and a start - calls `consumeAutoPlay`
     * again. The second call read an `autoPlayStream` the first had already cleared and retained
     * *null* over the real chain, so a source that then died had nothing to fail over to and the
     * user was told the catalogue had nothing safe in it, with two ranked candidates in hand.
     */
    @Test
    fun `a second start does not discard the retained chain`() {
        StreamsRepository.seedAutoPlayCandidates(listOf(stream("a"), stream("b"), stream("c")))
        StreamsRepository.consumeAutoPlay()
        // The pause and the resume.
        StreamsRepository.consumeAutoPlay()
        StreamsRepository.consumeAutoPlay()

        assertTrue(StreamsRepository.failOverAfterPlaybackStarted())
        assertEquals("b", StreamsRepository.uiState.value.autoPlayStream?.url)
    }

    @Test
    fun `nothing to fail over to when the chain was never seeded`() {
        StreamsRepository.consumeAutoPlay()
        assertFalse(StreamsRepository.failOverAfterPlaybackStarted())
    }

    private fun stream(id: String): StreamItem = StreamItem(
        url = id,
        addonName = "TestAddon",
        addonId = "test.addon",
    )

    @Test
    fun `a reload for the same video keeps a re-armed chain`() {
        // The retry path in full: playback started (so the chain was consumed), the source
        // died, `failOverAfterPlaybackStarted` re-armed and advanced - and then the stream
        // route re-mounted and reloaded, because `consumeAutoPlay` clears `activeRequestKey`
        // and the reload's no-op guard therefore never matches. The reload used to wipe what
        // had just been re-armed, and nothing refilled it: Streamlined and Instant both load
        // with manualSelection = true, so the auto-play evaluation never runs for them.
        StreamsRepository.seedAutoPlayCandidates(listOf(stream("a"), stream("b")))
        StreamsRepository.consumeAutoPlay()
        assertTrue(StreamsRepository.failOverAfterPlaybackStarted())

        val armed = StreamsRepository.uiState.value.copy(requestToken = "series::s1e1::1::1::true")
        val carried = carriedAutoPlayChain(armed, "series::s1e1::1::1::true")
        val reloaded = StreamsUiState(requestToken = "series::s1e1::1::1::true")
            .withCarriedChain(carried)

        assertEquals("b", reloaded.autoPlayStream?.url)
        assertEquals(listOf("b"), reloaded.autoPlayCandidates.map { it.url })
        // Restored without these the route stops treating it as an automatic play, and the
        // chain sits there with nothing to run it.
        assertTrue(reloaded.isDirectAutoPlayFlow)
    }

    @Test
    fun `a reload for different content never inherits the chain`() {
        StreamsRepository.seedAutoPlayCandidates(listOf(stream("a"), stream("b")))
        val armed = StreamsRepository.uiState.value.copy(requestToken = "series::s1e1::1::1::true")

        assertNull(carriedAutoPlayChain(armed, "series::s1e2::1::2::true"))
        assertNull(
            StreamsUiState(requestToken = "series::s1e2::1::2::true")
                .withCarriedChain(carriedAutoPlayChain(armed, "series::s1e2::1::2::true"))
                .autoPlayStream,
        )
    }

    @Test
    fun `a reload with nothing armed carries nothing`() {
        // The ordinary case, and the one that must not start behaving like an auto-play flow:
        // a spent chain restored with `isDirectAutoPlayFlow` set would put the overlay back up
        // over a list the user is browsing.
        val spent = StreamsUiState(requestToken = "series::s1e1::1::1::true")
        assertNull(carriedAutoPlayChain(spent, "series::s1e1::1::1::true"))
        assertFalse(
            spent.withCarriedChain(carriedAutoPlayChain(spent, "series::s1e1::1::1::true"))
                .isDirectAutoPlayFlow,
        )
    }


    @Test
    fun `a retry is signalled, never inferred`() {
        // The distinction this exists for: after a hand-off, "the route is current again and a
        // candidate is still armed" is equally true when the player died and when the user
        // pressed Back before the first frame ever played. Inferring a retry from that state
        // relaunched the source the user had just walked out of, forever.
        StreamsRepository.seedAutoPlayCandidates(listOf(stream("a"), stream("b")))

        // Nobody said anything: the user left.
        assertFalse(StreamsRepository.consumeFailoverRetry())

        StreamsRepository.signalFailoverRetry()
        assertTrue(StreamsRepository.consumeFailoverRetry())
        // One-shot. A second look must not resurrect the retry.
        assertFalse(StreamsRepository.consumeFailoverRetry())
    }

    @Test
    fun `a retired chain leaves no retry signal behind`() {
        // An exhausted chain calls consumeAutoPlay, and a signal surviving that would fire
        // against whatever plays next.
        StreamsRepository.seedAutoPlayCandidates(listOf(stream("a")))
        StreamsRepository.signalFailoverRetry()
        StreamsRepository.consumeAutoPlay()

        assertFalse(StreamsRepository.consumeFailoverRetry())
    }

    @Test
    fun `a fresh chain drops an unconsumed signal from the last one`() {
        StreamsRepository.seedAutoPlayCandidates(listOf(stream("old")))
        StreamsRepository.signalFailoverRetry()

        StreamsRepository.seedAutoPlayCandidates(listOf(stream("new-a"), stream("new-b")))
        assertFalse(StreamsRepository.consumeFailoverRetry())
    }

}
