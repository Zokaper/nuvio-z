package com.nuvio.app.core.auth

import com.nuvio.app.features.profiles.NuvioProfile

/**
 * Pure state machine governing authentication authority, operation epochs, and session status reconciliation.
 *
 * Authority Invariants:
 * 1. An explicit mutation (signInWithEmail, signUpWithEmail, signInAnonymously, signOut) is authoritative immediately.
 * 2. Async sessionStatus events reconcile external/background changes; they must never overwrite newer or active explicit auth.
 * 3. Stale async events (from an older operation epoch or contradicting an active session) are dropped.
 * 4. Anonymous identity cannot coexist as the active identity with an authenticated account.
 * 5. Transient connectivity failures (network timeout, 5xx, 429) do not log the user out and do not destroy sessions.
 * 6. UI/profile gating derives deterministically from authenticated state.
 */
data class AuthMachineState(
    val authState: AuthState = AuthState.Loading,
    val validatedUserId: String? = null,
    val anonymousUserId: String? = null,
    val currentEpoch: Long = 0L,
    val inFlightEpoch: Long? = null,
)

data class AuthTransitionResult(
    val newState: AuthMachineState,
    val clearAnonymousStorage: Boolean = false,
    val saveAnonymousStorage: String? = null,
    val clearLocalStorage: Boolean = false,
    val shouldValidateRemote: Boolean = false,
    val userIdToValidate: String? = null,
    val isDropped: Boolean = false,
    val logReason: String? = null,
)

sealed interface AppGateTransitionDecision {
    data object StayOnCurrent : AppGateTransitionDecision
    data object ShowAuth : AppGateTransitionDecision
    data object ShowLoading : AppGateTransitionDecision
    data class EnterProfileGate(
        val profiles: List<NuvioProfile>,
        val syncOnEnter: Boolean,
    ) : AppGateTransitionDecision
}

internal object AuthStateMachine {

    fun onInitialize(
        savedAnonId: String?,
        currentEpoch: Long = 0L,
    ): AuthMachineState {
        return if (savedAnonId != null) {
            AuthMachineState(
                authState = AuthState.Authenticated(
                    userId = savedAnonId,
                    email = null,
                    isAnonymous = true,
                ),
                validatedUserId = null,
                anonymousUserId = savedAnonId,
                currentEpoch = currentEpoch,
                inFlightEpoch = null,
            )
        } else {
            AuthMachineState(
                authState = AuthState.Loading,
                validatedUserId = null,
                anonymousUserId = null,
                currentEpoch = currentEpoch,
                inFlightEpoch = null,
            )
        }
    }

    fun onExplicitSignInStarted(
        state: AuthMachineState,
        epoch: Long,
    ): AuthMachineState {
        return state.copy(
            currentEpoch = maxOf(state.currentEpoch, epoch),
            inFlightEpoch = epoch,
        )
    }

    fun onExplicitSignInSucceeded(
        state: AuthMachineState,
        epoch: Long,
        userId: String,
        email: String?,
    ): AuthTransitionResult {
        if (epoch < state.currentEpoch && state.inFlightEpoch != epoch) {
            return AuthTransitionResult(
                newState = state,
                isDropped = true,
                logReason = "Explicit sign-in epoch $epoch superseded by currentEpoch ${state.currentEpoch}",
            )
        }
        if (userId.isBlank()) {
            return AuthTransitionResult(
                newState = state.copy(
                    inFlightEpoch = if (state.inFlightEpoch == epoch) null else state.inFlightEpoch,
                ),
                isDropped = true,
                logReason = "Explicit sign-in succeeded but userId was blank",
            )
        }

        val updated = state.copy(
            authState = AuthState.Authenticated(
                userId = userId,
                email = email,
                isAnonymous = false,
            ),
            validatedUserId = userId,
            anonymousUserId = null,
            currentEpoch = maxOf(state.currentEpoch, epoch),
            inFlightEpoch = if (state.inFlightEpoch == epoch) null else state.inFlightEpoch,
        )
        return AuthTransitionResult(
            newState = updated,
            clearAnonymousStorage = true,
            shouldValidateRemote = false,
            logReason = "Explicit sign-in adopted authoritatively",
        )
    }

    fun onExplicitSignInFailed(
        state: AuthMachineState,
        epoch: Long,
    ): AuthMachineState {
        return if (state.inFlightEpoch == epoch) {
            state.copy(inFlightEpoch = null)
        } else {
            state
        }
    }

