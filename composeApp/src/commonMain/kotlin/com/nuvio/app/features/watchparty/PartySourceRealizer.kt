package com.nuvio.app.features.watchparty

import com.nuvio.app.features.player.PartyPlayerLaunchKey
import com.nuvio.app.features.player.PlayerLaunch
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface PartySourceRealizationState {
    data object Unresolved : PartySourceRealizationState
    data class Matching(val key: PartyPlayerLaunchKey) : PartySourceRealizationState
    data class Resolving(val key: PartyPlayerLaunchKey) : PartySourceRealizationState
    data class Ready(val key: PartyPlayerLaunchKey, val realizationId: Long) : PartySourceRealizationState
    data class FallbackRequired(val key: PartyPlayerLaunchKey) : PartySourceRealizationState
    data class Failed(val key: PartyPlayerLaunchKey, val reason: String) : PartySourceRealizationState
}

/**
 * Process owner for party source work and for the sensitive launch it resolves.
 *
 * Every screen that used to own a piece of this lifetime lost it at the wrong moment. The launch
 * latch lived in the repository next to unrelated party state; the resolved launch lived in
 * `PlayerLaunchStore` behind three party-shaped methods; matching state existed only as local
 * composition variables inside a source route that is torn down the instant playback starts. Routes
 * may *present* this state and *consume* a ready realization, but none of them owns its lifetime.
 *
 * Everything is keyed by the exact authoritative party source identity
 * `(partyId, contentGeneration, sourceGeneration, descriptor)`. [updateAuthority] is the only way
 * that identity moves, and every other entry point is rejected unless it names the current one - so
 * work that finishes after the host changed the source cannot report into, or resolve for, the
 * party as it is now.
 *
 * The retained [PlayerLaunch] is process-local, never serialized, and never presented: it carries
 * resolved URLs and headers. Only the opaque realization ID leaves this object.
 */
object PartySourceRealizer {
    private val _state = MutableStateFlow<PartySourceRealizationState>(PartySourceRealizationState.Unresolved)
    val state: StateFlow<PartySourceRealizationState> = _state.asStateFlow()
    private var authorityKey: PartyPlayerLaunchKey? = null
    private var claimedLaunchKey: PartyPlayerLaunchKey? = null
    private var nextRealizationId = 1L
    private val launches = mutableMapOf<Long, PlayerLaunch>()

    /** The current authoritative party source, or null when there is no party source to realize. */
    val authority: PartyPlayerLaunchKey? get() = authorityKey

    /**
     * Installs the authoritative key, discarding everything realized for any other one.
     *
     * An unchanged key is deliberately inert: the durable snapshot arrives on every poll and every
     * broadcast, and re-arming here would drop a valid retained launch - and re-arm the automatic
     * launch claim - several times a minute.
     */
    fun updateAuthority(key: PartyPlayerLaunchKey?) {
        if (authorityKey == key) return
        authorityKey = key
        claimedLaunchKey = null
        launches.clear()
        _state.value = PartySourceRealizationState.Unresolved
    }

    /**
     * True exactly once per exact authority: the caller may take the party to the player by itself.
     *
     * This is what stops the lobby's start effect from throwing a member straight back into the
     * player after they deliberately backed out of it, and it is why the claim outlives the lobby's
     * composition. A genuine source or content change replaces the authority, which re-arms it.
     */
    fun claimAutomaticLaunch(key: PartyPlayerLaunchKey): Boolean {
        if (authorityKey != key || claimedLaunchKey == key) return false
        claimedLaunchKey = key
        return true
    }

    fun matching(key: PartyPlayerLaunchKey): Boolean = transition(key, PartySourceRealizationState.Matching(key))

    fun resolving(key: PartyPlayerLaunchKey): Boolean = transition(key, PartySourceRealizationState.Resolving(key))

    fun fallbackRequired(key: PartyPlayerLaunchKey): Boolean =
        transition(key, PartySourceRealizationState.FallbackRequired(key))

    /**
     * Records that automatic realization ended without playing.
     *
     * Only work that is still in flight for this key can fail. A [PartySourceRealizationState.Ready]
     * realization is a member who is already watching and has merely walked back through the source
     * list, and [PartySourceRealizationState.FallbackRequired] is the more specific answer for the
     * same generation - neither may be overwritten by a late dead end.
     */
    fun abandoned(key: PartyPlayerLaunchKey, reason: String): Boolean {
        val current = _state.value
        val inFlight = (current as? PartySourceRealizationState.Matching)?.key == key ||
            (current as? PartySourceRealizationState.Resolving)?.key == key
        if (!inFlight) return false
        return transition(key, PartySourceRealizationState.Failed(key, reason))
    }

    /**
     * Keeps the resolved launch for [key], replacing any earlier realization for the same party.
     *
     * Deliberately retains no player or controller: `PlayerRoute` still owns and tears down the
     * native engine. This exists so that stepping into the durable lobby and coming back does not
     * select and resolve the same release a second time.
     */
    fun retain(key: PartyPlayerLaunchKey, launch: PlayerLaunch): Long? {
        if (authorityKey != key) return null
        launches.clear()
        val id = nextRealizationId++
        launches[id] = launch
        val before = _state.value
        _state.value = PartySourceRealizationState.Ready(key, id)
        if (before !is PartySourceRealizationState.Ready) {
            realizerLog.i {
                "realization ${before::class.simpleName}->Ready party=${key.partyId.take(8)} " +
                    "gen=${key.contentGeneration}/${key.sourceGeneration} by=player-launch"
            }
        }
        return id
    }

