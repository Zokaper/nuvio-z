package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **Different URLs are fine. Different timelines are not.**
 *
 * The failure chain is a route-level mechanism with no idea a party exists: it picks the next
 * candidate and relaunches the player. That is right for most of what it does - a credential
 * re-mint, a renewed debrid link, the same torrent through another provider are all new URLs for
 * bytes the party has already agreed on - and wrong in exactly one way, which is a host quietly
 * playing a different cut while everybody else holds the old one at timestamps that no longer mean
 * the same frame.
 *
 * So the verdict turns on who changed, because the host defines the timeline:
 * **host changes release => the party follows; guest changes release => the guest must prove it
 * still matches.**
 */
class PartySourceTimelineTest {
    private val torrentA = "0123456789abcdef0123456789abcdef01234567"
    private val torrentB = "1123456789abcdef0123456789abcdef01234567"
    private val filmMs = 7_094_186L

    private fun descriptor(
        originId: String = "org.example",
        infoHash: String? = null,
        fileIndex: Int? = null,
        release: String = "Film.Name.2026.2160p.BluRay.REMUX",
        media: PartySourceMedia = PartySourceMedia(resolution = "2160p", releaseQuality = "bluray", codec = "hevc"),
    ) = PartySourceDescriptorV2(
        originKind = PartySourceOriginKind.addon,
        originId = originId,
        infoHash = infoHash,
        fileIndex = fileIndex,
        releaseFingerprint = partyReleaseFingerprint(release),
        media = media,
    )

    private fun decide(
        host: PartySourceDescriptorV2,
        local: PartySourceDescriptorV2,
        isHost: Boolean,
        hostDurationMs: Long? = null,
        localDurationMs: Long? = null,
    ) = partySourceTimelineDecision(
        isHost = isHost,
        tier = partySourceMatchTier(host, local),
        hostDurationMs = hostDurationMs,
        localDurationMs = localDurationMs,
    )

    // ---------------------------------------------------------------- the host

    @Test
    fun hostRemintingACredentialKeepsThePartyWhereItIs() {
        // The one the chain does most: the URL is dead, the release is not. Nothing about the party
        // has changed, and advancing its source would tear down every guest's working realization
        // to hand them back the file they already have.
        val party = descriptor(infoHash = torrentA, fileIndex = 3)
        val reminted = descriptor(infoHash = torrentA, fileIndex = 3)
        assertEquals(PartySourceTimelineDecision.KeepLocal, decide(party, reminted, isHost = true))
    }

    @Test
    fun hostReachingTheSameReleaseThroughAnotherProviderKeepsItLocalToo() {
        // Different addon, no torrent identity, same release fingerprint: another realization of the
        // party's own release, which is a new URL and not a new timeline.
        val party = descriptor(originId = "org.example")
        val alternate = descriptor(originId = "org.other")
        assertEquals(PartySourceMatchTier.ExactRelease, partySourceMatchTier(party, alternate))
        assertEquals(PartySourceTimelineDecision.KeepLocal, decide(party, alternate, isHost = true))
    }

    @Test
    fun hostFallingBackToADifferentReleaseTakesThePartyWithIt() {
        val party = descriptor(infoHash = torrentA, fileIndex = 1, release = "Film.Name.2026.2160p.BluRay.REMUX")
        val different = descriptor(infoHash = torrentB, fileIndex = 1, release = "Film.Name.2026.1080p.WEB-DL")
        assertEquals(
            PartySourceTimelineDecision.AdvancePartySource,
            decide(party, different, isHost = true),
            "the host defines the timeline, so its release change is the party's release change",
        )
    }

    @Test
    fun hostFallingBackToALookalikeReleaseAlsoTakesThePartyWithIt() {
        // `EquivalentMedia` is the trap: same resolution, same codec, a plausible substitute - and a
        // different release, whose timeline nobody has checked. Fine for a guest to fall back to
        // with a duration check behind it; never something for the host to keep to itself.
        val party = descriptor(release = "Film.Name.2026.2160p.BluRay.REMUX.GroupA")
        val lookalike = descriptor(release = "Film.Name.2026.2160p.BluRay.REMUX.GroupB")
        assertEquals(PartySourceMatchTier.EquivalentMedia, partySourceMatchTier(party, lookalike))
        assertEquals(PartySourceTimelineDecision.AdvancePartySource, decide(party, lookalike, isHost = true))
    }

    @Test
    fun hostWhoseOwnReleaseChangesLengthStillTellsTheParty() {
        // Identity says the same release; the duration says otherwise. For the host that is not a
        // matching failure - it is the timeline itself having changed, which is precisely what the
        // party has to be told.
        val party = descriptor(infoHash = torrentA, fileIndex = 0)
        val same = descriptor(infoHash = torrentA, fileIndex = 0)
        assertEquals(
            PartySourceTimelineDecision.AdvancePartySource,
            decide(party, same, isHost = true, hostDurationMs = filmMs, localDurationMs = filmMs - 600_000L),
        )
    }

    // ---------------------------------------------------------------- the guest