    fun onExplicitAnonymousSignIn(
        state: AuthMachineState,
        epoch: Long,
        generatedAnonId: String,
    ): AuthTransitionResult {
        val updated = state.copy(
            authState = AuthState.Authenticated(
                userId = generatedAnonId,
                email = null,
                isAnonymous = true,
            ),
            validatedUserId = null,
            anonymousUserId = generatedAnonId,
            currentEpoch = maxOf(state.currentEpoch, epoch),
            inFlightEpoch = null,
        )
        return AuthTransitionResult(
            newState = updated,
            saveAnonymousStorage = generatedAnonId,
            logReason = "Explicit anonymous sign-in adopted",
        )
    }

    fun onExplicitSignOut(
        state: AuthMachineState,
        epoch: Long,
    ): AuthTransitionResult {
        val updated = state.copy(
            authState = AuthState.Unauthenticated,
            validatedUserId = null,
            anonymousUserId = null,
            currentEpoch = maxOf(state.currentEpoch, epoch),
            inFlightEpoch = null,
        )
        return AuthTransitionResult(
            newState = updated,
            clearAnonymousStorage = true,
            clearLocalStorage = true,
            logReason = "Explicit sign-out completed",
        )
    }

    fun onSessionStatusAuthenticated(
        state: AuthMachineState,
        sessionUserId: String,
        email: String?,
    ): AuthTransitionResult {
        if (sessionUserId.isBlank()) {
            return AuthTransitionResult(
                newState = state,
                isDropped = true,
                logReason = "SessionStatus.Authenticated session contained blank userId",
            )
        }

        val currentAuth = state.authState as? AuthState.Authenticated

        // 1. If we are already authenticated as this user, keep authority and avoid redundant validation
        if (currentAuth != null && !currentAuth.isAnonymous && currentAuth.userId == sessionUserId) {
            val updated = state.copy(
                validatedUserId = sessionUserId,
                anonymousUserId = null,
            )
            return AuthTransitionResult(
                newState = updated,
                clearAnonymousStorage = true,
                shouldValidateRemote = false,
                logReason = "Session already authoritative for $sessionUserId",
            )
        }

        // 2. If an explicit sign-in mutation is actively in flight, adopt this session directly
        // as the result of the mutation without initiating redundant remote validation
        if (state.inFlightEpoch != null) {
            val updated = state.copy(
                authState = AuthState.Authenticated(
                    userId = sessionUserId,
                    email = email,
                    isAnonymous = false,
                ),
                validatedUserId = sessionUserId,
                anonymousUserId = null,
            )
            return AuthTransitionResult(
                newState = updated,
                clearAnonymousStorage = true,
                shouldValidateRemote = false,
                logReason = "Adopted in-flight mutation session for $sessionUserId",
            )
        }

        // 3. Otherwise (e.g. startup rehydration or DeviceLink import):
        // If already validated, adopt directly; otherwise request remote validation
        return if (state.validatedUserId == sessionUserId) {
            val updated = state.copy(
                authState = AuthState.Authenticated(
                    userId = sessionUserId,
                    email = email,
                    isAnonymous = false,
                ),
                anonymousUserId = null,
            )
            AuthTransitionResult(
                newState = updated,
                clearAnonymousStorage = true,
                shouldValidateRemote = false,
                logReason = "Adopted previously validated session for $sessionUserId",
            )
        } else {
            AuthTransitionResult(
                newState = state,
                clearAnonymousStorage = true,
                shouldValidateRemote = true,
                userIdToValidate = sessionUserId,
                logReason = "Requesting remote validation for rehydrated session $sessionUserId",
            )
        }
    }

    fun onRemoteValidationCompleted(
        state: AuthMachineState,
        userId: String,
        isSuccessOrTransient: Boolean,
        isDefinitiveRejection: Boolean,
        email: String?,
    ): AuthTransitionResult {
        if (isDefinitiveRejection) {
            val updated = state.copy(
                authState = AuthState.Unauthenticated,
                validatedUserId = null,
                anonymousUserId = null,
            )
            return AuthTransitionResult(
                newState = updated,
                clearAnonymousStorage = true,
                clearLocalStorage = true,
                logReason = "Remote session definitively rejected; clearing auth",
            )
        }

        if (isSuccessOrTransient) {
            val updated = state.copy(
                authState = AuthState.Authenticated(
                    userId = userId,
                    email = email,
                    isAnonymous = false,
                ),
                validatedUserId = userId,
                anonymousUserId = null,
            )
            return AuthTransitionResult(
                newState = updated,
                clearAnonymousStorage = true,
                shouldValidateRemote = false,
                logReason = "Remote validation successful or transient; retaining cached session",
            )
        }

        return AuthTransitionResult(
            newState = state,
            isDropped = true,
            logReason = "Remote validation outcome was non-terminal",
        )
    }

