package com.nuvio.app.features.social

import platform.Foundation.NSUserDefaults

internal actual object PhoneSocialAlertsStorage {
    private const val payloadKey = "nuvio_social_phone_alerts"

    actual fun load(): String? = NSUserDefaults.standardUserDefaults.stringForKey(payloadKey)

    actual fun save(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = payloadKey)
    }
}

internal actual val phoneSocialAlertsDescription: String =
    "Banners while Nuvio is open. iPhones get no system notifications for these"
