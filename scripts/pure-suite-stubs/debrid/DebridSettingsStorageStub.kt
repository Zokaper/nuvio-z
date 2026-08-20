// In-memory stand-in for the `expect object DebridSettingsStorage`. The real one is per-platform
// (SharedPreferences / NSUserDefaults / DesktopStorage) and its expect declaration cannot be
// compiled outside a multiplatform build.
//
// DebridSettingsRepository.kt is compiled from the shipped source here because
// `DebridStreamPreferences.normalized()` lives in it and every filter runs through it. This stub
// exists only so that file links; the pipeline under test never reads it.

package com.nuvio.app.features.debrid

internal object DebridSettingsStorage {
    private val values = mutableMapOf<String, Any>()

    private fun put(key: String, value: Any) {
        values[key] = value
    }

    private inline fun <reified T> get(key: String): T? = values[key] as? T

    fun loadEnabled(): Boolean? = get("enabled")
    fun saveEnabled(enabled: Boolean) = put("enabled", enabled)
    fun loadCloudLibraryEnabled(): Boolean? = get("cloud_library")
    fun saveCloudLibraryEnabled(enabled: Boolean) = put("cloud_library", enabled)
    fun loadPreferredResolverProviderId(): String? = get("preferred_resolver")
    fun savePreferredResolverProviderId(providerId: String) = put("preferred_resolver", providerId)
    fun loadProviderApiKey(providerId: String): String? = get("api_key_$providerId")
    fun saveProviderApiKey(providerId: String, apiKey: String) = put("api_key_$providerId", apiKey)
    fun loadInstantPlaybackPreparationLimit(): Int? = get("prepare_limit")
    fun saveInstantPlaybackPreparationLimit(limit: Int) = put("prepare_limit", limit)
    fun loadStreamMaxResults(): Int? = get("max_results")
    fun saveStreamMaxResults(maxResults: Int) = put("max_results", maxResults)
    fun loadStreamSortMode(): String? = get("sort_mode")
    fun saveStreamSortMode(mode: String) = put("sort_mode", mode)
    fun loadStreamMinimumQuality(): String? = get("minimum_quality")
    fun saveStreamMinimumQuality(quality: String) = put("minimum_quality", quality)
    fun loadStreamDolbyVisionFilter(): String? = get("dolby_vision_filter")
    fun saveStreamDolbyVisionFilter(filter: String) = put("dolby_vision_filter", filter)
    fun loadStreamHdrFilter(): String? = get("hdr_filter")
    fun saveStreamHdrFilter(filter: String) = put("hdr_filter", filter)
    fun loadStreamCodecFilter(): String? = get("codec_filter")
    fun saveStreamCodecFilter(filter: String) = put("codec_filter", filter)
    fun loadStreamPreferences(): String? = get("preferences")
    fun saveStreamPreferences(preferences: String) = put("preferences", preferences)
    fun loadStreamNameTemplate(): String? = get("name_template")
    fun saveStreamNameTemplate(template: String) = put("name_template", template)
    fun loadStreamDescriptionTemplate(): String? = get("description_template")
    fun saveStreamDescriptionTemplate(template: String) = put("description_template", template)
    fun loadStreamPreferenceScope(): String? = get("preference_scope")
    fun saveStreamPreferenceScope(scope: String) = put("preference_scope", scope)
}
