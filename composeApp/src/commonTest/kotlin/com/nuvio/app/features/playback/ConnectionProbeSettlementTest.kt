package com.nuvio.app.features.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionProbeSettlementTest {
    @Test
    fun `deadline settles the automatic decision but withholds the sheet figure`() {
        val state = ConnectionProbeSettlement()
            .onDeadline(nonce = 0)
            .stateFor(nonce = 0)

        assertTrue(state.isDecisionSettled)
        assertFalse(state.isFigureSettled)
    }

    @Test
    fun `a probe landing after the deadline finally settles the figure`() {
        val state = ConnectionProbeSettlement()
            .onDeadline(nonce = 0)
            .onProbeFinished(nonce = 0)
            .stateFor(nonce = 0)

        assertTrue(state.isDecisionSettled)
        assertTrue(state.isFigureSettled)
    }

    @Test
    fun `a late result from an older ask cannot regress the current deadline`() {
        val settlement = ConnectionProbeSettlement()
            .onDeadline(nonce = 1)
            .onProbeFinished(nonce = 0)

        val current = settlement.stateFor(nonce = 1)
        assertTrue(current.isDecisionSettled)
        assertFalse(current.isFigureSettled)

        val finished = settlement.onProbeFinished(nonce = 1).stateFor(nonce = 1)
        assertTrue(finished.isDecisionSettled)
        assertTrue(finished.isFigureSettled)
    }
}
