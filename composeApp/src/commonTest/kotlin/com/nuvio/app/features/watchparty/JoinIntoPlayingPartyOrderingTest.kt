package com.nuvio.app.features.watchparty

import com.nuvio.app.features.player.PartyPlayerLaunchKey
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.shouldStartParkedForParty
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Joining a party that is already PLAYING, with the guest's source realization held open while the
 * host's transport keeps arriving.
 *
 * Device QA 2026-10-03 (iPhone, Debug 77): after an accepted Ask-to-join, joins into a *playing*
 * party stalled at `resolving` and never reached `ready`, while the join into a *paused* party
 * reached `ready` in six seconds. The host's own log shows realization finishing in about eight
 * seconds every time, so these cases pin the half of the question the pure state machines own:
 * a host's play / pause / position stream, delivered at any point before the realization is
 * released, must neither re-arm the automatic launch, nor drop the held realization, nor move the
 * session off its path - and a playing join and a paused join must trace identically in everything
 * but the transport fields.
 */
class JoinIntoPlayingPartyOrderingTest {

    @AfterTest fun clear() = PartySourceRealizer.clear()

    private data class Trace(
        val phases: List<PartyClientPhase>,
        val realization: List<String>,
        val claims: List<Boolean>,
        val readiness: List<SourceResolutionState?>,
        val reusableAtEnd: Boolean,
    )

    private fun descriptor() = PartySourceDescriptorV2(
        originKind = PartySourceOriginKind.embedded,
        originId = "nuvio",
        releaseFingerprint = partyReleaseFingerprint("Movie 2026 1080p"),
    )

    /** A promoted party: source generation 0, exactly what a request-join into a playing host lands in. */
    private fun party(status: WatchPartyStatus, positionMs: Long, sequence: Int) = WatchPartyState(
        id = "party",
        hostProfileId = "host",
        status = status,
        controlMode = WatchPartyControlMode.host_only,
        contentGeneration = 1,
        sourceGeneration = 0,
        content = PartyContent("tt1", "movie", "tt1", "Movie"),
        sourceFingerprint = descriptor(),
        positionMs = positionMs,
        durationMs = 4_000_000,
        playbackSpeed = 1f,
        sequence = sequence,
        stateUpdatedAt = "2026-10-03T14:11:58Z",
    )

    private fun launch(key: PartyPlayerLaunchKey) = PlayerLaunch(
        profileId = 1,
        title = "Movie",
        sourceUrl = "https://local.invalid/media",
        streamTitle = "Movie 1080p",
        providerName = "Local",
        contentType = "movie",
        videoId = "tt1",
        parentMetaId = "tt1",
        parentMetaType = "movie",
        partySourceDescriptor = key.descriptor,
    )

    /**
     * The guest's side of a join, in order: the snapshot arrives, the lobby claims the one automatic
     * launch, realization starts and is HELD, the host's transport advances through [transport] while
     * it is held, then realization is released into the player.
     */
    private fun join(hostStatus: WatchPartyStatus, transport: List<WatchPartyStatus>): Trace {
        PartySourceRealizer.clear()
        val phases = mutableListOf<PartyClientPhase>()
        val realization = mutableListOf<String>()
        val claims = mutableListOf<Boolean>()
        val readiness = mutableListOf<SourceResolutionState?>()

        var session = PartySessionState()
        var snapshot = party(hostStatus, positionMs = 46_000, sequence = 0)
        val key = snapshot.partySourceKey()!!

        fun deliver(next: WatchPartyState) {
            snapshot = next
            session = observePartySnapshot(session, snapshot, selfProfileId = "guest")
            // The repository republishes authority on every snapshot; the same key must be a no-op.
            PartySourceRealizer.updateAuthority(snapshot.partySourceKey())
            phases += session.phase
        }

        // 1-2. Membership becomes live and the snapshot is received.
        deliver(snapshot)
        session = reducePartySession(session, PartySessionEvent.LobbyEntered(snapshot.id))
        phases += session.phase

        // 3-4. The lobby claims its one launch; realization starts and is then held.
        claims += PartySourceRealizer.claimAutomaticLaunch(key)
        PartySourceRealizer.matching(key)
        session = reducePartySession(session, PartySessionEvent.MatchStarted(snapshot.partyGenerationKey()))
        phases += session.phase
        realization += PartySourceRealizer.state.value::class.simpleName.orEmpty()
        readiness += partyReadinessReport(PartySourceRealizer.state.value)?.first

        // 7. The host's transport keeps arriving while realization is suspended.
        var position = 46_000L
        transport.forEachIndexed { index, status ->
            position += 5_200
            deliver(party(status, position, sequence = index + 1))
            claims += PartySourceRealizer.claimAutomaticLaunch(key)
            realization += PartySourceRealizer.state.value::class.simpleName.orEmpty()
            readiness += partyReadinessReport(PartySourceRealizer.state.value)?.first
        }

        // 5-6. Realization is released: resolving, then the player's launch is retained.
        PartySourceRealizer.resolving(key)
        realization += PartySourceRealizer.state.value::class.simpleName.orEmpty()
        readiness += partyReadinessReport(PartySourceRealizer.state.value)?.first
        PartySourceRealizer.retain(key, launch(key))
        session = reducePartySession(session, PartySessionEvent.SourceResolved)
        phases += session.phase
        realization += PartySourceRealizer.state.value::class.simpleName.orEmpty()
        readiness += partyReadinessReport(PartySourceRealizer.state.value)?.first

        // The party keeps moving after the player is up - a barrier pause, then a resume.
        listOf(WatchPartyStatus.paused, WatchPartyStatus.playing).forEachIndexed { index, status ->
            position += 5_200
            deliver(party(status, position, sequence = transport.size + index + 1))
            claims += PartySourceRealizer.claimAutomaticLaunch(key)
            realization += PartySourceRealizer.state.value::class.simpleName.orEmpty()
        }

        return Trace(phases, realization, claims, readiness, reusableAtEnd = PartySourceRealizer.reusable(key) != null)
    }