    fun reusable(key: PartyPlayerLaunchKey): PlayerLaunch? {
        val ready = _state.value as? PartySourceRealizationState.Ready ?: return null
        if (authorityKey != key || ready.key != key) return null
        return launches[ready.realizationId]
    }

    /** Leave, end, profile change, and account wipe. Nothing sensitive survives any of them. */
    fun clear() = updateAuthority(null)

    private fun transition(key: PartyPlayerLaunchKey, next: PartySourceRealizationState): Boolean {
        if (authorityKey != key) return false
        val before = _state.value
        _state.value = next
        if (before::class != next::class) {
            realizerLog.i {
                "realization ${before::class.simpleName}->${next::class.simpleName} " +
                    "party=${key.partyId.take(8)} gen=${key.contentGeneration}/${key.sourceGeneration}"
            }
        }
        return true
    }
}

private val realizerLog = Logger.withTag("PartySourceRealizer")

/**
 * The party source a player launch has realized, or null when it realizes none.
 *
 * ⚠ **Post-release Bug 2 (2026-09-17): a guest who joined from Watching Now watched the film with
 * "Matching <host>'s source…" pinned over it.** `PlayerDestination` retained a launch only when its
 * descriptor was *byte-equal* to the party's. Every other identity question in Watch Together - the
 * readiness report's `source_match`, the in-player handoff, the automatic-launch claim - asks
 * [partySourceMatchTier] instead, and a guest's own catalogue describes the same release with its
 * own addon version, size and language facts. So the strict matcher resolved the host's release,
 * the player opened it and reported `source_match=exact` to the party, and the realizer was never
 * told: it stayed `Resolving` for the life of the player, which the status pill reads as "Matching".
 * The physical run's member row carries exactly that pair - `ready`, `exact`, and a pill that never
 * cleared.
 *
 * One rule now: a launch of the party's current content completes its realization when it plays
 * the party's release at an exact tier, or when it plays an alternate the member chose by hand after
 * the matcher had already answered FallbackRequired (or Failed) for this very key. Either way the
 * member has a usable local source, which is the only thing "Matching" ever claimed they lacked.
 */
fun partyRealizationCompletedByLaunch(
    party: WatchPartyState?,
    launchContentId: String,
    launchVideoId: String?,
    launchDescriptor: PartySourceDescriptorV2?,
    realization: PartySourceRealizationState,
): PartyPlayerLaunchKey? {
    val live = party?.takeIf { it.matchesPlayback(launchContentId, launchVideoId) } ?: return null
    val key = live.partySourceKey() ?: return null
    val local = launchDescriptor ?: return null
    if (partySourceMatchTier(key.descriptor, local) in PartyExactMatchTiers) return key
    val alternateChosenAfterNoMatch = when (realization) {
        is PartySourceRealizationState.FallbackRequired -> realization.key == key
        is PartySourceRealizationState.Failed -> realization.key == key
        is PartySourceRealizationState.Ready -> realization.key == key
        else -> false
    }
    return key.takeIf { alternateChosenAfterNoMatch }
}

/** The status pill's reading of the realizer, for the party the player is showing. */
fun partyRealizationPhaseFor(realization: PartySourceRealizationState, partyId: String?): PartyRealizationPhase {
    val key = when (realization) {
        is PartySourceRealizationState.Matching -> realization.key
        is PartySourceRealizationState.Resolving -> realization.key
        is PartySourceRealizationState.FallbackRequired -> realization.key
        is PartySourceRealizationState.Failed -> realization.key
        else -> return PartyRealizationPhase.None
    }
    if (partyId == null || key.partyId != partyId) return PartyRealizationPhase.None
    return when (realization) {
        is PartySourceRealizationState.Matching -> PartyRealizationPhase.Matching
        is PartySourceRealizationState.Resolving -> PartyRealizationPhase.Resolving
        is PartySourceRealizationState.FallbackRequired -> PartyRealizationPhase.FallbackRequired
        is PartySourceRealizationState.Failed -> PartyRealizationPhase.Failed
        else -> PartyRealizationPhase.None
    }
}

/**
 * The exact authoritative source identity for this party, or null when it has not chosen one.
 *
 * One builder, because four callers were constructing this key by hand from four places. Any two of
 * them disagreeing - one omitting the content generation, say - silently turns a reuse into a
 * re-resolve, or a spent launch claim into a second automatic launch.
 */
fun WatchPartyState.partySourceKey(): PartyPlayerLaunchKey? = sourceFingerprint?.let { descriptor ->
    PartyPlayerLaunchKey(
        partyId = id,
        contentGeneration = contentGeneration,
        sourceGeneration = sourceGeneration,
        descriptor = descriptor,
    )
}
