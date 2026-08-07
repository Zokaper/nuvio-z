package com.nuvio.app.features.streams

import com.nuvio.app.features.playback.StickySourcePin
import kotlinx.serialization.json.Json

object BingeGroupCacheRepository {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Streamlined's sticky pins, deliberately **not** persisted.
     *
     * A pin skips the quality sheet outright, so a stored one silently turns Streamlined into
     * Instant for that season - for as long as the pin exists, on that device, with nothing in
     * the UI to clear it and no way to tell why the sheet stopped appearing. "Use this release
     * for the rest of the season" is a reasonable thing to mean for the rest of a sitting; it
     * is not a reasonable thing to mean forever.
     *
     * Keyed by [stickyContentId], which is a different key space from the binge-group cache
     * below - that one is keyed by `parentMetaId`, is genuinely a long-lived preference, and
     * keeps its storage untouched.
     */
    private val sessionPins = mutableMapOf<String, StickySourcePin>()

    fun saveSessionPin(contentId: String, pin: StickySourcePin) {
        if (pin.isEmpty) sessionPins.remove(contentId) else sessionPins[contentId] = pin
    }

    fun sessionPin(contentId: String): StickySourcePin? = sessionPins[contentId]

    fun clearSessionPins() = sessionPins.clear()

    fun save(contentId: String, bingeGroup: String) {
        save(contentId, StickySourcePin(bingeGroup = bingeGroup))
    }

    fun save(contentId: String, pin: StickySourcePin) {
        if (pin.isEmpty) return remove(contentId)
        BingeGroupCacheStorage.save(hashedKey(contentId), json.encodeToString(StickySourcePin.serializer(), pin))
    }

    fun get(contentId: String): StickySourcePin? {
        val stored = BingeGroupCacheStorage.load(hashedKey(contentId)) ?: return null
        return runCatching { json.decodeFromString(StickySourcePin.serializer(), stored) }
            .getOrElse { StickySourcePin(bingeGroup = stored.trim()) }
            .takeUnless(StickySourcePin::isEmpty)
    }

    fun stickyContentId(seriesId: String, seasonNumber: Int): String =
        "$seriesId|season:$seasonNumber"

    fun remove(contentId: String) {
        BingeGroupCacheStorage.remove(hashedKey(contentId))
    }

    private fun hashedKey(contentId: String): String {
        val hash = contentId.fold(0L) { acc, c -> acc * 31 + c.code }.toULong()
        return "binge_group_$hash"
    }
}
