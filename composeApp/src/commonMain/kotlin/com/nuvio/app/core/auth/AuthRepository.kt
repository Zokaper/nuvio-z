package com.nuvio.app.core.auth

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.storage.LocalAccountDataCleaner
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

object AuthRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("AuthRepository")

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val stateLock = SynchronizedObject()
    private var machineState = AuthMachineState()
    private val operationEpoch = atomic(0L)

    private var initialized = false
    private var sessionStatusJob: Job? = null

    fun initialize() {
        val shouldStart = synchronized(stateLock) {
            if (initialized) return@synchronized false
            initialized = true
            true
        }
        if (!shouldStart) return

        val savedAnonId = AuthStorage.loadAnonymousUserId()
        synchronized(stateLock) {
            machineState = AuthStateMachine.onInitialize(savedAnonId, operationEpoch.value)
            _state.value = machineState.authState
        }
        log.i { "[Auth #init] Initialized with state=${safeAuthStateDescription(_state.value)} (savedAnon=${maskId(savedAnonId)})" }

        sessionStatusJob = scope.launch {
            SupabaseProvider.client.auth.sessionStatus.collect { status ->
                processSessionStatus(status)
            }
        }
    }

    private suspend fun processSessionStatus(status: SessionStatus) {
        when (status) {
            is SessionStatus.Authenticated -> {
                val user = status.session.user
                val userId = user?.id.orEmpty()
                log.i { "[Auth #status] SessionStatus.Authenticated received (userId=${maskId(userId)}, email=${maskEmail(user?.email)})" }
                if (userId.isBlank()) return

                val transition = synchronized(stateLock) {
                    AuthStateMachine.onSessionStatusAuthenticated(machineState, userId, user?.email).also {
                        machineState = it.newState
                    }
                }

                if (transition.isDropped) {
                    log.i { "[Auth #status] ${transition.logReason}" }
                    return
                }

                if (transition.clearAnonymousStorage) {
                    AuthStorage.clearAnonymousUserId()
                }

                if (!transition.shouldValidateRemote) {
                    _state.value = transition.newState.authState
                    log.i { "[Auth #status] ${transition.logReason}; state is ${safeAuthStateDescription(_state.value)}" }
                    return
                }

                val validationReq = transition.validationRequest ?: ValidationRequest(machineState.currentEpoch, userId, user?.email)
                log.i { "[Auth #status] Validating remote session for ${maskId(userId)} at epoch ${validationReq.epoch}" }
                val validationResult = performRemoteValidation(userId, user?.email)
                log.i { "[Auth #status] Remote validation result for ${maskId(userId)}: $validationResult" }

                val outcome = synchronized(stateLock) {
                    AuthStateMachine.onRemoteValidationCompleted(
                        state = machineState,
                        request = validationReq,
                        result = validationResult,
                    ).also { machineState = it.newState }
                }

                if (outcome.isDropped) {
                    log.i { "[Auth #status] ${outcome.logReason}" }
                    return
                }

                if (outcome.clearLocalStorage) {
                    log.w { "[Auth #status] Remote session rejected; clearing local session" }
                    clearLocalSessionAfterRemoteInvalidation()
                    _state.value = outcome.newState.authState
                    return
                }

                if (outcome.clearAnonymousStorage) {
                    AuthStorage.clearAnonymousUserId()
                }
                _state.value = outcome.newState.authState
            }

            is SessionStatus.NotAuthenticated -> {
                log.i { "[Auth #status] SessionStatus.NotAuthenticated received (isSignOut=${status.isSignOut})" }
                val hasActiveSession = SupabaseProvider.client.auth.currentSessionOrNull() != null
                val hasAnonStorage = AuthStorage.loadAnonymousUserId() != null

                val transition = synchronized(stateLock) {
                    AuthStateMachine.onSessionStatusNotAuthenticated(
                        state = machineState,
                        hasActiveSession = hasActiveSession,
                        hasAnonIdInStorage = hasAnonStorage,
                    ).also { machineState = it.newState }
                }

                if (transition.isDropped) {
                    log.i { "[Auth #status] Dropped NotAuthenticated event (${transition.logReason})" }
                    return
                }
                _state.value = transition.newState.authState
                log.i { "[Auth #status] ${transition.logReason}; state is ${safeAuthStateDescription(_state.value)}" }
            }

            is SessionStatus.Initializing -> {
                log.i { "[Auth #status] SessionStatus.Initializing received" }
                val transition = synchronized(stateLock) {
                    AuthStateMachine.onSessionStatusInitializing(machineState).also {
                        machineState = it.newState
                    }
                }
                if (transition.isDropped) {
                    log.i { "[Auth #status] Dropped Initializing event (${transition.logReason})" }
                    return
                }
                _state.value = transition.newState.authState
            }

            is SessionStatus.RefreshFailure -> {
                val exception: Throwable? = when (val cause = status.cause) {
                    is RefreshFailureCause.InternalServerError -> cause.exception
                    is RefreshFailureCause.NetworkError -> cause.exception
                    else -> null
                }
                log.w(exception) { "[Auth #status] SessionStatus.RefreshFailure: ${status.cause}" }
                val isDefinitive = when (val cause = status.cause) {
                    is RefreshFailureCause.InternalServerError ->
                        OfficialSessionRejection.isSessionGone(cause.exception, ::isInvalidRemoteSessionError)
                    else -> false
                }
                val currentUserId = SupabaseProvider.client.auth.currentSessionOrNull()?.user?.id
                val transition = synchronized(stateLock) {
                    AuthStateMachine.onSessionStatusRefreshFailure(
                        state = machineState,
                        isDefinitiveRejection = isDefinitive,
                        failingUserId = currentUserId,
                    ).also { machineState = it.newState }
                }
                if (transition.isDropped) {
                    log.i { "[Auth #status] ${transition.logReason}" }
                    return
                }
                if (transition.clearLocalStorage) {
                    log.w { "[Auth #status] Refresh failure was definitive session invalidation; clearing local auth" }
                    clearLocalSessionAfterRemoteInvalidation()
                    _state.value = transition.newState.authState
                    return
                }
                _state.value = transition.newState.authState
            }
        }
    }

    private suspend fun performRemoteValidation(userId: String, email: String?): RemoteValidationResult {
        if (userId.isBlank()) return RemoteValidationResult.DefinitiveRejection(userId)
        val alreadyValidated = synchronized(stateLock) { machineState.validatedUserId == userId }
        if (alreadyValidated) return RemoteValidationResult.Success(userId, email)

        return runCatching {
            SupabaseProvider.client.auth.retrieveUserForCurrentSession(false)
            RemoteValidationResult.Success(userId, email)
        }.getOrElse { e ->
            if (OfficialSessionRejection.isSessionGone(e, ::isInvalidRemoteSessionError)) { // Z: vanilla-bug patch V2, drop-at-next-sync
                log.w(e) { "Stored Supabase session for ${maskId(userId)} rejected definitively" }
                RemoteValidationResult.DefinitiveRejection(userId)
            } else {
                log.w(e) { "Unable to validate stored Supabase session for ${maskId(userId)}; keeping cached auth state" }
                RemoteValidationResult.TransientFailure(userId, email)
            }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun signInAnonymously() {
        val opId = operationEpoch.incrementAndGet()
        _error.value = null
        val userId = Uuid.random().toString()
        log.i { "[Auth #$opId] Anonymous sign-in requested; generated userId=${maskId(userId)}" }

        val transition = synchronized(stateLock) {
            AuthStateMachine.onExplicitAnonymousSignIn(machineState, opId, userId).also {
                machineState = it.newState
            }
        }

        transition.saveAnonymousStorage?.let {
            AuthStorage.saveAnonymousUserId(it)
        }
        _state.value = transition.newState.authState
        log.i { "[Auth #$opId] Adopted anonymous authenticated state" }
    }

    suspend fun signUpWithEmail(email: String, password: String): Result<Unit> {
        val opId = operationEpoch.incrementAndGet()
        val cleanEmail = email.trim()
        _error.value = null
        log.i { "[Auth #$opId] signUpWithEmail started (email=${maskEmail(cleanEmail)})" }

        synchronized(stateLock) {
            machineState = AuthStateMachine.onExplicitSignInStarted(
                state = machineState,
                intent = InFlightAuthIntent.EmailSignUp(epoch = opId, email = cleanEmail),
            )
        }

        return runCatching {
            SupabaseProvider.client.auth.signUpWith(Email) {
                this.email = cleanEmail
                this.password = password
            }

            val session = SupabaseProvider.client.auth.currentSessionOrNull()
            val user = session?.user ?: SupabaseProvider.client.auth.currentUserOrNull()
            val userId = user?.id.orEmpty().trim()
            val userEmail = user?.email.orEmpty().trim()
            val emailMatches = userEmail.equals(cleanEmail, ignoreCase = true)
            log.i { "[Auth #$opId] Supabase signUpWith returned (hasSession=${session != null}, userId=${maskId(userId)}, emailMatches=$emailMatches)" }

            if (userId.isNotBlank() && emailMatches) {
                val transition = synchronized(stateLock) {
                    AuthStateMachine.onExplicitSignInSucceeded(
                        state = machineState,
                        epoch = opId,
                        userId = userId,
                        email = userEmail,
                    ).also { machineState = it.newState }
                }

                if (transition.clearAnonymousStorage) {
                    AuthStorage.clearAnonymousUserId()
                }
                _state.value = transition.newState.authState
                log.i { "[Auth #$opId] Adopted authenticated state (userId=${maskId(userId)})" }
            } else {
                synchronized(stateLock) {
                    machineState = AuthStateMachine.onExplicitSignInFailed(machineState, opId)
                }
                log.i { "[Auth #$opId] Sign-up created user but no immediate matching session returned" }
            }
            Unit
        }.onFailure { e ->
            synchronized(stateLock) {
                machineState = AuthStateMachine.onExplicitSignInFailed(machineState, opId)
            }
            log.e(e) { "[Auth #$opId] Email sign-up failed: ${e.message}" }
            if (_error.value == null) {
                _error.value = e.safeAuthErrorDescription()
                    ?: getString(Res.string.auth_sign_up_failed)
            }
        }
    }

    suspend fun signInWithEmail(email: String, password: String): Result<Unit> {
        val opId = operationEpoch.incrementAndGet()
        val cleanEmail = email.trim()
        _error.value = null
        log.i { "[Auth #$opId] signInWithEmail started (email=${maskEmail(cleanEmail)})" }

        synchronized(stateLock) {
            machineState = AuthStateMachine.onExplicitSignInStarted(
                state = machineState,
                intent = InFlightAuthIntent.EmailSignIn(epoch = opId, email = cleanEmail),
            )
        }

        return runCatching {
            SupabaseProvider.client.auth.signInWith(Email) {
                this.email = cleanEmail
                this.password = password
            }

            val session = SupabaseProvider.client.auth.currentSessionOrNull()
            val user = session?.user ?: SupabaseProvider.client.auth.currentUserOrNull()
            val userId = user?.id.orEmpty().trim()
            val userEmail = user?.email.orEmpty().trim()
            val emailMatches = userEmail.equals(cleanEmail, ignoreCase = true)
            log.i { "[Auth #$opId] Supabase signInWith returned (hasSession=${session != null}, userId=${maskId(userId)}, emailMatches=$emailMatches)" }

            if (userId.isBlank() || !emailMatches) {
                synchronized(stateLock) {
                    machineState = AuthStateMachine.onExplicitSignInFailed(machineState, opId)
                }
                val errorMsg = getString(Res.string.auth_sign_in_failed)
                _error.value = errorMsg
                log.e { "[Auth #$opId] Sign-in session did not match attempted credentials (expected=${maskEmail(cleanEmail)}, actual=${maskEmail(userEmail)}); failing explicitly" }
                throw IllegalStateException("Authenticated session did not match attempted credentials")
            }

            val transition = synchronized(stateLock) {
                AuthStateMachine.onExplicitSignInSucceeded(
                    state = machineState,
                    epoch = opId,
                    userId = userId,
                    email = userEmail,
                ).also { machineState = it.newState }
            }

            if (transition.clearAnonymousStorage) {
                AuthStorage.clearAnonymousUserId()
            }
            _state.value = transition.newState.authState
            log.i { "[Auth #$opId] Adopted authenticated state (userId=${maskId(userId)})" }
            Unit
        }.onFailure { e ->
            synchronized(stateLock) {
                machineState = AuthStateMachine.onExplicitSignInFailed(machineState, opId)
            }
            log.e(e) { "[Auth #$opId] Email sign-in failed: ${e.message}" }
            if (_error.value == null) {
                _error.value = e.safeAuthErrorDescription()
                    ?: getString(Res.string.auth_sign_in_failed)
            }
        }
    }

    suspend fun signOut(): Result<Unit> {
        val opId = operationEpoch.incrementAndGet()
        log.i { "[Auth #$opId] signOut requested" }
        _error.value = null

        val anonymousRead = runCatching { AuthStorage.loadAnonymousUserId() }
        val wasAnonymous = anonymousRead.getOrNull() != null
        val anonymousClear = runCatching { AuthStorage.clearAnonymousUserId() }

        val transition = synchronized(stateLock) {
            AuthStateMachine.onExplicitSignOut(machineState, opId).also {
                machineState = it.newState
            }
        }

        val remoteSignOut = if (wasAnonymous) {
            Result.success(Unit)
        } else {
            runCatching { SupabaseProvider.client.auth.signOut() }
        }

        val fallbackSessionClear = if (remoteSignOut.isFailure) {
            runCatching { SupabaseProvider.client.auth.clearSession() }
                .onFailure { error -> log.w(error) { "[Auth #$opId] Failed to clear Supabase session after sign-out failure" } }
        } else {
            Result.success(Unit)
        }
        val localCleanup = runCatching { LocalAccountDataCleaner.wipe() }
        _state.value = transition.newState.authState
        log.i { "[Auth #$opId] Sign-out completed; transitioned to Unauthenticated" }

        val failure = anonymousRead.exceptionOrNull()
            ?: anonymousClear.exceptionOrNull()
            ?: remoteSignOut.exceptionOrNull()
            ?: fallbackSessionClear.exceptionOrNull()
            ?: localCleanup.exceptionOrNull()
        val cancellation = remoteSignOut.exceptionOrNull() as? CancellationException
            ?: fallbackSessionClear.exceptionOrNull() as? CancellationException
        if (cancellation != null) throw cancellation
        return if (failure == null) {
            Result.success(Unit)
        } else {
            log.e(failure) { "[Auth #$opId] Sign-out did not complete cleanly; all local cleanup steps were attempted" }
            _error.value = failure.message ?: runCatching {
                getString(Res.string.auth_sign_out_failed)
            }.getOrDefault("Sign out failed")
            Result.failure(failure)
        }
    }

    suspend fun prepareForServerSwitch(): Result<Unit> {
        val opId = operationEpoch.incrementAndGet()
        log.i { "[Auth #$opId] prepareForServerSwitch requested" }
        _error.value = null
        val anonymousClear = runCatching { AuthStorage.clearAnonymousUserId() }
        synchronized(stateLock) {
            machineState = AuthStateMachine.onExplicitSignOut(machineState, opId).newState
        }
        val sessionClear = runCatching { SupabaseProvider.client.auth.clearSession() }
        _state.value = AuthState.Unauthenticated
        val failure = anonymousClear.exceptionOrNull() ?: sessionClear.exceptionOrNull()
        val cancellation = sessionClear.exceptionOrNull() as? CancellationException
        if (cancellation != null) throw cancellation
        return if (failure == null) Result.success(Unit) else Result.failure(failure)
    }

    fun reinitialize() {
        val opId = operationEpoch.incrementAndGet()
        log.i { "[Auth #$opId] reinitialize requested" }
        sessionStatusJob?.cancel()
        sessionStatusJob = null
        synchronized(stateLock) {
            initialized = false
            machineState = AuthMachineState(authState = AuthState.Loading, currentEpoch = opId)
        }
        _state.value = AuthState.Loading
        initialize()
    }

    suspend fun signOutIfSessionInvalid(error: Throwable, source: String): Boolean {
        if (!OfficialSessionRejection.isSessionGone(error, ::isInvalidRemoteSessionError)) return false // Z: vanilla-bug patch V2, drop-at-next-sync

        log.w(error) { "$source failed because the current Supabase account/session is no longer valid; clearing local auth" }
        clearLocalSessionAfterRemoteInvalidation()
        return true
    }

    private suspend fun clearLocalSessionAfterRemoteInvalidation() {
        val opId = operationEpoch.incrementAndGet()
        log.w { "[Auth #$opId] Clearing local session after remote invalidation" }
        _error.value = null
        AuthStorage.clearAnonymousUserId()
        synchronized(stateLock) {
            machineState = AuthStateMachine.onExplicitSignOut(machineState, opId).newState
        }
        runCatching {
            SupabaseProvider.client.auth.clearSession()
        }.onFailure { e ->
            log.w(e) { "[Auth #$opId] Failed to clear Supabase session after remote invalidation; continuing local reset" }
        }
        val localCleanup = runCatching { LocalAccountDataCleaner.wipe() }
        _state.value = AuthState.Unauthenticated
        localCleanup.onFailure { error ->
            log.e(error) { "[Auth #$opId] Local account cleanup failed after remote session invalidation" }
        }
    }

    suspend fun deleteAccount(): Result<Unit> = runCatching {
        val opId = operationEpoch.incrementAndGet()
        log.i { "[Auth #$opId] deleteAccount requested" }
        _error.value = null
        SupabaseProvider.client.functions.invoke("delete-account")
        SupabaseProvider.client.auth.signOut()
        synchronized(stateLock) {
            machineState = AuthStateMachine.onExplicitSignOut(machineState, opId).newState
        }
        try {
            LocalAccountDataCleaner.wipe()
        } finally {
            _state.value = AuthState.Unauthenticated
        }
    }.onFailure { e ->
        log.e(e) { "Account deletion failed" }
        _error.value = e.message ?: getString(Res.string.auth_account_deletion_failed)
    }

    fun clearError() {
        _error.value = null
    }

    internal suspend fun processSessionStatusForTesting(status: SessionStatus) {
        processSessionStatus(status)
    }

    internal fun getMachineStateForTesting(): AuthMachineState = synchronized(stateLock) { machineState }

    internal fun resetForTesting(initialState: AuthMachineState = AuthMachineState()) {
        synchronized(stateLock) {
            machineState = initialState
            _state.value = initialState.authState
            _error.value = null
        }
    }

    internal fun allocateEpochForTesting(): Long = operationEpoch.incrementAndGet()

    private fun isInvalidRemoteSessionError(error: Throwable): Boolean {
        val restError = error.findCause<RestException>()
        if (restError?.statusCode == 401 || restError?.statusCode == 403) return true

        val message = buildString {
            append(error.message.orEmpty())
            if (restError != null) {
                append(' ')
                append(restError.error)
                append(' ')
                append(restError.description)
            }
        }.lowercase()

        return (
            "jwt" in message &&
                ("invalid" in message || "expired" in message || "malformed" in message)
            ) || (
            "user" in message &&
                ("does not exist" in message || "not found" in message || "deleted" in message)
            ) || (
            "foreign key" in message &&
                ("auth.users" in message || "user_id" in message)
            )
    }

    private inline fun <reified T : Throwable> Throwable.findCause(): T? {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) return current
            current = current.cause
        }
        return null
    }

    private fun Throwable.safeAuthErrorDescription(): String? =
        findCause<AuthRestException>()
            ?.errorDescription
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: findCause<RestException>()
                ?.description
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
}
