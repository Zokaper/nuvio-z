package com.nuvio.app.features.streams

import com.nuvio.app.features.playback.StickySourcePin
import kotlinx.serialization.json.Json

/** Persistent binge-group preferences used by Classic auto-play. */
object BingeGroupCacheRepository {
    private val json = Json { ignoreUnknownKeys = true }

    fun save(contentId: String, bingeGroup: String, filename: String? = null, infoHash: String? = null) {
        save(contentId, StickySourcePin(bingeGroup = bingeGroup))
        val fn = filename?.takeIf { it.isNotBlank() }
        if (fn != null) {
            BingeGroupCacheStorage.save(filenameHashedKey(contentId), fn)
        } else {
            BingeGroupCacheStorage.remove(filenameHashedKey(contentId))
        }
        val ih = infoHash?.takeIf { it.isNotBlank() }
        if (ih != null) {
            BingeGroupCacheStorage.save(infoHashHashedKey(contentId), ih)
        } else {
            BingeGroupCacheStorage.remove(infoHashHashedKey(contentId))
        }
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

    fun getFilename(contentId: String): String? {
        return BingeGroupCacheStorage.load(filenameHashedKey(contentId))
    }

    fun getInfoHash(contentId: String): String? {
        return BingeGroupCacheStorage.load(infoHashHashedKey(contentId))
    }

    fun remove(contentId: String) {
        BingeGroupCacheStorage.remove(hashedKey(contentId))
        BingeGroupCacheStorage.remove(filenameHashedKey(contentId))
        BingeGroupCacheStorage.remove(infoHashHashedKey(contentId))
    }

    private fun hashedKey(contentId: String): String {
        val hash = contentId.fold(0L) { acc, c -> acc * 31 + c.code }.toULong()
        return "binge_group_$hash"
    }

    private fun filenameHashedKey(contentId: String): String {
        val hash = contentId.fold(0L) { acc, c -> acc * 31 + c.code }.toULong()
        return "binge_group_fn_$hash"
    }

    private fun infoHashHashedKey(contentId: String): String {
        val hash = contentId.fold(0L) { acc, c -> acc * 31 + c.code }.toULong()
        return "binge_group_ih_$hash"
    }
}
