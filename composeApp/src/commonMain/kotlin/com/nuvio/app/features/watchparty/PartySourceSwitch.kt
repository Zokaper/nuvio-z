package com.nuvio.app.features.watchparty

/** What an active player must do about the party's currently authoritative source. */
sealed interface PartySourceHandoff {
    /** Nothing to do: no party, no authoritative source, or this player is already playing it. */
    data object None : PartySourceHandoff

    /**
     * The party moved to a source this player is not playing, and it must be adopted in place.
     *
     * "In place" is the whole point of the stage this belongs to: the route, the controller and the
     * HWND survive, the old source keeps playing until the new one is ready, and nothing navigates.
     */
    data class Adopt(
        val target: PartySourceDescriptorV2,
        val sourceGeneration: Int,
    ) : PartySourceHandoff
}

/**
 * Decides whether an active player owes the party a source transition.
 *
 * [handledSourceGeneration] is the generation this player has already acted on - adopted, or found
 * it was already playing. It is what stops a transition from being attempted twice, and it is
 * deliberately *not* cleared by failure: a source the party moved to and this client could not
 * realize stays failed for that generation rather than being retried forever against a catalogue
 * that has already answered.
 *
 * A local descriptor that already matches the target at an exact tier is not a transition. That is
 * the ordinary case for whoever picked the source, and for anyone whose realization arrived through
 * the lobby - re-adopting there would restart playback for no reason.
 */
fun decidePartySourceHandoff(
    party: WatchPartyState?,
    localDescriptor: PartySourceDescriptorV2?,
    handledSourceGeneration: Int?,
): PartySourceHandoff {
    val target = party?.sourceFingerprint ?: return PartySourceHandoff.None
    if (handledSourceGeneration == party.sourceGeneration) return PartySourceHandoff.None
    if (localDescriptor != null && partySourceMatchTier(target, localDescriptor) in PartyExactMatchTiers) {
        return PartySourceHandoff.None
    }
    return PartySourceHandoff.Adopt(target = target, sourceGeneration = party.sourceGeneration)
}

/** The tiers that mean "this is the party's release", as opposed to a watchable alternate. */
val PartyExactMatchTiers = setOf(
    PartySourceMatchTier.ExactTorrentFile,
    PartySourceMatchTier.ExactOriginRelease,
    PartySourceMatchTier.ExactRelease,
    PartySourceMatchTier.EquivalentMedia,
)

/**
 * Whether this member may move the party to a different source.
 *
 * The same permission the rest of the party's controls use: the host always may, and a guest may
 * only while the party is collaborative. A guest picking a source in host-only mode changes their
 * own playback and nothing else - which is an alternate, not a party source change.
 */
fun WatchPartyState.allowsSourceChangeBy(profileId: String?): Boolean {
    if (profileId == null) return false
    if (hostProfileId == profileId) return true
    return controlMode == WatchPartyControlMode.collaborative
}

/**
 * Whether a locally picked source may be published to the party as its new authoritative one.
 *
 * A source change is a deliberate, one-time generation advance, so it is gated three ways: the
 * party must be playing this exact content, the member must be permitted, and the pick must not be
 * the source the party is already on - republishing that would advance the generation for a change
 * nobody made, and every other client would tear down a perfectly good realization for it.
 */
fun shouldPublishPartySourceChange(
    party: WatchPartyState?,
    profileId: String?,
    picked: PartySourceDescriptorV2?,
    publishedSourceGeneration: Int?,
): Boolean {
    if (party == null || picked == null) return false
    if (!party.allowsSourceChangeBy(profileId)) return false
    if (publishedSourceGeneration == party.sourceGeneration) return false
    val current = party.sourceFingerprint
    if (current != null && partySourceMatchTier(current, picked) in PartyExactMatchTiers) return false
    return true
}
