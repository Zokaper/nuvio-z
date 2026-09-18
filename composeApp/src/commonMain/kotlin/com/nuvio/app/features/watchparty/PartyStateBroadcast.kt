package com.nuvio.app.features.watchparty

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** What a live party state payload means for the party this client is holding. */
sealed interface PartyBroadcastOutcome {
    /**
     * The payload cannot be applied and a snapshot must be fetched.
     *
     * This is never merely "malformed". A payload for a newer identity - a content or source
     * generation move, or an authority advance - is *valid* and still lands here, because the
     * fields that describe that move are deliberately not on the channel.
     */
    data object RefreshRequired : PartyBroadcastOutcome

    /** Understood, for this identity, and older than what is already held. */
    data object Ignored : PartyBroadcastOutcome

    data class Applied(val party: WatchPartyState) : PartyBroadcastOutcome
}

/**
 * Folds a live party state payload into the held party, or says why it cannot be.
 *
 * Pure, and separate from the repository, because the ordering rule here is the one that decides
 * whether two clients agree: the pair `(sequence, state_updated_at)` compared lexicographically,
 * with the identity tuple gating the comparison entirely. `party_heartbeat` advances the host's
 * position and `state_updated_at` *without* bumping `sequence`, so a strict `sequence >` guard
 * would drop every running-position update and leave a guest correcting against a clock that never
 * moved. Timestamps are compared as epoch millis because Postgres trims trailing zeros from
 * fractional seconds, and the ISO strings do not sort correctly as text.
 */
fun applyPartyStateBroadcast(
    held: WatchPartyState?,
    payload: JsonObject,
    parseIsoEpochMs: (String) -> Long?,
): PartyBroadcastOutcome {
    fun str(key: String) = payload[key]?.jsonPrimitive?.contentOrNull

    if (held == null) return PartyBroadcastOutcome.RefreshRequired
    if (str("party_id") != held.id) return PartyBroadcastOutcome.RefreshRequired
    val sequence = payload["sequence"]?.jsonPrimitive?.longOrNull ?: return PartyBroadcastOutcome.RefreshRequired
    val updatedAt = str("state_updated_at") ?: return PartyBroadcastOutcome.RefreshRequired
    val updatedAtMs = parseIsoEpochMs(updatedAt) ?: return PartyBroadcastOutcome.RefreshRequired

    // The selected fingerprint is intentionally excluded from realtime, so an identity move is an
    // invalidation: refresh once for the new sanitized fingerprint rather than apply a payload that
    // can only describe half of the transition.
    val contentGeneration = payload["content_generation"]?.jsonPrimitive?.intOrNull
        ?: return PartyBroadcastOutcome.RefreshRequired
    if (contentGeneration != held.contentGeneration) return PartyBroadcastOutcome.RefreshRequired
    val sourceGeneration = payload["source_generation"]?.jsonPrimitive?.intOrNull ?: held.sourceGeneration
    if (sourceGeneration != held.sourceGeneration) return PartyBroadcastOutcome.RefreshRequired
    // The third member of the tuple, and the one that was missing. A host transfer bumps the epoch
    // and the sequence together: applying this payload would install the new host while keeping the
    // epoch that transfer replaced, and every command from that host would then be rejected as
    // belonging to a stale authority until an unrelated refresh happened along.
    val authorityEpoch = payload["authority_epoch"]?.jsonPrimitive?.longOrNull ?: held.authorityEpoch
    if (authorityEpoch != held.authorityEpoch) return PartyBroadcastOutcome.RefreshRequired

    val status = str("status")?.let { name -> runCatching { WatchPartyStatus.valueOf(name) }.getOrNull() }
        ?: return PartyBroadcastOutcome.RefreshRequired
    val positionMs = payload["position_ms"]?.jsonPrimitive?.longOrNull ?: return PartyBroadcastOutcome.RefreshRequired

    val heldUpdatedAtMs = parseIsoEpochMs(held.stateUpdatedAt) ?: Long.MIN_VALUE
    val newer = sequence > held.sequence || (sequence == held.sequence && updatedAtMs > heldUpdatedAtMs)
    if (!newer) return PartyBroadcastOutcome.Ignored

    return PartyBroadcastOutcome.Applied(
        held.copy(
            sequence = sequence,
            status = status,
            positionMs = positionMs,
            stateUpdatedAt = updatedAt,
            durationMs = payload["duration_ms"]?.jsonPrimitive?.longOrNull ?: held.durationMs,
            playbackSpeed = payload["playback_speed"]?.jsonPrimitive?.floatOrNull ?: held.playbackSpeed,
            hostProfileId = str("host_profile_id") ?: held.hostProfileId,
            controlMode = str("control_mode")
                ?.let { name -> runCatching { WatchPartyControlMode.valueOf(name) }.getOrNull() }
                ?: held.controlMode,
            sourceGeneration = sourceGeneration,
            authorityEpoch = authorityEpoch,
            stage = str("stage")
                ?.let { name -> runCatching { WatchPartyStage.valueOf(name) }.getOrNull() }
                ?: held.stage,
        ),
    )
}
