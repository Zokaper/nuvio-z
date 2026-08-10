package com.nuvio.app.core.network

import platform.Foundation.NSUserDefaults

internal actual object NetworkQualityStorage {
    private const val estimatesKey = "nuvio_network_quality_estimates_json"

    // Not profile-scoped, unlike most storages here. The measured speed of a network belongs
    // to the device, and scoping it would make the first play on every profile a guess again.
    actual fun loadEstimatesJson(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(estimatesKey)

    actual fun saveEstimatesJson(json: String) {
        NSUserDefaults.standardUserDefaults.setObject(json, forKey = estimatesKey)
    }
}
