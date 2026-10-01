package com.nuvio.app.core.auth

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** An immutable request identity. Never format this object or its credentials in diagnostics. */
internal class OwnedAuthSession(
    val userId: String,
    val email: String?,
    val accessToken: String,
    val refreshToken: String,
    val payload: Any? = null,
    val expiresAtEpochMilliseconds: Long? = null,
) {
    fun sameSession(other: OwnedAuthSession?) =
        other != null && userId == other.userId && accessToken == other.accessToken

    override fun toString() = "AuthSession(redacted)"
}

internal enum class SessionValidation { Valid, NeedsRefresh, Unavailable }

/** The SDK/storage boundary; the coordinator below is used unchanged by production and async tests. */
internal interface AuthSessionPort {
    fun currentSession(): OwnedAuthSession?
    suspend fun loadStoredSession(): OwnedAuthSession? = null
    fun loadAnonymousId(): String?
    fun saveAnonymousId(id: String)
    fun clearAnonymousId()
    fun wipeAccountData()
    /** Return only the credential response, without installing it in the SDK. */
    suspend fun login(email: String, password: String, signUp: Boolean): OwnedAuthSession?
    suspend fun signOut()
    suspend fun clearSession()
    suspend fun deleteAccount()
    suspend fun validate(session: OwnedAuthSession): SessionValidation
    /** Refresh the captured token without importing/saving the returned session. */
    suspend fun refresh(session: OwnedAuthSession): OwnedAuthSession
    suspend fun importSession(session: OwnedAuthSession)
    fun isDefinitiveRefreshRejection(error: Throwable): Boolean
}

