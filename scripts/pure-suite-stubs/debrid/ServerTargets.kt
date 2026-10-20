// Data-only media-server neighbours; no server behavior is exercised by this group.
package com.nuvio.app.features.servers

import kotlinx.serialization.Serializable

@Serializable
data class ServerItemRef(val connectionId: String, val itemId: String)
@Serializable
data class ServerPlaybackTarget(val item: ServerItemRef, val mediaSourceId: String?)
