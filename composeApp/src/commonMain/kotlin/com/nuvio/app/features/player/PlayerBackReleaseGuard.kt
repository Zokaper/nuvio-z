package com.nuvio.app.features.player

/**
 * Routes a system back - on desktop, **Escape** - to whichever route is on top.
 *
 * Two routes own their own exit, and both fail closed while their handler is not registered:
 *
 *  - the player, which must release the native surface before anything navigates;
 *  - the Watch Together lobby, which must never be dismissed out from under a live party.
 *
 * ⚠ **The lobby half is hardware Bug 1 (2026-09-15).** Escape reached this function as a plain
 * `pop()`. The lobby's own back arrow asks to leave or end the party, but the system back skipped
 * that entirely: the route went, the membership, the poll, the realtime channel and the session
 * stayed, and nothing on screen was left that could reach them - an orphaned live party. A guest in
 * that state was later started by a host it could not see; a host left everyone waiting on a lobby
 * nobody was in. The lobby now answers the system back exactly as it answers its own arrow.
 */
internal fun dispatchNavigationBack(
    isPlayerRoute: Boolean,
    playerBack: (() -> Unit)?,
    pop: () -> Unit,
    isPartyLobbyRoute: Boolean = false,
    partyLobbyBack: (() -> Unit)? = null,
) {
    when {
        isPlayerRoute -> playerBack?.invoke()
        isPartyLobbyRoute -> partyLobbyBack?.invoke()
        else -> pop()
    }
}

/**
 * Serializes release-before-navigation attempts.
 *
 * Every synchronous callback boundary deliberately contains [Throwable]: even an [Error]
 * must not escape after [inFlight] changes and leave Back permanently wedged. Each accepted
 * request also owns a single terminal callback claim, so late or duplicate callbacks cannot act
 * on a retry or navigate twice. Failures keep navigation closed and restore retryability where a
 * retry is still safe.
 */
internal class PlayerBackReleaseGuard {
    private var inFlight: Boolean = false
    private var nextAttemptId: Long = 0L
    private var activeAttemptId: Long? = null

    fun request(
        canStart: () -> Boolean,
        releaseBeforeBack: (
            onReleased: () -> Unit,
            onReleaseFailed: (String) -> Unit,
        ) -> Unit,
        beforePop: () -> Unit,
        pop: () -> Boolean,
    ) {
        if (inFlight) return
        val allowed = try {
            canStart()
        } catch (_: Throwable) {
            // Deliberately contain synchronous callback failures so the Back guard
            // remains fail-closed instead of escaping with partially updated state.
            false
        }
        if (!allowed) return

        inFlight = true
        val attemptId = ++nextAttemptId
        activeAttemptId = attemptId

        fun claimActiveAttempt(): Boolean {
            if (activeAttemptId != attemptId) return false
            activeAttemptId = null
            return true
        }

        val complete = complete@{
            if (!claimActiveAttempt()) return@complete
            try {
                beforePop()
                if (!pop()) inFlight = false
            } catch (_: Throwable) {
                inFlight = false
            }
        }
        val fail = fail@{ _: String ->
            if (!claimActiveAttempt()) return@fail
            inFlight = false
        }
        try {
            releaseBeforeBack(complete, fail)
        } catch (_: Throwable) {
            if (claimActiveAttempt()) inFlight = false
        }
    }
}
