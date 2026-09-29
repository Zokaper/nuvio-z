package com.nuvio.app.features.home

import com.nuvio.app.core.sync.ZProfileSyncContributor
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Home hero on/off, synced for a profile (setup + settings architecture pass, 2026-09-29).
 *
 * Upstream keeps `heroEnabled` out of the shared home-catalog channel, so the choice silently stayed
 * on the device it was made on - and Advanced Setup's Home step now asks it. It rides in the Z
 * blob rather than in `SyncHomeCatalogPayload`, which upstream owns and the TV port also reads.
 */
internal object HomePresentationSync : ZProfileSyncContributor {
    private const val HERO_ENABLED = "hero_enabled"

    override val key: String = "home_presentation"

    override val observed: StateFlow<*> get() = HomeCatalogSettingsRepository.uiState

    override fun ensureLoaded() {
        HomeCatalogSettingsRepository.ensureLoaded()
    }

    override fun export(): JsonElement = buildJsonObject {
        put(HERO_ENABLED, HomeCatalogSettingsRepository.snapshot().heroEnabled)
    }

    override fun applyFromSync(element: JsonElement?) {
        val heroEnabled = (element as? JsonObject)?.get(HERO_ENABLED)?.jsonPrimitive?.booleanOrNull ?: return
        HomeCatalogSettingsRepository.setHeroEnabled(heroEnabled)
    }
}
