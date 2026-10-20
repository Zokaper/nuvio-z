package com.nuvio.app.features.language

import com.nuvio.app.core.sync.ZProfileSyncContributor
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * The language settings, synced per profile in the Z blob under `z_features.language`.
 *
 * Exported only once the profile has saved them (see [LanguagePreferencesRepository]); an absent key
 * leaves this device alone. The old fields travel separately in the player blob, written on every save.
 */
internal object LanguagePreferencesSync : ZProfileSyncContributor {
    private val json = Json { ignoreUnknownKeys = true }

    override val key: String = "language"

    override val observed: StateFlow<*> get() = LanguagePreferencesRepository.uiState

    override fun ensureLoaded() {
        LanguagePreferencesRepository.ensureLoaded()
    }

    override fun export(): JsonElement? =
        LanguagePreferencesRepository.storedPreferences()
            ?.let { json.encodeToJsonElement(StoredLanguagePreferences.from(it)) }

    override fun applyFromSync(element: JsonElement?) {
        element ?: return
        val stored = runCatching { json.decodeFromJsonElement<StoredLanguagePreferences>(element) }.getOrNull() ?: return
        LanguagePreferencesRepository.applyFromSync(stored.toPreferences())
    }
}
