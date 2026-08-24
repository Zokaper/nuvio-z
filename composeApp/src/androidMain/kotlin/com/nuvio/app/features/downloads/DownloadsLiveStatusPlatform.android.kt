package com.nuvio.app.features.downloads

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nuvio.app.core.deeplink.buildDownloadsDeepLinkUrl
import com.nuvio.app.features.settings.AppIconPlatform
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import kotlin.math.abs

internal actual object DownloadsLiveStatusPlatform {
    private const val channelId = "downloads_live_status"
    private const val notificationsPrefName = "nuvio_download_live_notifications"
    private const val trackedDownloadIdsKey = "tracked_download_ids"

    /**
     * Negative so it can never collide with a download's own id, which is
     * [notificationId]'s absolute value and so never negative.
     */
    private const val preparingNotificationId = -1_000_001

    private var appContext: Context? = null
    private val lastRenderStateById = mutableMapOf<String, RenderState>()
    private var lastPreparingState: PreparingRenderState? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        ensureNotificationChannel()
    }

    actual fun onItemsChanged(items: List<DownloadItem>) {
        val context = appContext ?: return
        if (!canPostNotifications(context)) return

        val manager = NotificationManagerCompat.from(context)
        val trackedBefore = preferences(context)
            .getStringSet(trackedDownloadIdsKey, emptySet())
            .orEmpty()
            .toMutableSet()

        val activeItems = items.filter { item ->
            item.status == DownloadStatus.Queued ||
                item.status == DownloadStatus.Downloading ||
                item.status == DownloadStatus.Paused ||
                item.status == DownloadStatus.Failed
        }

        val trackedNow = mutableSetOf<String>()
        activeItems.forEach { item ->
            val renderState = RenderState(
                status = item.status,
                progressPercent = progressPercent(item),
                downloadedBucket = item.downloadedBytes / (512L * 1024L),
                totalBytes = item.totalBytes,
                errorMessage = item.errorMessage,
                sizeApprovalRequired = item.sizeApprovalRequired,
            )

            val existingState = lastRenderStateById[item.id]
            if (existingState == renderState) {
                trackedNow += item.id
                return@forEach
            }

            manager.notify(notificationId(item.id), buildNotification(context, item))
            lastRenderStateById[item.id] = renderState
            trackedNow += item.id
        }

        val staleIds = trackedBefore - trackedNow
        staleIds.forEach { downloadId ->
            manager.cancel(notificationId(downloadId))
            lastRenderStateById.remove(downloadId)
        }

        preferences(context)
            .edit()
            .putStringSet(trackedDownloadIdsKey, trackedNow)
            .apply()
    }

    /**
     * Keeps one ongoing notification alive while any batch is still finding sources.
     *
     * Discovery runs in the background with nothing queued yet, so without this the app
     * looks idle for as long as a season takes to resolve.
     */
    actual fun onBatchesChanged(batches: List<DownloadBatch>) {
        val context = appContext ?: return
        if (!canPostNotifications(context)) return

        val manager = NotificationManagerCompat.from(context)
        val preparingBatches = batches.filter { it.isPreparing }
        if (preparingBatches.isEmpty()) {
            if (lastPreparingState != null) {
                lastPreparingState = null
                manager.cancel(preparingNotificationId)
            }
            return
        }

        val single = preparingBatches.singleOrNull()
        val title = single?.title?.trim()?.takeIf { it.isNotBlank() }
            ?: runBlocking { getString(Res.string.downloads_preparing_notification_title) }
        val prepared = preparingBatches.sumOf { it.preparedEntryCount }
        val total = preparingBatches.sumOf { it.entries.size }
        val renderState = PreparingRenderState(title = title, prepared = prepared, total = total)
        if (renderState == lastPreparingState) return
        lastPreparingState = renderState

        val subtitle = runBlocking {
            getString(Res.string.downloads_preparing_progress, prepared, total)
        }
        manager.notify(
            preparingNotificationId,
            NotificationCompat.Builder(context, channelId)
                .setSmallIcon(com.nuvio.app.R.drawable.ic_notification_small)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setProgress(total, prepared, total <= 0)
                .setContentIntent(buildLaunchPendingIntent(context, preparingNotificationId))
                .build(),
        )
    }

    private fun buildNotification(context: Context, item: DownloadItem): android.app.Notification {
        val subtitle = buildSubtitle(item)
        val launchPendingIntent = buildLaunchPendingIntent(context, notificationId(item.id))

        val notificationBuilder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(com.nuvio.app.R.drawable.ic_notification_small)
            .setContentTitle(item.title)
            .setContentText(subtitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(subtitle))
            .setOnlyAlertOnce(true)
            .setContentIntent(launchPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        when (item.status) {
            DownloadStatus.Queued -> {
                // Waiting for a slot, so there is no progress to show - but it can still
                // be pushed out of the queue from here.
                notificationBuilder
                    .setOngoing(false)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setProgress(0, 0, false)
                    .addAction(
                        0,
                        runBlocking { getString(Res.string.compose_action_pause) },
                        buildActionPendingIntent(
                            context = context,
                            action = DownloadsNotificationActionReceiver.actionPause,
                            downloadId = item.id,
                        ),
                    )
                    .addAction(
                        0,
                        runBlocking { getString(Res.string.action_cancel) },
                        buildActionPendingIntent(
                            context = context,
                            action = DownloadsNotificationActionReceiver.actionCancel,
                            downloadId = item.id,
                        ),
                    )
            }

            DownloadStatus.Downloading -> {
                notificationBuilder
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .addAction(
                        0,
                        runBlocking { getString(Res.string.compose_action_pause) },
                        buildActionPendingIntent(
                            context = context,
                            action = DownloadsNotificationActionReceiver.actionPause,
                            downloadId = item.id,
                        ),
                    )
                    .addAction(
                        0,
                        runBlocking { getString(Res.string.action_cancel) },
                        buildActionPendingIntent(
                            context = context,
                            action = DownloadsNotificationActionReceiver.actionCancel,
                            downloadId = item.id,
                        ),
                    )

                val progress = progressPercent(item)
                if (progress >= 0) {
                    notificationBuilder.setProgress(100, progress, false)
                } else {
                    notificationBuilder.setProgress(100, 0, true)
                }
            }

            DownloadStatus.Paused,
            DownloadStatus.Failed,
            DownloadStatus.Completed,
            -> {
                notificationBuilder
                    .setOngoing(false)
                    .setAutoCancel(false)
                    .setPriority(
                        if (item.status == DownloadStatus.Failed) {
                            NotificationCompat.PRIORITY_DEFAULT
                        } else {
                            NotificationCompat.PRIORITY_LOW
                        },
                    )
                    .setProgress(0, 0, false)
                    .addAction(
                        0,
                        runBlocking {
                            when {
                                item.sizeApprovalRequired -> getString(Res.string.download_approve_size)
                                item.status == DownloadStatus.Failed -> getString(Res.string.action_retry)
                                else -> getString(Res.string.action_resume)
                            }
                        },
                        buildActionPendingIntent(
                            context = context,
                            action = if (item.sizeApprovalRequired) {
                                DownloadsNotificationActionReceiver.actionApproveSize
                            } else {
                                DownloadsNotificationActionReceiver.actionResume
                            },
                            downloadId = item.id,
                        ),
                    )
                    .addAction(
                        0,
                        runBlocking { getString(Res.string.action_cancel) },
                        buildActionPendingIntent(
                            context = context,
                            action = DownloadsNotificationActionReceiver.actionCancel,
                            downloadId = item.id,
                        ),
                    )
            }
        }

        return notificationBuilder.build()
    }

    private fun buildSubtitle(item: DownloadItem): String {
        val detail = item.displaySubtitle
        return when (item.status) {
            DownloadStatus.Queued -> runBlocking { getString(Res.string.downloads_live_queued, detail) }
            DownloadStatus.Downloading -> {
                val downloaded = formatBytes(item.downloadedBytes)
                val total = item.totalBytes?.let(::formatBytes)
                if (total != null) {
                    runBlocking { getString(Res.string.downloads_live_downloading_with_total, detail, downloaded, total) }
                } else {
                    runBlocking { getString(Res.string.downloads_live_downloading, detail, downloaded) }
                }
            }

            DownloadStatus.Paused -> runBlocking { getString(Res.string.downloads_live_paused, detail) }
            DownloadStatus.Failed -> item.errorMessage?.takeIf { it.isNotBlank() } ?: runBlocking { getString(Res.string.downloads_live_failed) }
            DownloadStatus.Completed -> runBlocking { getString(Res.string.downloads_live_completed) }
        }
    }

    private fun formatBytes(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L).toDouble()
        val units = runBlocking {
            arrayOf(
                getString(Res.string.unit_bytes_b),
                getString(Res.string.unit_bytes_kb),
                getString(Res.string.unit_bytes_mb),
                getString(Res.string.unit_bytes_gb),
                getString(Res.string.unit_bytes_tb),
            )
        }
        var value = safe
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex += 1
        }
        return if (unitIndex == 0) {
            "${value.toLong()} ${units[unitIndex]}"
        } else {
            "${"%.1f".format(value)} ${units[unitIndex]}"
        }
    }

    private fun progressPercent(item: DownloadItem): Int {
        val total = item.totalBytes?.takeIf { it > 0L } ?: return -1
        return ((item.downloadedBytes.toDouble() / total.toDouble()) * 100.0)
            .toInt()
            .coerceIn(0, 100)
    }

    private fun buildLaunchPendingIntent(context: Context, requestCode: Int): PendingIntent {
        val launchIntent = Intent().apply {
            component = AppIconPlatform.currentLauncherComponent(context)
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse(buildDownloadsDeepLinkUrl())
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildActionPendingIntent(
        context: Context,
        action: String,
        downloadId: String,
    ): PendingIntent {
        val intent = Intent(context, DownloadsNotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(DownloadsNotificationActionReceiver.extraDownloadId, downloadId)
        }
        return PendingIntent.getBroadcast(
            context,
            notificationId("$action:$downloadId"),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureNotificationChannel() {
        val context = appContext ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (manager.getNotificationChannel(channelId) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                channelId,
                runBlocking { getString(Res.string.downloads_channel_name) },
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = runBlocking { getString(Res.string.downloads_channel_description) }
            },
        )
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionState = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            )
            if (permissionState != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(notificationsPrefName, Context.MODE_PRIVATE)

    private fun notificationId(downloadId: String): Int = abs(downloadId.hashCode())

    private data class PreparingRenderState(
        val title: String,
        val prepared: Int,
        val total: Int,
    )

    private data class RenderState(
        val status: DownloadStatus,
        val progressPercent: Int,
        val downloadedBucket: Long,
        val totalBytes: Long?,
        val errorMessage: String?,
        val sizeApprovalRequired: Boolean,
    )
}
