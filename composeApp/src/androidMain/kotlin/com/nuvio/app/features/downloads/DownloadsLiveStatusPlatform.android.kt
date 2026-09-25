package com.nuvio.app.features.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nuvio.app.core.deeplink.buildDownloadsDeepLinkUrl
import com.nuvio.app.features.settings.AppIconPlatform
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import kotlin.math.abs

/**
 * Android's downloads notifications: **one** ongoing summary, plus a dismissible notification
 * when a title or season finishes (Phase 9 decision).
 *
 * The summary is posted under [SUMMARY_NOTIFICATION_ID], which is also the id the background host
 * (user-initiated job, or the foreground worker) runs under - so the notification the system
 * requires for background work *is* the summary, and there is never a second one saying
 * "Downloading for offline playback" beside it. What it says comes from
 * [DownloadsSummaryPolicy]; this file only renders.
 */
internal actual object DownloadsLiveStatusPlatform {
    const val SUMMARY_NOTIFICATION_ID = 0x4e5a46

    private const val summaryChannelId = "downloads_live_status"
    private const val completedChannelId = "downloads_completed"
    private const val legacyPrefName = "nuvio_download_live_notifications"
    private const val legacyTrackedIdsKey = "tracked_download_ids"
    private const val legacyPreparingNotificationId = -1_000_001

    private var appContext: Context? = null
    private var items: List<DownloadItem> = emptyList()
    private var batches: List<DownloadBatch> = emptyList()
    private var itemsSeen = false
    private var lastRendered: RenderKey? = null
    private var lastSummary: DownloadsSummary? = null

    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        ensureChannels()
        clearLegacyNotifications()
    }

    @Synchronized
    actual fun onItemsChanged(items: List<DownloadItem>) {
        val context = appContext ?: return
        val previous = this.items
        val firstSnapshot = !itemsSeen
        this.items = items
        itemsSeen = true
        render(context)
        // The first snapshot after a launch is the queue as it was on disk, not a transition.
        if (!firstSnapshot) announceCompletions(context, previous, items)
    }

    @Synchronized
    actual fun onBatchesChanged(batches: List<DownloadBatch>) {
        val context = appContext ?: return
        this.batches = batches
        render(context)
    }

    actual fun onDownloadRequested() {
        DownloadsAndroidLifecycle.requestNotificationPermissionOnce()
    }

    /** The notification a background host starts under, before the first render has run. */
    @Synchronized
    fun hostNotification(context: Context): Notification {
        ensureChannels(context)
        val summary = DownloadsSummaryPolicy.summarize(items, batches) ?: lastSummary
        return buildSummary(context, summary)
    }

    private fun render(context: Context) {
        val summary = DownloadsSummaryPolicy.summarize(items, batches)
        val key = summary?.let(::renderKey)
        if (key == lastRendered) return
        lastRendered = key
        lastSummary = summary
        val manager = NotificationManagerCompat.from(context)
        if (summary == null) {
            manager.cancel(SUMMARY_NOTIFICATION_ID)
            return
        }
        if (!DownloadsAndroidLifecycle.notificationsAllowed(context)) return
        runCatching { manager.notify(SUMMARY_NOTIFICATION_ID, buildSummary(context, summary)) }
    }

    private fun buildSummary(context: Context, summary: DownloadsSummary?): Notification {
        val builder = NotificationCompat.Builder(context, summaryChannelId)
            .setSmallIcon(com.nuvio.app.R.drawable.ic_notification_small)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(buildLaunchPendingIntent(context, SUMMARY_NOTIFICATION_ID))

        if (summary == null) {
            return builder
                .setContentTitle(string(Res.string.downloads_preparing_notification_title))
                .setProgress(0, 0, true)
                .build()
        }

        val head = summary.head
        // The count and the percent are the whole queue's (`DownloadAggregateProgress`), so a
        // season reads "2 of 6 done · 38%" rather than its lead episode's own percent.
        val progress = summary.progress
        val progressParts = listOfNotNull(
            progress?.takeIf { it.memberCount > 1 }?.let { string(Res.string.downloads_summary_done, it.doneCount, it.memberCount) }
                ?: summary.waitingCount.takeIf { it > 0 }?.let { string(Res.string.downloads_summary_queued, it) },
            progress?.percent?.let { "$it%" },
            summary.needsYouCount.takeIf { it > 0 }?.let { string(Res.string.download_summary_needs_you, it) },
        )
        val (title, text) = when {
            head != null && summary.singleSelection -> {
                val now = episodeLabel(head).takeIf { summary.downloadingCount == 1 }
                head.title to (listOfNotNull(now) + progressParts).joinToString(" · ")
            }
            head != null -> {
                val now = listOfNotNull(head.title, episodeLabel(head)).joinToString(" ")
                string(Res.string.downloads_summary_downloading_many, summary.downloadingCount) to
                    (listOf(now) + progressParts).joinToString(" · ")
            }
            summary.waitingReason != null -> {
                // The same words as the Downloads row (DownloadPresentation).
                val reason = runBlocking {
                    DownloadPresentation(DownloadUserPhase.WAITING, waitReason = summary.waitingReason).plainTextOf()
                }
                string(Res.string.downloads_summary_waiting_title) to listOfNotNull(
                    reason,
                    summary.needsYouCount.takeIf { it > 0 }?.let { string(Res.string.download_summary_needs_you, it) },
                ).joinToString(" · ")
            }
            else -> {
                (summary.preparingTitle ?: string(Res.string.downloads_preparing_notification_title)) to
                    string(Res.string.downloads_preparing_progress, summary.preparedEntries, summary.preparingEntries)
            }
        }
        builder.setContentTitle(title).setContentText(text)

        when {
            head != null && summary.progress?.percent != null ->
                builder.setProgress(100, summary.progress.percent ?: 0, false)
            head != null -> builder.setProgress(0, 0, true)
            summary.waitingReason == null && summary.preparingEntries > 0 ->
                builder.setProgress(summary.preparingEntries, summary.preparedEntries, false)
            else -> builder.setProgress(0, 0, false)
        }

        if (summary.downloadingCount + summary.waitingCount > 0) {
            builder.addAction(
                0,
                string(Res.string.downloads_summary_pause_all),
                buildActionPendingIntent(context, DownloadsNotificationActionReceiver.actionPauseAll, ""),
            )
        }
        return builder.build()
    }

    private fun announceCompletions(context: Context, before: List<DownloadItem>, after: List<DownloadItem>) {
        val groups = DownloadsSummaryPolicy.newlyCompletedGroups(before, after)
        if (groups.isEmpty() || !DownloadsAndroidLifecycle.notificationsAllowed(context)) return
        val manager = NotificationManagerCompat.from(context)
        groups.forEach { group ->
            val title = group.seasonNumber
                ?.let { string(Res.string.downloads_completed_notification_season, group.title, it) }
                ?: string(Res.string.downloads_completed_notification_title, group.title)
            val id = notificationId("completed:${group.parentMetaId}:${group.seasonNumber}")
            runCatching {
                manager.notify(
                    id,
                    NotificationCompat.Builder(context, completedChannelId)
                        .setSmallIcon(com.nuvio.app.R.drawable.ic_notification_small)
                        .setContentTitle(title)
                        .setContentText(string(Res.string.downloads_completed_notification_body))
                        .setAutoCancel(true)
                        .setCategory(NotificationCompat.CATEGORY_STATUS)
                        .setContentIntent(buildLaunchPendingIntent(context, id))
                        .build(),
                )
            }
        }
    }

    private fun episodeLabel(item: DownloadItem): String? {
        val season = item.seasonNumber ?: return null
        val episode = item.episodeNumber ?: return null
        return string(Res.string.downloads_summary_episode, season, episode)
    }

    private fun renderKey(summary: DownloadsSummary) = RenderKey(
        downloading = summary.downloadingCount,
        waiting = summary.waitingCount,
        headId = summary.head?.id,
        percent = summary.progress?.percent,
        done = summary.progress?.doneCount,
        reason = summary.waitingReason,
        needsYou = summary.needsYouCount,
        preparing = summary.preparedEntries to summary.preparingEntries,
        preparingTitle = summary.preparingTitle,
    )

    private data class RenderKey(
        val downloading: Int,
        val waiting: Int,
        val headId: String?,
        val percent: Int?,
        val done: Int?,
        val reason: DownloadWaitReason?,
        val needsYou: Int,
        val preparing: Pair<Int, Int>,
        val preparingTitle: String?,
    )

    private fun string(resource: org.jetbrains.compose.resources.StringResource, vararg args: Any): String =
        runBlocking { getString(resource, *args) }

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

    private fun buildActionPendingIntent(context: Context, action: String, downloadId: String): PendingIntent {
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

    private fun ensureChannels(context: Context? = appContext) {
        val ctx = context ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(summaryChannelId) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    summaryChannelId,
                    string(Res.string.downloads_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = string(Res.string.downloads_channel_description) },
            )
        }
        if (manager.getNotificationChannel(completedChannelId) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    completedChannelId,
                    string(Res.string.downloads_completed_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
    }

    /**
     * Builds before Phase 9 posted one notification per item and a separate "preparing" one,
     * tracked in a preference. Clear any left behind once, so an upgrade does not leave a shade
     * full of rows nothing will ever update again.
     */
    private fun clearLegacyNotifications() {
        val context = appContext ?: return
        val prefs = context.getSharedPreferences(legacyPrefName, Context.MODE_PRIVATE)
        val tracked = prefs.getStringSet(legacyTrackedIdsKey, null) ?: return
        val manager = NotificationManagerCompat.from(context)
        tracked.forEach { manager.cancel(notificationId(it)) }
        manager.cancel(legacyPreparingNotificationId)
        prefs.edit().remove(legacyTrackedIdsKey).apply()
    }

    private fun notificationId(key: String): Int = abs(key.hashCode())
}
