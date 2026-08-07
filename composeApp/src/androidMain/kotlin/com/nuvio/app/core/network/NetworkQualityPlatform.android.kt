package com.nuvio.app.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

actual object NetworkQualityPlatform {
    private var context: Context? = null

    fun initialize(context: Context) {
        this.context = context.applicationContext
    }

    actual fun current(): PlatformNetworkQuality {
        val manager = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return PlatformNetworkQuality(NetworkConnectionType.UNKNOWN, false, "android:unknown")
        val network = manager.activeNetwork
            ?: return PlatformNetworkQuality(NetworkConnectionType.OFFLINE, false, "android:offline")
        val capabilities = manager.getNetworkCapabilities(network)
            ?: return PlatformNetworkQuality(NetworkConnectionType.UNKNOWN, false, "android:${network.networkHandle}")
        val type = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkConnectionType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkConnectionType.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkConnectionType.ETHERNET
            else -> NetworkConnectionType.UNKNOWN
        }
        return PlatformNetworkQuality(
            connectionType = type,
            isMetered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            networkId = "android:${network.networkHandle}:$type",
        )
    }
}
