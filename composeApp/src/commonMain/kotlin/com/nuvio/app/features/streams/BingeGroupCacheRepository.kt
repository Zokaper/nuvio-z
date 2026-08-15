package com.nuvio.app.features.streams

import com.nuvio.app.features.playback.StickySourcePin
import kotlinx.serialization.json.Json

object BingeGroupCacheRepository {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The quality band the user chose in Streamlined's sheet, for this sitting only.
     *
     * The complaint it answers: two taps that look identical to the user - same show, next
     * episode - landed on different resolutions, because the next episode was picked by
     * bandwidth estimate while the first was picked by hand, and the estimate ratchets upward
     * as you watch. Someone who deliberately chose "1080p Low" got whatever the line could
     * carry from episode two onward.
     *
     * A **resolution height, not a release**. Deliberately weaker than the sticky pin this
     * replaced: it is a tie-break towards stability applied by
     * `PlaybackQualityOptions.stickyAffordable`, never a ceiling and never a floor, so it can
     * never make the sheet stop appearing or hold a quality the connection cannot carry.
     *
     * Session-scoped and keyed by `parentMetaId`, because the churn is episode-to-episode
     * within one show and within one sitting. A stored value would silently outlive the
     * decision that produced it. That is a different key space from the binge-group cache
     * below, which is keyed the same way but is genuinely a long-lived preference and keeps
     * its storage untouched.
     */
    private val sessionQualityHeights = mutableMapOf<String, Int>()

    fun saveSessionQualityHeight(parentMetaId: String, height: Int) {
        if (parentMetaId.isBlank() || height <= 0) return
        sessionQualityHeights[parentMetaId] = height
    }

    fun sessionQualityHeight(parentMetaId: String): Int? = sessionQualityHeights[parentMetaId]

    fun clearSessionPins() {
        sessionQualityHeights.clear()
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

    fun remove(contentId: String) {
        BingeGroupCacheStorage.remove(hashedKey(contentId))
    }

    private fun hashedKey(contentId: String): String {
        val hash = contentId.fold(0L) { acc, c -> acc * 31 + c.code }.toULong()
        return "binge_group_$hash"
    }
}
