package com.nuvio.app.features.shuffle

import com.nuvio.app.core.sync.ZProfileSyncContributor
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Random Episode's availability switch, synced for a profile (mobile only; setup + settings
 * architecture pass, 2026-09-29).
 *
 * Only the profile-wide switch travels. The per-show choices stay on the device: they are keyed by
 * content id, grow without bound, and are the kind of state `EpisodeShuffle` already treats as
 * local. Registered only in `nuvio-z`'s `ZProfileSyncContributors.kt` - desktop has no shuffle.
 */
internal object EpisodeShuffleSync : ZProfileSyncContributor {
    private const val AVAILABLE = "available"

    override val key: String = "episode_shuffle"

    override val observed: StateFlow<*> get() = EpisodeShuffleRepository.uiState

    override fun ensureLoaded() {
        EpisodeShuffleRepository.ensureLoaded()
    }

    override fun export(): JsonElement = buildJsonObject {
        EpisodeShuffleRepository.ensureLoaded()
        put(AVAILABLE, EpisodeShuffleRepository.uiState.value.available)
    }

    override fun applyFromSync(element: JsonElement?) {
        val available = (element as? JsonObject)?.get(AVAILABLE)?.jsonPrimitive?.booleanOrNull ?: return
        EpisodeShuffleRepository.ensureLoaded()
        if (EpisodeShuffleRepository.uiState.value.available != available) {
            EpisodeShuffleRepository.setAvailable(available)
        }
    }
}
