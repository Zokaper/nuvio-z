package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The edge the semantic rule was right about and the publish path was wrong about.
 *
 * `partySourceTimelineDecision` said `AdvancePartySource` for a host whose own release had changed
 * length, and then nothing happened: `shouldPublishPartySourceChange` exists to refuse republishing
 * the source the party is already on - normally a generation advance for a change nobody made - and
 * a re-cut file carries the *same* descriptor. The verdict and the mechanism disagreed, and the
 * mechanism won silently.
 *
 * Two things were wrong and both are covered here: the guard swallowing the publish, and the host
 * never being given a duration to compare against in the first place (it was excluded from its own
 * check, so the contradiction branch was reachable only from a unit test).
 *
 * The guard itself is **not** weakened. `timelineContradicted` is the one door through it, it is
 * only ever opened by `partyHostTimelineContradicted`, and the one-shot guard on
 * `publishedSourceGeneration` is outside the door - so "exactly once" holds whichever way the
 * publish was justified.
 */
class PartyHostSourceRepublishTest {
    private val torrentA = "0123456789abcdef0123456789abcdef01234567"
    private val torrentB = "1123456789abcdef0123456789abcdef01234567"
    private val filmMs = 7_094_186L

    private fun descriptor(
        originId: String = "org.example",
        infoHash: String? = torrentA,
        fileIndex: Int? = 0,
        release: String = "Film.Name.2026.2160p.BluRay.REMUX",
    ) = PartySourceDescriptorV2(
        originKind = PartySourceOriginKind.addon,
        originId = originId,
        infoHash = infoHash,
        fileIndex = fileIndex,
        releaseFingerprint = partyReleaseFingerprint(release),
        media = PartySourceMedia(resolution = "2160p", releaseQuality = "bluray", codec = "hevc"),
    )

    private fun party(
        source: PartySourceDescriptorV2? = descriptor(),
        sourceGeneration: Int = 3,
        hostDurationMs: Long? = filmMs,
    ) = WatchPartyState(
        id = "party-1",
        hostProfileId = "host",
        status = WatchPartyStatus.playing,
        controlMode = WatchPartyControlMode.host_only,
        contentGeneration = 1,
        content = PartyContent("tt1", "movie", "tt1", "Film"),
        sourceFingerprint = source,
        sourceGeneration = sourceGeneration,
        positionMs = 0,
        durationMs = filmMs,
        playbackSpeed = 1f,
        sequence = 1,
        stateUpdatedAt = "2026-09-20T10:00:00Z",
        members = listOf(
            WatchPartyParticipant(
                profileId = "host",
                role = "host",
                readyState = SourceResolutionState.ready,
                resolvedDurationMs = hostDurationMs,
                clientLocation = WatchPartyClientLocation.player,
                joinedAt = "2026-09-20T10:00:00Z",
            ),
            WatchPartyParticipant(
                profileId = "guest",
                role = "participant",
                readyState = SourceResolutionState.ready,
                clientLocation = WatchPartyClientLocation.player,
                joinedAt = "2026-09-20T10:00:00Z",
            ),
        ),
    )

    /** The whole host-side path: the verdict, the contradiction flag, and the publish guard. */
    private fun publishes(
        local: PartySourceDescriptorV2,
        localDurationMs: Long?,
        partyState: WatchPartyState = party(),
        publishedSourceGeneration: Int? = null,
    ): Boolean {
        val target = partyState.sourceFingerprint ?: return false
        val tier = partySourceMatchTier(target, local)
        val partyHostDurationMs = partyState.members
            .firstOrNull { it.profileId == partyState.hostProfileId }?.resolvedDurationMs
        val decision = partySourceTimelineDecision(
            isHost = true,
            tier = tier,
            hostDurationMs = partyHostDurationMs,
            localDurationMs = localDurationMs,
        )
        if (decision != PartySourceTimelineDecision.AdvancePartySource) return false
        // What the player passes: reaching the publish at all is the verdict.
        return shouldPublishPartySourceChange(
            party = partyState,
            profileId = "host",
            picked = local,
            publishedSourceGeneration = publishedSourceGeneration,
            timelineChanged = true,
        )
    }

