package com.nuvio.app.features.downloads

import com.nuvio.app.core.ui.NuvioToastAction
import com.nuvio.app.core.ui.NuvioToastController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.download_batch_view_downloads
import nuvio.composeapp.generated.resources.download_flow_change
import nuvio.composeapp.generated.resources.download_flow_finding_source
import nuvio.composeapp.generated.resources.download_flow_needs_attention
import nuvio.composeapp.generated.resources.download_flow_nothing_new
import nuvio.composeapp.generated.resources.download_flow_started
import nuvio.composeapp.generated.resources.download_flow_started_detail
import nuvio.composeapp.generated.resources.download_flow_started_many
import nuvio.composeapp.generated.resources.download_flow_started_many_attention
import org.jetbrains.compose.resources.getString

/** The download flow's toasts. Long enough to read and still reach the action. */
internal object ToastDownloadFlowNotices : DownloadFlowNotices {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private const val ACTION_TOAST_MS = 6_000L

    override fun findingSource() {
        scope.launch { NuvioToastController.show(getString(Res.string.download_flow_finding_source)) }
    }

    override fun started(item: DownloadItem?, height: Int?, bytes: Long?, onChange: (() -> Unit)?) {
        scope.launch {
            val detail = listOfNotNull(
                height?.let(DownloadFlowRules::resolutionLabel),
                bytes?.takeIf { it > 0L }?.let(DownloadFlowRules::sizeLabel),
            ).joinToString(" · ")
            val message = if (detail.isBlank()) {
                getString(Res.string.download_flow_started)
            } else {
                getString(Res.string.download_flow_started_detail, detail)
            }
            if (onChange != null) {
                NuvioToastController.show(
                    message = message,
                    durationMillis = ACTION_TOAST_MS,
                    actionLabel = getString(Res.string.download_flow_change),
                    effect = onChange,
                )
            } else {
                NuvioToastController.show(message)
            }
        }
    }

    override fun startedMany(count: Int, needAttention: Int) {
        scope.launch {
            val message = if (needAttention > 0) {
                getString(Res.string.download_flow_started_many_attention, count, needAttention)
            } else {
                getString(Res.string.download_flow_started_many, count)
            }
            NuvioToastController.show(
                message = message,
                durationMillis = ACTION_TOAST_MS,
                actionLabel = getString(Res.string.download_batch_view_downloads),
                action = NuvioToastAction.OpenDownloads,
            )
        }
    }

    override fun needsAttention() {
        scope.launch {
            NuvioToastController.show(
                message = getString(Res.string.download_flow_needs_attention),
                durationMillis = ACTION_TOAST_MS,
                actionLabel = getString(Res.string.download_batch_view_downloads),
                action = NuvioToastAction.OpenDownloads,
            )
        }
    }

    override fun nothingNew() {
        scope.launch { NuvioToastController.show(getString(Res.string.download_flow_nothing_new)) }
    }
}
