package com.nuvio.app.features.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OptimisticSettingTest {

    @Test
    fun aChoiceShowsImmediatelyBeforeTheServerAnswers() {
        val state = OptimisticSetting<Boolean>().begin(false)
        assertEquals(false, state.shown(confirmed = true))
        assertTrue(state.saving)
    }

    @Test
    fun successKeepsTheChoiceUntilTheConfirmedValueCatchesUp() {
        val started = OptimisticSetting<Boolean>().begin(false)
        val accepted = started.succeeded(started.generation)
        // The confirmed value has not refreshed yet: no flicker back to it.
        assertEquals(false, accepted.shown(confirmed = true))
        assertFalse(accepted.saving)
        val caughtUp = accepted.observed(false)
        assertNull(caughtUp.pending)
        assertEquals(false, caughtUp.shown(confirmed = false))
    }

    @Test
    fun aRefusalRevertsToTheConfirmedValueAndAsksToTellTheUser() {
        val started = OptimisticSetting<Boolean>().begin(false)
        val failure = started.failed(started.generation)
        assertTrue(failure.notify)
        assertNull(failure.state.pending)
        assertEquals(true, failure.state.shown(confirmed = true))
    }

    @Test
    fun aRefusalOfASupersededWriteChangesNothing() {
        val first = OptimisticSetting<String>().begin("direct")
        val firstGeneration = first.generation
        val second = first.begin("disabled")
        val failure = second.failed(firstGeneration)
        assertFalse(failure.notify)
        assertEquals("disabled", failure.state.shown(confirmed = "approval"))
        // Nor does an old success settle the newer write.
        assertTrue(second.succeeded(firstGeneration).saving)
    }

    @Test
    fun anUnrelatedConfirmedChangeWhileSavingDoesNotOverrideTheChoice() {
        val started = OptimisticSetting<String>().begin("direct")
        assertEquals("direct", started.observed("disabled").shown(confirmed = "disabled"))
    }

    @Test
    fun aNewerConfirmedValueAfterASettledWriteWins() {
        val started = OptimisticSetting<String>().begin("direct")
        val settled = started.succeeded(started.generation)
        // Another device changed it again before this one's refresh landed.
        val later = settled.observed("disabled")
        assertNull(later.pending)
        assertEquals("disabled", later.shown(confirmed = "disabled"))
    }

    @Test
    fun withNothingPendingTheConfirmedValueIsShown() {
        val idle = OptimisticSetting<Boolean>()
        assertEquals(true, idle.shown(confirmed = true))
        assertFalse(idle.saving)
        assertEquals(idle, idle.observed(false))
        assertFalse(idle.failed(0).notify)
    }
}