    // 1.
    @Test
    fun sameDescriptorWithACompatibleDurationChangesNothing() {
        assertFalse(
            publishes(descriptor(), localDurationMs = filmMs + 30_000L),
            "half a minute is a different rip of the same cut, not a different film",
        )
    }

    // 2.
    @Test
    fun aCredentialRemintChangesNothing() {
        // The most common thing the chain does: the URL died, the release did not. Nothing about the
        // descriptor moves, and the party must not be made to re-realize for it.
        val reminted = descriptor(originId = "org.example")
        assertFalse(publishes(reminted, localDurationMs = filmMs))
        // Nor when the same release arrives through another provider entirely.
        assertFalse(publishes(descriptor(originId = "org.other", infoHash = null, fileIndex = null), localDurationMs = filmMs))
    }

    // 3. The edge this file exists for.
    @Test
    fun sameDescriptorWithAProvenDurationContradictionAdvancesTheParty() {
        val recut = descriptor()
        assertTrue(
            partyHostTimelineContradicted(
                tier = partySourceMatchTier(party().sourceFingerprint!!, recut),
                partyDurationMs = filmMs,
                localDurationMs = filmMs + 900_000L,
            ),
        )
        assertTrue(
            publishes(recut, localDurationMs = filmMs + 900_000L),
            "the descriptor is the same and the film is not; the party has to be told",
        )
    }

    @Test
    fun theOrdinaryGuardStillRefusesThatSameDescriptorWithoutTheContradiction() {
        // The proof that the override is the only thing letting it through, rather than the guard
        // having been loosened.
        assertFalse(
            shouldPublishPartySourceChange(
                party = party(),
                profileId = "host",
                picked = descriptor(),
                publishedSourceGeneration = null,
            ),
            "no contradiction, no republication",
        )
        assertTrue(
            shouldPublishPartySourceChange(
                party = party(),
                profileId = "host",
                picked = descriptor(),
                publishedSourceGeneration = null,
                timelineChanged = true,
            ),
        )
    }

    // 4.
    @Test
    fun recompositionCannotDoubleAdvanceTheContradiction() {
        val recut = descriptor()
        val longer = filmMs + 900_000L
        assertTrue(publishes(recut, localDurationMs = longer), "the first pass publishes")
        // What the player records the instant it publishes. Every later pass - a recomposition, the
        // effect re-running on the next duration reading, a retry - sees it.
        assertFalse(
            publishes(recut, localDurationMs = longer, publishedSourceGeneration = 3),
            "one advance per generation, whatever justified it",
        )
    }

    @Test
    fun theContradictionIsTransientOnceTheNewDurationIsOnRecord() {
        // The party's record catches up the moment readiness is reported, and the same state then
        // reads as agreement - so nothing keeps republishing even without the one-shot guard.
        val longer = filmMs + 900_000L
        assertFalse(
            publishes(descriptor(), localDurationMs = longer, partyState = party(hostDurationMs = longer)),
        )
    }

    // 5.
    @Test
    fun aDifferentHostReleaseAdvancesAsItAlreadyDid() {
        // A genuinely different release: different hash, different fingerprint, different media.
        val different = descriptor(
            infoHash = torrentB,
            release = "Film.Name.2026.720p.CAM",
        ).copy(media = PartySourceMedia(resolution = "720p", codec = "avc"))
        assertEquals(PartySourceMatchTier.Fallback, partySourceMatchTier(party().sourceFingerprint!!, different))
        assertTrue(publishes(different, localDurationMs = filmMs))
        // That one needs no bypass at all: the ordinary guard already passes it.
        assertTrue(
            shouldPublishPartySourceChange(
                party = party(),
                profileId = "host",
                picked = different,
                publishedSourceGeneration = null,
            ),
        )
    }

