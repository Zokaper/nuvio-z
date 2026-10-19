package com.nuvio.app.features.social

import com.nuvio.app.core.sync.ZProfileSyncContributor
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Social settings -> Home, synced for a profile: "Show friends on Home" (Social V2, 2026-10-16) and
 * "Show recommendations on Home" (2026-10-19).
 *
 * Rides in the Z blob like [com.nuvio.app.features.home.HomePresentationSync]. Each switch is exported
 * only once the user has moved it, so an untouched device never overwrites another's choice with the
 * default; a key that is absent leaves this device's value alone.
 */
internal object SocialHomeSync : ZProfileSyncContributor {
    private const val SHOW_FRIENDS_ON_HOME = "show_friends_on_home"
    private const val SHOW_RECOMMENDED_ON_HOME = "show_recommended_on_home"

    override val key: String = "social_home"

    override val observed: StateFlow<*> get() = SocialFeaturePreferencesRepository.uiState

    override fun ensureLoaded() {
        SocialFeaturePreferencesRepository.ensureLoaded()
    }

    override fun export(): JsonElement? {
        val friends = SocialFeaturePreferencesRepository.storedShowFriendsOnHome()
        val recommended = SocialFeaturePreferencesRepository.storedShowRecommendedOnHome()
        if (friends == null && recommended == null) return null
        return buildJsonObject {
            friends?.let { put(SHOW_FRIENDS_ON_HOME, it) }
            recommended?.let { put(SHOW_RECOMMENDED_ON_HOME, it) }
        }
    }

    override fun applyFromSync(element: JsonElement?) {
        val values = element as? JsonObject ?: return
        values[SHOW_FRIENDS_ON_HOME]?.jsonPrimitive?.booleanOrNull
            ?.let(SocialFeaturePreferencesRepository::setShowFriendsOnHome)
        values[SHOW_RECOMMENDED_ON_HOME]?.jsonPrimitive?.booleanOrNull
            ?.let(SocialFeaturePreferencesRepository::setShowRecommendedOnHome)
    }
}
