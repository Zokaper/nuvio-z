package com.nuvio.app.features.social

import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.watchedItemKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Bridges only explicit/local Nuvio watched mutations into the durable social outbox. */
object SocialWatchedActivity {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun publish(items: Collection<WatchedItem>) {
        items.filterNot(::isSeriesSummaryMarker).forEach { item ->
            scope.launch {
                SocialRepository.publishWatched(
                    SocialWatchedPublish(
                        originKey = originKey(item),
                        contentId = item.id,
                        contentType = item.type,
                        videoId = item.videoId,
                        title = item.name,
                        poster = item.poster,
                        season = item.season,
                        episode = item.episode,
                        watchedAtEpochMs = item.markedAtEpochMs,
                    ),
                )
            }
        }
    }

    fun remove(items: Collection<WatchedItem>) {
        items.filterNot(::isSeriesSummaryMarker).forEach { item ->
            scope.launch { SocialRepository.removeWatched(originKey(item)) }
        }
    }

    private fun originKey(item: WatchedItem): String = "watched:${watchedItemKey(item.type, item.id, item.season, item.episode)}"
    private fun isSeriesSummaryMarker(item: WatchedItem): Boolean =
        item.season == null && item.episode == null && item.type.lowercase() in setOf("series", "show", "tv", "tvshow", "anime")
}