    @Test
    fun aLookalikeHostReleaseAlsoAdvances() {
        // The second suppression, found by this file: `EquivalentMedia` is *inside*
        // `PartyExactMatchTiers`, so the duplicate test called a different release a duplicate and
        // the host's move was swallowed exactly as the re-cut file's was.
        val lookalike = descriptor(infoHash = null, fileIndex = null, release = "Film.Name.2026.2160p.BluRay.REMUX.Other")
        assertEquals(PartySourceMatchTier.EquivalentMedia, partySourceMatchTier(party().sourceFingerprint!!, lookalike))
        assertFalse(
            shouldPublishPartySourceChange(
                party = party(),
                profileId = "host",
                picked = lookalike,
                publishedSourceGeneration = null,
            ),
            "the ordinary duplicate test still calls it a duplicate, and still may",
        )
        assertTrue(publishes(lookalike, localDurationMs = filmMs), "the host's own verdict is what publishes it")
    }

    @Test
    fun anUnknownDurationOnEitherSideNeverRepublishes() {
        // A host that has not read its duration yet must not republish its own source at every
        // start, which is what a contradiction test that treated "unknown" as evidence would do.
        assertFalse(publishes(descriptor(), localDurationMs = null))
        assertFalse(publishes(descriptor(), localDurationMs = filmMs, partyState = party(hostDurationMs = null)))
        assertFalse(partyHostTimelineContradicted(PartySourceMatchTier.ExactTorrentFile, null, filmMs))
        assertFalse(partyHostTimelineContradicted(PartySourceMatchTier.ExactTorrentFile, filmMs, null))
    }

    @Test
    fun theOverrideIsNeverOpenedForATierThatIsNotThePartysOwnRelease() {
        // A different release does not need the door and must not be given a key to it: the flag is
        // about one shape only, and a wider one would be the duplicate guard weakened by the back.
        for (tier in PartySourceMatchTier.entries - PartySameReleaseTiers) {
            assertFalse(
                partyHostTimelineContradicted(tier, filmMs, filmMs + 900_000L),
                "$tier is not the party's own release",
            )
        }
    }

    // 6 and 7: the guest half of the same rule, restated here so the two halves cannot drift apart.
    @Test
    fun aGuestOnACompatibleEquivalentIsStillKeepLocal() {
        val equivalent = descriptor(infoHash = null, fileIndex = null, release = "Film.Name.2026.2160p.BluRay.REMUX.Other")
        val tier = partySourceMatchTier(party().sourceFingerprint!!, equivalent)
        assertEquals(PartySourceMatchTier.EquivalentMedia, tier)
        assertEquals(
            PartySourceTimelineDecision.KeepLocal,
            partySourceTimelineDecision(isHost = false, tier = tier, hostDurationMs = filmMs, localDurationMs = filmMs + 30_000L),
        )
        assertEquals(
            SourceResolutionState.ready,
            partySourceReadyState(
                partySourceTimelineDecision(isHost = false, tier = tier, hostDurationMs = filmMs, localDurationMs = filmMs + 30_000L),
            ),
        )
    }

    @Test
    fun aGuestWhoseDurationContradictsIsNeedsPartyMatch() {
        val equivalent = descriptor(infoHash = null, fileIndex = null, release = "Film.Name.2026.2160p.BluRay.REMUX.Other")
        val tier = partySourceMatchTier(party().sourceFingerprint!!, equivalent)
        val decision = partySourceTimelineDecision(
            isHost = false,
            tier = tier,
            hostDurationMs = filmMs,
            localDurationMs = filmMs + 900_000L,
        )
        assertEquals(PartySourceTimelineDecision.NeedsPartyMatch, decision)
        assertEquals(SourceResolutionState.choosing_fallback, partySourceReadyState(decision))
    }
}
