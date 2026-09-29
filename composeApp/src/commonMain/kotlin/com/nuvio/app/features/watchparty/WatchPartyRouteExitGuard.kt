package com.nuvio.app.features.watchparty

import com.nuvio.app.navigation.WatchPartyLobbyRoute

/** Native navigation asks the live lobby owner to show its guarded departure flow. */
object WatchPartyRouteExitGuard {
    private val handlers = mutableMapOf<WatchPartyLobbyRoute, () -> Unit>()
    private val authorizedRemovals = mutableSetOf<WatchPartyLobbyRoute>()

    fun register(route: WatchPartyLobbyRoute, handler: () -> Unit) {
        handlers[route] = handler
    }

    fun unregister(route: WatchPartyLobbyRoute) {
        handlers.remove(route)
        authorizedRemovals.remove(route)
    }

    fun authorizeRemoval(route: WatchPartyLobbyRoute) {
        authorizedRemovals.add(route)
    }

    fun revokeRemoval(route: WatchPartyLobbyRoute) {
        authorizedRemovals.remove(route)
    }

    /** True means Swift must retain the route. No live owner also fails closed. */
    fun shouldBlockRemoval(route: WatchPartyLobbyRoute): Boolean {
        if (authorizedRemovals.remove(route)) return false
        handlers[route]?.invoke()
        return true
    }
}
