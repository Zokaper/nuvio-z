package com.nuvio.app.features.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Open the player's source panel", asked for from outside the player.
 *
 * One caller today: the toast shown when Streamlined reuses a cached link instead of
 * showing its quality sheet. That toast is raised at the moment `StreamRoute` pops itself,
 * so by the time the user reads it there is no route left to send them back to - and the
 * affordance they actually want, "Change source", already exists inside the player.
 *
 * A counter rather than a boolean, so two consecutive requests are two events. A flag would
 * need clearing, and the clear is what gets forgotten.
 */
object PlayerSourcePanelRequest {
    private val _requests = MutableStateFlow(0L)

    /** Bumped on every request; the player acts on a change, never on the value. */
    val requests: StateFlow<Long> = _requests.asStateFlow()

    fun request() {
        _requests.value += 1L
    }
}
