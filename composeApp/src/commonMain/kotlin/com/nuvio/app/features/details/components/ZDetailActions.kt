package com.nuvio.app.features.details.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.People
import androidx.compose.runtime.Composable
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.TitleDownloadState
import com.nuvio.app.features.social.socialRecommendAction
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.watch_party_title
import org.jetbrains.compose.resources.stringResource

/** Z actions at the same seam in the phone section and upstream's tablet hero. */
@Composable
internal fun ZDetailActions(
    playLabel: String,
    playEnabled: Boolean,
    isSaved: Boolean,
    isWatched: Boolean,
    isTablet: Boolean,
    shuffleEnabled: Boolean,
    contentType: String,
    hasEpisodes: Boolean,
    titleDownloadState: TitleDownloadState,
    onDownloadClick: () -> Unit,
    onDownloadedItemManage: (DownloadItem) -> Unit,
    onWatchTogetherClick: (() -> Unit)?,
    onPlayClick: () -> Unit,
    onPlayLongClick: (() -> Unit)?,
    onShuffleClick: (() -> Unit)?,
    onWatchedClick: () -> Unit,
    onSaveClick: () -> Unit,
    onSaveLongClick: (() -> Unit)?,
) {
    DetailActions(
        playLabel = playLabel, playEnabled = playEnabled, isSaved = isSaved, isWatched = isWatched,
        isTablet = isTablet, shuffleEnabled = shuffleEnabled, onPlayClick = onPlayClick,
        onPlayLongClick = onPlayLongClick, onShuffleClick = onShuffleClick,
        onWatchedClick = onWatchedClick, onSaveClick = onSaveClick, onSaveLongClick = onSaveLongClick,
        prefixActions = listOf(titleDownloadSecondaryAction(
            isSeriesLike = isSeriesLikeTitle(contentType, hasEpisodes),
            titleDownloadState = titleDownloadState, onDownloadClick = onDownloadClick,
            onDownloadedItemManage = onDownloadedItemManage,
        )),
        suffixActions = buildList {
            onWatchTogetherClick?.let { action ->
                add(DetailSecondaryAction(
                    label = stringResource(Res.string.watch_party_title),
                    icon = Icons.Rounded.People, onClick = action,
                ))
            }
            socialRecommendAction()?.let(::add)
        },
    )
}
