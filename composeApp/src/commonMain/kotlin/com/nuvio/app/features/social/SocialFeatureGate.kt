package com.nuvio.app.features.social

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Whether the social product layer exists for this profile.
 *
 * **One reader, so no two surfaces can be gated on different questions.** The social layer is
 * spread over sixteen places - a tab in four navigation layouts, two home rows, a details
 * button, a player control, two notification prompts, presence and party effects, activity
 * publishing, a route and the saved-tab restore - and the failure this object prevents is the
 * one where fifteen of them agree and the sixteenth leaves a dead button behind.
 *
 * Composables collect [enabled]; anything outside composition reads [isEnabled].
 *
 * ⚠ Not to be confused with `SocialCapabilities.socialEnabled`, which is the **server** saying
 * whether it has a social layer deployed at all. This is the **user** saying whether they want
 * one. Both have to be true for the feature to appear, and they fail differently: the server
 * flag being false is an outage, this being false is a preference.
 */
object SocialFeatureGate {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val enabled: StateFlow<Boolean> = SocialFeaturePreferencesRepository.uiState
        .map { it.enabled }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = SocialFeaturePreferencesRepository.uiState.value.enabled,
        )

    /**
     * The same answer, outside composition.
     *
     * ⚠ `ensureLoaded()` first, and it is not belt-and-braces. `AppGate` loads the repository at
     * startup, so in practice the value is on disk before anything asks - but the non-composable
     * readers are things like `SocialWatchedActivity`, which fire from the middle of a watched-history
     * write and have no ordering relationship with the gate at all. An unloaded repository answers
     * `false`, and a `false` here means "silently publish nothing", which is the kind of fault that
     * is only ever noticed as an empty activity feed weeks later. The load is a synchronous
     * key/value read and idempotent.
     */
    val isEnabled: Boolean
        get() {
            SocialFeaturePreferencesRepository.ensureLoaded()
            return enabled.value
        }
}

/**
 * [SocialFeatureGate.enabled], for a composable.
 *
 * A named function rather than sixteen copies of the same `collectAsStateWithLifecycle` call, so
 * that every gated surface is visibly asking the same question and a search for this name finds
 * all of them.
 */
@Composable
fun rememberSocialEnabled(): Boolean {
    val enabled by SocialFeatureGate.enabled.collectAsStateWithLifecycle()
    return enabled
}
