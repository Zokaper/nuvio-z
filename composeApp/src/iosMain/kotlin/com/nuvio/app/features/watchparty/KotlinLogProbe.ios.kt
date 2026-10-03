package com.nuvio.app.features.watchparty

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
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

/**
 * Every Kotlin log line of a Debug build, in the Files export.
 *
 * ⚠ **Why this exists.** The export carried Swift view snapshots, touches and the realtime transport trace
 * and nothing from the Kotlin side of playback or the party - so an iPhone guest dropped on the source list
 * after an accepted join (device QA 2026-10-03) left no line that could say which path took it there,
 * whether the player ever loaded, or why it stopped. Kermit's default iOS writer goes to the system log,
 * which the Files export cannot reach.
 *
 * Debug builds only. Bounded: at most [MaxBytes] per file, and only the newest [KeepFiles] files are kept,
 * so it cannot grow without limit on a device that is left running.
 */
@OptIn(ExperimentalForeignApi::class)
internal object KotlinLogProbe {
    private const val MaxBytes = 6_000_000
    private const val KeepFiles = 5
    private val queue = dispatch_queue_create("com.nuvio.kotlin.log.probe", null)
    private var path: String? = null
    private var written = 0
    private var enabled = false

    fun enable() {
        if (!isDebugBuild || enabled) return
        enabled = true
        dispatch_async(queue) {
            val directory = "${NSHomeDirectory()}/Documents/nuvio_diagnostics"
            val manager = NSFileManager.defaultManager
            manager.createDirectoryAtPath(directory, true, null, null)
            manager.contentsOfDirectoryAtPath(directory, null)?.filterIsInstance<String>()
                ?.filter { it.startsWith("kotlin-") && it.endsWith(".log") }
                ?.sorted()?.dropLast(KeepFiles - 1)?.forEach { manager.removeItemAtPath("$directory/$it", null) }
            val created = "$directory/kotlin-${currentEpochMs()}.log"
            manager.createFileAtPath(created, null,
                mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication))
            path = created
        }
        Logger.addLogWriter(object : LogWriter() {
            override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                val line = buildString {
                    append(currentEpochMs()).append(' ').append(severity.name.first()).append(" [").append(tag).append("] ")
                    append(message)
                    throwable?.let { append(" | ").append(it::class.simpleName).append(": ").append(it.message) }
                }
                dispatch_async(queue) {
                    if (written > MaxBytes) return@dispatch_async
                    path?.let { target ->
                        fopen(target, "a")?.let { file ->
                            fputs(line + "\n", file)
                            fclose(file)
                            written += line.length + 1
                        }
                    }
                }
            }
        })
    }
}
