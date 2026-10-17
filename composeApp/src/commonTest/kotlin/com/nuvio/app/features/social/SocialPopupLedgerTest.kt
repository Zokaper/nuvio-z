package com.nuvio.app.features.social

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SocialPopupLedgerTest {
    private val friend = SocialProfileSummary("f", "friend", "Friend")
    private val quiet = SocialProfileSummary("q", "quiet", "Quiet")
    private val now = parseSocialTimestampMs("2026-10-17T13:10:00Z")!!

    private fun request(id: String, at: String = "2026-10-17T13:05:00Z") = SocialNotification(
        id = id,
        kind = SocialNotificationKind.FriendRequest,
        actor = friend,
        createdAt = at,
        state = "pending",
        availableActions = setOf(SocialNotificationAction.Accept, SocialNotificationAction.Decline),
    )

    private fun watching(id: String, actor: SocialProfileSummary = friend, at: String = "2026-10-17T13:05:00Z") =
        SocialInboxEvent(
            id = id,
            kind = SocialInboxEvent.FriendWatching,
            actor = actor,
            createdAt = at,
            payload = SocialInboxPayload(title = "Andor", season = 1, episode = 2),
        )

    private fun state(
        notifications: List<SocialNotification> = emptyList(),
        inbox: List<SocialInboxEvent> = emptyList(),
        profile: String = "me",
        loading: Boolean = false,
    ) = SocialUiState(
        activeProfileId = profile,
        notifications = notifications,
        inbox = inbox,
        friendPrefs = listOf(SocialFriendPrefs("f", notifyWhenWatching = true)),
        isLoading = loading,
    )

    private class Store {
        val saved = mutableMapOf<String, String>()
        fun ledger() = SocialPopupLedger({ saved[it] }, { profile, keys -> saved[profile] = keys }, freshnessMs = 30L * 60_000L)
    }

    @Test fun aProfileFirstSeenOnThisDeviceTreatsEverythingAsHistory() {
        val store = Store()
        val ledger = store.ledger()
        assertTrue(ledger.take(state(listOf(request("old"))), now).isEmpty())
        assertEquals(listOf("notice:new"), ledger.take(state(listOf(request("old"), request("new"))), now).map { it.key })
    }

    @Test fun eachPopsOnceEvenAcrossARestart() {
        val store = Store()
        store.ledger().take(state(), now)
        val first = store.ledger()
        assertEquals(1, first.take(state(listOf(request("a"))), now).size)
        assertTrue(first.take(state(listOf(request("a"))), now).isEmpty())
        assertTrue(store.ledger().take(state(listOf(request("a"))), now).isEmpty())
    }

    @Test fun onlyFriendsWithNotifyOnPopWhenTheyStartWatching() {
        val store = Store()
        val ledger = store.ledger()
        ledger.take(state(), now)
        val popped = ledger.take(state(inbox = listOf(watching("w1"), watching("w2", actor = quiet))), now)
        assertEquals(listOf("inbox:w1"), popped.map { it.key })
        assertEquals(SocialPopupKind.FriendWatching, popped.single().kind)
        assertEquals("S1 E2", popped.single().meta)
    }

    @Test fun staleOrReplayedStateNeverPops() {
        val store = Store()
        val ledger = store.ledger()
        ledger.take(state(), now)
        assertTrue(ledger.take(state(listOf(request("x")), loading = true), now).isEmpty())
        // Older than the freshness window: marked seen, not popped, and not popped later either.
        assertTrue(ledger.take(state(listOf(request("old", at = "2026-10-17T12:00:00Z"))), now).isEmpty())
        assertTrue(ledger.take(state(listOf(request("old", at = "2026-10-17T12:00:00Z"))), now).isEmpty())
        assertEquals(listOf("notice:x"), ledger.take(state(listOf(request("x"))), now).map { it.key })
    }

    @Test fun switchingProfilesKeepsEachProfilesOwnHistory() {
        val store = Store()
        val ledger = store.ledger()
        ledger.take(state(profile = "me"), now)
        ledger.take(state(profile = "kid"), now)
        assertEquals(1, ledger.take(state(listOf(request("a")), profile = "me"), now).size)
        assertTrue(ledger.take(state(listOf(request("a")), profile = "me"), now).isEmpty())
    }
}
