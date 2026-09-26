package com.nuvio.app.features.downloads

import androidx.compose.runtime.Composable
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.download_choice_checking_progress
import nuvio.composeapp.generated.resources.download_choice_chosen
import nuvio.composeapp.generated.resources.download_flow_episode_count
import nuvio.composeapp.generated.resources.download_phase_completed
import nuvio.composeapp.generated.resources.download_phase_downloading
import nuvio.composeapp.generated.resources.download_phase_downloading_unknown
import nuvio.composeapp.generated.resources.download_phase_finding
import nuvio.composeapp.generated.resources.download_phase_gave_up
import nuvio.composeapp.generated.resources.download_phase_manual_pick
import nuvio.composeapp.generated.resources.download_phase_no_sources
import nuvio.composeapp.generated.resources.download_phase_not_cached
import nuvio.composeapp.generated.resources.download_phase_over_limit
import nuvio.composeapp.generated.resources.download_phase_paused
import nuvio.composeapp.generated.resources.download_phase_paused_progress
import nuvio.composeapp.generated.resources.download_phase_queued
import nuvio.composeapp.generated.resources.download_phase_refreshing_sources
import nuvio.composeapp.generated.resources.download_phase_ready_to_choose
import nuvio.composeapp.generated.resources.download_phase_resolution_missing
import nuvio.composeapp.generated.resources.download_phase_storage
import nuvio.composeapp.generated.resources.download_wait_connection
import nuvio.composeapp.generated.resources.download_wait_resuming
import nuvio.composeapp.generated.resources.download_wait_retrying
import nuvio.composeapp.generated.resources.download_wait_starting
import nuvio.composeapp.generated.resources.downloads_preparing_progress
import nuvio.composeapp.generated.resources.downloads_status_waiting_wifi
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * The words for a [DownloadPresentation]. One mapping, two resolvers - [plainText] for Compose and
 * [plainTextOf] for the Android notification and the iOS Live Activity - so all three say the same
 * thing about the same download.
 */
internal class PlainLine(val resource: StringResource, val args: List<Any> = emptyList())

internal fun DownloadPresentation.plainLine(): PlainLine {
    val done = formatDownloadBytes(downloadedBytes)
    val total = totalBytes?.takeIf { it > 0L }?.let(::formatDownloadBytes)
    return when (phase) {
        DownloadUserPhase.FINDING_SOURCE -> PlainLine(Res.string.download_phase_finding)
        DownloadUserPhase.READY_TO_CHOOSE -> PlainLine(Res.string.download_phase_ready_to_choose)
        DownloadUserPhase.QUEUED -> PlainLine(Res.string.download_phase_queued)
        DownloadUserPhase.DOWNLOADING -> if (total != null) {
            PlainLine(Res.string.download_phase_downloading, listOf(done, total, "${progressPercent ?: 0}%"))
        } else {
            PlainLine(Res.string.download_phase_downloading_unknown, listOf(done))
        }
        DownloadUserPhase.WAITING -> when (waitReason) {
            DownloadWaitReason.CONNECTION -> PlainLine(Res.string.download_wait_connection)
            DownloadWaitReason.WIFI -> PlainLine(Res.string.downloads_status_waiting_wifi)
            DownloadWaitReason.RETRYING_SHORTLY -> PlainLine(Res.string.download_wait_retrying)
            DownloadWaitReason.RESUMING -> PlainLine(Res.string.download_wait_resuming)
            DownloadWaitReason.STARTING, null -> PlainLine(Res.string.download_wait_starting)
        }
        DownloadUserPhase.PAUSED -> if (total != null) {
            PlainLine(Res.string.download_phase_paused_progress, listOf(done, total))
        } else {
            PlainLine(Res.string.download_phase_paused)
        }
        DownloadUserPhase.NEEDS_YOU -> when (needsYou) {
            DownloadNeedsYouKind.STORAGE -> PlainLine(Res.string.download_phase_storage)
            DownloadNeedsYouKind.MANUAL_PICK -> PlainLine(Res.string.download_phase_manual_pick)
            DownloadNeedsYouKind.GAVE_UP, null -> PlainLine(Res.string.download_phase_gave_up)
            DownloadNeedsYouKind.NO_SOURCE_FITS -> PlainLine(noSourceResource(noSourceReason))
        }
        DownloadUserPhase.COMPLETED -> PlainLine(Res.string.download_phase_completed, listOf(total ?: done))
    }
}

internal fun noSourceResource(reason: DownloadEntryDecisionKind?): StringResource = when (reason) {
    DownloadEntryDecisionKind.RESOLUTION_MISSING -> Res.string.download_phase_resolution_missing
    DownloadEntryDecisionKind.NOTHING_CACHED -> Res.string.download_phase_not_cached
    DownloadEntryDecisionKind.NO_SOURCES -> Res.string.download_phase_no_sources
    DownloadEntryDecisionKind.MANUAL_PICK -> Res.string.download_phase_manual_pick
    DownloadEntryDecisionKind.OVER_LIMIT, null -> Res.string.download_phase_over_limit
}

@Composable
internal fun DownloadPresentation.plainText(): String {
    val line = plainLine()
    return stringResource(line.resource, *line.args.toTypedArray())
}

internal suspend fun DownloadPresentation.plainTextOf(): String {
    val line = plainLine()
    return getString(line.resource, *line.args.toTypedArray())
}

/**
 * An Assisted batch's line - "Finding sources · 7 of 22 · 1080p chosen", "Checking sources · 3 of 22 ·
 * 1080p chosen", "Ready to choose quality · 22 episodes". The Downloads row and the iOS Live Activity
 * both read it, so they cannot disagree.
 */
internal fun DownloadChoiceStatus.plainLines(): List<PlainLine> {
    val chosen = chosenHeight?.let { PlainLine(Res.string.download_choice_chosen, listOf(DownloadFlowRules.resolutionLabel(it))) }
    return when (phase) {
        DownloadChoicePhase.REFRESHING -> listOfNotNull(PlainLine(Res.string.download_phase_refreshing_sources), chosen)
        DownloadChoicePhase.FINDING -> listOfNotNull(PlainLine(Res.string.downloads_preparing_progress, listOf(done, total)), chosen)
        DownloadChoicePhase.CHECKING -> listOfNotNull(PlainLine(Res.string.download_choice_checking_progress, listOf(done, total)), chosen)
        DownloadChoicePhase.READY -> listOf(
            PlainLine(Res.string.download_phase_ready_to_choose),
            PlainLine(Res.string.download_flow_episode_count, listOf(total)),
        )
    }
}

@Composable
internal fun downloadChoiceStatusText(status: DownloadChoiceStatus): String =
    status.plainLines().map { stringResource(it.resource, *it.args.toTypedArray()) }.joinToString(" · ")

internal suspend fun DownloadChoiceStatus.plainTextOf(): String =
    plainLines().map { getString(it.resource, *it.args.toTypedArray()) }.joinToString(" · ")
