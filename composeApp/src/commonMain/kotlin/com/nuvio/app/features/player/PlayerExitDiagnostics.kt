package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.TimeSource

/**
 * Timestamped diagnostics for the player exit path.
 *
 * Requirements from ROADMAP.md Phase 2 follow-up:
 * - T0: Escape/back action received
 * - T1: Navigation pop/reveal requested
 * - T2: Previous Compose destination actually draws
 * - T3: Native player/video surface detached or hidden
 * - T4: Native player teardown/release completes
 */
object PlayerExitDiagnostics {
    private val log = Logger.withTag("PlayerExit")
    private val lock = SynchronizedObject()
    private val clockOrigin = TimeSource.Monotonic.markNow()
    private val afterPreviousDraw = mutableListOf<() -> Unit>()

    private var t0Ms: Long = 0L
    private var t1Ms: Long = 0L
    private var t2Ms: Long = 0L
    private var t3Ms: Long = 0L
    private var t4Ms: Long = 0L

    fun recordT0(source: String = "back_action") {
        val now = monotonicNowMs()
        synchronized(lock) {
            t0Ms = now
            t1Ms = 0L
            t2Ms = 0L
            t3Ms = 0L
            t4Ms = 0L
            afterPreviousDraw.clear()
        }
        log.i { "T0 [0 ms] Escape/back received source=$source" }
    }

    fun recordT1(targetRoute: String = "") {
        val now = monotonicNowMs()
        val elapsedMs = synchronized(lock) {
            t1Ms = now
            elapsedFromT0(now)
        }
        log.i { "T1 [+$elapsedMs ms] Navigation pop requested target=$targetRoute" }
    }

    fun recordT2(destination: String = "") {
        val now = monotonicNowMs()
        val result = synchronized(lock) {
            if (t0Ms == 0L || t2Ms != 0L) return@synchronized null
            t2Ms = now
            Triple(
                afterPreviousDraw.toList().also { afterPreviousDraw.clear() },
                elapsedFromT0(now),
                if (t1Ms > 0L) now - t1Ms else -1L,
            )
        } ?: return
        val (callbacks, elapsedMs, fromT1) = result
        log.i { "T2 [+$elapsedMs ms, +$fromT1 ms from T1] Previous destination drew: $destination" }
        callbacks.forEach { it() }
    }

    /** Defers work until the destination under the player has really drawn. */
    fun runAfterPreviousDraw(action: () -> Unit): Boolean = synchronized(lock) {
        if (t0Ms == 0L || t2Ms != 0L) return@synchronized false
        afterPreviousDraw += action
        true
    }

    fun recordT3(details: String = "") {
        val now = monotonicNowMs()
        val timing = synchronized(lock) {
            if (t0Ms == 0L || t3Ms != 0L) return@synchronized null
            t3Ms = now
            elapsedFromT0(now) to if (t1Ms > 0L) now - t1Ms else -1L
        } ?: return
        val (elapsedMs, fromT1) = timing
        log.i { "T3 [+$elapsedMs ms, +$fromT1 ms from T1] Native player surface detached/hidden: $details" }
    }

    fun recordT4(handle: Long = 0L) {
        val now = monotonicNowMs()
        val timing = synchronized(lock) {
            if (t0Ms == 0L || t4Ms != 0L) return@synchronized null
            t4Ms = now
            elapsedFromT0(now) to if (t1Ms > 0L) now - t1Ms else -1L
        } ?: return
        val (elapsedMs, fromT1) = timing
        log.i { "T4 [+$elapsedMs ms, +$fromT1 ms from T1] Native player teardown completed handle=$handle" }
    }

    private fun elapsedFromT0(nowMs: Long): Long {
        if (t0Ms == 0L) return 0L
        return nowMs - t0Ms
    }

    private fun monotonicNowMs(): Long = clockOrigin.elapsedNow().inWholeMilliseconds + 1L
}
