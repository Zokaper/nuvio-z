package com.nuvio.app.features.watchparty

import com.nuvio.app.navigation.WatchPartyLobbyRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchPartyRouteExitGuardTest {
    @Test fun unguardedNativeAndProgrammaticRemovalsCannotBypassDeparture() {
        val route = WatchPartyLobbyRoute(partyId = "party")
        var questions = 0
        WatchPartyRouteExitGuard.register(route) { questions++ }
        try {
            assertTrue(WatchPartyRouteExitGuard.shouldBlockRemoval(route))
            assertEquals(1, questions)
            WatchPartyRouteExitGuard.authorizeRemoval(route)
            assertFalse(WatchPartyRouteExitGuard.shouldBlockRemoval(route))
            assertTrue(WatchPartyRouteExitGuard.shouldBlockRemoval(route))
            assertEquals(2, questions)
        } finally {
            WatchPartyRouteExitGuard.unregister(route)
        }
        assertTrue(WatchPartyRouteExitGuard.shouldBlockRemoval(route))
    }
}
