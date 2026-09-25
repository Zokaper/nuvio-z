package com.nuvio.app.features.setup

import platform.Foundation.NSUserDefaults

internal actual object DeviceSetupStorage {
    private const val revisionKey = "nuvio_device_setup_revision"

    actual fun loadRevision(): Int? {
        val defaults = NSUserDefaults.standardUserDefaults
        if (defaults.objectForKey(revisionKey) == null) return null
        return defaults.integerForKey(revisionKey).toInt()
    }

    actual fun saveRevision(revision: Int) {
        NSUserDefaults.standardUserDefaults.setInteger(revision.toLong(), forKey = revisionKey)
    }
}
