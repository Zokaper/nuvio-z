package com.nuvio.app.features.watchparty

/** An absence ends before the returning player's catch-up ends. Keep those two edges separate. */
data class PartyAwayRecovery(
    val awayObservedAt: Map<String, Long> = emptyMap(),
    val returningAt: Map<String, Long> = emptyMap(),
    val readySince: Map<String, Long> = emptyMap(),
    val releasedBy: Map<String, String> = emptyMap(),
) {
    fun advance(
        away: List<String>,
        members: List<WatchPartyParticipant>,
        telemetry: Map<String, PartyPeerTelemetry>,
        nowMs: Long,
        enabled: Boolean,
    ): PartyAwayRecovery {
        if (!enabled) return PartyAwayRecovery()
        val eligible = members.filter {
            it.readyState != SourceResolutionState.left && it.readyState != SourceResolutionState.failed
        }.map { it.profileId }.toSet()
        val absent = away.filter { it in eligible }.toSet()
        val last = awayObservedAt.filterKeys { it in eligible }.toMutableMap()
        val returning = returningAt.filterKeys { it in eligible }.toMutableMap()
        val settled = readySince.filterKeys { it in eligible }.toMutableMap()
        val released = mutableMapOf<String, String>()
        absent.forEach { id ->
            // Preserve the absence's start: a ready return packet can arrive before the durable
            // clear. Moving the baseline on every poll would unnecessarily reject that answer.
            if (id !in last || id in returning) last[id] = nowMs
            returning.remove(id)
            settled.remove(id)
        }
        (last.keys - absent).forEach { id -> returning.getOrPut(id) { nowMs } }
        returning.toMap().forEach { (id, started) ->
            // The same positive playback evidence and ceiling as startup/seek readiness. An
            // expired lease or lost socket cannot leave an automatic hold standing forever.
            val ready = partyPeerPlaybackReady(telemetry[id], nowMs, last.getValue(id))
            if (ready) settled.getOrPut(id) { nowMs } else settled.remove(id)
            val timedOut = nowMs - started >= WatchPartyStartPlaybackReadyMaxWaitMs
            if (timedOut ||
                settled[id]?.let { nowMs - it >= WatchPartyStallRecoverySettleMs } == true
            ) {
                released[id] = if (timedOut) "ceiling" else "all-ready"
                last.remove(id)
                returning.remove(id)
                settled.remove(id)
            }
        }
        return PartyAwayRecovery(last, returning, settled, released)
    }

    val holding: List<String> get() = awayObservedAt.keys.sorted()
}

/** Shared by startup, seek and Away return. Old, Away or empty-engine reports cannot release. */
internal fun partyPeerPlaybackReady(peer: PartyPeerTelemetry?, nowMs: Long, freshSinceMs: Long): Boolean =
    peer != null && nowMs - peer.receivedAtPartyMs <= WatchPartyClockStaleMs &&
        peer.reportedAtPartyMs >= freshSinceMs && !peer.away && !peer.starved &&
        (peer.status == WatchPartyStatus.paused || peer.status == WatchPartyStatus.playing)

enum class PartyAutoHoldAction { Retry, Hold, Keep, Clear, Resume }

/** A skipped edge must be retried; releasing one automatic hold must respect the other holds. */
fun partyAutoHoldAction(
    requested: List<String>,
    held: List<String>,
    playbackIntended: Boolean,
    otherHoldActive: Boolean,
): PartyAutoHoldAction = when {
    requested.isNotEmpty() && held.isEmpty() ->
        if (playbackIntended || otherHoldActive) PartyAutoHoldAction.Hold else PartyAutoHoldAction.Retry
    requested.isEmpty() && held.isNotEmpty() ->
        if (otherHoldActive) PartyAutoHoldAction.Clear else PartyAutoHoldAction.Resume
    else -> PartyAutoHoldAction.Keep
}
