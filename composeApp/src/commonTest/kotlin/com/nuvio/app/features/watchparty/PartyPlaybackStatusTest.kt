package com.nuvio.app.features.watchparty

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PartyPlaybackStatusTest {
    private val seraph = PartyStatusPerson("seraph", "Seraph")
    private val ahmed = PartyStatusPerson("ahmed", "Ahmed")
    private val debug = PartyStatusPerson("debug", "debug")
    private val guest = PartyPlaybackStatusInputs(inParty = true, isHost = false, host = seraph)
    private val host = PartyPlaybackStatusInputs(inParty = true, isHost = true, host = PartyStatusPerson("me", "You"))
    private fun gate(reason: PartyHoldReason, waitingOn: Int = 0) = PartyPlaybackGate(false, reason, waitingOn)

    private fun kind(inputs: PartyPlaybackStatusInputs) = projectPartyPlaybackStatus(inputs)?.kind

    @Test fun nothingOutsideAPartyWithNoRequests() {
        assertNull(projectPartyPlaybackStatus(PartyPlaybackStatusInputs(inParty = false, isHost = false)))
        assertNull(projectPartyPlaybackStatus(guest))
    }

    @Test fun everyRowsWordingAndAction() {
        projectPartyPlaybackStatus(guest.copy(realization = PartyRealizationPhase.FallbackRequired, handoffEpisodeLabel = "S1E3"))!!.let {
            assertEquals("Couldn't find Seraph's version of S1E3", it.text)
            assertEquals(PartyStatusAction.ChooseSource, it.action)
            assertEquals(PartyStatusTone.Error, it.tone)
        }
        assertEquals("Your version is shorter than Seraph's", projectPartyPlaybackStatus(guest.copy(positionUnreachable = true))!!.text)
        assertEquals(
            "Switching to Seraph's episode…",
            projectPartyPlaybackStatus(guest.copy(realization = PartyRealizationPhase.Resolving, realizationChangesEpisode = true))!!.text,
        )
        assertEquals("Matching Seraph's source…", projectPartyPlaybackStatus(guest.copy(realization = PartyRealizationPhase.Matching))!!.text)
        assertEquals("Seraph is choosing a source", projectPartyPlaybackStatus(guest.copy(waitingForHostSource = true))!!.text)
        projectPartyPlaybackStatus(host.copy(gate = gate(PartyHoldReason.WAITING_FOR_PARTICIPANTS, 1), awaitingSource = listOf(ahmed)))!!.let {
            assertEquals("Waiting for Ahmed to find a source", it.text)
            assertEquals(PartyStatusAction.StartAnyway, it.action)
        }
        assertEquals(
            "Waiting for Ahmed and 2 others",
            projectPartyPlaybackStatus(host.copy(gate = gate(PartyHoldReason.WAITING_FOR_PARTICIPANTS, 3), awaitingSource = listOf(ahmed, debug, seraph)))!!.text,
        )
        projectPartyPlaybackStatus(host.copy(stallHoldOthers = listOf(ahmed)))!!.let {
            assertEquals("Waiting for Ahmed to buffer", it.text)
            assertEquals(PartyStatusAction.DontWait, it.action)
        }
        projectPartyPlaybackStatus(guest.copy(stallHoldOthers = listOf(ahmed)))!!.let {
            assertEquals("Waiting for Ahmed to buffer", it.text)
            assertNull(it.action, "only the host can stop waiting")
        }
        assertEquals("Everyone's waiting while you buffer", projectPartyPlaybackStatus(guest.copy(selfHeld = true))!!.text)
        assertEquals("Seraph is buffering", projectPartyPlaybackStatus(guest.copy(hostBuffering = true))!!.text)
        assertEquals("Waiting for Seraph to start", projectPartyPlaybackStatus(guest.copy(gate = gate(PartyHoldReason.WAITING_FOR_HOST)))!!.text)
        assertEquals("Catching up with the party…", projectPartyPlaybackStatus(guest.copy(barrierHoldMs = 1_501))!!.text)
        projectPartyPlaybackStatus(host.copy(incomingRequester = ahmed))!!.let {
            assertEquals("Ahmed wants to join", it.text)
            assertEquals(PartyStatusAction.LetIn, it.action)
            assertEquals(PartyStatusAction.Decline, it.secondaryAction)
        }
        assertEquals("Reconnecting to the party…", projectPartyPlaybackStatus(guest.copy(realtimeUnhealthyMs = 3_001))!!.text)
        assertEquals(
            "Connection lost · playing on your own",
            projectPartyPlaybackStatus(guest.copy(capability = PartySyncCapability.OfflineLocalPlayback))!!.text,
        )
        assertEquals("Paused by Seraph", projectPartyPlaybackStatus(guest.copy(pausedBy = seraph))!!.text)
        projectPartyPlaybackStatus(PartyPlaybackStatusInputs(inParty = false, isHost = false, outgoingRequestTarget = seraph))!!.let {
            assertEquals("Asking Seraph to join…", it.text)
            assertEquals(PartyStatusAction.CancelOutgoing, it.action)
        }
    }

    @Test fun belowThresholdConditionsAndDurableFallbackShowNothing() {
        assertNull(projectPartyPlaybackStatus(guest.copy(barrierHoldMs = 1_500)))
        assertNull(projectPartyPlaybackStatus(guest.copy(realtimeUnhealthyMs = 3_000)))
        assertNull(projectPartyPlaybackStatus(guest.copy(capability = PartySyncCapability.DurableFallback)))
    }

    @Test fun rowsNeedTheRightRole() {
        // A host does not read about waiting for themselves to start, or about their own buffering.
        assertNull(projectPartyPlaybackStatus(host.copy(gate = gate(PartyHoldReason.WAITING_FOR_HOST), hostBuffering = true, waitingForHostSource = true)))
        // A guest never sees the start gate.
        assertNull(projectPartyPlaybackStatus(guest.copy(gate = gate(PartyHoldReason.WAITING_FOR_PARTICIPANTS, 2))))
        // A timeline already playing answers "waiting for host" before the row does.
        assertNull(projectPartyPlaybackStatus(guest.copy(gate = gate(PartyHoldReason.WAITING_FOR_HOST), timelinePlaying = true)))
        // A started party that is paused (by this guest, say) is not waiting for the host to start.
        assertNull(projectPartyPlaybackStatus(guest.copy(gate = gate(PartyHoldReason.WAITING_FOR_HOST), partyStarted = true)))
        // An open panel already shows the request row.
        assertNull(projectPartyPlaybackStatus(host.copy(incomingRequester = ahmed, panelOpen = true)))
        // Party-only rows need a party.
        assertNull(projectPartyPlaybackStatus(PartyPlaybackStatusInputs(inParty = false, isHost = false, pausedBy = seraph, positionUnreachable = true)))
        // An incoming request reaches a host who is only sharing presence.
        assertEquals(PartyStatusKind.IncomingJoinRequest, kind(PartyPlaybackStatusInputs(inParty = false, isHost = false, incomingRequester = ahmed)))
    }

    @Test fun priorityCollisions() {
        val everything = guest.copy(
            realization = PartyRealizationPhase.Failed,
            positionUnreachable = true,
            waitingForHostSource = true,
            stallHoldOthers = listOf(ahmed),
            awayHoldOthers = listOf(ahmed),
            selfHeld = true,
            hostBuffering = true,
            gate = gate(PartyHoldReason.WAITING_FOR_HOST),
            barrierHoldMs = 5_000,
            incomingRequester = debug,
            realtimeUnhealthyMs = 10_000,
            capability = PartySyncCapability.OfflineLocalPlayback,
            pausedBy = seraph,
            outgoingRequestTarget = debug,
        )
        val order = listOf<(PartyPlaybackStatusInputs) -> PartyPlaybackStatusInputs>(
            { it.copy(realization = PartyRealizationPhase.None) },
            { it.copy(positionUnreachable = false) },
            { it.copy(waitingForHostSource = false) },
            { it.copy(awayHoldOthers = emptyList()) },
            { it.copy(selfHeld = false) },
            { it.copy(stallHoldOthers = emptyList()) },
            { it.copy(hostBuffering = false) },
            { it.copy(gate = PartyPlaybackGate(true, PartyHoldReason.NONE)) },
            { it.copy(barrierHoldMs = 0) },
            { it.copy(incomingRequester = null) },
            { it.copy(capability = PartySyncCapability.FullSync) },
            { it.copy(realtimeUnhealthyMs = 0) },
            { it.copy(pausedBy = null) },
            { it.copy(outgoingRequestTarget = null) },
        )
        val expected = listOf(
            PartyStatusKind.SourceNotFound,
            PartyStatusKind.VersionTooShort,
            PartyStatusKind.HostChoosingSource,
            // Above every buffering row: when the party is stopped for a person *and* a buffer,
            // the person is the reason, and telling the others to wait for the buffer is worse
            // than saying nothing.
            PartyStatusKind.WaitingForAway,
            PartyStatusKind.EveryoneWaitingOnYou,
            PartyStatusKind.WaitingForBuffering,
            PartyStatusKind.HostBuffering,
            PartyStatusKind.WaitingForHostStart,
            PartyStatusKind.CatchingUp,
            PartyStatusKind.IncomingJoinRequest,
            PartyStatusKind.Offline,
            PartyStatusKind.Reconnecting,
            PartyStatusKind.PausedBy,
            PartyStatusKind.OutgoingJoinRequest,
        )
        var inputs = everything
        expected.forEachIndexed { i, want ->
            assertEquals(want, kind(inputs), "step $i")
            inputs = order[i](inputs)
        }
        assertNull(kind(inputs))
    }

    @Test fun aStallHoldIsNeverAttributedAsAPause() {
        assertEquals(PartyStatusKind.WaitingForBuffering, kind(guest.copy(stallHoldOthers = listOf(ahmed), pausedBy = seraph)))
    }

    /**
     * An away hold says a person is not here, never that something is loading.
     *
     * "Buffering…", "Waiting for the host" and a bare "Paused" were all wrong for the same reason:
     * every one of them describes a machine, and the party is waiting for a person.
     */
    @Test fun anAwayHoldIsItsOwnSentence() {
        projectPartyPlaybackStatus(guest.copy(awayHoldOthers = listOf(ahmed)))!!.let {
            assertEquals(PartyStatusKind.WaitingForAway, it.kind)
            assertEquals("Waiting for Ahmed to return…", it.text)
            assertEquals(PartyStatusTone.Waiting, it.tone)
            // Only the host can decide not to wait, so only the host is offered the way out.
            assertNull(it.action)
        }
        assertEquals(
            PartyStatusAction.DontWait,
            projectPartyPlaybackStatus(host.copy(awayHoldOthers = listOf(ahmed)))!!.action,
        )
        assertEquals(
            "Waiting for Ahmed and Seraph to return…",
            projectPartyPlaybackStatus(guest.copy(awayHoldOthers = listOf(ahmed, seraph)))!!.text,
        )
    }

    /** A member away while another genuinely buffers reads as the away, not as the buffer. */
    @Test fun awayOutranksAStallHoldForTheSameParty() {
        assertEquals(
            PartyStatusKind.WaitingForAway,
            kind(guest.copy(awayHoldOthers = listOf(ahmed), stallHoldOthers = listOf(seraph))),
        )
    }

    /** A member with no source yet is not away, they are still getting ready. */
    @Test fun sourceWorkStillOutranksAnAwayHold() {
        assertEquals(
            PartyStatusKind.MatchingSource,
            kind(guest.copy(awayHoldOthers = listOf(ahmed), realization = PartyRealizationPhase.Matching)),
        )
    }

    // Debounce ---------------------------------------------------------------------------------

    private val a = PartyStatusLine(PartyStatusKind.CatchingUp, "Catching up with the party…")
    private val b = PartyStatusLine(PartyStatusKind.HostBuffering, "Seraph is buffering", listOf(seraph))

    @Test fun aConditionMustHoldBeforeItAppears() {
        var s = debouncePartyStatus(PartyStatusDebounceState(), a, 0)
        assertNull(s.shown)
        s = debouncePartyStatus(s, a, 699)
        assertNull(s.shown)
        s = debouncePartyStatus(s, a, 700)
        assertEquals(a, s.shown)
    }

    @Test fun aFlickerShorterThanTheDelayNeverShows() {
        var s = debouncePartyStatus(PartyStatusDebounceState(), a, 0)
        s = debouncePartyStatus(s, null, 400)
        s = debouncePartyStatus(s, a, 500)
        s = debouncePartyStatus(s, a, 1_100)
        assertNull(s.shown, "the clock restarted when the condition cleared")
        s = debouncePartyStatus(s, a, 1_200)
        assertEquals(a, s.shown)
    }

    @Test fun aShownLineStaysForTheMinimumEvenAfterItsConditionClears() {
        var s = debouncePartyStatus(debouncePartyStatus(PartyStatusDebounceState(), a, 0), a, 700)
        s = debouncePartyStatus(s, null, 1_000)
        assertEquals(a, s.shown)
        s = debouncePartyStatus(s, null, 1_900)
        assertNull(s.shown)
    }

    @Test fun sameIdentityUpdatesInPlace() {
        var s = debouncePartyStatus(debouncePartyStatus(PartyStatusDebounceState(), b, 0), b, 700)
        val renamed = b.copy(text = "Seraph is buffering (still)")
        s = debouncePartyStatus(s, renamed, 710)
        assertEquals(renamed, s.shown)
        assertEquals(700L, s.shownAtMs)
    }

    @Test fun aReplacementWaitsForBothClocks() {
        var s = debouncePartyStatus(debouncePartyStatus(PartyStatusDebounceState(), a, 0), a, 700)
        s = debouncePartyStatus(s, b, 800)
        s = debouncePartyStatus(s, b, 1_500)
        assertEquals(a, s.shown, "b has held 700ms but a has only been up 800ms")
        s = debouncePartyStatus(s, b, 1_900)
        assertEquals(b, s.shown)
    }

    // Tick hold on the wire ----------------------------------------------------------------------

    private fun tick(hold: List<String> = emptyList()) = PartyTickMessage(
        fromProfileId = "host",
        tick = PartyTick(
            partyId = "p", contentGeneration = 2, sequence = 9, status = WatchPartyStatus.paused,
            positionMs = 1_000, capturedAtPartyMs = 5_000, playbackSpeed = 1f, durationMs = 9_000,
            sourceGeneration = 3, authorityEpoch = 4, hold = hold,
        ),
    )

    @Test fun holdRoundTripsAndTheProtocolVersionDoesNotMove() {
        val encoded = encodePartySyncMessage(tick(listOf("ahmed", "debug")))
        assertEquals(2, (encoded["v"] as JsonPrimitive).content.toInt())
        val decoded = assertIs<PartyTickMessage>(decodePartySyncMessage(encoded))
        assertEquals(listOf("ahmed", "debug"), decoded.tick.hold)
        assertEquals(tick(listOf("ahmed", "debug")).tick, decoded.tick)
    }

    @Test fun anOrdinaryTickCarriesNoHoldKeyAtAll() {
        assertFalse("hold" in encodePartySyncMessage(tick()))
    }

    @Test fun anOlderSendersTickDecodesWithNoHold() {
        val withHold = encodePartySyncMessage(tick(listOf("ahmed")))
        val older = JsonObject(withHold - "hold")
        assertEquals(emptyList<String>(), assertIs<PartyTickMessage>(decodePartySyncMessage(older)).tick.hold)
    }

    @Test fun anOlderDecoderSeesEveryFieldItKnewUnchanged() {
        // Everything a pre-hold build reads is identical with or without a hold on the tick.
        val plain = encodePartySyncMessage(tick())
        val held = encodePartySyncMessage(tick(listOf("ahmed")))
        assertEquals(plain, JsonObject(held - "hold"))
    }

    @Test fun aMalformedHoldKeepsTheTick() {
        val bad = JsonObject(encodePartySyncMessage(tick()) + ("hold" to JsonPrimitive("ahmed")))
        assertEquals(emptyList<String>(), assertIs<PartyTickMessage>(decodePartySyncMessage(bad)).tick.hold)
        val mixed = JsonObject(encodePartySyncMessage(tick()) + ("hold" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive(3)))))
        assertTrue(assertIs<PartyTickMessage>(decodePartySyncMessage(mixed)).tick.hold == listOf("a"))
    }

    private fun peerStatus(starved: Boolean) = PartyPeerStatusMessage(
        partyId = "p",
        fromProfileId = "guest",
        status = WatchPartyStatus.paused,
        atPartyMs = 5_000,
        rttMs = 42,
        starved = starved,
        contentGeneration = 2,
        sourceGeneration = 3,
        authorityEpoch = 4,
    )

    @Test fun starvationRoundTripsOnThePeerStatus() {
        listOf(true, false).forEach { starved ->
            val decoded = assertIs<PartyPeerStatusMessage>(
                decodePartySyncMessage(encodePartySyncMessage(peerStatus(starved))),
            )
            assertEquals(starved, decoded.starved)
            assertEquals(peerStatus(starved), decoded)
        }
    }

    /**
     * An older sender reads as "not starved", which is the behaviour those builds already have.
     *
     * The opposite default would hold a party open for every pre-2026-09-19 guest until the
     * abandon ceiling, so the direction here is the safe one rather than the eager one.
     */
    @Test fun anOlderSendersPeerStatusDecodesAsNotStarved() {
        val older = JsonObject(encodePartySyncMessage(peerStatus(starved = true)) - "st")
        assertFalse(assertIs<PartyPeerStatusMessage>(decodePartySyncMessage(older)).starved)
    }

    @Test fun anOlderDecoderSeesEveryPeerStatusFieldItKnewUnchanged() {
        val empty = encodePartySyncMessage(peerStatus(starved = true))
        val full = encodePartySyncMessage(peerStatus(starved = false))
        assertEquals(JsonObject(empty - "st"), JsonObject(full - "st"))
    }
}
