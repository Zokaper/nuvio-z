@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nuvio.app.core.network

import platform.Network.nw_interface_type_cellular
import platform.Network.nw_interface_type_wifi
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_constrained
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_uses_interface_type
import platform.darwin.dispatch_get_main_queue

actual object NetworkQualityPlatform {
    private var latest = PlatformNetworkQuality(NetworkConnectionType.UNKNOWN, false, "ios:unknown")
    private val monitor = nw_path_monitor_create().also { monitor ->
        nw_path_monitor_set_update_handler(monitor) { path ->
            if (path == null || nw_path_get_status(path) != nw_path_status_satisfied) {
                latest = PlatformNetworkQuality(NetworkConnectionType.OFFLINE, false, "ios:offline")
            } else {
                val type = when {
                    nw_path_uses_interface_type(path, nw_interface_type_wifi) -> NetworkConnectionType.WIFI
                    nw_path_uses_interface_type(path, nw_interface_type_cellular) -> NetworkConnectionType.CELLULAR
                    else -> NetworkConnectionType.ETHERNET
                }
                val metered = nw_path_is_expensive(path) || nw_path_is_constrained(path)
                latest = PlatformNetworkQuality(type, metered, "ios:$type:$metered")
            }
        }
        nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
        nw_path_monitor_start(monitor)
    }

    actual fun current(): PlatformNetworkQuality = latest
}
