package com.nuvio.app.features.watchparty

import com.nuvio.app.features.player.PartyPlayerLaunchKey
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.shouldStartParkedForParty
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PartySourceRealizerTest {
    @AfterTest fun clear() = PartySourceRealizer.clear()

    @Test fun samePartyContentAndSourceGenerationReusesResolvedLaunch() {
        val key = key(sourceGeneration = 4)
        val launch = launch(key.descriptor)
        PartySourceRealizer.updateAuthority(key)
        PartySourceRealizer.retain(key, launch)
        assertSame(launch, PartySourceRealizer.reusable(key))
    }

    @Test fun realSourceGenerationChangeInvalidatesResolvedLaunch() {
        val old = key(sourceGeneration = 4)
        PartySourceRealizer.updateAuthority(old)
        PartySourceRealizer.retain(old, launch(old.descriptor))
        val next = key(sourceGeneration = 5)
        PartySourceRealizer.updateAuthority(next)
        assertNull(PartySourceRealizer.reusable(old))
        assertNull(PartySourceRealizer.reusable(next))
    }

    @Test fun realContentGenerationChangeInvalidatesResolvedLaunch() {
        val old = key(contentGeneration = 2)
        PartySourceRealizer.updateAuthority(old)
        PartySourceRealizer.retain(old, launch(old.descriptor))
        PartySourceRealizer.updateAuthority(old.copy(contentGeneration = 3))
        assertNull(PartySourceRealizer.reusable(old))
    }

    /** The same durable snapshot arrives every poll; re-arming on it would drop a live realization. */
    @Test fun repeatingTheSameAuthorityKeepsTheRealizationAndTheSpentClaim() {
        val key = key()
        PartySourceRealizer.updateAuthority(key)
        assertTrue(PartySourceRealizer.claimAutomaticLaunch(key))
        val launch = launch(key.descriptor)
        PartySourceRealizer.retain(key, launch)

        PartySourceRealizer.updateAuthority(key)

        assertSame(launch, PartySourceRealizer.reusable(key))
        assertFalse(PartySourceRealizer.claimAutomaticLaunch(key))
    }

    @Test fun staleCompletionCannotReplaceNewAuthority() {
        val old = key(sourceGeneration = 4)
        val next = key(sourceGeneration = 5)
        PartySourceRealizer.updateAuthority(old)
        PartySourceRealizer.matching(old)
        PartySourceRealizer.updateAuthority(next)

        assertFalse(PartySourceRealizer.resolving(old))
        assertFalse(PartySourceRealizer.fallbackRequired(old))
        assertNull(PartySourceRealizer.retain(old, launch(old.descriptor)))
        assertNull(PartySourceRealizer.reusable(old))
        assertEquals(PartySourceRealizationState.Unresolved, PartySourceRealizer.state.value)
    }

    /** Reuse is keyed, not merely current: a launch retained for another key is never handed back. */
    @Test fun aRealizationIsOnlyReusableForTheKeyItWasResolvedFor() {
        val key = key()
        PartySourceRealizer.updateAuthority(key)
        PartySourceRealizer.retain(key, launch(key.descriptor))
        assertNull(PartySourceRealizer.reusable(key.copy(partyId = "other")))
    }

    @Test fun automaticLaunchIsClaimedOncePerExactAuthority() {
        val key = key()
        PartySourceRealizer.updateAuthority(key)
        assertTrue(PartySourceRealizer.claimAutomaticLaunch(key))
        assertFalse(PartySourceRealizer.claimAutomaticLaunch(key))
        PartySourceRealizer.updateAuthority(key.copy(sourceGeneration = key.sourceGeneration + 1))
        assertFalse(PartySourceRealizer.claimAutomaticLaunch(key))
    }

    /** Backing out of the player must not re-arm the lobby's one-shot launch for that generation. */
    @Test fun returningToTheLobbyDoesNotRearmTheAutomaticLaunch() {
        val key = key()
        PartySourceRealizer.updateAuthority(key)
        assertTrue(PartySourceRealizer.claimAutomaticLaunch(key))
        PartySourceRealizer.retain(key, launch(key.descriptor))

        // The player exits; the party state it exits into is the same authority.
        PartySourceRealizer.updateAuthority(key)

        assertFalse(PartySourceRealizer.claimAutomaticLaunch(key))
    }

    @Test fun abandonedOnlyEndsWorkThatIsStillInFlightForThatKey() {
        val key = key()
        PartySourceRealizer.updateAuthority(key)
        PartySourceRealizer.resolving(key)
        assertTrue(PartySourceRealizer.abandoned(key, "debrid_resolve_failed"))
        assertEquals(
            PartySourceRealizationState.Failed(key, "debrid_resolve_failed"),
            PartySourceRealizer.state.value,
        )
    }

    @Test fun abandonedNeverOverwritesAReadyRealization() {
        val key = key()
        PartySourceRealizer.updateAuthority(key)
        val launch = launch(key.descriptor)
        PartySourceRealizer.retain(key, launch)

        assertFalse(PartySourceRealizer.abandoned(key, "manual_escape_from_player"))
        assertSame(launch, PartySourceRealizer.reusable(key))
    }

    @Test fun abandonedNeverOverwritesTheMoreSpecificFallbackAnswer() {
        val key = key()
        PartySourceRealizer.updateAuthority(key)
        PartySourceRealizer.matching(key)
        PartySourceRealizer.fallbackRequired(key)

        assertFalse(PartySourceRealizer.abandoned(key, "party_source_unavailable"))
        assertEquals(PartySourceRealizationState.FallbackRequired(key), PartySourceRealizer.state.value)
    }

    /** Leave, end, profile change, account wipe: nothing sensitive may survive any of them. */
    @Test fun clearDropsTheRetainedLaunchAndTheClaim() {
        val key = key()
        PartySourceRealizer.updateAuthority(key)
        assertTrue(PartySourceRealizer.claimAutomaticLaunch(key))
        PartySourceRealizer.retain(key, launch(key.descriptor))

        PartySourceRealizer.clear()

        assertNull(PartySourceRealizer.reusable(key))
        assertNull(PartySourceRealizer.authority)
        assertEquals(PartySourceRealizationState.Unresolved, PartySourceRealizer.state.value)
        assertFalse(PartySourceRealizer.claimAutomaticLaunch(key))
    }

    @Test fun partySourceKeyCarriesTheCompleteAuthorityIdentity() {
        val descriptor = descriptor()
        val party = partyState(descriptor = descriptor, contentGeneration = 7, sourceGeneration = 9)
        assertEquals(
            PartyPlayerLaunchKey(
                partyId = party.id,
                contentGeneration = 7,
                sourceGeneration = 9,
                descriptor = descriptor,
            ),
            party.partySourceKey(),
        )
        assertNull(partyState(descriptor = null).partySourceKey())
    }

    /** A joining guest waits for the party's play; a host, or a party already playing, does not. */
    @Test fun aGuestOpeningIntoAPartyThatIsNotPlayingStartsParked() {
        val paused = partyState(descriptor = descriptor()).copy(status = WatchPartyStatus.paused)
        assertTrue(shouldStartParkedForParty(paused, viewerProfileId = "guest"))
        assertFalse(shouldStartParkedForParty(paused, viewerProfileId = "host"))
        assertFalse(shouldStartParkedForParty(paused.copy(status = WatchPartyStatus.playing), viewerProfileId = "guest"))
    }

    /**
     * Post-release Bug 2, end to end: Watching Now join -> Matching -> Resolving -> player opens the
     * host's release as this guest's catalogue describes it -> playback starts. The visible line
     * must stop saying "Matching" whether the guest is playing or parked.
     */
    @Test fun watchingNowJoinClearsMatchingOnceThePlayerOpensTheHostsRelease() {
        val host = descriptor().copy(
            originVersion = "1.4.0",
            media = PartySourceMedia(resolution = "1080p", sizeBytes = 4_000_000_000, languages = setOf("en")),
        )
        val party = partyState(descriptor = host, contentGeneration = 1, sourceGeneration = 0)
        val key = party.partySourceKey()!!
        PartySourceRealizer.updateAuthority(key)

        PartySourceRealizer.matching(key)
        assertEquals(PartyRealizationPhase.Matching, partyRealizationPhaseFor(PartySourceRealizer.state.value, party.id))
        assertEquals(PartyStatusKind.MatchingSource, projectedFor(party, playing = false)?.kind)
        PartySourceRealizer.resolving(key)
        assertEquals(PartyStatusKind.MatchingSource, projectedFor(party, playing = false)?.kind)

        // The same release, described by the guest's own addon install: not byte-equal.
        val guest = host.copy(
            originVersion = "1.4.2",
            media = host.media.copy(sizeBytes = 4_000_100_000, languages = setOf("en", "ru")),
        )
        assertTrue(guest != host)
        val completed = partyRealizationCompletedByLaunch(party, "tt1", "tt1", guest, PartySourceRealizer.state.value)
        assertEquals(key, completed)
        PartySourceRealizer.retain(completed!!, launch(guest))

        assertEquals(PartyRealizationPhase.None, partyRealizationPhaseFor(PartySourceRealizer.state.value, party.id))
        assertTrue(projectedFor(party, playing = true)?.kind != PartyStatusKind.MatchingSource)
        assertTrue(projectedFor(party, playing = false)?.kind != PartyStatusKind.MatchingSource)
    }

    @Test fun aDifferentReleaseDoesNotCompleteAnInFlightMatch() {
        val party = partyState(descriptor = descriptor())
        val key = party.partySourceKey()!!
        PartySourceRealizer.updateAuthority(key)
        PartySourceRealizer.resolving(key)
        val other = descriptor().copy(originId = "other", releaseFingerprint = partyReleaseFingerprint("Other Movie 720p"))
        assertNull(partyRealizationCompletedByLaunch(party, "tt1", "tt1", other, PartySourceRealizer.state.value))
    }

    @Test fun anAlternateChosenAfterNoMatchCompletesTheRealization() {
        val party = partyState(descriptor = descriptor())
        val key = party.partySourceKey()!!
        PartySourceRealizer.updateAuthority(key)
        PartySourceRealizer.fallbackRequired(key)
        val alternate = descriptor().copy(originId = "other", releaseFingerprint = partyReleaseFingerprint("Other Movie 720p"))
        assertEquals(key, partyRealizationCompletedByLaunch(party, "tt1", "tt1", alternate, PartySourceRealizer.state.value))
    }

    @Test fun aLaunchOfOtherContentCompletesNothing() {
        val party = partyState(descriptor = descriptor())
        PartySourceRealizer.updateAuthority(party.partySourceKey())
        assertNull(partyRealizationCompletedByLaunch(party, "tt2", "tt2", descriptor(), PartySourceRealizer.state.value))
        assertNull(partyRealizationCompletedByLaunch(null, "tt1", "tt1", descriptor(), PartySourceRealizer.state.value))
    }

    @Test fun aRealizationOfAnotherPartyIsNotThisPlayersLine() {
        assertEquals(
            PartyRealizationPhase.None,
            partyRealizationPhaseFor(PartySourceRealizationState.Resolving(key()), partyId = "another-party"),
        )
    }

    /** The host log's `ready` -> `source_ready` -> `ready`: the realizer may not walk a player back. */
    @Test fun realizerSourceReadyNeverOverwritesAPlayersReady() {
        val ready = PublishedPartyReadiness("party", SourceResolutionState.ready, sourceGeneration = 0)
        assertFalse(realizerReadinessMayPublish(SourceResolutionState.source_ready to 0, "party", ready))
        // A different generation or party is a different question.
        assertTrue(realizerReadinessMayPublish(SourceResolutionState.source_ready to 1, "party", ready))
        assertTrue(realizerReadinessMayPublish(SourceResolutionState.source_ready to 0, "other", ready))
        // Before the player has said anything, and for genuine re-matching, the realizer speaks.
        assertTrue(realizerReadinessMayPublish(SourceResolutionState.source_ready to 0, "party", null))
        assertTrue(realizerReadinessMayPublish(SourceResolutionState.fetching to 0, "party", ready))
    }

    private fun projectedFor(party: WatchPartyState, playing: Boolean) = projectPartyPlaybackStatus(
        PartyPlaybackStatusInputs(
            inParty = true,
            isHost = false,
            host = PartyStatusPerson("host", "Ahmed"),
            realization = partyRealizationPhaseFor(PartySourceRealizer.state.value, party.id),
            partyStarted = true,
            timelinePlaying = playing,
            gate = PartyPlaybackGate(allowPlayback = true, reason = PartyHoldReason.NONE),
        ),
    )

    private fun partyState(
        descriptor: PartySourceDescriptorV2?,
        contentGeneration: Int = 1,
        sourceGeneration: Int = 2,
    ) = WatchPartyState(
        id = "party",
        hostProfileId = "host",
        status = WatchPartyStatus.playing,
        controlMode = WatchPartyControlMode.collaborative,
        contentGeneration = contentGeneration,
        sourceGeneration = sourceGeneration,
        content = PartyContent("tt1", "movie", "tt1", "Movie"),
        sourceFingerprint = descriptor,
        positionMs = 0,
        durationMs = 100_000,
        playbackSpeed = 1f,
        sequence = 1,
        stateUpdatedAt = "2026-09-09T00:00:00Z",
    )

    private fun descriptor() = PartySourceDescriptorV2(
        originKind = PartySourceOriginKind.embedded,
        originId = "nuvio",
        releaseFingerprint = partyReleaseFingerprint("Movie 2026 1080p"),
    )

    private fun key(contentGeneration: Int = 2, sourceGeneration: Int = 4) = PartyPlayerLaunchKey(
        partyId = "party",
        contentGeneration = contentGeneration,
        sourceGeneration = sourceGeneration,
        descriptor = descriptor(),
    )

    private fun launch(descriptor: PartySourceDescriptorV2) = PlayerLaunch(
        profileId = 1,
        title = "Movie",
        sourceUrl = "https://local.invalid/media",
        streamTitle = "Movie 1080p",
        providerName = "Local",
        contentType = "movie",
        videoId = "tt1",
        parentMetaId = "tt1",
        parentMetaType = "movie",
        partySourceDescriptor = descriptor,
    )
}
