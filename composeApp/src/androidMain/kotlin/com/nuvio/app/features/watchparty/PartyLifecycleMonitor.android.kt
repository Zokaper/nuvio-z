package com.nuvio.app.features.watchparty

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Two Android facts, and nothing decided from either.
 *
 * `ProcessLifecycleOwner` rather than the player activity's own lifecycle, for the picture-in-
 * picture case: an activity in a PiP window is `STARTED` and not `RESUMED`, so `ON_STOP` fires only
 * when the window is genuinely gone - which is exactly the distinction Away needs, and exactly the
 * one an `ON_PAUSE`-driven observer would get wrong on every single entry into PiP.
 *
 * The screen receiver goes on the application context so it outlives the player activity being
 * stopped, which is the moment it has the most to say.
 */
@Composable
internal actual fun rememberPartyPlatformLifecycle(): PartyPlatformLifecycle {
    val context = LocalContext.current.applicationContext
    val keyguard = remember(context) { context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager }
    var facts by remember(context) {
        mutableStateOf(
            PartyPlatformLifecycle(
                appForeground = ProcessLifecycleOwner.get().lifecycle.currentState
                    .isAtLeast(Lifecycle.State.STARTED),
                // `isKeyguardLocked` rather than `isDeviceLocked`: the question is whether a lock
                // screen is between the viewer and the video, not whether the device has secure
                // credentials configured at all.
                screenLocked = keyguard?.isKeyguardLocked == true,
            ),
        )
    }
    DisposableEffect(context) {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // The keyguard is re-read here, not just trusted from the broadcasts. It is the
                // belt to `ACTION_USER_PRESENT`'s braces: by the time the process is STARTED again
                // the keyguard has settled, so a lock fact that was missed - a broadcast dropped
                // while the process was cached, or an unlock straight into the app whose
                // `USER_PRESENT` raced this - cannot leave the member latched Away with the video
                // in front of them.
                Lifecycle.Event.ON_START -> facts = facts.copy(
                    appForeground = true,
                    screenLocked = keyguard?.isKeyguardLocked == true,
                )
                Lifecycle.Event.ON_STOP -> facts = facts.copy(appForeground = false)
                else -> Unit
            }
        }
        val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(receivedContext: Context?, intent: Intent?) {
                // The action is turned into a signal here and decided on in `PartyPresence.kt`,
                // like every other fact this adapter reports. `USER_PRESENT` in particular must
                // not be second-guessed by re-reading the keyguard, and the reason it must not is
                // written down where the rule lives - see [partyScreenLockedAfter].
                val signal = when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> PartyScreenSignal.ScreenOff
                    Intent.ACTION_SCREEN_ON -> PartyScreenSignal.ScreenOn
                    Intent.ACTION_USER_PRESENT -> PartyScreenSignal.UserPresent
                    else -> return
                }
                facts = facts.copy(
                    screenLocked = partyScreenLockedAfter(
                        signal = signal,
                        keyguardLocked = keyguard?.isKeyguardLocked == true,
                    ),
                )
            }
        }
        ContextCompat.registerReceiver(
            context,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            runCatching { context.unregisterReceiver(screenReceiver) }
        }
    }
    return facts
}
