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
 * "Show friends on Home" (Social settings -> Home), synced for a profile (Social V2, 2026-10-16).
 *
 * Rides in the Z blob like [com.nuvio.app.features.home.HomePresentationSync]. Exports nothing until
 * the user has moved the switch, so an untouched device never overwrites another's choice with the
 * default.
 */
internal object SocialHomeSync : ZProfileSyncContributor {
    private const val SHOW_FRIENDS_ON_HOME = "show_friends_on_home"

    override val key: String = "social_home"

    override val observed: StateFlow<*> get() = SocialFeaturePreferencesRepository.uiState

    override fun ensureLoaded() {
        SocialFeaturePreferencesRepository.ensureLoaded()
    }

    override fun export(): JsonElement? {
        val show = SocialFeaturePreferencesRepository.storedShowFriendsOnHome() ?: return null
        return buildJsonObject { put(SHOW_FRIENDS_ON_HOME, show) }
    }

    override fun applyFromSync(element: JsonElement?) {
        val show = (element as? JsonObject)?.get(SHOW_FRIENDS_ON_HOME)?.jsonPrimitive?.booleanOrNull ?: return
        SocialFeaturePreferencesRepository.setShowFriendsOnHome(show)
    }
}
