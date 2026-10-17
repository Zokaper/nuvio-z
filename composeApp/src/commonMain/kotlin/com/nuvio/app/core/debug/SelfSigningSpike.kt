package com.nuvio.app.core.debug

/**
 * Phase 2 (`PLAN-nuvio-z-self-signing.md`): Nuvio Z keeping itself signed without SideStore.
 *
 * Every field is non-null only in an iOS build linked with the installer's Rust core (`NUVIO_IOS_SELF_SIGNING`),
 * which is also the availability flag for the debug rows in Advanced settings, the same way
 * [SelfTestHooks.launch] is. All return the library's JSON.
 */
internal object SelfSigningSpike {

    /** Connects to the phone through LocalDevVPN by both routes and lists its apps. */
    var probe: (suspend () -> String)? = null

    /** This app's bundle ids and when the profile embedded in it expires. Reads the bundle only. */
    var status: (suspend () -> String)? = null

    /** The newest profile the phone has installed for each of this app's bundle ids, over LocalDevVPN. */
    var installedProfiles: (suspend () -> String)? = null

    /** Renews this app the way SideStore does: Apple sign-in, fresh profiles, installed on the phone. */
    var refresh: (suspend (email: String, password: String) -> String)? = null

    /** True while [refresh] waits for the code Apple sent to the account's devices. */
    var waitingForCode: (() -> Boolean)? = null

    /** Answers that wait with a code, or null to give up. */
    var answerCode: ((String?) -> Unit)? = null
}
