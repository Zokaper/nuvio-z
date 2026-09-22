package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchPartyPresentationProjectorTest {
    private fun member(id: String) = WatchPartyParticipant(
        profileId = id,
        role = if (id == "host") "host" else "member",
        readyState = SourceResolutionState.ready,
        connected = true,
        clientLocation = WatchPartyClientLocation.player,
        joinedAt = "2026-09-09T00:00:00Z",
    )

    private fun party(status: WatchPartyStatus = WatchPartyStatus.playing) = WatchPartyState(
        id = "party",
        hostProfileId = "host",
        status = status,
        controlMode = WatchPartyControlMode.collaborative,
        contentGeneration = 1,
        sourceGeneration = 2,
        content = PartyContent("tt1", "movie", "tt1", "Movie"),
        positionMs = 0,
        durationMs = 100_000,
        playbackSpeed = 1f,
        sequence = 1,
        stateUpdatedAt = "2026-09-09T00:00:00Z",
        authorityEpoch = 3,
        members = listOf(member("host"), member("guest")),
    )

    @Test fun globalPartyStatusIsNeverUsedAsAMemberEngineProxy() {
        val projected = PartyPresentationProjector.project(
            party = party(WatchPartyStatus.playing),
            selfProfileId = "observer",
            health = PartyHealthState(),
            realtime = WatchPartySyncState(),
            partyNowMs = 10_000,
        )
        assertEquals("Ready", projected.members.getValue("host").label)
        assertEquals("Ready", projected.members.getValue("guest").label)
    }

    @Test fun freshPerMemberTelemetryWinsAndExpiresIndependently() {
        val realtime = WatchPartySyncState(
            tickStatus = WatchPartyStatus.buffering,
            tickCapturedAtPartyMs = 9_000,
            peerTelemetry = mapOf("guest" to PartyPeerTelemetry(WatchPartyStatus.paused, 9_500)),
        )
        val fresh = PartyPresentationProjector.project(
            party = party(), selfProfileId = "observer", health = PartyHealthState(),
            realtime = realtime, partyNowMs = 10_000,
        )
        assertEquals("Buffering", fresh.members.getValue("host").label)
        assertEquals("Paused", fresh.members.getValue("guest").label)
        val stale = PartyPresentationProjector.project(
            party = party(), selfProfileId = "observer", health = PartyHealthState(),
            realtime = realtime, partyNowMs = 10_000 + WatchPartyClockStaleMs + 1,
        )
        assertEquals("Ready", stale.members.getValue("host").label)
        assertEquals("Ready", stale.members.getValue("guest").label)
        assertNull(stale.freshHostStatus)
    }

    @Test fun healthPlanesProjectTruthfulCapabilitiesAndBanners() {
        val durableOnly = PartyPresentationProjector.project(
            party = party(), selfProfileId = "guest",
            health = PartyHealthState(
                api = PartyApiHealth.Reachable,
                realtime = PartyRealtimeHealth.SubscribedUnverified,
            ),
            realtime = WatchPartySyncState(), partyNowMs = 0,
        )
        assertEquals(PartySyncCapability.DurableFallback, durableOnly.capability)
        assertEquals(PartyConnectionState.reconnecting, durableOnly.connection)
        assertEquals("Live sync unavailable — following the party every few seconds", durableOnly.connectionBanner)

        val full = PartyPresentationProjector.project(
            party = party(), selfProfileId = "guest",
            health = PartyHealthState(api = PartyApiHealth.Reachable, realtime = PartyRealtimeHealth.Live),
            realtime = WatchPartySyncState(), partyNowMs = 0,
        )
        assertEquals(PartySyncCapability.FullSync, full.capability)
        assertEquals(PartyConnectionState.connected, full.connection)
        assertNull(full.connectionBanner)
    }

    @Test fun actualLocalPlaybackOwnsTheSelfLabel() {
        val projected = PartyPresentationProjector.project(
            party = party(WatchPartyStatus.playing),
            selfProfileId = "guest",
            health = PartyHealthState(),
            realtime = WatchPartySyncState(
                peerTelemetry = mapOf("guest" to PartyPeerTelemetry(WatchPartyStatus.playing, 10_000)),
            ),
            partyNowMs = 10_000,
            localPlaybackStatus = WatchPartyStatus.paused,
        )
        assertEquals("Paused", projected.members.getValue("guest").label)
    }

    // A member's readiness row is durable and generation-stamped; their telemetry is not. These
    // four say which one wins, and the generation is the only thing that decides it - the same
    // `fetching` means "behind" in one and "genuinely resolving right now" in the other.

    private fun resolvingGuest(sourceGeneration: Int, location: WatchPartyClientLocation) =
        member("guest").copy(
            readyState = SourceResolutionState.fetching,
            sourceGeneration = sourceGeneration,
            clientLocation = location,
        )

    private fun projectGuest(
        guest: WatchPartyParticipant,
        telemetry: WatchPartyStatus? = null,
    ) = PartyPresentationProjector.project(
        party = party().copy(members = listOf(member("host"), guest)),
        selfProfileId = "observer",
        health = PartyHealthState(),
        realtime = WatchPartySyncState(
            peerTelemetry = telemetry?.let { mapOf("guest" to PartyPeerTelemetry(it, 10_000)) }.orEmpty(),
        ),
        partyNowMs = 10_000,
    ).members.getValue("guest").label

    @Test fun staleReadinessLosesToFreshPlaybackTelemetry() {
        // The physical complaint: a participant watching uninterrupted, read as "Resolving source"
        // because the party moved generation and nothing rewrote their row.
        assertEquals(
            "Playing",
            projectGuest(
                resolvingGuest(sourceGeneration = 1, location = WatchPartyClientLocation.player),
                telemetry = WatchPartyStatus.playing,
            ),
        )
        assertEquals(
            "Paused",
            projectGuest(
                resolvingGuest(sourceGeneration = 1, location = WatchPartyClientLocation.player),
                telemetry = WatchPartyStatus.paused,
            ),
        )
    }

    @Test fun currentGenerationResolutionStillShowsEvenOverTelemetry() {
        // `party_change_content_v2` resets every member to `fetching` on the *new* generation. That
        // member really is resolving, and a tick still arriving from the episode they are leaving
        // must not hide it.
        assertEquals(
            "Resolving source",
            projectGuest(
                resolvingGuest(sourceGeneration = 2, location = WatchPartyClientLocation.player),
                telemetry = WatchPartyStatus.playing,
            ),
        )
    }

    @Test fun staleReadinessIsKeptWhenNothingBetterIsKnown() {
        // Staleness alone never drops the label. Without live evidence the readiness row is the
        // only thing anyone knows about this member, so it is what the list says.
        assertEquals(
            "Resolving source",
            projectGuest(resolvingGuest(sourceGeneration = 1, location = WatchPartyClientLocation.player)),
        )
        assertEquals(
            "Resolving source",
            projectGuest(
                resolvingGuest(sourceGeneration = 1, location = WatchPartyClientLocation.lobby),
                telemetry = WatchPartyStatus.playing,
            ),
        )
    }

    @Test fun departureAndFailureOutrankTelemetryAtAnyGeneration() {
        val failed = member("guest").copy(
            readyState = SourceResolutionState.failed,
            sourceGeneration = 1,
            clientLocation = WatchPartyClientLocation.player,
        )
        assertEquals("Failed", projectGuest(failed, telemetry = WatchPartyStatus.playing))

        val gone = member("guest").copy(
            readyState = SourceResolutionState.fetching,
            sourceGeneration = 1,
            clientLocation = WatchPartyClientLocation.player,
            connected = false,
        )
        assertEquals("Offline", projectGuest(gone, telemetry = WatchPartyStatus.playing))
    }
}
