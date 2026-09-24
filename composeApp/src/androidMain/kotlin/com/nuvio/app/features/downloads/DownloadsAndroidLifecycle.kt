package com.nuvio.app.features.downloads

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.nuvio.app.core.debug.isDebugBuild
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationPlatform
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Everything Android does to keep a queued download moving once the app leaves the screen, and
 * the record of whether it worked.
 *
 * Reported on a real phone at the Phase 9 opening: queue a season, turn the screen off, come back -
 * every row reads "Waiting for connection", and there was never a notification. Three things in
 * the code fit that, and none of them could be told apart from outside the device:
 *
 * - downloads never asked for `POST_NOTIFICATIONS`, so on Android 13+ nothing they posted showed;
 * - the background host is scheduled with an **unmetered** network constraint unless the batch
 *   allowed mobile data, so on cellular it never starts, and the transfer lives only as long as
 *   the app is visible;
 * - "offline" is decided by an HTTP probe that only runs again in the foreground, so a transfer
 *   that failed while the process was frozen stayed "Waiting for connection" until the user came
 *   back.
 *
 * This file answers the second and third by reacting to the platform's own network callback and
 * foreground transitions, asks for the permission once, and - in debug builds - writes every
 * lifecycle event to `Download/NuvioZ-diagnostics/` where the tester can open it with Files.
 */
internal object DownloadsAndroidLifecycle {
    private const val prefsName = "nuvio_downloads_android"
    private const val notificationPermissionAskedKey = "notification_permission_asked"

    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val diagnosticsWriter = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nuvio-download-diagnostics").apply { isDaemon = true }
    }
    private var diagnosticsStream: OutputStream? = null
    private var lastNetworkSignature: String? = null

    fun initialize(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app

        if (isDebugBuild) installDiagnosticsFile(app)
        DownloadDiagnostics.appState = {
            "foreground=${isForeground()} hosting=${DownloadsBackgroundScheduler.isHostingQueue}"
        }
        DownloadDiagnostics.note(
            "android_start",
            "api=${Build.VERSION.SDK_INT} notifications=${notificationsAllowed(app)} " +
                "batteryUnrestricted=${ignoringBatteryOptimizations(app)}",
        )
        registerNetworkCallback(app)
        registerPowerReceivers(app)
        Handler(Looper.getMainLooper()).post { observeForeground() }
    }

    /**
     * Asks for `POST_NOTIFICATIONS` the first time the user downloads something, and never again
     * after they have answered - Android will not show the prompt a third time anyway, and a
     * denial is theirs to reverse in system settings.
     */
    fun requestNotificationPermissionOnce() {
        val context = appContext ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (notificationsAllowed(context)) return
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        if (prefs.getBoolean(notificationPermissionAskedKey, false)) return
        if (!isForeground()) return
        scope.launch {
            val granted = EpisodeReleaseNotificationPlatform.requestAuthorization()
            prefs.edit().putBoolean(notificationPermissionAskedKey, true).apply()
            DownloadDiagnostics.note("notification_permission", "granted=$granted")
        }
    }

    fun notificationsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun isForeground(): Boolean = runCatching {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }.getOrDefault(false)

    private fun observeForeground() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        DownloadDiagnostics.note("app_foreground", "")
                        // A reclaimed background host leaves its items System-paused, and before
                        // this nothing in the running process ever brought them back.
                        DownloadsRepository.resumeSystemPausedDownloads()
                        NetworkStatusRepository.requestRefresh(force = true)
                    }
                    Lifecycle.Event.ON_STOP -> DownloadDiagnostics.note(
                        "app_background",
                        "hosting=${DownloadsBackgroundScheduler.isHostingQueue} " +
                            DownloadsRepository.hostQueueSummary(),
                    )
                    else -> Unit
                }
            },
        )
    }

    /**
     * The platform knows the moment the network comes back; the HTTP probe only finds out the next
     * time something asks it. Asking on every real change is what lets a queue parked on
     * "Waiting for connection" recover without the user opening the app.
     */
    private fun registerNetworkCallback(context: Context) {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            manager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = onNetworkChanged("available", null)

                    override fun onLost(network: Network) = onNetworkChanged("lost", null)

                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                        onNetworkChanged("capabilities", capabilities)
                },
            )
        }.onFailure { DownloadDiagnostics.note("network_callback_failed", "error=${it::class.simpleName}") }
    }

    private fun onNetworkChanged(kind: String, capabilities: NetworkCapabilities?) {
        val signature = if (capabilities == null) {
            kind
        } else {
            val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val unmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            "validated=$validated unmetered=$unmetered"
        }
        if (signature == lastNetworkSignature) return
        lastNetworkSignature = signature
        DownloadDiagnostics.note("network", signature)
        NetworkStatusRepository.requestRefresh(force = true)
        // Wi-Fi back, or mobile data gone: items waiting on the network rule may start now.
        DownloadsRepository.onNetworkChanged()
    }

    private fun registerPowerReceivers(context: Context) {
        val filter = IntentFilter().apply {
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val power = context.getSystemService(PowerManager::class.java)
                DownloadDiagnostics.note(
                    "power",
                    "idle=${power?.isDeviceIdleMode} saver=${power?.isPowerSaveMode}",
                )
            }
        }
        runCatching {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    private fun ignoringBatteryOptimizations(context: Context): Boolean? =
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName)

    /**
     * Debug builds only. One file per process under `Download/NuvioZ-diagnostics/`, readable from
     * the Files app without adb - the same role `nuvio_diagnostics` plays on iOS. No URLs, headers
     * or credentials reach [DownloadDiagnostics], so nothing here needs redacting.
     */
    private fun installDiagnosticsFile(context: Context) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "downloads-$stamp-${android.os.Process.myPid()}.log"
        diagnosticsWriter.execute {
            diagnosticsStream = runCatching { openDiagnosticsStream(context, name) }.getOrNull()
        }
        val lineStamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        DownloadDiagnostics.sink = { line ->
            val stamped = "${lineStamp.format(Date())} $line\n"
            diagnosticsWriter.execute {
                runCatching {
                    diagnosticsStream?.apply {
                        write(stamped.toByteArray())
                        flush()
                    }
                }
            }
        }
    }

    private fun openDiagnosticsStream(context: Context, name: String): OutputStream? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/NuvioZ-diagnostics")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            return context.contentResolver.openOutputStream(uri, "wa")
        }
        val dir = File(context.getExternalFilesDir(null), "nuvio_diagnostics").apply { mkdirs() }
        return File(dir, name).outputStream()
    }
}
