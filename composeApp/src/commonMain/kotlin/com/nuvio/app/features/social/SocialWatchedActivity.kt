package com.nuvio.app.features.social

import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.watchedItemKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Bridges only explicit/local Nuvio watched mutations into the durable social outbox.
 *
 * ⚠ **Both entry points are inert when the social layer is off, and the check belongs here rather
 * than at the call site.** `WatchedRepository` calls these two functions from the middle of its
 * own write path; making it ask a social question first would put a social dependency in the
 * watched history, which is the one part of the app that must keep working identically in both
 * states. Answering "no" here keeps that boundary where it is.
 *
 * This stops *publishing*. It deletes nothing: activity already on the backend stays there, and
 * re-enabling social makes it visible again without any of it having to be rebuilt.
 */
object SocialWatchedActivity {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun publish(items: Collection<WatchedItem>) {
        if (!SocialFeatureGate.isEnabled) return
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
        if (!SocialFeatureGate.isEnabled) return
        items.filterNot(::isSeriesSummaryMarker).forEach { item ->
            scope.launch { SocialRepository.removeWatched(originKey(item)) }
        }
    }

    private fun originKey(item: WatchedItem): String = "watched:${watchedItemKey(item.type, item.id, item.season, item.episode)}"
    private fun isSeriesSummaryMarker(item: WatchedItem): Boolean =
        item.season == null && item.episode == null && item.type.lowercase() in setOf("series", "show", "tv", "tvshow", "anime")
}
