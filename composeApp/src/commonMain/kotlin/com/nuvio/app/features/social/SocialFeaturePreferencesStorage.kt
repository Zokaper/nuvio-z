package com.nuvio.app.features.social

/**
 * Where the social layer's master preference lives on disk.
 *
 * ⚠ **Deliberately its own store rather than a field on `PlayerSettingsStorage`.** Whether the
 * social product layer exists is an application-level feature preference, not a player setting,
 * and the sync layer is a registry of independent feature repositories - `ProfileSettingsSync`
 * carries a dozen of them, each with its own payload. Bolting this onto the player blob would
 * also drag in that blob's special cases, notably `mergeMonotonicSyncInt`, which is correct for
 * `setup_wizard_completed_revision` (it may only rise) and exactly wrong for a preference the
 * user may legitimately turn back off.
 *
 * The shape is `EpisodeReleaseNotificationsStorage`, which is the smallest complete example of
 * the pattern in this repository.
 *
 * Every actual scopes its key with `ProfileScopedKey`, so two profiles on one machine can differ
 * - social identity is already per-profile, and a machine-global toggle would be wrong. It is a
 * plain synchronous read, which is what lets the preference be resolved before any social
 * runtime starts.
 */
internal expect object SocialFeaturePreferencesStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}