    private val hostTransportDuringRealization = listOf(
        WatchPartyStatus.playing,
        WatchPartyStatus.playing,
        WatchPartyStatus.paused, // the join barrier's pause, landing while the guest is still loading
        WatchPartyStatus.paused,
        WatchPartyStatus.playing,
    )

    @Test
    fun `a held realization survives every transport event and launches exactly once`() {
        val trace = join(WatchPartyStatus.playing, hostTransportDuringRealization)

        // One automatic launch for the whole join, however many snapshots arrived.
        assertEquals(1, trace.claims.count { it })
        assertTrue(trace.claims.first())
        // While held, the realizer never leaves Matching - no transport event advances, drops or fails it.
        val heldCount = hostTransportDuringRealization.size + 1
        assertEquals(List(heldCount) { "Matching" }, trace.realization.take(heldCount))
        // And once released it ends Ready and stays Ready through the later pause and resume.
        assertEquals(listOf("Resolving", "Ready", "Ready", "Ready"), trace.realization.drop(heldCount))
        assertTrue(trace.reusableAtEnd)
        // The readiness the host sees is fetching the whole time it is held, then resolving, then source_ready.
        assertEquals(
            List(heldCount) { SourceResolutionState.fetching } +
                listOf(SourceResolutionState.resolving, SourceResolutionState.source_ready),
            trace.readiness,
        )
    }

    @Test
    fun `the session never leaves the join path while transport arrives`() {
        val trace = join(WatchPartyStatus.playing, hostTransportDuringRealization)
        val allowed = setOf(PartyClientPhase.Lobby, PartyClientPhase.MatchingHostSource, PartyClientPhase.LoadingPlayer)
        assertTrue(trace.phases.all { it in allowed }, "phases=${trace.phases}")
        assertFalse(PartyClientPhase.None in trace.phases)
        assertFalse(PartyClientPhase.Ended in trace.phases)
        assertFalse(PartyClientPhase.AwaitingFallbackChoice in trace.phases)
        assertFalse(PartyClientPhase.Detached in trace.phases)
    }

    @Test
    fun `transport before the source is ready never asks for a fallback choice`() {
        // The only phase that opens a source list for a guest is a fallback answer; nothing the host
        // sends can produce one.
        val trace = join(WatchPartyStatus.playing, hostTransportDuringRealization)
        assertFalse(trace.realization.any { it == "FallbackRequired" || it == "Failed" })
        assertFalse(trace.readiness.any { it == SourceResolutionState.choosing_fallback || it == SourceResolutionState.failed })
    }

    @Test
    fun `a playing join and a paused join trace identically apart from the transport`() {
        val playing = join(WatchPartyStatus.playing, hostTransportDuringRealization)
        val paused = join(WatchPartyStatus.paused, List(hostTransportDuringRealization.size) { WatchPartyStatus.paused })
        assertEquals(paused, playing)
    }

    @Test
    fun `the one thing that differs is whether the guest opens parked`() {
        // The player-side half of the same difference, and the only code that branches on the host's status
        // at start: a guest opening into a paused party waits for the party's play; into a playing party it starts.
        val playing = party(WatchPartyStatus.playing, 46_000, 0)
        val paused = party(WatchPartyStatus.paused, 46_000, 0)
        assertFalse(shouldStartParkedForParty(playing, viewerProfileId = "guest"))
        assertTrue(shouldStartParkedForParty(paused, viewerProfileId = "guest"))
    }

    @Test
    fun `a snapshot with the same authority never clears a spent claim or a held realization`() {
        val snap = party(WatchPartyStatus.playing, 46_000, 0)
        val key = assertNotNull(snap.partySourceKey())
        PartySourceRealizer.updateAuthority(key)
        assertTrue(PartySourceRealizer.claimAutomaticLaunch(key))
        PartySourceRealizer.matching(key)
        repeat(20) { n ->
            PartySourceRealizer.updateAuthority(party(WatchPartyStatus.playing, 46_000L + n * 1_000, n).partySourceKey())
            assertFalse(PartySourceRealizer.claimAutomaticLaunch(key))
            assertEquals(PartySourceRealizationState.Matching(key), PartySourceRealizer.state.value)
        }
    }
}
