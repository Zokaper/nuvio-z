package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One rule for who may move the party, asked in the two places that must agree.
 *
 * The player is the side the *button* asks and the transport is the side the command asks; the two
 * disagreeing is a guest whose controls look alive and do nothing, or look dead and still move its
 * own engine for a second before the party drags it back - which is what the 2026-09-10 run
 * reported.
 */
class WatchPartyControlAuthorityTest {
    private fun party(mode: WatchPartyControlMode) = WatchPartyState(
        id = "party",
        hostProfileId = "host",
        status = WatchPartyStatus.playing,
        controlMode = mode,
        contentGeneration = 1,
        sourceGeneration = 1,
        content = PartyContent("tt1", "movie", "tt1", "Movie"),
        positionMs = 0,
        durationMs = 100_000,
        playbackSpeed = 1f,
        sequence = 1,
        stateUpdatedAt = "2026-09-10T00:00:00Z",
        authorityEpoch = 1,
    )

    private fun authority(mode: WatchPartyControlMode, self: String) = PartyAuthorityContext(
        partyId = "party",
        hostProfileId = "host",
        selfProfileId = self,
        controlMode = mode,
        durableSequence = 1,
        generation = PartyGenerationKey(
            partyId = "party",
            contentGeneration = 1,
            sourceGeneration = 1,
            authorityEpoch = 1,
        ),
    )

    @Test fun hostOnlyAdmitsTheHostAndNobodyElse() {
        val state = party(WatchPartyControlMode.host_only)
        assertTrue(state.memberMayControl("host"))
        assertFalse(state.memberMayControl("guest"))
    }

    @Test fun collaborativeAdmitsAnyMember() {
        val state = party(WatchPartyControlMode.collaborative)
        assertTrue(state.memberMayControl("host"))
        assertTrue(state.memberMayControl("guest"))
    }

    /** No active profile is nobody, and nobody may control a party. */
    @Test fun aMissingViewerMayNotControl() {
        assertFalse(party(WatchPartyControlMode.collaborative).memberMayControl(null))
        assertFalse(party(WatchPartyControlMode.host_only).memberMayControl(null))
    }

    /** The player's rule and the transport's rule are the same rule, on every combination. */
    @Test fun theDurableRuleAndTheTransportRuleNeverDisagree() {
        WatchPartyControlMode.entries.forEach { mode ->
            listOf("host", "guest").forEach { profileId ->
                assertTrue(
                    party(mode).memberMayControl(profileId) ==
                        authority(mode, profileId).mayControl(profileId),
                    "mode=$mode profile=$profileId",
                )
            }
        }
    }
}
