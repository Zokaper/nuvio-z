package com.nuvio.app.features.social

import platform.Foundation.NSUserDefaults

actual object SocialStorage {
    actual fun loadPayload(profileId: String): String? = NSUserDefaults.standardUserDefaults.stringForKey("social_state_$profileId")
    actual fun savePayload(profileId: String, payload: String) { NSUserDefaults.standardUserDefaults.setObject(payload, forKey="social_state_$profileId") }
    actual fun loadOutbox(profileId: String): String? = NSUserDefaults.standardUserDefaults.stringForKey("social_outbox_$profileId")
    actual fun saveOutbox(profileId: String, payload: String) { NSUserDefaults.standardUserDefaults.setObject(payload, forKey="social_outbox_$profileId") }
}

