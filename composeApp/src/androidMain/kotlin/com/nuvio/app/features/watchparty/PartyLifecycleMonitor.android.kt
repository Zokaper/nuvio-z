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
                // A second chance at a lock fact that went missing, and no more than that: it
                // re-reads the keyguard, so it may only ever *clear* the lock and never declare
                // one. On an unlock-and-return this read is taken during the dismiss animation,
                // where the keyguard still answers `true` and the chance is wasted - which is why
                // ON_RESUME below exists and this is not the signal the return depends on.
                Lifecycle.Event.ON_START -> facts = facts.copy(
                    appForeground = true,
                    screenLocked = partyScreenLockedOnForeground(
                        heldScreenLocked = facts.screenLocked,
                        keyguardLocked = keyguard?.isKeyguardLocked == true,
                        resumed = false,
                    ),
                )
                // **The signal a return actually hangs on.** An activity cannot be RESUMED behind
                // the keyguard, so this is proof rather than a reading - nothing to race, and
                // unlike `ACTION_USER_PRESENT` it cannot be dropped for being sent to a cached
                // process, which is how a locked phone sat Away for eight minutes on hardware with
                // the film in front of it. Deliberately not merged into ON_START: they carry
                // different evidence, and only this one may clear a lock without asking.
                Lifecycle.Event.ON_RESUME -> facts = facts.copy(
                    appForeground = true,
                    screenLocked = partyScreenLockedOnForeground(
                        heldScreenLocked = facts.screenLocked,
                        keyguardLocked = keyguard?.isKeyguardLocked == true,
                        resumed = true,
                    ),
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
