package com.nuvio.app.core.debug

/**
 * Phase 2 spike (`PLAN-nuvio-z-self-signing.md`): Nuvio Z reaching the phone's own services from inside the
 * app, the first step towards signing and updating itself without SideStore.
 *
 * Non-null only in an iOS build linked with the installer's Rust core (`NUVIO_IOS_SELF_SIGNING`), which is
 * also the availability flag for the debug row in Advanced settings, the same way [SelfTestHooks.launch] is.
 */
internal object SelfSigningSpike {

    /** Connects to the phone through LocalDevVPN by both routes and returns the JSON report. */
    var probe: (suspend () -> String)? = null
}
