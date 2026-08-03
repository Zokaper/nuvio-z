package com.nuvio.app.features.details.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.downloads.ContentDownloadState
import com.nuvio.app.features.downloads.DownloadPresence
import com.nuvio.app.features.downloads.DownloadsRepository
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The download affordance on an episode or movie card. It starts a download when nothing exists
 * yet, and otherwise reflects live state and opens [DownloadManageSheet] on tap.
 */
@Composable
fun DownloadStateButton(
    state: ContentDownloadState,
    onDownload: () -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val resolvedTint = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint

    IconButton(
        onClick = if (state.presence == DownloadPresence.None) onDownload else onManage,
        modifier = modifier,
    ) {
        when (state.presence) {
            DownloadPresence.None -> Icon(
                imageVector = Icons.Default.Download,
                contentDescription = stringResource(Res.string.download_preset_title),
                tint = resolvedTint,
            )

            DownloadPresence.Preparing,
            DownloadPresence.Queued,
            -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = resolvedTint,
            )

            DownloadPresence.Downloading -> Box(
                contentAlignment = Alignment.Center,
            ) {
                if (state.progressFraction > 0f) {
                    CircularProgressIndicator(
                        progress = state.progressFraction,
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = resolvedTint,
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = resolvedTint,
                    )
                }
                Icon(
                    imageVector = Icons.Default.Pause,
                    contentDescription = stringResource(Res.string.downloads_cd_state_downloading),
                    tint = resolvedTint,
                    modifier = Modifier.size(11.dp),
                )
            }

            DownloadPresence.Paused -> Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = stringResource(Res.string.action_resume),
                tint = resolvedTint,
            )

            DownloadPresence.NeedsApproval,
            DownloadPresence.Failed,
            -> Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = stringResource(Res.string.downloads_status_failed),
                tint = MaterialTheme.colorScheme.error,
            )

            DownloadPresence.Completed -> Icon(
                imageVector = Icons.Default.DownloadDone,
                contentDescription = stringResource(Res.string.downloads_cd_state_downloaded),
                tint = resolvedTint,
            )
        }
    }
}

/**
 * Actions for one download that already exists. Every action maps onto an existing
 * [DownloadsRepository] call, so this sheet works the same on a detail screen and in the tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadManageSheet(
    state: ContentDownloadState,
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
    onPlayOffline: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val item = state.item

    val dismiss: () -> Unit = {
        coroutineScope.launch {
            dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
        }
    }

    NuvioModalBottomSheet(
        onDismissRequest = { dismiss() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = nuvioSafeBottomPadding(16.dp)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (state.isPlayable && onPlayOffline != null) {
                DownloadSheetActionRow(
                    icon = Icons.Default.PlayArrow,
                    label = stringResource(Res.string.downloads_play_offline),
                    onClick = { onPlayOffline(); dismiss() },
                )
            }

            if (item != null) {
                when (state.presence) {
                    DownloadPresence.Downloading -> DownloadSheetActionRow(
                        icon = Icons.Default.Pause,
                        label = stringResource(Res.string.compose_action_pause),
                        onClick = { DownloadsRepository.pauseDownload(item.id); dismiss() },
                    )

                    DownloadPresence.Paused -> DownloadSheetActionRow(
                        icon = Icons.Default.PlayArrow,
                        label = stringResource(Res.string.action_resume),
                        onClick = { DownloadsRepository.resumeDownload(item.id); dismiss() },
                    )

                    DownloadPresence.NeedsApproval -> DownloadSheetActionRow(
                        icon = Icons.Default.Download,
                        label = stringResource(Res.string.download_approve_size),
                        onClick = { DownloadsRepository.approveUnexpectedSize(item.id); dismiss() },
                    )

                    DownloadPresence.Failed -> DownloadSheetActionRow(
                        icon = Icons.Rounded.Refresh,
                        label = stringResource(Res.string.action_retry),
                        onClick = { DownloadsRepository.retryDownload(item.id); dismiss() },
                    )

                    else -> Unit
                }

                DownloadSheetActionRow(
                    icon = Icons.Rounded.Delete,
                    label = stringResource(Res.string.action_delete),
                    onClick = { DownloadsRepository.cancelDownload(item.id); dismiss() },
                )
            } else if (state.batchId != null) {
                DownloadSheetActionRow(
                    icon = Icons.Rounded.Delete,
                    label = stringResource(Res.string.action_cancel),
                    onClick = { DownloadsRepository.removeBatch(state.batchId); dismiss() },
                )
            }
        }
    }
}

@Composable
private fun DownloadSheetActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    NuvioBottomSheetDivider()
    NuvioBottomSheetActionRow(
        icon = icon,
        title = label,
        onClick = onClick,
    )
}
