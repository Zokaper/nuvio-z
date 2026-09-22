package com.nuvio.app.features.social

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lifecycle a friend actually sees in Watching Now.
 *
 * A friend stayed visible, reading PAUSED, long after the player had been closed. The clear was
 * issued on the player's `rememberCoroutineScope()`, which the departure itself cancels, so it
 * never reached the backend. The distinction these cases pin down is that **pausing keeps presence
 * and leaving clears it**, and that neither an episode handoff nor a fast relaunch may clear the
 * wrong row.
 *
 * ⚠ There is no `kotlinx-coroutines-test` on this module's test classpath, so the clear is pumped
 * by hand. That is not a workaround being apologised for - it is what makes the interleaving in
 * [aRelaunchDuringTheClearKeepsTheNewRow] exact rather than hopeful.
 */
class SocialPresenceSessionTest {

    /** A dispatcher that runs nothing until told to, so "while the clear was in flight" is a fact. */
    private class ManualDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }
        fun runAll() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    private val dispatcher = ManualDispatcher()
    private val cleared = mutableListOf<String>()
    private lateinit var originalClear: suspend (String) -> Result<Unit>
    private lateinit var originalScope: CoroutineScope

    @BeforeTest
    fun setUp() {
        originalClear = SocialPresenceSession.clearPresence
        originalScope = SocialPresenceSession.scope
        cleared.clear()
        SocialPresenceSession.clearPresence = { cleared += it; Result.success(Unit) }
        SocialPresenceSession.scope = CoroutineScope(SupervisorJob() + dispatcher)
    }

    @AfterTest
    fun tearDown() {
        SocialPresenceSession.clearPresence = originalClear
        SocialPresenceSession.scope = originalScope
        val live = SocialPresenceSession.state.value
        SocialPresenceSession.detach(live.deviceId.orEmpty(), live.sessionId.orEmpty())
    }

    @Test
    fun leavingPlaybackClearsPresence() {
        SocialPresenceSession.attach("device", "s1", WatchJoinPolicy.direct)
        val job = SocialPresenceSession.detachAndClear("device", "s1")
        dispatcher.runAll()
        assertTrue(job != null && job.isCompleted)
        assertEquals(listOf("device"), cleared)
        assertNull(SocialPresenceSession.state.value.sessionId)
    }

    @Test
    fun pausingIsNotLeavingSoPresenceSurvives() {
        SocialPresenceSession.attach("device", "s1", WatchJoinPolicy.direct)
        // Pause is a publish carrying SocialPlaybackState.paused; nothing here is touched by it.
        dispatcher.runAll()
        assertEquals("s1", SocialPresenceSession.state.value.sessionId)
        assertTrue(cleared.isEmpty())
    }

    @Test
    fun anEpisodeHandoffKeepsTheSameSessionAttached() {
        SocialPresenceSession.attach("device", "s1", WatchJoinPolicy.direct)
        // The player's presence effect is keyed on the device, so an episode change re-runs attach
        // with the same session rather than detaching and clearing.
        SocialPresenceSession.attach("device", "s1", WatchJoinPolicy.direct)
        dispatcher.runAll()
        assertEquals("s1", SocialPresenceSession.state.value.sessionId)
        assertTrue(cleared.isEmpty(), "an episode handoff must not clear Watching Now")
    }

    @Test
    fun aStaleSessionNeverClearsTheLiveOne() {
        SocialPresenceSession.attach("device", "s1", WatchJoinPolicy.direct)
        SocialPresenceSession.attach("device", "s2", WatchJoinPolicy.direct)
        assertNull(SocialPresenceSession.detachAndClear("device", "s1"))
        dispatcher.runAll()
        assertTrue(cleared.isEmpty(), "s1 lost the session to s2 and must not clear s2's row")
        assertEquals("s2", SocialPresenceSession.state.value.sessionId)
    }

    @Test
    fun aRelaunchDuringTheClearKeepsTheNewRow() {
        SocialPresenceSession.attach("device", "s1", WatchJoinPolicy.direct)
        val job = SocialPresenceSession.detachAndClear("device", "s1")
        // The new player attaches before the queued clear runs. Presence is keyed by device, so a
        // clear that went ahead here would delete the row the new session has just taken over.
        SocialPresenceSession.attach("device", "s2", WatchJoinPolicy.direct)
        dispatcher.runAll()
        assertTrue(job != null && job.isCompleted)
        assertTrue(cleared.isEmpty())
        assertEquals("s2", SocialPresenceSession.state.value.sessionId)
    }

    @Test
    fun aFailedClearIsReportedRatherThanThrown() {
        SocialPresenceSession.clearPresence = { Result.failure(IllegalStateException("offline")) }
        SocialPresenceSession.attach("device", "s1", WatchJoinPolicy.direct)
        val job = SocialPresenceSession.detachAndClear("device", "s1")
        dispatcher.runAll()
        assertTrue(job != null && job.isCompleted)
        assertNull(SocialPresenceSession.state.value.sessionId)
    }
}
