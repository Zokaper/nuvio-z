package com.nuvio.app.features.details.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.runtime.Composable
import com.nuvio.app.features.downloads.ContentDownloadState
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadPresence
import com.nuvio.app.features.downloads.TitleDownloadState
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.download_flow_download
import nuvio.composeapp.generated.resources.download_preset_seasons
import nuvio.composeapp.generated.resources.downloads_cd_state_downloaded
import nuvio.composeapp.generated.resources.downloads_cd_state_downloading
import org.jetbrains.compose.resources.stringResource

/*
 * Nuvio Z: the title-level Download action of the details hero, built in one place for every
 * layout (wizard polish, 2026-09-27).
 *
 * ⚠ **Why this exists.** The details screen has two mutually exclusive hero layouts: the stacked
 * one (phones, tablets, narrow desktop windows) draws the `ACTIONS` section, and the wide desktop
 * one (`useDesktopDetailLayout`, from 1000 dp) draws `DesktopDetailHero`, which owns that section
 * (`desktopHeroOwnedMetaSectionKeys`) and built its own action list. Download was only ever added to
 * the first, so on a wide window the title-level Download disappeared - leaving only the season
 * row's download, which is a narrower action, not a substitute. Watch Together had already been
 * inserted into both layouts by hand; this makes the download one list entry both of them take.
 */

/** Whether the title is a show (the action opens the season chooser) rather than a film. */
fun isSeriesLikeTitle(type: String, hasEpisodes: Boolean): Boolean =
    type.lowercase() in setOf("series", "show", "tv", "tvshow") || hasEpisodes

/**
 * The Download entry for the hero's action row: "Download seasons" for a show (always the whole-
 * show flow; seasons are chosen in its season chooser), or the film's own state for a movie - a
 * finished or running download opens its manage sheet instead of starting another.
 */
@Composable
fun titleDownloadSecondaryAction(
    isSeriesLike: Boolean,
    titleDownloadState: TitleDownloadState,
    onDownloadClick: () -> Unit,
    onDownloadedItemManage: (DownloadItem) -> Unit,
): DetailSecondaryAction {
    val movieDownload = if (isSeriesLike) ContentDownloadState.None else titleDownloadState.forMovie()
    return DetailSecondaryAction(
        label = when {
            isSeriesLike -> stringResource(Res.string.download_preset_seasons)
            movieDownload.presence == DownloadPresence.Completed -> stringResource(Res.string.downloads_cd_state_downloaded)
            movieDownload.presence.isActive -> stringResource(Res.string.downloads_cd_state_downloading)
            else -> stringResource(Res.string.download_flow_download)
        },
        icon = if (movieDownload.presence == DownloadPresence.Completed) Icons.Default.DownloadDone else Icons.Default.Download,
        isActive = if (isSeriesLike) titleDownloadState.completedCount > 0 else movieDownload.presence.isEngaged,
        onClick = movieDownload.item
            ?.takeIf { !isSeriesLike }
            ?.let { item -> { onDownloadedItemManage(item) } }
            ?: onDownloadClick,
    )
}
