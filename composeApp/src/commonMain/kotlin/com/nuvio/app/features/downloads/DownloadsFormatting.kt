package com.nuvio.app.features.downloads

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.i18n.localizedByteUnit
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Presentation helpers shared by the Downloads tab, the download settings page, and the
 * download controls embedded in movie and series detail screens.
 */

internal fun DownloadItem.displayTitle(): String =
    if (isEpisode) {
        episodeTitle?.trim()?.takeIf { it.isNotBlank() } ?: title
    } else {
        title
    }

@Composable
internal fun downloadDisplaySubtitle(
    item: DownloadItem,
    displayTitle: String,
): String {
    val seasonNumber = item.seasonNumber
    val episodeNumber = item.episodeNumber
    if (seasonNumber == null || episodeNumber == null) {
        return item.displaySubtitle
    }

    val episodeCode = stringResource(
        Res.string.compose_player_episode_code_full,
        seasonNumber,
        episodeNumber,
    )
    return listOf(
        episodeCode,
        item.episodeTitle?.trim().orEmpty().takeIf { it.isNotBlank() && it != displayTitle },
        item.title.trim().takeIf { it.isNotBlank() && it != displayTitle },
    ).filterNotNull().joinToString(" • ")
}

@Composable
internal fun downloadStatusText(item: DownloadItem): String {
    val size = if (item.totalBytes != null && item.totalBytes > 0L) {
        "${formatDownloadBytes(item.downloadedBytes)} / ${formatDownloadBytes(item.totalBytes)}"
    } else {
        formatDownloadBytes(item.downloadedBytes)
    }

    return when (item.status) {
        DownloadStatus.Queued -> {
            val retryAtEpochMs = item.nextRetryAtEpochMs
            val nowEpochMs = tickingNowEpochMs(
                active = retryAtEpochMs != null && retryAtEpochMs > DownloadsClock.nowEpochMs(),
            )
            if (retryAtEpochMs != null && retryAtEpochMs > nowEpochMs) {
                val countdown = stringResource(
                    Res.string.downloads_status_retry_countdown,
                    ((retryAtEpochMs - nowEpochMs + 999L) / 1000L).toInt(),
                )
                // A bare countdown does not say why. When something explained the wait -
                // a source still preparing the file, most often - lead with that.
                item.errorMessage?.takeIf { it.isNotBlank() }?.let { "$it $countdown" } ?: countdown
            } else {
                stringResource(Res.string.downloads_status_queued_position, item.queuePosition + 1L)
            }
        }
        DownloadStatus.Downloading -> if (item.downloadedBytes <= 0L && item.totalBytes == null) {
            stringResource(Res.string.downloads_status_waiting_to_start)
        } else {
            stringResource(Res.string.downloads_status_downloading, size)
        }
        DownloadStatus.Paused -> if (item.sizeApprovalRequired) {
            item.errorMessage ?: stringResource(Res.string.downloads_status_paused, size)
        } else {
            stringResource(Res.string.downloads_status_paused, size)
        }
        DownloadStatus.Completed -> stringResource(
            Res.string.downloads_status_completed,
            formatDownloadBytes(item.totalBytes ?: item.downloadedBytes),
        )
        DownloadStatus.Failed -> item.errorMessage ?: stringResource(Res.string.downloads_status_failed)
    }
}

/**
 * A clock that only ticks while something is counting down.
 *
 * Retry backoffs are the one place a row has to re-render with no state change behind
 * it; a static "retrying in 15s" that never moves reads as a stuck download.
 */
@Composable
private fun tickingNowEpochMs(active: Boolean): Long {
    var nowEpochMs by remember { mutableStateOf(DownloadsClock.nowEpochMs()) }
    LaunchedEffect(active) {
        while (active) {
            nowEpochMs = DownloadsClock.nowEpochMs()
            delay(500L)
        }
    }
    return nowEpochMs
}

internal fun formatDownloadBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 ${localizedByteUnit("B")}"
    val kib = 1024.0
    val mib = kib * 1024.0
    val gib = mib * 1024.0
    val value = bytes.toDouble()
    return when {
        value >= gib -> "${((value / gib) * 10.0).toInt() / 10.0} ${localizedByteUnit("GB")}"
        value >= mib -> "${((value / mib) * 10.0).toInt() / 10.0} ${localizedByteUnit("MB")}"
        value >= kib -> "${((value / kib) * 10.0).toInt() / 10.0} ${localizedByteUnit("KB")}"
        else -> "$bytes ${localizedByteUnit("B")}"
    }
}

@Composable
internal fun DownloadSectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        modifier = modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}
