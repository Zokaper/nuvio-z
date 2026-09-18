package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchPartySessionStateTest {
    private val generation=PartyGenerationKey("party",1,2,3)
    private val playback=ActivePlaybackContext(
        attachmentId="attachment",contentId="tt1",videoId="tt1",
        descriptor=PartySourceDescriptorV2(
            originKind=PartySourceOriginKind.embedded,originId="nuvio",
            releaseFingerprint=partyReleaseFingerprint("Movie 2026 1080p"),
        ),positionMs=10,durationMs=100,playbackSpeed=1f,
    )

    @Test fun restoreReturnsToDurableLobby() {
        val state=reducePartySession(reducePartySession(PartySessionState(),PartySessionEvent.RestoreStarted),PartySessionEvent.Restored(generation))
        assertEquals(PartyClientPhase.Lobby,state.phase)
        assertTrue(state.membershipRetained)
        assertEquals("party",state.pendingLobbyPartyId)
    }

    @Test fun playerExitRetainsMembershipAndDropsOnlyAttachment() {
        val active=reducePartySession(PartySessionState(),PartySessionEvent.PlayerAttached(playback,generation))
        val detached=reducePartySession(active,PartySessionEvent.PlayerAttachmentLost("attachment"))
        assertEquals(PartyClientPhase.Detached,detached.phase)
        assertTrue(detached.membershipRetained)
        assertNull(detached.playback)
        assertEquals(generation,detached.generation)
        val lobby=reducePartySession(detached,PartySessionEvent.LobbyEntered("party"))
        assertEquals(PartyClientPhase.Lobby,lobby.phase)
    }

    @Test fun promotionAttachesWithoutReplacingPlayback() {
        val normal=reducePartySession(PartySessionState(),PartySessionEvent.PlayerAttached(playback,null))
        val promoted=reducePartySession(normal,PartySessionEvent.PlayerAttached(playback,generation))
        assertEquals(playback,promoted.playback)
        assertEquals(PartyClientPhase.ActivePlayer,promoted.phase)
    }

    @Test fun generationChangeCancelsReuseAndReturnsToMatching() {
        val active=reducePartySession(PartySessionState(),PartySessionEvent.PlayerAttached(playback,generation))
        val changed=reducePartySession(active,PartySessionEvent.SnapshotAdvanced(generation.copy(sourceGeneration=3)))
        assertEquals(PartyClientPhase.MatchingHostSource,changed.phase)
        assertFalse(generation.accepts(changed.generation!!))
    }

    @Test fun sameGenerationSnapshotDoesNotRestartMatching() {
        val active=reducePartySession(PartySessionState(),PartySessionEvent.PlayerAttached(playback,generation))
        val unchanged=reducePartySession(active,PartySessionEvent.SnapshotAdvanced(generation))
        assertEquals(PartyClientPhase.ActivePlayer,unchanged.phase)
        assertEquals(generation,unchanged.generation)
    }

    @Test fun repeatedPlayerLobbyCyclesAreIdempotent() {
        var state=PartySessionState()
        repeat(3) {
            state=reducePartySession(state,PartySessionEvent.PlayerAttached(playback,generation))
            assertEquals(PartyClientPhase.ActivePlayer,state.phase)
            state=reducePartySession(state,PartySessionEvent.PlayerAttachmentLost("attachment"))
            assertEquals(PartyClientPhase.Detached,state.phase)
            state=reducePartySession(state,PartySessionEvent.LobbyEntered("party"))
            assertEquals(PartyClientPhase.Lobby,state.phase)
            assertEquals(generation,state.generation)
            assertTrue(state.membershipRetained)
        }
    }

    @Test fun contentGenerationChangeStartsNewMatchingFlow() {
        val active=reducePartySession(PartySessionState(),PartySessionEvent.PlayerAttached(playback,generation))
        val changed=reducePartySession(active,PartySessionEvent.SnapshotAdvanced(generation.copy(contentGeneration=2)))
        assertEquals(PartyClientPhase.MatchingHostSource,changed.phase)
    }

    @Test fun hostEndKeepsNormalPlaybackWhileGuestGetsChoice() {
        val active=reducePartySession(PartySessionState(),PartySessionEvent.PlayerAttached(playback,generation))
        val host=reducePartySession(active,PartySessionEvent.Ended(viewerWasHost=true))
        assertEquals(playback,host.playback)
        assertEquals(PartyClientPhase.None,host.phase)
        val guest=reducePartySession(active,PartySessionEvent.Ended(viewerWasHost=false))
        assertEquals(PartyClientPhase.Ended,guest.phase)
        assertTrue(guest.guestPostEndChoice)
    }

    @Test fun staleAttachmentLossCannotDetachReplacementPlayer() {
        val replacement=playback.copy(attachmentId="replacement")
        val active=reducePartySession(PartySessionState(),PartySessionEvent.PlayerAttached(replacement,generation))
        assertEquals(active,reducePartySession(active,PartySessionEvent.PlayerAttachmentLost("attachment")))
    }

    @Test fun subscriptionAndSuccessfulSendDoNotClaimFullSync() {
        var health=reducePartyHealth(PartyHealthState(),PartyHealthEvent.RealtimeConnecting(7))
        health=reducePartyHealth(health,PartyHealthEvent.RealtimeSubscribed(7))
        health=reducePartyHealth(health,PartyHealthEvent.RealtimeSendCompleted(7,10,PartyRealtimeSendOutcome.LocallyAccepted))
        health=reducePartyHealth(health,PartyHealthEvent.DurableSucceeded(11,heartbeat=true))
        assertEquals(PartyRealtimeHealth.SubscribedUnverified,health.realtime)
        assertEquals(PartySyncCapability.DurableFallback,health.capability())
    }

    @Test fun actualPeerTrafficProvesFullSync() {
        var health=reducePartyHealth(PartyHealthState(),PartyHealthEvent.RealtimeConnecting(7))
        health=reducePartyHealth(health,PartyHealthEvent.RealtimeSubscribed(7))
        health=reducePartyHealth(health,PartyHealthEvent.DurableSucceeded(10))
        health=reducePartyHealth(health,PartyHealthEvent.RealtimeReceived(7,11))
        assertEquals(PartyRealtimeHealth.Live,health.realtime)
        assertEquals(PartySyncCapability.FullSync,health.capability())
    }

    @Test fun authorityPlaneTrafficIsTrackedApartFromPeerAndClock() {
        // The three axes answer different questions, and after the capability defect the useful one
        // is "has the server-authored plane ever delivered a command here". A party can exchange
        // clock pings all day while no command has ever crossed.
        var health=reducePartyHealth(PartyHealthState(),PartyHealthEvent.RealtimeConnecting(7))
        health=reducePartyHealth(health,PartyHealthEvent.RealtimeSubscribed(7))
        health=reducePartyHealth(health,PartyHealthEvent.RealtimeReceived(7,11,PartyRealtimeTrafficKind.Clock))
        assertNull(health.lastAuthorityTrafficAtMs)
        health=reducePartyHealth(health,PartyHealthEvent.RealtimeReceived(7,12,PartyRealtimeTrafficKind.Authority))
        assertEquals(12,health.lastAuthorityTrafficAtMs)
        assertEquals(11,health.lastClockTrafficAtMs)
        assertNull(health.lastPeerTrafficAtMs)
        assertEquals(PartyRealtimeHealth.Live,health.realtime)
    }

    @Test fun clockAndPeerFreshnessRemainIndependent() {
        var health=reducePartyHealth(PartyHealthState(),PartyHealthEvent.RealtimeConnecting(7))
        health=reducePartyHealth(health,PartyHealthEvent.RealtimeReceived(7,11,PartyRealtimeTrafficKind.Clock))
        assertEquals(11,health.lastClockTrafficAtMs)
        assertNull(health.lastPeerTrafficAtMs)
        health=reducePartyHealth(health,PartyHealthEvent.RealtimeReceived(7,12,PartyRealtimeTrafficKind.Peer))
        assertEquals(11,health.lastClockTrafficAtMs)
        assertEquals(12,health.lastPeerTrafficAtMs)
    }

    @Test fun replacementChannelStartsWithNoInheritedTelemetry() {
        var health=reducePartyHealth(PartyHealthState(),PartyHealthEvent.RealtimeConnecting(7))
        health=reducePartyHealth(health,PartyHealthEvent.RealtimeReceived(7,11,PartyRealtimeTrafficKind.Clock))
        health=reducePartyHealth(health,PartyHealthEvent.RealtimeSendCompleted(7,12,PartyRealtimeSendOutcome.LocallyAccepted))
        health=reducePartyHealth(health,PartyHealthEvent.RealtimeConnecting(8))
        assertEquals(PartyRealtimeHealth.Connecting,health.realtime)
        assertNull(health.lastRealtimeReceiveAtMs)
        assertNull(health.lastPeerTrafficAtMs)
        assertNull(health.lastClockTrafficAtMs)
        assertNull(health.lastRealtimeSendAtMs)
        assertEquals(PartyRealtimeSendOutcome.None,health.lastRealtimeSendOutcome)
    }

    @Test fun staleChannelEventsCannotHealReplacementChannel() {
        var health=reducePartyHealth(PartyHealthState(),PartyHealthEvent.RealtimeConnecting(7))
        health=reducePartyHealth(health,PartyHealthEvent.RealtimeConnecting(8))
        health=reducePartyHealth(health,PartyHealthEvent.RealtimeReceived(7,11))
        assertEquals(PartyRealtimeHealth.Connecting,health.realtime)
        assertNull(health.lastRealtimeReceiveAtMs)
    }

    @Test fun durableBusinessRejectionStillProvesApiReachability() {
        val health=reducePartyHealth(PartyHealthState(),PartyHealthEvent.DurableRejected(12))
        assertEquals(PartyApiHealth.Reachable,health.api)
        assertEquals(PartySyncCapability.DurableFallback,health.capability())
    }

    @Test fun immutableAuthorityRejectsHostOnlyGuestsBeforeAnyDirective() {
        val hostOnly = PartyAuthorityContext(
            partyId = "party", selfProfileId = "guest", hostProfileId = "host",
            controlMode = WatchPartyControlMode.host_only, durableSequence = 1,
            generation = generation,
        )
        assertFalse(hostOnly.mayControl("guest"))
        assertTrue(hostOnly.mayControl("host"))
        assertTrue(hostOnly.copy(controlMode = WatchPartyControlMode.collaborative).mayControl("guest"))
    }
}
