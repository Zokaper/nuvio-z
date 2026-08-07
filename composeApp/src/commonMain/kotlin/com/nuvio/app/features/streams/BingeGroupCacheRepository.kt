package com.nuvio.app.features.streams

import com.nuvio.app.features.playback.StickySourcePin
import kotlinx.serialization.json.Json

object BingeGroupCacheRepository {
    private val json = Json { ignoreUnknownKeys = true }

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
