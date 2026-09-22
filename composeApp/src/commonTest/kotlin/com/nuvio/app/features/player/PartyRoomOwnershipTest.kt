package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PartyRoomOwnershipTest {
    @Test
    fun backClosesOpenRoomBeforeExitingPlayer() {
        assertEquals(PartyRoomBackDecision.CloseRoom, partyRoomBackDecision(roomOpen = true))
        assertEquals(PartyRoomBackDecision.ExitPlayer, partyRoomBackDecision(roomOpen = false))
    }
}
