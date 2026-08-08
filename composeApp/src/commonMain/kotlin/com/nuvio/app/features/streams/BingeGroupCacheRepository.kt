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

    /**
     * Instant's remembered resolution for a series, for this sitting only.
     *
     * Deliberately **not** a [StickySourcePin]: a pin carrying only a resolution reads as
     * [StickySourcePin.isEmpty], so it would be dropped on save, and a non-empty one would
     * make Streamlined skip its quality sheet. This is a weaker, separate idea - "keep giving
     * me the resolution you gave me last episode" - and it must not leak into that path.
     *
     * Keyed by `parentMetaId`, because the complaint it answers is episode-to-episode churn
     * within one show, not within one season.
     */
    private val sessionInstantHeights = mutableMapOf<String, Int>()

    fun saveSessionInstantHeight(parentMetaId: String, height: Int) {
        if (parentMetaId.isBlank() || height <= 0) return
        sessionInstantHeights[parentMetaId] = height
    }

    fun sessionInstantHeight(parentMetaId: String): Int? = sessionInstantHeights[parentMetaId]

    fun clearSessionPins() {
        sessionPins.clear()
        sessionInstantHeights.clear()
    }

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
