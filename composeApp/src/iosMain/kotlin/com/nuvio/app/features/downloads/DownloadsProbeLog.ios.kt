package com.nuvio.app.features.downloads

import kotlin.concurrent.Volatile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationProtectedDataDidBecomeAvailable
import platform.UIKit.UIApplicationProtectedDataWillBecomeUnavailable
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs

/**
 * Debug-only record of what the background session really did, for the `.46` physical test.
 *
 * The question it answers is when bytes started flowing while the phone was locked. The app
 * cannot see that directly - a suspended app gets no callbacks - but the session records it
 * and reports it later in `didFinishCollectingMetrics`, so every task's request and response
 * start times land here next to the app state at the moment the task was created.
 *
 * One JSON object per line in `Documents/nuvio_diagnostics/downloads-<stamp>.jsonl`, the
 * folder the Debug build exposes in Files. Nothing here writes a URL or a header. Off unless
 * the Debug build switches it on from Swift.
 */
@OptIn(ExperimentalForeignApi::class)
internal object DownloadsProbeLog {
    private const val KEEP_FILES = 4

    @Volatile
    private var enabled = false

    /** Tracked from notifications: UIKit's own accessors are main-thread only. */
    @Volatile
    private var appState = "unknown"

    @Volatile
    private var protectedDataAvailable = true

    private val queue = dispatch_queue_create("com.nuvio.downloads.probe", null)
    /** Only touched on [queue]. */
    private var path: String? = null

    fun enable() {
        if (enabled) return
        appState = when (UIApplication.sharedApplication.applicationState) {
            UIApplicationState.UIApplicationStateActive -> "active"
            UIApplicationState.UIApplicationStateInactive -> "inactive"
            else -> "background"
        }
        protectedDataAvailable = UIApplication.sharedApplication.protectedDataAvailable
        val center = NSNotificationCenter.defaultCenter
        fun observe(name: String?, update: () -> Unit) {
            center.addObserverForName(name, null, NSOperationQueue.mainQueue) { _ -> update() }
        }
        observe(UIApplicationDidBecomeActiveNotification) { appState = "active"; event("app_state") }
        observe(UIApplicationWillResignActiveNotification) { appState = "inactive"; event("app_state") }
        observe(UIApplicationDidEnterBackgroundNotification) { appState = "background"; event("app_state") }
        observe(UIApplicationWillEnterForegroundNotification) { appState = "inactive"; event("app_state") }
        observe(UIApplicationProtectedDataWillBecomeUnavailable) {
            protectedDataAvailable = false
            event("device_locked")
        }
        observe(UIApplicationProtectedDataDidBecomeAvailable) {
            protectedDataAvailable = true
            event("device_unlocked")
        }
        enabled = true
        // The repository's own lifecycle lines - completion accepted, rejected or fenced,
        // lost claims released - land in the same file as the session's events.
        DownloadDiagnostics.sink = { line -> event("repo", "line" to line) }
        dispatch_async(queue) { openFile() }
        event("probe_start")
    }

    /** The app state as last reported, for a caller that wants to branch on it in a log line. */
    val currentAppState: String get() = appState

    fun event(name: String, vararg fields: Pair<String, Any?>) {
        if (!enabled) return
        val line = buildJsonObject {
            put("t", nowMs())
            put("event", name)
            put("app", appState)
            put("unlocked", protectedDataAvailable)
            fields.forEach { (key, value) ->
                when (value) {
                    null -> Unit
                    is Number -> put(key, JsonPrimitive(value))
                    is Boolean -> put(key, value)
                    else -> put(key, value.toString())
                }
            }
        }.toString() + "\n"
        dispatch_async(queue) { write(line) }
    }

    fun epochMs(date: NSDate?): Long? = date?.let { (it.timeIntervalSince1970 * 1000.0).toLong() }

    fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

    private fun directory(): String {
        val path = "${NSHomeDirectory().trimEnd('/')}/Documents/nuvio_diagnostics"
        NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
        return path
    }

    private fun openFile() {
        val directory = directory()
        val manager = NSFileManager.defaultManager
        manager.contentsOfDirectoryAtPath(directory, null)
            ?.filterIsInstance<String>()
            ?.filter { it.startsWith("downloads-") && it.endsWith(".jsonl") }
            ?.sorted()
            ?.dropLast(KEEP_FILES - 1)
            ?.forEach { manager.removeItemAtPath("$directory/$it", null) }
        val created = "$directory/downloads-${DownloadsClock.nowEpochMs()}.jsonl"
        // Writable after the first unlock, so a locked phone can still append.
        manager.createFileAtPath(
            created,
            null,
            mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication),
        )
        path = created
    }

    private fun write(line: String) {
        val target = path ?: return
        val file = fopen(target, "a") ?: return
        fputs(line, file)
        fclose(file)
    }
}

/** Called from the Debug build's app delegate, next to `FreezeDiagnostics`. */
fun enableDownloadsProbeLog() = DownloadsProbeLog.enable()
