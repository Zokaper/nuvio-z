package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PartyNominalSpeedTest {

    @Test
    fun driftNudgeIsHiddenBehindThePartySpeed() {
        // The S25 on 2026-09-18: the engine at 1.0035 while the party plays at 1x.
        assertEquals(1f, partyCorrectionNominalSpeed(actual = 1.0035f, nominal = 1f))
        assertEquals(1.5f, partyCorrectionNominalSpeed(actual = 1.4925f, nominal = 1.5f))
    }

    @Test
    fun noCorrectionMeansNothingToHide() {
        assertNull(partyCorrectionNominalSpeed(actual = 1f, nominal = 1f))
        assertNull(partyCorrectionNominalSpeed(actual = 1.25f, nominal = 1.25f))
        assertNull(partyCorrectionNominalSpeed(actual = 1.0004f, nominal = 1f), "float noise is not a correction")
    }
}