    fun onSessionStatusNotAuthenticated(
        state: AuthMachineState,
        hasActiveSession: Boolean,
        hasAnonIdInStorage: Boolean = false,
    ): AuthTransitionResult {
        if (state.anonymousUserId != null || hasAnonIdInStorage) {
            return AuthTransitionResult(
                newState = state,
                isDropped = true,
                logReason = "Ignoring NotAuthenticated because anonymous user is active",
            )
        }

        if (state.inFlightEpoch != null) {
            return AuthTransitionResult(
                newState = state,
                isDropped = true,
                logReason = "Ignoring NotAuthenticated because explicit auth mutation is in flight",
            )
        }

        val currentAuth = state.authState as? AuthState.Authenticated
        if (currentAuth != null && !currentAuth.isAnonymous && hasActiveSession) {
            return AuthTransitionResult(
                newState = state,
                isDropped = true,
                logReason = "Ignoring stale NotAuthenticated because active session is present and authenticated",
            )
        }

        val updated = state.copy(
            authState = AuthState.Unauthenticated,
            validatedUserId = null,
        )
        return AuthTransitionResult(
            newState = updated,
            logReason = "Transitioned to Unauthenticated",
        )
    }

    fun onSessionStatusInitializing(
        state: AuthMachineState,
    ): AuthTransitionResult {
        if (state.authState is AuthState.Authenticated) {
            return AuthTransitionResult(
                newState = state,
                isDropped = true,
                logReason = "Ignoring Initializing event because state is already Authenticated",
            )
        }
        if (state.anonymousUserId != null) {
            return AuthTransitionResult(
                newState = state,
                isDropped = true,
                logReason = "Ignoring Initializing event because anonymous user is active",
            )
        }

        val updated = state.copy(authState = AuthState.Loading)
        return AuthTransitionResult(
            newState = updated,
            logReason = "Transitioned to Loading",
        )
    }

    fun onSessionStatusRefreshFailure(
        state: AuthMachineState,
        isDefinitiveRejection: Boolean,
    ): AuthTransitionResult {
        if (isDefinitiveRejection) {
            val updated = state.copy(
                authState = AuthState.Unauthenticated,
                validatedUserId = null,
                anonymousUserId = null,
            )
            return AuthTransitionResult(
                newState = updated,
                clearAnonymousStorage = true,
                clearLocalStorage = true,
                logReason = "Refresh failure was definitive rejection; clearing auth",
            )
        }

        return AuthTransitionResult(
            newState = state,
            isDropped = true,
            logReason = "Refresh failure was transient; retaining cached session",
        )
    }

    fun decideAppGateTransition(
        currentGateScreen: String,
        authState: AuthState,
        cachedProfiles: List<NuvioProfile>,
        isOnline: Boolean,
    ): AppGateTransitionDecision {
        val hasCachedProfileAccess = cachedProfiles.isNotEmpty() && authState !is AuthState.Authenticated
        val allowCachedProfileAccess = hasCachedProfileAccess && (!isOnline || currentGateScreen != "Auth")

        return when (authState) {
            is AuthState.Loading -> {
                if (hasCachedProfileAccess) {
                    AppGateTransitionDecision.EnterProfileGate(cachedProfiles, syncOnEnter = false)
                } else {
                    AppGateTransitionDecision.ShowLoading
                }
            }
            is AuthState.Unauthenticated -> {
                if (allowCachedProfileAccess) {
                    AppGateTransitionDecision.EnterProfileGate(cachedProfiles, syncOnEnter = false)
                } else {
                    AppGateTransitionDecision.ShowAuth
                }
            }
            is AuthState.Authenticated -> {
                if (currentGateScreen == "Loading" || currentGateScreen == "Auth") {
                    AppGateTransitionDecision.EnterProfileGate(cachedProfiles, syncOnEnter = true)
                } else {
                    AppGateTransitionDecision.StayOnCurrent
                }
            }
        }
    }
}

internal fun maskEmail(email: String?): String {
    if (email.isNullOrBlank()) return "<empty>"
    val trimmed = email.trim()
    val atIndex = trimmed.indexOf('@')
    if (atIndex <= 1) return "***@***"
    val local = trimmed.substring(0, atIndex)
    val domain = trimmed.substring(atIndex + 1)
    val maskedLocal = local.first() + "***" + (if (local.length > 2) local.last() else "")
    return "$maskedLocal@$domain"
}

internal fun maskId(id: String?): String {
    if (id.isNullOrBlank()) return "<empty>"
    val trimmed = id.trim()
    return if (trimmed.length <= 8) trimmed else "${trimmed.take(4)}...${trimmed.takeLast(4)}"
}
