package com.nuvio.app.features.language

/**
 * Where the language settings live on disk: their own store, per profile, rather than fields on
 * `PlayerSettingsStorage` - the shape of `SocialFeaturePreferencesStorage`, for the same reasons.
 * Every actual scopes its key with `ProfileScopedKey`.
 */
internal expect object LanguagePreferencesStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}
