package com.nuvio.app.features.downloads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.features.details.MetaDetailsRepository

/**
 * Where a title's page sends the user when it cannot load offline but the title is downloaded.
 *
 * Title metadata is cached in memory only, so after a restart on a plane every title page failed
 * with "check your connection" and offered none of the episodes sitting on disk. Decided at the
 * Phase 9 opening: offline, a downloaded title opens its **Downloads view** instead. The page
 * switches back by itself when the network returns, because the condition is observed.
 */
sealed interface OfflineTitleFallback {
    /** A series: its per-show Downloads view. */
    data class Show(val showId: String) : OfflineTitleFallback

    /** A film: the Downloads root, where it is one tap away. */
    data object DownloadsRoot : OfflineTitleFallback
}

@Composable
fun rememberOfflineTitleFallback(type: String, id: String): OfflineTitleFallback? {
    val network by NetworkStatusRepository.uiState.collectAsState()
    val downloads by DownloadsRepository.uiState.collectAsState()
    if (network.condition != NetworkCondition.NoInternet) return null
    // A page whose metadata is already in memory still works offline - its own download
    // controls play the local files - so only a page that has nothing to show is replaced.
    if (MetaDetailsRepository.peek(type, id) != null) return null
    val completed = downloads.items.filter {
        it.parentMetaId == id.trim() && it.status == DownloadStatus.Completed
    }
    if (completed.isEmpty()) return null
    return if (completed.any { it.isEpisode }) OfflineTitleFallback.Show(id.trim()) else OfflineTitleFallback.DownloadsRoot
}
