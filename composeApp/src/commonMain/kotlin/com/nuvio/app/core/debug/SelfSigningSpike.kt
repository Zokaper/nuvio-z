package com.nuvio.app.core.debug

/**
 * Phase 2 (`PLAN-nuvio-z-self-signing.md`): Nuvio Z keeping itself signed without SideStore.
 *
 * [platform] is non-null only in an iOS build linked with the installer's Rust core (`NUVIO_IOS_SELF_SIGNING`),
 * which is also the availability flag for the debug rows in Advanced settings, the same way
 * [SelfTestHooks.launch] is. Results are the library's JSON unless a member says otherwise.
 */
internal object SelfSigningSpike {
    var platform: SelfSigningPlatform? = null
}

internal interface SelfSigningPlatform {
    /** Connects to the phone through LocalDevVPN by both routes and lists its apps. */
    suspend fun probe(): String

    /** This app's bundle ids and when the profile embedded in it expires. Reads the bundle only. */
    suspend fun status(): String

    /** The newest profile the phone has installed for each of this app's bundle ids, over LocalDevVPN. */
    suspend fun installedProfiles(): String

    /** Whether LocalDevVPN's address answers right now (a quick connect, no Apple). */
    suspend fun vpnReachable(): Boolean

    /**
     * Renews this app the way SideStore does (Apple sign-in, fresh profiles installed on the phone), waiting for a
     * person: Apple's code is answered through [waitingForCode] and [answerCode]. A null [password] means the saved
     * one; [remember] saves a typed one to this iPhone's Keychain for unattended renewals.
     */
    suspend fun refresh(email: String, password: String?, remember: Boolean): String

    /** Renews with the saved account and never waits for a person (Shortcuts, opening the app, background). */
    suspend fun refreshSaved(trigger: String): String

    /** True while a sign-in waits for the code Apple sent to the account's devices. */
    fun waitingForCode(): Boolean

    /** Answers that wait with a code, or null to give up. */
    fun answerCode(code: String?)

    /** While an update waits for the person to pick certificates to revoke: the list, otherwise null. */
    fun pendingCertificates(): String?

    /** Answers that wait with serials to revoke, or null to stop. */
    fun answerCertificates(serials: List<String>?)

    /** Signs and installs a newer IPA (https URL or a path on this phone) over this app with the saved account. */
    suspend fun installUpdate(source: String, sha256: String?): String

    /** The running update's `{ stage, fraction }`, otherwise null. */
    fun updateProgress(): String?

    /** The IPA the installer left for the update test, if it is there. A path. */
    fun updateTestIpa(): String?

    /** The email of the account saved for unattended renewals, if any. Never the password. */
    fun savedAccount(): String?

    fun forgetAccount()

    /** Forgets this iPhone's sign-in identity with Apple (the fix for -22410 "try again"). */
    suspend fun resetSignIn(): String

    /** The last renewal as recorded `{ at, trigger, ok, kind, code, summary, route, expires }`, or null. */
    fun lastRefresh(): String?

    /** Every check in order, as one plain-text block for a remote tester to paste back. */
    suspend fun testReport(): String

    /** The hosted daily-refresh shortcut (an iCloud link), or null when only the guided build exists. */
    suspend fun refreshShortcutUrl(): String?
}
