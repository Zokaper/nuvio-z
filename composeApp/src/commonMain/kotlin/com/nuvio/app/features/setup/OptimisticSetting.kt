package com.nuvio.app.features.setup

// No imports, and none may be added: group 3 of `scripts/run-pure-suites.sh` compiles this beside
// `SetupWizardSteps.kt` and `AdvancedSetupModel.kt`.

/**
 * A control whose write is a server round trip, shown as the user's choice the moment it is made.
 *
 * ## Why this exists (setup polish, physical QA)
 *
 * Advanced Setup's Social switches drew `SocialRepository.uiState.me`, which only changes once the
 * RPC has returned and the state has been re-read, so a tap left the switch visibly frozen for about
 * two seconds and read as broken. A settings control has to move under the finger; the server is told
 * afterwards, and only a *refusal* moves it back.
 *
 * ## The rules, which are the whole of this file
 *
 * - [begin]: the user chose [value]. It is shown at once and a new write generation starts.
 * - [succeeded]: the server accepted that write. The choice stays shown until the confirmed value
 *   catches up ([observed]); it is not dropped back to a stale confirmed value in between, which is
 *   the flicker a naive "clear on success" produces.
 * - [failed]: the server refused. The choice is withdrawn and the confirmed value shows again - and
 *   the answer says whether the caller should tell the user, which it should only for the **latest**
 *   write: a refusal of a write the user has already superseded changes nothing on screen.
 * - [observed]: the confirmed value changed. It clears the pending choice when it agrees with it, or
 *   when the write already settled and something newer (another device, a refresh) has spoken since.
 *
 * Immutable, so a Compose caller holds it in one `mutableStateOf` and every transition recomposes.
 */
data class OptimisticSetting<T>(
    val pending: T? = null,
    val generation: Int = 0,
    val settled: Boolean = false,
) {
    /** What the control shows: the user's unconfirmed choice, else the confirmed value. */
    fun shown(confirmed: T): T = pending ?: confirmed

    /** Whether a write is still in flight - the control may say "saving" but must not freeze. */
    val saving: Boolean get() = pending != null && !settled

    fun begin(value: T): OptimisticSetting<T> = OptimisticSetting(pending = value, generation = generation + 1, settled = false)

    fun succeeded(writeGeneration: Int): OptimisticSetting<T> =
        if (writeGeneration == generation && pending != null) copy(settled = true) else this

    fun failed(writeGeneration: Int): OptimisticFailure<T> =
        if (writeGeneration == generation && pending != null) {
            OptimisticFailure(OptimisticSetting(pending = null, generation = generation, settled = false), notify = true)
        } else {
            OptimisticFailure(this, notify = false)
        }

    fun observed(confirmed: T): OptimisticSetting<T> = when {
        pending == null -> this
        confirmed == pending -> copy(pending = null, settled = false)
        settled -> copy(pending = null, settled = false)
        else -> this
    }
}

/** A refusal: the next state, and whether the user should be told (only for the latest write). */
data class OptimisticFailure<T>(val state: OptimisticSetting<T>, val notify: Boolean)