    @Test
    fun guestLandingBackOnThePartysOwnReleaseStaysLocalAndReady() {
        val party = descriptor(infoHash = torrentA, fileIndex = 2)
        val mine = descriptor(infoHash = torrentA, fileIndex = 2, originId = "org.other")
        val decision = decide(party, mine, isHost = false, hostDurationMs = filmMs, localDurationMs = filmMs)
        assertEquals(PartySourceTimelineDecision.KeepLocal, decision)
        assertEquals(SourceResolutionState.ready, partySourceReadyState(decision))
    }

    @Test
    fun guestOnACompatibleEquivalentIsAllowedWhenTheDurationAgrees() {
        val party = descriptor(release = "Film.Name.2026.2160p.BluRay.REMUX.GroupA")
        val equivalent = descriptor(release = "Film.Name.2026.2160p.BluRay.REMUX.GroupB")
        val decision = decide(party, equivalent, isHost = false, hostDurationMs = filmMs, localDurationMs = filmMs + 30_000L)
        assertEquals(PartySourceTimelineDecision.KeepLocal, decision)
        assertEquals(SourceResolutionState.ready, partySourceReadyState(decision))
    }

    @Test
    fun guestOnAnEquivalentWithAContradictingDurationIsRefused() {
        // Ten minutes is not an intro; it is a different cut. Identity was never strong here, and
        // the duration is the evidence against it.
        val party = descriptor(release = "Film.Name.2026.2160p.BluRay.REMUX.GroupA")
        val longer = descriptor(release = "Film.Name.2026.2160p.BluRay.REMUX.GroupB")
        val decision = decide(party, longer, isHost = false, hostDurationMs = filmMs, localDurationMs = filmMs + 600_000L)
        assertEquals(PartySourceTimelineDecision.NeedsPartyMatch, decision)
        assertEquals(SourceResolutionState.choosing_fallback, partySourceReadyState(decision))
    }

    @Test
    fun guestOnAnArbitraryFallbackNeverSilentlyBecomesReady() {
        // Something else that plays this title. No shared identity, nothing contradicted - and no
        // evidence at all of a shared timeline, which is the whole bar.
        val party = descriptor(release = "Film.Name.2026.2160p.BluRay.REMUX", media = PartySourceMedia(resolution = "2160p", codec = "hevc"))
        val other = descriptor(release = "Film.Name.2026.720p.CAM", media = PartySourceMedia(resolution = "720p", codec = "avc"))
        assertEquals(PartySourceMatchTier.Fallback, partySourceMatchTier(party, other))
        val decision = decide(party, other, isHost = false, hostDurationMs = filmMs, localDurationMs = filmMs)
        assertEquals(PartySourceTimelineDecision.NeedsPartyMatch, decision)
        assertEquals(SourceResolutionState.choosing_fallback, partySourceReadyState(decision))
    }

    @Test
    fun guestWithContradictedIdentityIsRefusedHoweverTheDurationLooks() {
        // The same torrent, a different file in it: `None`, because a multi-file torrent's file
        // *is* the release identity, and two files of one torrent are two different videos.
        val party = descriptor(infoHash = torrentA, fileIndex = 1)
        val wrongFile = descriptor(infoHash = torrentA, fileIndex = 7)
        assertEquals(PartySourceMatchTier.None, partySourceMatchTier(party, wrongFile))
        assertEquals(
            PartySourceTimelineDecision.NeedsPartyMatch,
            decide(party, wrongFile, isHost = false, hostDurationMs = filmMs, localDurationMs = filmMs),
        )
    }

    @Test
    fun aGuestNeverAdvancesTheAuthoritativeSource() {
        // The invariant, stated as the thing that must never happen: whatever a guest lands on, the
        // answer is about the guest.
        val party = descriptor(infoHash = torrentA, fileIndex = 1)
        val everything = listOf(
            descriptor(infoHash = torrentA, fileIndex = 1),
            descriptor(release = "Film.Name.2026.2160p.BluRay.REMUX.Other"),
            descriptor(release = "Film.Name.2026.720p.CAM", media = PartySourceMedia(resolution = "720p")),
            descriptor(infoHash = torrentB, fileIndex = 1),
            descriptor(infoHash = torrentA, fileIndex = 9),
        )
        for (local in everything) {
            val decision = decide(party, local, isHost = false, hostDurationMs = filmMs, localDurationMs = filmMs)
            assertEquals(
                false,
                decision == PartySourceTimelineDecision.AdvancePartySource,
                "a guest may never move the party: $decision for tier ${partySourceMatchTier(party, local)}",
            )
        }
    }

    @Test
    fun anUnknownDurationOnEitherSideLeavesTheIdentityVerdictStanding() {
        // Duration can only ever subtract here. A source that has not reported one yet must not be
        // treated as contradicted, or every member would be unready for its first few seconds.
        val party = descriptor(release = "Film.Name.2026.2160p.BluRay.REMUX.GroupA")
        val equivalent = descriptor(release = "Film.Name.2026.2160p.BluRay.REMUX.GroupB")
        assertEquals(
            PartySourceTimelineDecision.KeepLocal,
            decide(party, equivalent, isHost = false, hostDurationMs = filmMs, localDurationMs = null),
        )
        assertEquals(
            PartySourceTimelineDecision.KeepLocal,
            decide(party, equivalent, isHost = false, hostDurationMs = null, localDurationMs = filmMs),
        )
    }
}
