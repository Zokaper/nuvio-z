package com.nuvio.app.features.auth

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * "Sign in" from a session that lost its login by itself and kept running on cached profiles - the
 * signed-out toast, Settings -> Account.
 *
 * `AppGate` collects these and shows the sign-in screen exactly as its own "no session" transition
 * does. The only way there before was Sign Out, which wipes the account data first - including
 * anything recorded locally while the session was gone. A request rather than a callback, for the
 * reason `ZSettingsNavigation` gives: the askers sit far below the gate.
 */
object SignInNavigation {
    private val requestChannel = Channel<Unit>(Channel.CONFLATED)

    internal val requests: Flow<Unit> = requestChannel.receiveAsFlow()

    internal fun request() {
        requestChannel.trySend(Unit)
    }
}
