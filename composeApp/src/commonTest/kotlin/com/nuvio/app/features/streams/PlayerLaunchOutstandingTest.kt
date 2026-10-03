package com.nuvio.app.features.streams

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "The stream route is current again" is not evidence that the user left the player.
 *
 * Device QA 2026-10-03 (iPhone, Debug 78), recovered from the first Kotlin log the iOS export ever carried:
 *
 *     19:08:53.202 handoff: attempt=1 candidate=[TB] ComeTorz 2160p          (route navigates to the player)
 *     19:08:53.389 realization Resolving->Ready by=player-launch              (player composed, no media yet)
 *     19:08:53.458 loading session close reason=leave_to_details              (256 ms after the hand-off)
 *     19:08:53.673 give up to source list: path=back_from_quality_sheet ... realization=Ready
 *
 * and the Swift session log shows no touch on the screen between the Ask to join and 19:09:03. On iOS the
 * navigator hands `navigate` to the native stack and does not add the route to its own back stack, so for the
 * length of the push `currentRoute` still names the stream route while the player is already running; the
 * return effect read that as "the user came back". These cases pin the stated fact it now reads instead.
 */
class PlayerLaunchOutstandingTest {

    @AfterTest
    fun reset() {
        StreamsRepository.abandonAutoPlay()
    }

    @Test
    fun `a launch handed to the player is outstanding from the hand-off, before the player has composed`() {
        StreamsRepository.beginPlayerLaunch(launchId = 7, nowMs = 1_000)
        assertTrue(StreamsRepository.hasOutstandingPlayerLaunch(nowMs = 1_100))
    }

    @Test
    fun `it stays outstanding for as long as the player is composed, however long that is`() {
        StreamsRepository.beginPlayerLaunch(launchId = 7, nowMs = 1_000)
        StreamsRepository.notePlayerEntered(launchId = 7)
        assertTrue(StreamsRepository.hasOutstandingPlayerLaunch(nowMs = 1_000 + 3 * 3_600_000L))
    }

    @Test
    fun `the player's disposal ends it - that is the real return`() {
        StreamsRepository.beginPlayerLaunch(launchId = 7, nowMs = 1_000)
        StreamsRepository.notePlayerEntered(launchId = 7)
        StreamsRepository.endPlayerLaunch(launchId = 7)
        assertFalse(StreamsRepository.hasOutstandingPlayerLaunch(nowMs = 1_200))
    }

    @Test
    fun `disposal bumps the revision the return effect is keyed on, so a deferred wake-up is re-evaluated`() {
        StreamsRepository.beginPlayerLaunch(launchId = 7, nowMs = 1_000)
        val before = StreamsRepository.playerLaunchRevision.value
        StreamsRepository.endPlayerLaunch(launchId = 7)
        assertEquals(before + 1, StreamsRepository.playerLaunchRevision.value)
    }

    @Test
    fun `a launch whose player never composed stops being outstanding, so a failed navigation cannot hide a real return`() {
        StreamsRepository.beginPlayerLaunch(launchId = 7, nowMs = 1_000)
        assertTrue(StreamsRepository.hasOutstandingPlayerLaunch(nowMs = 1_000 + PLAYER_LAUNCH_GRACE_MS))
        assertFalse(StreamsRepository.hasOutstandingPlayerLaunch(nowMs = 1_000 + PLAYER_LAUNCH_GRACE_MS + 1))
        // And it stays ended: the answer does not flip back.
        assertFalse(StreamsRepository.hasOutstandingPlayerLaunch(nowMs = 1_000 + PLAYER_LAUNCH_GRACE_MS + 2))
    }

    @Test
    fun `a composed player is never expired by the grace period`() {
        StreamsRepository.beginPlayerLaunch(launchId = 7, nowMs = 1_000)
        StreamsRepository.notePlayerEntered(launchId = 7)
        assertTrue(StreamsRepository.hasOutstandingPlayerLaunch(nowMs = 1_000 + PLAYER_LAUNCH_GRACE_MS * 10))
    }

    @Test
    fun `a stale player's disposal cannot end a newer launch`() {
        // A failover relaunches while the previous player is still being torn down.
        StreamsRepository.beginPlayerLaunch(launchId = 7, nowMs = 1_000)
        StreamsRepository.beginPlayerLaunch(launchId = 8, nowMs = 2_000)
        StreamsRepository.endPlayerLaunch(launchId = 7)
        assertTrue(StreamsRepository.hasOutstandingPlayerLaunch(nowMs = 2_100))
        StreamsRepository.endPlayerLaunch(launchId = 8)
        assertFalse(StreamsRepository.hasOutstandingPlayerLaunch(nowMs = 2_200))
    }

    @Test
    fun `the user leaving settles it, whatever the player is doing`() {
        StreamsRepository.beginPlayerLaunch(launchId = 7, nowMs = 1_000)
        StreamsRepository.notePlayerEntered(launchId = 7)
        StreamsRepository.abandonAutoPlay()
        assertFalse(StreamsRepository.hasOutstandingPlayerLaunch(nowMs = 1_100))
    }

    @Test
    fun `a failover keeps its signal until the player is gone, then retries once`() {
        // The player says "retry" and pops. The route may wake between the two: it must not consume the signal
        // while the player is outstanding, and must find it intact once the player is disposed.
        StreamsRepository.beginPlayerLaunch(launchId = 7, nowMs = 1_000)
        StreamsRepository.notePlayerEntered(launchId = 7)
        StreamsRepository.signalFailoverRetry()

        assertTrue(StreamsRepository.hasOutstandingPlayerLaunch(nowMs = 1_100)) // the route returns early; nothing consumed

        StreamsRepository.endPlayerLaunch(launchId = 7)
        assertFalse(StreamsRepository.hasOutstandingPlayerLaunch(nowMs = 1_200))
        assertTrue(StreamsRepository.consumeFailoverRetry())
        assertFalse(StreamsRepository.consumeFailoverRetry())
    }

    @Test
    fun `with nothing handed off there is nothing outstanding`() {
        assertFalse(StreamsRepository.hasOutstandingPlayerLaunch(nowMs = 5_000))
    }
}
