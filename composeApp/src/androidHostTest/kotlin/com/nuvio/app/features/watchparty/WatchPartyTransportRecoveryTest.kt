package com.nuvio.app.features.watchparty

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Exercises the real adapter's teardown/invalidation, without creating a Supabase socket. */
class WatchPartyTransportRecoveryTest {
    private val adapter = WatchPartySync
    private fun field(name: String) = adapter.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun get(name: String): Any? = field(name).get(adapter)
    private fun set(name: String, value: Any?) = field(name).set(adapter, value)
    private fun invoke(name: String) = adapter.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(adapter)

    @Test
    fun foregroundAndReconnectCannotRepublishPreAwayReadiness() = runBlocking {
        delay(100)
        try {
            set("authority", PartyAuthorityContext("party", "guest", "host", WatchPartyControlMode.host_only,
                9, PartyGenerationKey("party", 1, 0, 0)))
            set("selfAway", false)
            adapter.publishPeerStatus(WatchPartyStatus.paused, false)
            adapter.setLocalPresence(true)
            adapter.setLocalPresence(false)
            assertTrue(adapter.isLocallyReturning())
            assertTrue(get("peerStarved") as Boolean)
            invoke("stopChannelJobs")
            // Clock/reconnect publication of the old paused/ready sample is still withdrawn.
            adapter.publishPeerStatus(WatchPartyStatus.paused, false)
            assertTrue(get("peerStarved") as Boolean)
            adapter.completeLocalReturn()
            adapter.publishPeerStatus(WatchPartyStatus.paused, false)
            assertFalse(adapter.isLocallyReturning())
            assertFalse(get("peerStarved") as Boolean)
        } finally {
            set("authority", null)
            set("selfAway", false)
            adapter.completeLocalReturn()
            invoke("resetProtocolState")
        }
    }

    @Test
    fun secondAbsenceCannotBeCompletedByAnOlderCatchup() = runBlocking {
        delay(100)
        try {
            set("authority", PartyAuthorityContext("party", "guest", "host", WatchPartyControlMode.host_only,
                9, PartyGenerationKey("party", 1, 0, 0)))
            set("selfAway", false)
            adapter.setLocalPresence(true)
            adapter.setLocalPresence(false)
            adapter.setLocalPresence(true)
            adapter.completeLocalReturn()
            assertTrue(adapter.isLocallyAway())
            assertFalse(adapter.isLocallyReturning())
        } finally {
            set("authority", null)
            set("selfAway", false)
            adapter.completeLocalReturn()
            invoke("resetProtocolState")
        }
    }

    @Test
    fun durableReturnClearsHeldAndDelayedPeerAwayButAcceptsANewAbsence() = runBlocking {
        delay(100)
        try {
            set("authority", PartyAuthorityContext("party", "host", "host", WatchPartyControlMode.host_only,
                9, PartyGenerationKey("party", 1, 0, 0)))
            val before = WatchPartyState(id = "party", hostProfileId = "host",
                status = WatchPartyStatus.playing, controlMode = WatchPartyControlMode.host_only,
                contentGeneration = 1, positionMs = 0, durationMs = 100_000, playbackSpeed = 1f,
                sequence = 9, stateUpdatedAt = "2026-09-30T12:00:00Z", content = PartyContent(
                contentType = "movie", contentId = "id", videoId = "id", title = "Test"),
                members = listOf(WatchPartyParticipant("guest", "member", SourceResolutionState.ready,
                    awaySince = "2026-09-30T12:00:00Z", joinedAt = "2026-09-30T11:00:00Z")))
            val reportAt = currentEpochMs() - 1_000
            fun peer(away: Boolean, at: Long) {
                val message = PartyPeerStatusMessage("party", "guest", WatchPartyStatus.paused, at, 20,
                    away = away, contentGeneration = 1)
                adapter.javaClass.getDeclaredMethod("acceptPeerStatus", PartyPeerStatusMessage::class.java)
                    .apply { isAccessible = true }.invoke(adapter, message)
            }
            peer(true, reportAt)
            assertTrue("guest" in adapter.state.value.awayProfileIds)
            adapter.reconcileDurablePresence(before, before.copy(members = before.members.map { it.copy(awaySince = null) }))
            assertFalse("guest" in adapter.state.value.awayProfileIds)
            peer(true, reportAt)
            assertFalse("guest" in adapter.state.value.awayProfileIds)
            val clearAt = (get("guestAwayClearedAtPartyMs") as Map<*, *>)["guest"] as Long
            peer(true, clearAt + 1)
            assertTrue("guest" in adapter.state.value.awayProfileIds)
        } finally {
            set("authority", null)
            invoke("resetProtocolState")
        }
    }

    @Test
    fun reconnectRetainsProtocolButGenerationAndDepartureInvalidateIt() = runBlocking {
        // Allow the process-owned collector's initial null binding to settle before seeding it.
        delay(100)
        val now = currentEpochMs()
        val clock = PartyClock(locked = true, samples = listOf(PartyClockSample(0, 20, now)))
        val command = PartyCommand("accepted-seek", PartyCommandKind.seek, "host", 9, 1, 50_000, now, 1f)
        val log = PartyCommandLog().record(command)
        val tick = PartyTick("party", 1, 9, WatchPartyStatus.playing, 50_000, now, 1f, 100_000)
        try {
            set("authority", PartyAuthorityContext("party", "guest", "host", WatchPartyControlMode.host_only,
                9, PartyGenerationKey("party", 1, 0, 0)))
            set("clock", clock)
            set("commandLog", log)
            set("commandCounter", 12L)
            set("tick", tick)
            set("peerStatus", WatchPartyStatus.playing)
            set("peerStarved", false)
            set("selfAway", true)
            set("tickAway", listOf("guest"))

            invoke("stopChannelJobs")
            assertSame(clock, get("clock"))
            assertSame(log, get("commandLog"))
            assertFalse((get("commandLog") as PartyCommandLog).accepts(command))
            assertEquals(12L, get("commandCounter"))
            assertSame(tick, get("tick"))
            assertEquals(WatchPartyStatus.playing, get("peerStatus"))
            assertTrue(adapter.isLocallyAway())
            assertEquals(listOf("guest"), get("tickAway"))
            // Keeping evidence does not extend its freshness or declare the transport live.
            assertTrue(tick.isStale(now + WatchPartyClockStaleMs + 1))
            assertTrue(clock.isStale(now + WatchPartyClockStaleMs + 1))

            invoke("invalidateGenerationState")
            assertNull(get("tick"))
            assertTrue((get("commandLog") as PartyCommandLog).accepts(command))
            assertEquals(0L, get("commandCounter"))
            assertTrue(adapter.isLocallyAway())

            invoke("resetProtocolState")
            assertEquals(PartyClock(), get("clock"))
            assertNull(get("peerStatus"))
            assertEquals(WatchPartySyncState(), adapter.state.value)
        } finally {
            set("authority", null)
            set("selfAway", false)
            invoke("resetProtocolState")
        }
    }
}
