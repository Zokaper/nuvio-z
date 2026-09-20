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
                Lifecycle.Event.ON_START -> facts = facts.copy(appForeground = true)
                Lifecycle.Event.ON_STOP -> facts = facts.copy(appForeground = false)
                else -> Unit
            }
        }
        val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(receivedContext: Context?, intent: Intent?) {
                val locked = when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> true
                    // The screen coming on is not the lock coming off - the lock screen is lit.
                    // `USER_PRESENT` means somebody got past it, and asking the keyguard covers a
                    // device with no lock set, where `SCREEN_ON` is the whole story.
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> keyguard?.isKeyguardLocked == true
                    else -> return
                }
                facts = facts.copy(screenLocked = locked)
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
