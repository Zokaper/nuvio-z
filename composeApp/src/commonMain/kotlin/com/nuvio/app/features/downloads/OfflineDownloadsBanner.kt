package com.nuvio.app.features.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_offline_banner_action
import nuvio.composeapp.generated.resources.downloads_offline_banner_body
import nuvio.composeapp.generated.resources.downloads_offline_banner_title
import org.jetbrains.compose.resources.stringResource

/**
 * A request, from anywhere in the app, to show Library → Downloads.
 *
 * Typed rather than a lambda threaded through Home's parameters, for the reason
 * `NuvioToastAction.OpenDownloads` is: navigation stays with `MainAppContent`, which answers
 * this exactly as it answers the toast.
 */
object DownloadsNavigationRequests {
    private val _requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requests: SharedFlow<Unit> = _requests.asSharedFlow()

    fun openDownloads() {
        _requests.tryEmit(Unit)
    }
}

/**
 * "You're offline · Go to Downloads", shown on Home only while there is no connection **and**
 * something is downloaded to go to. Decided at the Phase 9 opening: offline, Home keeps what it
 * has and points at the one part of the app that still works. It draws nothing otherwise, so it
 * costs Home nothing online.
 */
@Composable
fun OfflineDownloadsBanner(modifier: Modifier = Modifier) {
    val network by NetworkStatusRepository.uiState.collectAsState()
    val downloads by DownloadsRepository.uiState.collectAsState()
    if (network.condition != NetworkCondition.NoInternet) return
    if (downloads.items.none { it.status == DownloadStatus.Completed }) return

    Surface(
        modifier = modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        onClick = DownloadsNavigationRequests::openDownloads,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.downloads_offline_banner_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(Res.string.downloads_offline_banner_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = DownloadsNavigationRequests::openDownloads) {
                Text(stringResource(Res.string.downloads_offline_banner_action))
            }
        }
    }
}
