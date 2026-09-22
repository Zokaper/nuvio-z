package com.nuvio.app.features.watchparty

/**
 * What an active player must do about the party moving to different **content**.
 *
 * The sibling of [PartySourceHandoff], and deliberately a separate decision rather than a widening
 * of it. A source change keeps the episode and swaps the release; a content change is the party
 * watching something else, so the player has to change what it is playing as well as where it is
 * playing it from. They also advance different generations - `party_change_content_v2` moves
 * `content_generation` *and* `source_generation`, while a source publish moves only the latter -
 * and a client that tracked one counter for both would either re-adopt a source it already had or
 * miss an episode change that reused a descriptor.
 */
sealed interface PartyContentHandoff {
    /** Nothing to do: no party, a different title entirely, already on it, or already handled. */
    data object None : PartyContentHandoff

    /**
     * The party moved to content this player is not playing, and it must be adopted in place.
     *
     * In place means exactly what it means for a source handoff: the route, the controller and the
     * HWND survive, the current episode keeps playing until the new one is ready, and nothing
     * navigates. [target] is null while the host has not yet published a source for the new
     * content, which the backend models as `waiting_for_host_source`.
     */
    data class Adopt(
        val content: PartyContent,
        val contentGeneration: Int,
        val sourceGeneration: Int,
        val target: PartySourceDescriptorV2?,
    ) : PartyContentHandoff
}

/**
 * Decides whether an active player owes the party a content transition.
 *
 * [handledContentGeneration] is the generation this player has already acted on, and is what stops
 * one episode change being adopted twice. It is not cleared by failure, for the same reason the
 * source handoff's is not: the party moved, and only the party can move it again.
 *
 * ⚠ **A different `content_id` is not a transition.** This player is watching something else
 * entirely - the member walked out of the party's title and started another one - and pulling them
 * into the party's episode would be hijacking a playback they deliberately started. They are simply
 * not on the party's content, which every other surface already reads correctly.
 *
 * ⚠ **[pendingPublishedContentGeneration] is the host's own advance, still in flight.** The host
 * switches episode locally *before* `party_change_content_v2` answers, so for the length of that
 * round trip the party still names the previous episode and this function, asked without the latch,
 * answers "the party is on a different episode of your show - go back to it". Once the in-player
 * catalogue actually settled (it never did before the 2026-09-15 fix, which is the only reason this
 * never fired) a fast addon would win that race and drag the host back to the episode it had just
 * left. While this client has published an advance *from* the party's current generation, the party
 * is known to be behind it, and there is nothing to adopt. A refusal releases the latch, and the
 * party is then correctly authoritative again.
 */
fun decidePartyContentHandoff(
    party: WatchPartyState?,
    localContentId: String,
    localVideoId: String?,
    handledContentGeneration: Int?,
    pendingPublishedContentGeneration: Int? = null,
): PartyContentHandoff {
    if (party == null || party.status == WatchPartyStatus.ended) return PartyContentHandoff.None
    if (party.content.contentId != localContentId) return PartyContentHandoff.None
    if (party.content.videoId == localVideoId) return PartyContentHandoff.None
    if (handledContentGeneration == party.contentGeneration) return PartyContentHandoff.None
    if (pendingPublishedContentGeneration == party.contentGeneration) return PartyContentHandoff.None
    return PartyContentHandoff.Adopt(
        content = party.content,
        contentGeneration = party.contentGeneration,
        sourceGeneration = party.sourceGeneration,
        target = party.sourceFingerprint,
    )
}

/**
 * Whether this member may move the party to different content.
 *
 * ⚠ **Host only, and unlike [allowsSourceChangeBy] a collaborative party does not widen it.** That
 * is the product decision - the host drives which episode the party watches, guests follow - and it
 * is also what the server enforces: `party_change_content_v2` refuses anyone but
 * `host_profile_id` with `host_required` (42501). Answering anything else here would only produce
 * a rejected round trip.
 */
fun WatchPartyState.allowsContentChangeBy(profileId: String?): Boolean =
    profileId != null && hostProfileId == profileId

/**
 * Whether locally chosen content may be published to the party as its new current content.
 *
 * Gated the same three ways [shouldPublishPartySourceChange] is, for the same reasons: the member
 * must be permitted, the party must not already be on this content - republishing that would burn
 * a content generation for a change nobody made and reset every member to `fetching` for it - and
 * one advance must be published once. The `expected_content_generation` the RPC carries is what
 * settles two hosts racing, but not sending the second request at all is cheaper than losing it.
 */
fun shouldPublishPartyContentChange(
    party: WatchPartyState?,
    profileId: String?,
    nextVideoId: String?,
    publishedContentGeneration: Int?,
): Boolean {
    if (party == null || nextVideoId.isNullOrBlank()) return false
    if (party.status == WatchPartyStatus.ended) return false
    if (!party.allowsContentChangeBy(profileId)) return false
    if (publishedContentGeneration == party.contentGeneration) return false
    return party.content.videoId != nextVideoId
}

/**
 * Whether this member drives its own next-episode flow.
 *
 * A guest in a party does not choose the episode and does not get a cancelable countdown - the host
 * advances the party and everyone follows, which is what keeps one content generation authoritative.
 * Outside a party, and for the host, this is ordinary playback and answers true.
 *
 * ⚠ Read against the party's **title**, not merely against membership. A member watching a
 * different title is having an ordinary evening with a party open in the background, and taking
 * their Next episode button away would be wrong.
 *
 * ⚠⚠ **The title, not the exact episode.** This used to test `matchesPlayback`, which is false for
 * every guest from the instant the host advances until that guest has adopted the new episode. For
 * that whole window the guest "owned" its next episode again - so a guest near the end of the
 * previous episode, or one whose episode ended while the handoff was still matching, raised its own
 * card and ran its own autoplay-next: a second, independent content decision racing the host's. It
 * is the same rule [decidePartyContentHandoff] uses to decide the guest *is* being moved, and the
 * two must agree - a member the handoff is carrying cannot also be choosing for itself.
 */
fun ownsNextEpisodeChoice(
    party: WatchPartyState?,
    profileId: String?,
    localContentId: String,
    localVideoId: String?,
): Boolean {
    if (party == null || party.status == WatchPartyStatus.ended) return true
    if (party.content.contentId != localContentId) return true
    return party.allowsContentChangeBy(profileId)
}
