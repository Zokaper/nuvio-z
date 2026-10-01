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
sealed interface InFlightAuthIntent {
    val epoch: Long

    data class EmailSignIn(
        override val epoch: Long,
        val email: String,
    ) : InFlightAuthIntent

    data class EmailSignUp(
        override val epoch: Long,
        val email: String,
    ) : InFlightAuthIntent

    data class Anonymous(
        override val epoch: Long,
    ) : InFlightAuthIntent

    data class SignOut(
        override val epoch: Long,
    ) : InFlightAuthIntent

    data class ExternalSession(
        override val epoch: Long,
        val expectedUserId: String? = null,
    ) : InFlightAuthIntent
}

data class ValidationRequest(
    val epoch: Long,
    val userId: String,
    val email: String?,
)

sealed interface RemoteValidationResult {
    val userId: String

    data class Success(
        override val userId: String,
        val email: String?,
    ) : RemoteValidationResult

    data class TransientFailure(
        override val userId: String,
        val email: String?,
    ) : RemoteValidationResult

    data class DefinitiveRejection(
        override val userId: String,
    ) : RemoteValidationResult
}

data class AuthMachineState(
    val authState: AuthState = AuthState.Loading,
    val validatedUserId: String? = null,
    val anonymousUserId: String? = null,
    val currentEpoch: Long = 0L,
    val inFlightIntent: InFlightAuthIntent? = null,
    val activeValidationRequest: ValidationRequest? = null,
) {
    val inFlightEpoch: Long? get() = inFlightIntent?.epoch
}

