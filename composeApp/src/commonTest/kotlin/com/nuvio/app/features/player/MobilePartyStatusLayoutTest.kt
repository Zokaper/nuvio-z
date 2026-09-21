package com.nuvio.app.features.player

import com.nuvio.app.features.watchparty.PartyStatusKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MobilePartyStatusLayoutTest {
    private val withEverything = PartyStatusBridgeState(
        visible = true,
        kind = "WaitingForBuffering",
        text = "Waiting for main debug to buffer",
        detail = "Pausing until they catch up",
        tone = "waiting",
        action = "wtDontWait",
        actionLabel = "Don't wait",
    )

    private fun layout(
        status: PartyStatusBridgeState = withEverything,
        controlsVisible: Boolean = true,
        locked: Boolean = false,
        gesture: Boolean = false,
        short: Boolean = false,
    ) = mobilePartyStatusLayout(status, controlsVisible, locked, gesture, short)

    @Test fun withTheChromeUpThePillIsFullAndStaysUnderTheTitleRow() {
        assertEquals(MobilePartyStatusLayout(compact = false, raised = false, showDetail = true, showActions = true), layout())
    }

    @Test fun withTheChromeGoneItCompactsRisesAndDropsItsSecondaryLines() {
        assertEquals(MobilePartyStatusLayout(compact = true, raised = true, showDetail = false, showActions = false), layout(controlsVisible = false))
    }

    @Test fun aRunningGestureKeepsTheSlotSoTheCompactPillStaysDown() {
        val l = layout(controlsVisible = false, gesture = true)
        assertTrue(l.compact)
        assertFalse(l.raised)
    }

    @Test fun lockedWithholdsButtonsEvenIfTheRuntimeStillSaysControlsVisible() {
        val l = layout(controlsVisible = true, locked = true)
        assertFalse(l.showActions)
        assertTrue(l.compact)
    }

    @Test fun aShortWindowNeverDrawsTheDetailLine() {
        val l = layout(short = true)
        assertFalse(l.showDetail)
        assertTrue(l.showActions)
    }

    @Test fun blankDetailAndNoActionsDrawNeither() {
        val l = layout(status = PartyStatusBridgeState(visible = true, text = "Playing together"))
        assertFalse(l.showDetail)
        assertFalse(l.showActions)
    }

    @Test fun everyStatusKindHasADeliberateGlyph() {
        // Party is the fallback for a name this renderer has never heard of; no real kind may land there by accident.
        PartyStatusKind.entries.forEach { kind ->
            assertTrue(partyStatusGlyph(kind.name) != PartyStatusGlyph.Party, "$kind fell through to the fallback glyph")
        }
        assertEquals(PartyStatusGlyph.Party, partyStatusGlyph(""))
    }
}
