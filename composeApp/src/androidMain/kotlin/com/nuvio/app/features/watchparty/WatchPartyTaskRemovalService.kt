package com.nuvio.app.features.watchparty

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import co.touchlab.kermit.Logger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val taskRemovalLog = Logger.withTag("WatchPartyTaskRemoval")

/**
 * Runs while this app holds a live party, so that swiping the app away still leaves it (2026-10-23).
 *
 * Swiped from the player, the playback service kept the process alive long enough for
 * `MainActivity.onDestroy` to leave. Swiped from the lobby there was no service, and the system
 * killed the process outright ("remove task") before any of the app's code ran: the party kept the
 * member until the next launch cleaned it up. A process with a running service is not killed for a
 * removed task - the service is told instead, here, and stops itself once the leave has been sent.
 *
 * Not a foreground service: nothing is shown. Android may stop it after the app has sat in the
 * background for a while, and then a later swipe falls back to the next launch's leftover check and
 * the server's silent-member reaper.
 */
class WatchPartyTaskRemovalService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        taskRemovalLog.i { "task removed while in a party - leaving it" }
        val main = Handler(Looper.getMainLooper())
        val giveUp = Runnable { stopSelf() }
        main.postDelayed(giveUp, LeaveBudgetMs)
        WatchPartySessionCoordinator.appClosed {
            main.post {
                main.removeCallbacks(giveUp)
                stopSelf()
            }
        }
    }

    companion object {
        private const val LeaveBudgetMs = 10_000L
        private val installed = AtomicBoolean(false)

        /** Starts the service while a live party is held and stops it when there is none. Once per process. */
        fun install(context: Context) {
            if (!installed.compareAndSet(false, true)) return
            val app = context.applicationContext
            CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
                WatchPartyRepository.uiState
                    .map { state -> state.party?.takeIf { it.status != WatchPartyStatus.ended } != null }
                    .distinctUntilChanged()
                    .collect { inParty ->
                        val intent = Intent(app, WatchPartyTaskRemovalService::class.java)
                        // A start from the background is refused on Android 8+; a party joined there is
                        // rare (the player and lobby are foreground), and the fallbacks above cover it.
                        runCatching { if (inParty) app.startService(intent) else app.stopService(intent) }
                            .onFailure { taskRemovalLog.w(it) { "guard ${if (inParty) "start" else "stop"} refused" } }
                    }
            }
        }
    }
}
