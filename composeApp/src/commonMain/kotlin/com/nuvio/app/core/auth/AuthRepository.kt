package com.nuvio.app.core.auth

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.storage.LocalAccountDataCleaner
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionSource
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

object AuthRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("AuthRepository")
    private val lifecycleLock = SynchronizedObject()
    private var initialized = false
    private var sessionStatusJob: Job? = null
    private val coordinator = AuthSessionCoordinator(SupabaseAuthSessionPort) { message -> log.i { message } }
    val state = coordinator.state
    val error = coordinator.error

    fun initialize() = synchronized(lifecycleLock) {
        if (initialized) return
        initialized = true
        coordinator.initialize()
        sessionStatusJob = scope.launch {
            SupabaseProvider.client.auth.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        val session = status.session.owned() ?: return@collect
                        val source = status.source
                        val external = source is SessionSource.External ||
                            (source is SessionSource.SignIn && source.provider != Email)
                        coordinator.authenticated(session, external)
                    }
                    is SessionStatus.NotAuthenticated -> coordinator.notAuthenticated(SupabaseAuthSessionPort.currentSession() != null)
                    is SessionStatus.Initializing -> coordinator.initializing()
                    is SessionStatus.RefreshFailure -> coordinator.refreshFailure()
                }
            }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun signInAnonymously() {
        val epoch = coordinator.beginAnonymous()
        scope.launch { coordinator.completeAnonymous(epoch, Uuid.random().toString()) }
    }

    suspend fun signUpWithEmail(email: String, password: String) =
        coordinator.login(email, password, true, getString(Res.string.auth_sign_up_failed))

    suspend fun signInWithEmail(email: String, password: String) =
        coordinator.login(email, password, false, getString(Res.string.auth_sign_in_failed))

    suspend fun signOut() = coordinator.signOut(getString(Res.string.auth_sign_out_failed))

    suspend fun prepareForServerSwitch() = coordinator.signOut(getString(Res.string.auth_sign_out_failed), serverSwitch = true)

    fun reinitialize() = synchronized(lifecycleLock) {
        sessionStatusJob?.cancel()
        sessionStatusJob = null
        initialized = false
        initialize()
    }

    suspend fun signOutIfSessionInvalid(error: Throwable, source: String): Boolean {
        if (!looksInvalidSession(error)) return false
        // A request failure has no session provenance. Revalidate our current captured token,
        // never refresh/clear whatever happens to be in the SDK using the old request's cause.
        coordinator.refreshFailure()
        return state.value is AuthState.Unauthenticated
    }

    internal suspend fun importExternalSession(session: UserSession): Result<Unit> {
        val owned = session.owned() ?: return Result.failure(IllegalArgumentException("External session has no identity"))
        return coordinator.importExternalSession(owned, getString(Res.string.auth_sign_in_failed))
    }

    suspend fun deleteAccount() = coordinator.deleteAccount(getString(Res.string.auth_account_deletion_failed))
    fun clearError() = coordinator.clearError()
}

private object SupabaseAuthSessionPort : AuthSessionPort {
    override fun currentSession() = SupabaseProvider.client.auth.currentSessionOrNull()?.owned()
    override fun loadAnonymousId() = AuthStorage.loadAnonymousUserId()
    override fun saveAnonymousId(id: String) = AuthStorage.saveAnonymousUserId(id)
    override fun clearAnonymousId() = AuthStorage.clearAnonymousUserId()
    override fun wipeAccountData() = LocalAccountDataCleaner.wipe()
    override suspend fun login(email: String, password: String, signUp: Boolean): OwnedAuthSession? {
        // Use the same Email provider as Auth.signInWith, but capture its response before import.
        // Reading currentSession after a request would allow an unrelated stored/timer session.
        var result: OwnedAuthSession? = null
        val onSession: suspend (UserSession) -> Unit = { result = it.owned() }
        if (signUp) Email.signUp(SupabaseProvider.client, onSession, null) { this.email = email; this.password = password }
        else Email.login(SupabaseProvider.client, onSession, null) { this.email = email; this.password = password }
        return result
    }
    override suspend fun signOut() = SupabaseProvider.client.auth.signOut()
    override suspend fun clearSession() = SupabaseProvider.client.auth.clearSession()
    override suspend fun deleteAccount() { SupabaseProvider.client.functions.invoke("delete-account") }
    override suspend fun validate(session: OwnedAuthSession): SessionValidation = try {
        val user = withTimeout(10_000) { SupabaseProvider.client.auth.retrieveUser(session.accessToken) }
        if (user.id == session.userId) SessionValidation.Valid else SessionValidation.Unavailable
    } catch (error: Throwable) {
        if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
        if (looksInvalidSession(error)) SessionValidation.NeedsRefresh else SessionValidation.Unavailable
    }
    override suspend fun refresh(session: OwnedAuthSession): OwnedAuthSession =
        withTimeout(10_000) { SupabaseProvider.client.auth.refreshSession(session.refreshToken) }.owned()
            ?: throw IllegalStateException("Refresh has no identity")
    override suspend fun importSession(session: OwnedAuthSession) {
        SupabaseProvider.client.auth.importSession(session.payload as UserSession)
    }
    override fun isDefinitiveRefreshRejection(error: Throwable): Boolean =
        refreshOutcomeForStatus(error.restStatus()) == OfficialRefreshOutcome.Rejected
}

private fun UserSession.owned(): OwnedAuthSession? {
    val user = user ?: return null
    if (user.id.isBlank() || accessToken.isBlank() || refreshToken.isBlank()) return null
    return OwnedAuthSession(user.id, user.email, accessToken, refreshToken, this)
}

private fun Throwable.restStatus(): Int? =
    generateSequence(this as Throwable?) { it.cause }.filterIsInstance<RestException>().firstOrNull()?.statusCode

private fun looksInvalidSession(error: Throwable): Boolean {
    if (error.restStatus() in listOf(401, 403)) return true
    val rest = generateSequence(error as Throwable?) { it.cause }.filterIsInstance<RestException>().firstOrNull()
    val message = listOf(error.message, rest?.error, rest?.description).joinToString(" ").lowercase()
    return ("jwt" in message && listOf("invalid", "expired", "malformed").any { it in message }) ||
        ("user" in message && listOf("does not exist", "not found", "deleted").any { it in message })
}
