package com.nuvio.app.features.playback

import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsRepository
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Debug 83, American Horror Story S1E1: a one-candidate Instant chain hit `NeverStarted`, the player
 * said "No safe automatic source matched" and popped, and the stream route took the pop for the
 * user leaving. It went to the details screen and a loading session stayed open over it for 25.8 s.
 *
 * `resolveAutoChainFatal` is what the player now runs on every fatal failure of an automatic play.
 */
class AutoChainFatalTest {

    @AfterTest
    fun tearDown() {
        StreamsRepository.abandonAutoPlay()
        StreamsRepository.seedAutoPlayCandidates(emptyList())
        StreamsRepository.consumeAutoPlay()
        StreamsRepository.consumeManualSourceRequest()
        StreamsRepository.consumeFailoverRetry()
        PlaybackLoadingController.session?.let { PlaybackLoadingController.close(it.token, reason = "test") }
    }

    private fun stream(id: String) = StreamItem(url = id, addonName = "TestAddon", addonId = "test.addon")

    private fun handedOffSession(): Long {
        val token = PlaybackLoadingController.open(
            step = PlaybackProgressStep.StartingPlayback,
            reason = "test",
        )
        PlaybackLoadingController.handOff(token)
        return token
    }

    @Test
    fun `one candidate instant chain that never started closes the session and asks for the source list`() {
        // `NeverStarted` arrives with the candidate still armed: no frame played, so
        // `onPlaybackStarted` never consumed it.
        StreamsRepository.seedAutoPlayCandidates(listOf(stream("only")))
        handedOffSession()

        assertEquals(AutoChainFatalOutcome.Exhausted, resolveAutoChainFatal())

        // Zero active loading sessions - the leaked surface of Debug 83.
        assertNull(PlaybackLoadingController.session)
        assertNull(PlaybackLoadingController.activeToken)
        // Lands on the manual fallback, by name - and is not a retry, which is what a back press looks like.
        assertTrue(StreamsRepository.isManualSourceRequestPending)
        assertEquals(CHAIN_EXHAUSTED_SOURCE_LIST_PATH, StreamsRepository.pendingManualSourceRequestPath)
        assertFalse(StreamsRepository.consumeFailoverRetry())
        // And nothing is left armed to relaunch behind the list.
        assertNull(StreamsRepository.uiState.value.autoPlayStream)
    }

    @Test
    fun `a source that played then died on a spent chain takes the same exit`() {
        StreamsRepository.seedAutoPlayCandidates(listOf(stream("only")))
        StreamsRepository.consumeAutoPlay() // the first frame retired the chain
        handedOffSession()

        assertEquals(AutoChainFatalOutcome.Exhausted, resolveAutoChainFatal())

        assertNull(PlaybackLoadingController.session)
        assertTrue(StreamsRepository.isManualSourceRequestPending)
    }

    @Test
    fun `a chain with a next candidate retries behind the same session and does not ask for the list`() {
        StreamsRepository.seedAutoPlayCandidates(listOf(stream("a"), stream("b")))
        val token = handedOffSession()

        assertEquals(AutoChainFatalOutcome.Retry, resolveAutoChainFatal())

        assertEquals(token, PlaybackLoadingController.activeToken)
        assertEquals("b", StreamsRepository.uiState.value.autoPlayStream?.url)
        assertFalse(StreamsRepository.isManualSourceRequestPending)
        assertTrue(StreamsRepository.consumeFailoverRetry())
    }

    @Test
    fun `the exit is consumed once and resets the path`() {
        StreamsRepository.signalManualSourceRequest(CHAIN_EXHAUSTED_SOURCE_LIST_PATH)
        assertTrue(StreamsRepository.consumeManualSourceRequest())
        assertFalse(StreamsRepository.consumeManualSourceRequest())
        assertEquals("manual_escape_from_player", StreamsRepository.pendingManualSourceRequestPath)
    }
}
