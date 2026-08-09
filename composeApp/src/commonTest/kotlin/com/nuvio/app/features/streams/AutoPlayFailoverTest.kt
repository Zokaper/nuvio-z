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
}
