package com.nuvio.app.features.watchparty

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The rule that decides whether two clients agree about the party.
 *
 * Every case here is a payload the server genuinely sends. The interesting ones are the payloads
 * that are perfectly valid and must still be refused: the channel deliberately carries no
 * fingerprint or content, so an identity move can only be an invalidation.
 */
class PartyStateBroadcastTest {
    private val heldUpdatedAt = "2026-09-09T12:00:00Z"

    @Test fun aLaterSequenceForTheSameIdentityIsApplied() {
        val outcome = applyPartyStateBroadcast(held(), payload(sequence = 8), ::parseIso)
        val applied = assertIs<PartyBroadcastOutcome.Applied>(outcome)
        assertEquals(8L, applied.party.sequence)
        assertEquals(42_000L, applied.party.positionMs)
    }

    /**
     * `party_heartbeat` moves the host's position and `state_updated_at` without bumping the
     * sequence. A strict `sequence >` guard drops every running-position update.
     */
    @Test fun theSameSequenceWithALaterTimestampStillMovesThePosition() {
        val outcome = applyPartyStateBroadcast(
            held(),
            payload(sequence = 7, updatedAt = "2026-09-09T12:00:02Z"),
            ::parseIso,
        )
        assertEquals(42_000L, assertIs<PartyBroadcastOutcome.Applied>(outcome).party.positionMs)
    }

    @Test fun anOlderPayloadIsIgnoredRatherThanRefreshed() {
        assertEquals(
            PartyBroadcastOutcome.Ignored,
            applyPartyStateBroadcast(held(), payload(sequence = 6), ::parseIso),
        )
    }

    @Test fun aContentGenerationMoveRequiresARefresh() {
        assertEquals(
            PartyBroadcastOutcome.RefreshRequired,
            applyPartyStateBroadcast(held(), payload(sequence = 8, contentGeneration = 2), ::parseIso),
        )
    }

    @Test fun aSourceGenerationMoveRequiresARefresh() {
        assertEquals(
            PartyBroadcastOutcome.RefreshRequired,
            applyPartyStateBroadcast(held(), payload(sequence = 8, sourceGeneration = 5), ::parseIso),
        )
    }

    /**
     * The Stage 5 gap. A transfer bumps the epoch and the sequence together, so applying the
     * payload would install the new host under the epoch it replaced - and then reject every
     * command that host sends.
     */
    @Test fun anAuthorityAdvanceRequiresARefreshRatherThanInstallingTheNewHostUnderTheOldEpoch() {
        val outcome = applyPartyStateBroadcast(
            held(),
            payload(sequence = 8, authorityEpoch = 4, hostProfileId = "guest"),
            ::parseIso,
        )
        assertEquals(PartyBroadcastOutcome.RefreshRequired, outcome)
    }

    /** An older server that omits the field says nothing about the epoch, so it must not move one. */
    @Test fun aPayloadWithoutAnEpochKeepsTheHeldOne() {
        val payload = buildJsonObject {
            put("party_id", "party")
            put("sequence", 8L)
            put("state_updated_at", "2026-09-09T12:00:01Z")
            put("content_generation", 1)
            put("source_generation", 4)
            put("status", WatchPartyStatus.playing.name)
            put("position_ms", 42_000L)
        }
        val applied = assertIs<PartyBroadcastOutcome.Applied>(
            applyPartyStateBroadcast(held(), payload, ::parseIso),
        )
        assertEquals(3L, applied.party.authorityEpoch)
    }

    @Test fun aPayloadForAnotherPartyIsNeverApplied() {
        assertEquals(
            PartyBroadcastOutcome.RefreshRequired,
            applyPartyStateBroadcast(held(), payload(sequence = 8, partyId = "other"), ::parseIso),
        )
    }

    @Test fun aPayloadWithNothingHeldAsksForASnapshot() {
        assertEquals(
            PartyBroadcastOutcome.RefreshRequired,
            applyPartyStateBroadcast(null, payload(sequence = 8), ::parseIso),
        )
    }

    private fun parseIso(value: String): Long? =
        runCatching { kotlin.time.Instant.parse(value).toEpochMilliseconds() }.getOrNull()

    private fun payload(
        sequence: Long,
        updatedAt: String = "2026-09-09T12:00:01Z",
        contentGeneration: Int = 1,
        sourceGeneration: Int = 4,
        authorityEpoch: Long = 3,
        hostProfileId: String = "host",
        partyId: String = "party",
    ): JsonObject = buildJsonObject {
        put("party_id", partyId)
        put("reason", "watch_parties")
        put("sequence", sequence)
        put("state_updated_at", updatedAt)
        put("content_generation", contentGeneration)
        put("source_generation", sourceGeneration)
        put("authority_epoch", authorityEpoch)
        put("status", WatchPartyStatus.playing.name)
        put("control_mode", WatchPartyControlMode.collaborative.name)
        put("stage", WatchPartyStage.playing.name)
        put("host_profile_id", hostProfileId)
        put("position_ms", 42_000L)
        put("duration_ms", 7_200_000L)
        put("playback_speed", 1f)
    }

    private fun held() = WatchPartyState(
        id = "party",
        hostProfileId = "host",
        status = WatchPartyStatus.paused,
        controlMode = WatchPartyControlMode.collaborative,
        contentGeneration = 1,
        sourceGeneration = 4,
        stage = WatchPartyStage.playing,
        content = PartyContent("tt1", "movie", "tt1", "Movie"),
        positionMs = 1_000,
        durationMs = 7_200_000,
        playbackSpeed = 1f,
        sequence = 7,
        stateUpdatedAt = heldUpdatedAt,
        authorityEpoch = 3,
    )
}
