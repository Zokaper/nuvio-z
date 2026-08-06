package com.nuvio.app.core.network

enum class NetworkConnectionType {
    UNKNOWN,
    WIFI,
    CELLULAR,
    ETHERNET,
    OFFLINE,
}

data class PlatformNetworkQuality(
    val connectionType: NetworkConnectionType,
    val isMetered: Boolean,
    /** Session-stable identity. It must change when the active network changes. */
    val networkId: String,
)

expect object NetworkQualityPlatform {
    fun current(): PlatformNetworkQuality
}
