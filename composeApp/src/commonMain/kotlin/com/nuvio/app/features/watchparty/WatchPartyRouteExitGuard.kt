package com.nuvio.app.features.watchparty

import com.nuvio.app.navigation.WatchPartyLobbyRoute

/** Native navigation asks the live lobby owner to show its guarded departure flow. */
object WatchPartyRouteExitGuard {
    private val handlers = mutableMapOf<WatchPartyLobbyRoute, () -> Unit>()

    fun register(route: WatchPartyLobbyRoute, handler: () -> Unit) {
        handlers[route] = handler
    }

    fun unregister(route: WatchPartyLobbyRoute) {
        handlers.remove(route)
    }

    fun request(route: WatchPartyLobbyRoute): Boolean {
        val handler = handlers[route] ?: return false
        handler()
        return true
    }
}
