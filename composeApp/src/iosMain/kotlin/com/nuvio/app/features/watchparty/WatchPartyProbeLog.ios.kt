package com.nuvio.app.features.watchparty

import com.nuvio.app.core.debug.isDebugBuild
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSHomeDirectory
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs

/** Files export must include Kotlin WT events, not just Swift view/lifecycle snapshots. */
@OptIn(ExperimentalForeignApi::class)
internal object WatchPartyProbeLog {
    private val queue = dispatch_queue_create("com.nuvio.watchparty.probe", null)
    private var path: String? = null

    fun enable() {
        // The Kotlin half of every Debug export - see `KotlinLogProbe`. Idempotent.
        KotlinLogProbe.enable()
        if (!isDebugBuild || WatchPartyDiagnostics.sink != null) return
        dispatch_async(queue) {
            val directory = "${NSHomeDirectory()}/Documents/nuvio_diagnostics"
            val manager = NSFileManager.defaultManager
            manager.createDirectoryAtPath(directory, true, null, null)
            manager.contentsOfDirectoryAtPath(directory, null)?.filterIsInstance<String>()
                ?.filter { it.startsWith("watchparty-") && it.endsWith(".log") }
                ?.sorted()?.dropLast(11)?.forEach { manager.removeItemAtPath("$directory/$it", null) }
            val created = "$directory/watchparty-${currentEpochMs()}.log"
            manager.createFileAtPath(created, null,
                mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication))
            path = created
        }
        WatchPartyDiagnostics.sink = { line ->
            dispatch_async(queue) {
                path?.let { target ->
                    fopen(target, "a")?.let { file ->
                        fputs(line + "\n", file)
                        fclose(file)
                    }
                }
            }
        }
    }
}