data class AuthTransitionResult(
    val newState: AuthMachineState,
    val clearAnonymousStorage: Boolean = false,
    val saveAnonymousStorage: String? = null,
    val clearLocalStorage: Boolean = false,
    val shouldValidateRemote: Boolean = false,
    val validationRequest: ValidationRequest? = null,
    val userIdToValidate: String? = validationRequest?.userId,
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
                inFlightIntent = null,
                activeValidationRequest = null,
            )
        } else {
            AuthMachineState(
                authState = AuthState.Loading,
                validatedUserId = null,
                anonymousUserId = null,
                currentEpoch = currentEpoch,
                inFlightIntent = null,
                activeValidationRequest = null,
            )
        }
    }

    fun onExplicitSignInStarted(
        state: AuthMachineState,
        intent: InFlightAuthIntent,
    ): AuthMachineState {
        val nextEpoch = maxOf(state.currentEpoch, intent.epoch)
        return state.copy(
            currentEpoch = nextEpoch,
            inFlightIntent = intent,
            activeValidationRequest = null,
        )
    }

    fun onExplicitSignInStarted(
        state: AuthMachineState,
        epoch: Long,
    ): AuthMachineState {
        return onExplicitSignInStarted(state, InFlightAuthIntent.ExternalSession(epoch))
    }

    fun onExplicitSignInSucceeded(
        state: AuthMachineState,
        epoch: Long,
        userId: String,
        email: String?,
    ): AuthTransitionResult {
        if (epoch < state.currentEpoch || (state.inFlightIntent != null && state.inFlightIntent.epoch != epoch)) {
            return AuthTransitionResult(
                newState = state,
                isDropped = true,
                logReason = "Explicit sign-in epoch $epoch superseded by currentEpoch ${state.currentEpoch}",
            )
        }
        if (userId.isBlank()) {
            return AuthTransitionResult(
                newState = state.copy(
                    inFlightIntent = if (state.inFlightIntent?.epoch == epoch) null else state.inFlightIntent,
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
            inFlightIntent = if (state.inFlightIntent?.epoch == epoch) null else state.inFlightIntent,
            activeValidationRequest = null,
        )
        return AuthTransitionResult(
            newState = updated,
            clearAnonymousStorage = true,
            shouldValidateRemote = false,
            logReason = "Explicit sign-in adopted authoritatively for ${maskId(userId)}",
        )
    }

    fun onExplicitSignInFailed(
        state: AuthMachineState,
        epoch: Long,
    ): AuthMachineState {
        return if (state.inFlightIntent?.epoch == epoch) {
            state.copy(inFlightIntent = null)
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
            inFlightIntent = null,
            activeValidationRequest = null,
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
        val nextEpoch = maxOf(state.currentEpoch, epoch)
        val updated = state.copy(
            authState = AuthState.Unauthenticated,
            validatedUserId = null,
            anonymousUserId = null,
            currentEpoch = nextEpoch,
            inFlightIntent = null,
            activeValidationRequest = null,
        )
        return AuthTransitionResult(
            newState = updated,
            clearAnonymousStorage = true,
            clearLocalStorage = true,
            logReason = "Explicit sign-out completed at epoch $nextEpoch",
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

        // Status carries account identity, not the credential request that produced it.
        // Even a matching-email old session must wait for the explicit SDK call to succeed.
        if (state.inFlightIntent != null) {
            return AuthTransitionResult(newState = state, isDropped = true,
                logReason = "Session status deferred during explicit auth operation")
        }

        // 2. If NO in-flight intent:
        val currentAuth = state.authState as? AuthState.Authenticated
        if (currentAuth != null && !currentAuth.isAnonymous && currentAuth.userId == sessionUserId && state.validatedUserId == sessionUserId) {
            return AuthTransitionResult(
                newState = state,
                clearAnonymousStorage = true,
                shouldValidateRemote = false,
                logReason = "Session already authoritative for ${maskId(sessionUserId)}",
            )
        }

        val valRequest = ValidationRequest(
            epoch = state.currentEpoch,
            userId = sessionUserId,
            email = email,
        )
        val updated = state.copy(activeValidationRequest = valRequest)
        return AuthTransitionResult(
            newState = updated,
            clearAnonymousStorage = true,
            shouldValidateRemote = true,
            validationRequest = valRequest,
            userIdToValidate = sessionUserId,
            logReason = "Requesting remote validation for session ${maskId(sessionUserId)} at epoch ${state.currentEpoch}",
        )
    }

    fun onRemoteValidationCompleted(
        state: AuthMachineState,
        request: ValidationRequest,
        result: RemoteValidationResult,
    ): AuthTransitionResult {
        if (request.epoch != state.currentEpoch || state.activeValidationRequest != request) {
            return AuthTransitionResult(
                newState = state,
                isDropped = true,
                clearLocalStorage = false,
                clearAnonymousStorage = false,
                logReason = "Stale remote validation outcome for ${maskId(request.userId)} dropped (reqEpoch=${request.epoch} < currentEpoch=${state.currentEpoch})",
            )
        }

        if (state.inFlightIntent != null) {
            return AuthTransitionResult(
                newState = state,
                isDropped = true,
                clearLocalStorage = false,
                clearAnonymousStorage = false,
                logReason = "Remote validation outcome for ${maskId(request.userId)} dropped because in-flight intent is active",
            )
        }

        val currentAuth = state.authState as? AuthState.Authenticated
        if (currentAuth != null && !currentAuth.isAnonymous && currentAuth.userId != request.userId) {
            return AuthTransitionResult(
                newState = state,
                isDropped = true,
                clearLocalStorage = false,
                clearAnonymousStorage = false,
                logReason = "Remote validation outcome for ${maskId(request.userId)} dropped because active user is ${maskId(currentAuth.userId)}",
            )
        }

        return when (result) {
            is RemoteValidationResult.DefinitiveRejection -> {
                val updated = state.copy(
                    authState = AuthState.Unauthenticated,
                    validatedUserId = null,
                    anonymousUserId = null,
                    activeValidationRequest = null,
                )
                AuthTransitionResult(
                    newState = updated,
                    clearAnonymousStorage = true,
                    clearLocalStorage = true,
                    logReason = "Remote session definitively rejected for ${maskId(request.userId)}; clearing auth",
                )
            }
            is RemoteValidationResult.Success -> {
                val updated = state.copy(
                    authState = AuthState.Authenticated(
                        userId = result.userId,
                        email = result.email,
                        isAnonymous = false,
                    ),
                    validatedUserId = result.userId,
                    anonymousUserId = null,
                    activeValidationRequest = null,
                )
                AuthTransitionResult(
                    newState = updated,
                    clearAnonymousStorage = true,
                    shouldValidateRemote = false,
                    logReason = "Remote validation successful for ${maskId(result.userId)}",
                )
            }
            is RemoteValidationResult.TransientFailure -> {
                val updated = state.copy(
                    authState = AuthState.Authenticated(
                        userId = result.userId,
                        email = result.email,
                        isAnonymous = false,
                    ),
                    validatedUserId = result.userId,
                    anonymousUserId = null,
                    activeValidationRequest = null,
                )
                AuthTransitionResult(
                    newState = updated,
                    clearAnonymousStorage = true,
                    shouldValidateRemote = false,
                    logReason = "Remote validation transient failure for ${maskId(result.userId)}; retaining cached session",
                )
            }
        }
    }

    fun onRemoteValidationCompleted(
        state: AuthMachineState,
        userId: String,
        isSuccessOrTransient: Boolean,
        isDefinitiveRejection: Boolean,
        email: String?,
        epoch: Long = state.currentEpoch,
    ): AuthTransitionResult {
        val request = state.activeValidationRequest ?: ValidationRequest(epoch, userId, email)
        val result = if (isDefinitiveRejection) {
            RemoteValidationResult.DefinitiveRejection(userId)
        } else if (isSuccessOrTransient) {
            RemoteValidationResult.Success(userId, email)
        } else {
            RemoteValidationResult.TransientFailure(userId, email)
        }
        return onRemoteValidationCompleted(state, request, result)
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

        if (state.inFlightIntent != null) {
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
            activeValidationRequest = null,
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
        failingUserId: String? = null,
    ): AuthTransitionResult {
        if (state.inFlightIntent != null) {
            return AuthTransitionResult(
                newState = state,
                isDropped = true,
                clearLocalStorage = false,
                logReason = "RefreshFailure dropped because explicit mutation is in flight",
            )
        }

        val currentAuth = state.authState as? AuthState.Authenticated
        if (currentAuth != null && currentAuth.isAnonymous) {
            return AuthTransitionResult(
                newState = state,
                isDropped = true,
                clearLocalStorage = false,
                logReason = "RefreshFailure dropped because active user is anonymous",
            )
        }

        if (failingUserId != null && currentAuth != null && currentAuth.userId != failingUserId) {
            return AuthTransitionResult(
                newState = state,
                isDropped = true,
                clearLocalStorage = false,
                logReason = "RefreshFailure for ${maskId(failingUserId)} dropped because active user is ${maskId(currentAuth.userId)}",
            )
        }

        if (!isDefinitiveRejection) {
            return AuthTransitionResult(
                newState = state,
                isDropped = true,
                clearLocalStorage = false,
                logReason = "Refresh failure was transient; retaining cached session",
            )
        }

        val updated = state.copy(
            authState = AuthState.Unauthenticated,
            validatedUserId = null,
            anonymousUserId = null,
            activeValidationRequest = null,
        )
        return AuthTransitionResult(
            newState = updated,
            clearAnonymousStorage = true,
            clearLocalStorage = true,
            logReason = "Refresh failure was definitive rejection; clearing auth",
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

internal fun safeAuthStateDescription(state: AuthState): String = when (state) {
    is AuthState.Authenticated -> "Authenticated(userId=${maskId(state.userId)}, isAnon=${state.isAnonymous})"
    is AuthState.Loading -> "Loading"
    is AuthState.Unauthenticated -> "Unauthenticated"
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
    return if (trimmed.length <= 8) "***" else "${trimmed.take(4)}...${trimmed.takeLast(4)}"
}
