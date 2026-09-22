package com.nuvio.app.features.watchparty

/** The durable-vs-local decision made when the lobby's primary playback action is pressed. */
enum class PartyPlaybackEntryAction {
    AwaitHostSource,
    PublishStagedHostSource,
    ReuseLocalLaunch,
    ResolveAuthoritativeSource,
}

/**
 * Decides how to attach a local player without treating attachment as a new source lifecycle.
 *
 * A published descriptor is latched by the backend for its source generation. Once it exists,
 * neither route recomposition nor player re-entry is allowed to publish it again. Local reuse and
 * local rematching are deliberately separate from that durable publication decision.
 */
fun partyPlaybackEntryAction(
    authoritativeSourcePublished: Boolean,
    stagedHostSourceAvailable: Boolean,
    reusableLocalLaunchAvailable: Boolean,
    viewerIsHost: Boolean,
): PartyPlaybackEntryAction = when {
    authoritativeSourcePublished && reusableLocalLaunchAvailable ->
        PartyPlaybackEntryAction.ReuseLocalLaunch
    authoritativeSourcePublished -> PartyPlaybackEntryAction.ResolveAuthoritativeSource
    viewerIsHost && stagedHostSourceAvailable -> PartyPlaybackEntryAction.PublishStagedHostSource
    else -> PartyPlaybackEntryAction.AwaitHostSource
}

/** Rejects a delayed snapshot from an older durable generation/authority/sequence. */
fun isStalePartySnapshot(current: WatchPartyState, incoming: WatchPartyState): Boolean {
    if (current.id != incoming.id) return false
    return when {
        incoming.contentGeneration != current.contentGeneration ->
            incoming.contentGeneration < current.contentGeneration
        incoming.sourceGeneration != current.sourceGeneration ->
            incoming.sourceGeneration < current.sourceGeneration
        incoming.authorityEpoch != current.authorityEpoch ->
            incoming.authorityEpoch < current.authorityEpoch
        else -> incoming.sequence < current.sequence
    }
}

/** A local host pick belongs only to the durable generation in which it was made. */
fun shouldRetainStagedHostSource(current: WatchPartyState?, incoming: WatchPartyState): Boolean =
    current != null &&
        current.id == incoming.id &&
        current.contentGeneration == incoming.contentGeneration &&
        current.sourceGeneration == incoming.sourceGeneration