internal class AuthSessionCoordinator(
    private val port: AuthSessionPort,
    private val diagnostic: (String) -> Unit = {},
) {
    private val lock = SynchronizedObject()
    private val mutations = Mutex()
    private var machine = AuthMachineState()
    private var acceptedSession: OwnedAuthSession? = null
    private var pendingSession: OwnedAuthSession? = null
    private var allowRestoration = true
    private var appliedEpoch = 0L // accessed only while holding mutations
    private val mutableState = MutableStateFlow<AuthState>(AuthState.Loading)
    val state = mutableState.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()

    fun initialize() = synchronized(lock) {
        machine = AuthStateMachine.onInitialize(port.loadAnonymousId(), machine.currentEpoch + 1)
        acceptedSession = null
        pendingSession = null
        allowRestoration = true
        mutableError.value = null
        mutableState.value = machine.authState
    }

    fun clearError() = synchronized(lock) { mutableError.value = null }

    /** Read-only storage loading, with the import fenced just like an explicit login. */
    suspend fun restoreStoredSession() {
        val epoch = synchronized(lock) {
            if (!allowRestoration || machine.inFlightIntent != null) return
            machine.currentEpoch
        }
        val restored = mutations.withLock {
            if (!synchronized(lock) { owns(epoch) && allowRestoration }) return@withLock null
            val session = try { port.loadStoredSession() } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                diagnostic("auth session storage unavailable")
                null
            }
            if (!synchronized(lock) { owns(epoch) && allowRestoration }) return@withLock null
            if (session == null) {
                synchronized(lock) {
                    if (owns(epoch) && allowRestoration) {
                        allowRestoration = false
                        publish(AuthStateMachine.onSessionStatusNotAuthenticated(machine, false, port.loadAnonymousId() != null))
                    }
                }
                return@withLock null
            }
            port.importSession(session)
            session.takeIf { synchronized(lock) { owns(epoch) && allowRestoration } }
        }
        restored?.let { authenticated(it) }
    }

    private fun ownsAccess(epoch: Long, session: OwnedAuthSession) =
        owns(epoch) && machine.inFlightIntent == null &&
            (machine.authState as? AuthState.Authenticated)?.let { !it.isAnonymous && it.userId == session.userId } == true &&
            session.sameSession(port.currentSession())

    /** Social must not import an old official refresh over a newer login or sign-out. */
    suspend fun accessToken(session: OwnedAuthSession, refresh: Boolean): String? {
        val epoch = synchronized(lock) {
            machine.currentEpoch.takeIf { ownsAccess(it, session) }
        } ?: return null
        if (!refresh) return synchronized(lock) { session.accessToken.takeIf { ownsAccess(epoch, session) } }
        return mutations.withLock {
            if (!synchronized(lock) { ownsAccess(epoch, session) }) return@withLock null
            val refreshed = try { port.refresh(session) } catch (error: Throwable) {
                if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                return@withLock null // Validation, not a token consumer's unowned failure, settles rejection.
            }
            if (refreshed.userId != session.userId || !synchronized(lock) { ownsAccess(epoch, session) }) return@withLock null
            port.importSession(refreshed)
            synchronized(lock) {
                if (!owns(epoch) || machine.inFlightIntent != null || !refreshed.sameSession(port.currentSession())) return@withLock null
                acceptedSession = refreshed
                pendingSession = null
                publish(AuthStateMachine.onExplicitSignInSucceeded(machine, epoch, refreshed.userId, refreshed.email))
                refreshed.accessToken
            }
        }
    }

    private fun begin(intent: (Long) -> InFlightAuthIntent): Long = synchronized(lock) {
        val epoch = machine.currentEpoch + 1
        machine = AuthStateMachine.onExplicitSignInStarted(machine, intent(epoch))
        pendingSession = null
        allowRestoration = false
        mutableError.value = null
        epoch
    }

    private fun owns(epoch: Long) = machine.currentEpoch == epoch

    private fun publish(transition: AuthTransitionResult) {
        // All callers hold lock. Storage and public state cannot outlive the transition's authority.
        if (transition.isDropped) return
        transition.saveAnonymousStorage?.let(port::saveAnonymousId)
        if (transition.clearAnonymousStorage) port.clearAnonymousId()
        machine = transition.newState
        mutableState.value = machine.authState
    }

    suspend fun login(email: String, password: String, signUp: Boolean, failureMessage: String): Result<Unit> {
        val cleanEmail = email.trim()
        val epoch = begin {
            if (signUp) InFlightAuthIntent.EmailSignUp(it, cleanEmail) else InFlightAuthIntent.EmailSignIn(it, cleanEmail)
        }
        return operation(epoch, failureMessage) {
            val session = port.login(cleanEmail, password, signUp)
            if (session == null && signUp) {
                // Email confirmation can create a user without creating a session.
                synchronized(lock) { if (owns(epoch)) machine = AuthStateMachine.onExplicitSignInFailed(machine, epoch) }
            } else {
                check(session != null && session.userId.isNotBlank() && session.email?.trim().equals(cleanEmail, true)) {
                    "Credential response has no matching session"
                }
                if (!synchronized(lock) { owns(epoch) }) return@operation
                port.importSession(session)
                synchronized(lock) {
                    if (owns(epoch)) {
                        acceptedSession = session
                        publish(AuthStateMachine.onExplicitSignInSucceeded(machine, epoch, session.userId, session.email))
                    }
                }
            }
        }
    }

    // Allocate authority synchronously, even when the UI schedules the suspend work below.
    fun beginAnonymous(): Long = begin { InFlightAuthIntent.Anonymous(it) }

    suspend fun completeAnonymous(epoch: Long, userId: String) = mutations.withLock {
        if (!synchronized(lock) { owns(epoch) }) return@withLock
        appliedEpoch = epoch
        // Remove a pre-existing official session so it cannot later supersede the local anonymous ID.
        port.clearSession()
        synchronized(lock) {
            if (owns(epoch)) {
                acceptedSession = null
                publish(AuthStateMachine.onExplicitAnonymousSignIn(machine, epoch, userId))
            }
        }
    }

    suspend fun signOut(failureMessage: String, serverSwitch: Boolean = false): Result<Unit> {
        val epoch = begin { InFlightAuthIntent.SignOut(it) }
        synchronized(lock) {
            if (owns(epoch)) {
                acceptedSession = null
                publish(AuthStateMachine.onExplicitSignOut(machine, epoch).let {
                    it.copy(newState = it.newState.copy(inFlightIntent = InFlightAuthIntent.SignOut(epoch)))
                })
            }
        }
        return operation(epoch, failureMessage, cleanupEvenIfQueued = true) {
            var failure: Throwable? = null
            try {
                if (serverSwitch) port.clearSession() else port.signOut()
            } catch (error: Throwable) {
                failure = error
            } finally {
                // Cancellation must not abandon account cleanup. New SDK mutations wait for this.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    try { port.clearSession() } catch (error: Throwable) { if (failure == null) failure = error }
                    if (!serverSwitch) port.wipeAccountData()
                }
                synchronized(lock) {
                    if (owns(epoch)) {
                        machine = machine.copy(inFlightIntent = null)
                        mutableState.value = machine.authState
                    }
                }
            }
            failure?.let { throw it }
        }
    }

    suspend fun deleteAccount(failureMessage: String): Result<Unit> {
        val epoch = begin { InFlightAuthIntent.ExternalSession(it) }
        return operation(epoch, failureMessage) {
            port.deleteAccount()
            port.clearSession()
            port.wipeAccountData()
            synchronized(lock) {
                if (owns(epoch)) {
                    acceptedSession = null
                    publish(AuthStateMachine.onExplicitSignOut(machine, epoch))
                }
            }
        }
    }

    suspend fun importExternalSession(session: OwnedAuthSession, failureMessage: String): Result<Unit> {
        val epoch = begin { InFlightAuthIntent.ExternalSession(it, session.userId) }
        return operation(epoch, failureMessage) {
            port.importSession(session)
            synchronized(lock) {
                if (owns(epoch)) {
                    acceptedSession = session
                    publish(AuthStateMachine.onExplicitSignInSucceeded(machine, epoch, session.userId, session.email))
                }
            }
        }
    }

    private suspend fun operation(
        epoch: Long,
        failureMessage: String,
        cleanupEvenIfQueued: Boolean = false,
        block: suspend () -> Unit,
    ): Result<Unit> = try {
        mutations.withLock {
            if (appliedEpoch > epoch || (!cleanupEvenIfQueued && !synchronized(lock) { owns(epoch) })) {
                return@withLock Result.failure<Unit>(CancellationException("Auth operation superseded"))
            }
            appliedEpoch = epoch
            block()
            Result.success(Unit)
        }
    } catch (error: Throwable) {
        synchronized(lock) {
            if (owns(epoch)) {
                machine = AuthStateMachine.onExplicitSignInFailed(machine, epoch)
                if (error !is CancellationException) mutableError.value = failureMessage
            }
        }
        // The diagnostic channel intentionally receives neither errors nor request/session identities.
        diagnostic(if (error is CancellationException) "auth operation cancelled" else "auth operation failed")
        if (error is CancellationException) throw error
        Result.failure(error)
    }

    suspend fun authenticated(session: OwnedAuthSession, external: Boolean = false) {
        val request = synchronized(lock) {
            if (machine.inFlightIntent != null || session.userId.isBlank() || !session.sameSession(port.currentSession())) return
            if (machine.activeValidationRequest != null && session.sameSession(pendingSession)) return
            val active = machine.authState as? AuthState.Authenticated
            if (!external && !allowRestoration && (active == null || active.isAnonymous || active.userId != session.userId)) return
            // Timer/status refreshes may replace the token of the same account; never a different identity.
            pendingSession = session
            val transition = AuthStateMachine.onSessionStatusAuthenticated(machine, session.userId, session.email)
            if (transition.isDropped) return
            machine = transition.newState
            if (!transition.shouldValidateRemote) {
                acceptedSession = session
                pendingSession = null
                publish(transition)
                return
            }
            transition.validationRequest!!
        }
        validate(request, session)
    }

    suspend fun refreshFailure() {
        // SDK RefreshFailure has no originating session. Ignore its cause; ask about *our* captured
        // current session instead. An old error is never labelled with the new account's identity.
        val captured = synchronized(lock) {
            if (machine.inFlightIntent != null || machine.activeValidationRequest != null) return
            val session = acceptedSession ?: return
            if ((machine.authState as? AuthState.Authenticated)?.isAnonymous != false) return
            val current = port.currentSession()
            if (current != null && !session.sameSession(current)) return
            val request = ValidationRequest(machine.currentEpoch, session.userId, session.email)
            pendingSession = session
            machine = machine.copy(activeValidationRequest = request)
            request to session
        }
        validate(captured.first, captured.second)
    }

    /** SDK timer refresh is disabled: its parser/import/clear bypass current authority. */
    suspend fun refreshDue(nowEpochMilliseconds: Long) {
        val captured = synchronized(lock) {
            if (machine.inFlightIntent != null || machine.activeValidationRequest != null) return
            val session = acceptedSession ?: return
            val expiresAt = session.expiresAtEpochMilliseconds ?: return
            if (expiresAt - nowEpochMilliseconds > 60_000 || !session.sameSession(port.currentSession())) return
            val request = ValidationRequest(machine.currentEpoch, session.userId, session.email)
            pendingSession = session
            machine = machine.copy(activeValidationRequest = request)
            request to session
        }
        try {
            confirmRejection(captured.first, captured.second)
        } finally {
            synchronized(lock) {
                if (owns(captured.first, captured.second)) {
                    machine = machine.copy(activeValidationRequest = null)
                    pendingSession = null
                }
            }
        }
    }

    suspend fun notAuthenticated(hasSession: Boolean) {
        val recheck = synchronized(lock) {
            if (machine.inFlightIntent != null || machine.activeValidationRequest != null) return
            if (allowRestoration) return // Our guarded loader, not the SDK's initial empty status, settles startup.
            if (acceptedSession != null) !hasSession
            else {
                publish(AuthStateMachine.onSessionStatusNotAuthenticated(machine, hasSession, port.loadAnonymousId() != null))
                false
            }
        }
        if (recheck) refreshFailure()
    }

    fun initializing() = synchronized(lock) {
        if (machine.inFlightIntent == null) publish(AuthStateMachine.onSessionStatusInitializing(machine))
    }

    private fun owns(request: ValidationRequest, session: OwnedAuthSession) =
        owns(request.epoch) && machine.inFlightIntent == null && machine.activeValidationRequest == request && pendingSession === session

    private suspend fun validate(request: ValidationRequest, session: OwnedAuthSession) {
        // No import, refresh, clear, storage mutation or epoch allocation in remote validation.
        val result = port.validate(session)
        if (result == SessionValidation.NeedsRefresh) {
            confirmRejection(request, session)
            return
        }
        synchronized(lock) {
            if (!owns(request, session)) return
            acceptedSession = session
            allowRestoration = false
            publish(AuthStateMachine.onRemoteValidationCompleted(machine, request,
                if (result == SessionValidation.Valid) RemoteValidationResult.Success(session.userId, session.email)
                else RemoteValidationResult.TransientFailure(session.userId, session.email)))
        }
    }

    private suspend fun confirmRejection(request: ValidationRequest, session: OwnedAuthSession) = mutations.withLock {
        if (!synchronized(lock) { owns(request, session) }) return@withLock
        val current = port.currentSession()
        if (current != null && !session.sameSession(current)) return@withLock
        val refreshed = try {
            port.refresh(session)
        } catch (error: Throwable) {
            if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
            if (!port.isDefinitiveRefreshRejection(error)) {
                synchronized(lock) {
                    if (owns(request, session)) {
                        acceptedSession = session
                        publish(AuthStateMachine.onRemoteValidationCompleted(machine, request,
                            RemoteValidationResult.TransientFailure(session.userId, session.email)))
                    }
                }
                return@withLock
            }
            if (!synchronized(lock) { owns(request, session) }) return@withLock
            // No newer login can install a session while this SDK mutation is held.
            port.clearSession()
            synchronized(lock) {
                if (owns(request, session)) {
                    port.wipeAccountData()
                    acceptedSession = null
                    allowRestoration = false
                    publish(AuthStateMachine.onRemoteValidationCompleted(machine, request,
                        RemoteValidationResult.DefinitiveRejection(session.userId)))
                }
            }
            return@withLock
        }
        if (!synchronized(lock) { owns(request, session) } || refreshed.userId != session.userId) return@withLock
        port.importSession(refreshed)
        synchronized(lock) {
            if (owns(request, session)) {
                acceptedSession = refreshed
                publish(AuthStateMachine.onRemoteValidationCompleted(machine, request,
                    RemoteValidationResult.Success(refreshed.userId, refreshed.email)))
            }
        }
    }
}
