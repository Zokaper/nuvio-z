package com.nuvio.app.features.social

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneSocialAlertsStateTest {
    @Test fun everyKindIsOnByDefault() {
        val alerts = PhoneSocialAlertsState()
        SocialPopupKind.entries.forEach { assertTrue(alerts.allows(it), "$it") }
    }

    @Test fun theMasterSwitchOutranksEveryKind() {
        val off = PhoneSocialAlertsState(enabled = false)
        SocialPopupKind.entries.forEach { assertFalse(off.allows(it), "$it") }
    }

    @Test fun partiesCoverInvitesAndJoinRequestsAndNothingElse() {
        val noParties = PhoneSocialAlertsState(parties = false)
        assertFalse(noParties.allows(SocialPopupKind.PartyInvite))
        assertFalse(noParties.allows(SocialPopupKind.JoinRequest))
        assertTrue(noParties.allows(SocialPopupKind.FriendRequest))
        assertTrue(noParties.allows(SocialPopupKind.FriendWatching))
        assertTrue(noParties.allows(SocialPopupKind.Recommendation))
    }

    @Test fun recommendationsHaveTheirOwnSwitch() {
        val off = PhoneSocialAlertsState(recommendations = false)
        assertFalse(off.allows(SocialPopupKind.Recommendation))
        assertTrue(off.allows(SocialPopupKind.FriendRequest))
    }
}
