package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchPartyPlaybackLifecycleTest {
    @Test fun publishedSourceWithReusableLaunchAttachesDirectlyWithoutRepublishing() {
        assertEquals(
            PartyPlaybackEntryAction.ReuseLocalLaunch,
            partyPlaybackEntryAction(true, true, true, viewerIsHost = true),
        )
    }

    @Test fun publishedSourceWithoutReusableLaunchRematchesSameAuthorityWithoutRepublishing() {
        assertEquals(
            PartyPlaybackEntryAction.ResolveAuthoritativeSource,
            partyPlaybackEntryAction(true, true, false, viewerIsHost = true),
        )
    }

    @Test fun guestReentryNeverPublishesOrBecomesAuthority() {
        assertEquals(
            PartyPlaybackEntryAction.ResolveAuthoritativeSource,
            partyPlaybackEntryAction(true, false, false, viewerIsHost = false),
        )
        assertEquals(
            PartyPlaybackEntryAction.AwaitHostSource,
            partyPlaybackEntryAction(false, true, true, viewerIsHost = false),
        )
    }

    @Test fun stagedHostSelectionPublishesOnlyBeforeAnAuthoritativeSourceExists() {
        assertEquals(
            PartyPlaybackEntryAction.PublishStagedHostSource,
            partyPlaybackEntryAction(false, true, false, viewerIsHost = true),
        )
        assertFalse(
            partyPlaybackEntryAction(true, true, false, viewerIsHost = true) ==
                PartyPlaybackEntryAction.PublishStagedHostSource,
        )
    }

    @Test fun olderDurableGenerationsCannotOverwriteCurrentSnapshot() {
        val current=party(contentGeneration=3,sourceGeneration=7,authorityEpoch=4,sequence=20)
        assertTrue(isStalePartySnapshot(current,current.copy(contentGeneration=2,sourceGeneration=99,sequence=99)))
        assertTrue(isStalePartySnapshot(current,current.copy(sourceGeneration=6,sequence=99)))
        assertTrue(isStalePartySnapshot(current,current.copy(authorityEpoch=3,sequence=99)))
        assertTrue(isStalePartySnapshot(current,current.copy(sequence=19)))
        assertFalse(isStalePartySnapshot(current,current.copy(sourceGeneration=8,sequence=1)))
        assertFalse(isStalePartySnapshot(current,current.copy(authorityEpoch=5,sequence=1)))
    }

    @Test fun stagedHostPickSurvivesOnlyItsOwnContentAndSourceGeneration() {
        val current=party(contentGeneration=4,sourceGeneration=7,authorityEpoch=2,sequence=11)
        assertTrue(shouldRetainStagedHostSource(current,current.copy(sequence=12)))
        assertFalse(shouldRetainStagedHostSource(current,current.copy(sourceGeneration=8)))
        assertFalse(shouldRetainStagedHostSource(current,current.copy(contentGeneration=5)))
        assertFalse(shouldRetainStagedHostSource(current,current.copy(id="another-party")))
    }

    private fun party(
        contentGeneration:Int,
        sourceGeneration:Int,
        authorityEpoch:Long,
        sequence:Long,
    )=WatchPartyState(
        id="party",hostProfileId="host",status=WatchPartyStatus.playing,
        controlMode=WatchPartyControlMode.host_only,contentGeneration=contentGeneration,
        sourceGeneration=sourceGeneration,content=PartyContent("tt1","movie","tt1","Movie"),
        positionMs=100,durationMs=1000,playbackSpeed=1f,sequence=sequence,
        stateUpdatedAt="2026-09-08T00:00:00Z",authorityEpoch=authorityEpoch,
    )
}
