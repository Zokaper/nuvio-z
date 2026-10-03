package com.nuvio.app.features.social

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import io.github.jan.supabase.postgrest.result.PostgrestResult
import kotlinx.coroutines.CompletableDeferred
import kotlin.coroutines.cancellation.CancellationException
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.network.ZSessionBridge
import com.nuvio.app.core.network.ZSupabaseProvider
import com.nuvio.app.core.network.shouldReexchangeZSession
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.concurrent.Volatile
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import com.nuvio.app.features.watchparty.PartySourceContractVersion
import com.nuvio.app.features.watchparty.WatchPartyState
import com.nuvio.app.features.setup.SocialIdentityProbe

/** How long to wait for the social channel to report itself subscribed before giving up on it. */
private const val SocialChannelSubscribeTimeoutMs = 12_000L

/** How long to wait for it to be given up, over a socket that may be exactly what failed. */
private const val SocialChannelCloseTimeoutMs = 3_000L

object SocialRepository {
    /**
     * ⚠ `explicitNulls = false`, and it is load-bearing rather than tidiness.
     *
     * Every payload here is handed to a `security definer` RPC as a `jsonb` parameter, and those
     * functions read it with `->>` and `->`, where an **absent key and a JSON null are not the
     * same thing**. `social_publish_presence` sanitizes with
     * `sanitize_source_descriptor_v2(p_entry->'source_fingerprint')`, whose null guard is
     * `if p_value is null then return null` - an *SQL* NULL test. An absent key yields SQL NULL and
     * returns cleanly; an explicit `"source_fingerprint": null` yields jsonb `'null'`, which is not
     * SQL NULL, so the guard misses it, `jsonb_typeof` answers `'null'` rather than `'object'`, and
     * the whole publish aborts with `invalid_source_descriptor` (22023).
     *
     * kotlinx defaults `explicitNulls` to **true**, so with the descriptor added to the presence
     * payload every publish made outside a Watch Together party failed - silently, because the
     * `Result` was discarded - and `Watching Now` showed nobody for anyone not already in a party.
     * Proven against `pzbpghmmordvzcfbayoh`: the absent key returns null, the JSON null raises.
     */
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(SocialUiState())
    val uiState: StateFlow<SocialUiState> = _uiState.asStateFlow()

    private var realtimeChannel: RealtimeChannel? = null
    private var realtimeCollector: Job? = null
    private val authorityLock = SynchronizedObject()
    private var authority = SocialOperation(null)
    private var sessionAccess: SocialSessionAccess = LiveSocialSessionAccess
    private val activeProfileId: String? get() = synchronized(authorityLock) { authority.profileId }

    private suspend fun operation(): SocialOperation =
        currentCoroutineContext()[SocialOperation] ?: synchronized(authorityLock) { authority }

    private fun checkAuthority(owner: SocialOperation) = synchronized(authorityLock) {
        if (authority !== owner) throw CancellationException("Social activation superseded")
    }

    private suspend fun checkAuthority() = checkAuthority(operation())

    private suspend fun <T> owned(block: suspend () -> T): T {
        val owner = operation()
        checkAuthority(owner)
        return withContext(owner) { block().also { checkAuthority(owner) } }
    }

    /** The check and publication share activate's lock; switching cannot interleave them. */
    private suspend fun publish(transform: (SocialUiState) -> SocialUiState) {
        val owner = operation()
        synchronized(authorityLock) {
            checkAuthority(owner)
            _uiState.value = transform(_uiState.value)
        }
    }

    private suspend fun socialRpc(name: String, params: JsonObject = buildJsonObject {}): PostgrestResult {
        checkAuthority()
        return ZSupabaseProvider.client.postgrest.rpc(name, params).also { checkAuthority() }
    }

    private suspend fun ensureSession(profileId: String): Boolean {
        checkAuthority()
        return sessionAccess.ensure(profileId).also { checkAuthority() }
    }

    private suspend fun reexchange(profileId: String): Boolean {
        checkAuthority()
        return sessionAccess.reexchange(profileId).also { checkAuthority() }
    }
    private val publishedPresenceDeviceIds = mutableSetOf<String>()

    private var identityBoundary: Job? = null

    private var activation: Job? = null
    private var recovery: Job? = null
    private var recoveryTriggers: Job? = null
    private val recoveryLock = Mutex()
    @Volatile
    private var capabilitiesLoaded = false

    /**
     * Returns once the identity boundary [activate] started has finished its server cleanup (bounded).
     *
     * ⚠ **The shell must await this before the party layer moves.** `WatchPartyRepository.setActiveProfile`
     * and `restore()` can call `ZSessionBridge.ensureSession(next)`, which swaps the one Z session to
     * the next profile; a boundary cancel still on its way out then goes as the old `p_profile_id`
     * on the new profile's token, is refused, and the old request stays open for the host to accept.
     */
    suspend fun awaitIdentityBoundary() {
        identityBoundary?.join()
    }

    fun activate(profileId: String?) = activate(profileId, forceBoundary = false)

    /** Also fences setup-time work, for which the active profile can already be null. */
    internal fun onAccountWipe() = activate(null, forceBoundary = true)

    private fun activate(profileId: String?, forceBoundary: Boolean) = synchronized(authorityLock) {
        if (!forceBoundary && authority.profileId == profileId) return@synchronized
        val previousProfileId = authority.profileId
        val owner = SocialOperation(profileId)
        authority = owner
        capabilitiesLoaded = false
        _uiState.value = SocialUiState(activeProfileId = profileId, isLoading = profileId != null)
        val previousBoundary = identityBoundary
        val previousChannel = realtimeChannel
        realtimeChannel = null
        realtimeCollector?.cancel()
        realtimeCollector = null
        val previousDevices = publishedPresenceDeviceIds.toList()
        publishedPresenceDeviceIds.clear()
        val boundary = CompletableDeferred<Unit>()
        identityBoundary = boundary
        val staleRecovery = recovery?.also { it.cancel() }
        recovery = null
        if (profileId != null) startRecoveryTriggers()
        activation = scope.launch(owner) {
            // Only boundary cleanup is ordered. Independent refresh requests may finish; their
            // captured activation cannot publish or retry after authority changes.
            try {
                previousBoundary?.join()
                staleRecovery?.join()
                OutgoingJoinRequestStore.onIdentityBoundary(previousProfileId, serverCleanup = true)
                if (previousProfileId != null) {
                    previousDevices.forEach { deviceId ->
                        runCatching {
                            ZSupabaseProvider.client.postgrest.rpc("social_clear_presence", buildJsonObject {
                                put("p_profile_id", previousProfileId); put("p_device_id", deviceId)
                            })
                        }
                    }
                }
                previousChannel?.let {
                    runCatching { withTimeout(SocialChannelCloseTimeoutMs) { ZSupabaseProvider.client.realtime.removeChannel(it) } }
                }
            } finally {
                boundary.complete(Unit)
            }
            checkAuthority(owner)
            if (profileId == null) return@launch
            val cached = SocialStorage.loadPayload(profileId)?.let { runCatching { json.decodeFromString<SocialStatePayload>(it) }.getOrNull() }
            publish { SocialUiState(
                activeProfileId = profileId,
                me = cached?.me,
                friends = cached?.friends.orEmpty(),
                requests = cached?.requests.orEmpty(),
                partyInvites = cached?.partyInvites.orEmpty(),
                notifications = cached?.notifications.orEmpty(),
                watchingNow = cached?.watchingNow.orEmpty(),
                activity = cached?.activity.orEmpty(),
                isOfflineCache = cached != null,
                isLoading = true,
            ) }
            refreshCapabilities()
            refresh(forceLoading = false)
            flushOutbox()
            openRealtime(profileId)
        }
    }

    /**
     * Re-runs what [activate] could not finish, once whatever stopped it may have gone away.
     *
     * Called when the official session comes back, when the network does, and when the Social screen
     * is opened. It does nothing unless [socialNeedsRecovery] says the surface is stale, and nothing
     * while an activation or another recovery is still running.
     */
    fun recoverIfStale() = synchronized(authorityLock) {
        val owner = synchronized(authorityLock) { authority }
        val profileId = owner.profileId ?: return@synchronized
        if (activation?.isActive == true || recovery?.isActive == true) return@synchronized
        if (!socialNeedsRecovery(_uiState.value, capabilitiesLoaded, realtimeOpen = realtimeChannel != null)) return@synchronized
        recovery = scope.launch(owner) {
            // Two triggers can arrive together (the session and the network return at once); the
            // second finds the lock held and leaves the work to the first.
            if (!recoveryLock.tryLock()) return@launch
            try {
                if (!capabilitiesLoaded) refreshCapabilities()
                checkAuthority(owner)
                refresh(forceLoading = false)
                flushOutbox()
                if (realtimeChannel == null) openRealtime(profileId)
            } finally {
                recoveryLock.unlock()
            }
        }
    }

    private fun startRecoveryTriggers() {
        if (recoveryTriggers != null) return
        recoveryTriggers = scope.launch {
            launch {
                // Keyed on the signed-in user, not on every status emission: the official client
                // re-emits Authenticated on each hourly refresh, and a failed refresh passes through
                // Unauthenticated on its way back, which is exactly the return worth reacting to.
                AuthRepository.state
                    .map { state -> (state as? AuthState.Authenticated)?.takeUnless { it.isAnonymous }?.userId }
                    .distinctUntilChanged()
                    .collect { userId -> if (userId != null) recoverIfStale() }
            }
            launch {
                NetworkStatusRepository.uiState
                    .map { it.isOnline }
                    .distinctUntilChanged()
                    .collect { online -> if (online) recoverIfStale() }
            }
        }
    }

    suspend fun refresh(forceLoading: Boolean = true, append: Boolean = false) = owned {
        refreshOwned(forceLoading, append)
    }

    private suspend fun refreshOwned(forceLoading: Boolean, append: Boolean) {
        val profileId = activeProfileId ?: return
        val current = _uiState.value
        if (!current.capabilities.socialEnabled) {
            publish { it.copy(isLoading = false, isLoadingMore = false) }
            return
        }
        if (!ensureSession(profileId)) {
            // Hiding the surface is right when the backend is simply not deployed, but a failed
            // exchange is a fault the user should see - not least because the commonest one is
            // "you are not signed in", which they can fix.
            publish { it.copy(
                isLoading = false,
                isLoadingMore = false,
                errorMessage = ZSessionBridge.lastFailure,
            ) }
            return
        }
        val cursor = if (append) current.nextCursor else null
        if (forceLoading) publish { it.copy(isLoading = !append, isLoadingMore = append, errorMessage = null) }
        runCatching {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                cursor?.let { put("p_before_time", it.lastEventTime); put("p_before_run_id", it.runId) }
                put("p_limit", SocialPageSize)
                current.selectedFriendId?.let { put("p_filter_profile_id", it) }
            }
            val rpcName=if (current.capabilities.partyContractVersion>=PartySourceContractVersion) "social_get_state_v2" else "social_get_state"
            val fetch = suspend { socialRpc(rpcName, params).decodeAs<SocialStatePayload>() }
            // A refused Z token is normally an expired one. Every other social call re-exchanges and
            // asks once more through [socialCall]; the feed did not, and showed "JWT expired" instead.
            runCatching { fetch() }.getOrElse { error ->
                if (error is CancellationException || !shouldReexchangeZSession(error)) throw error
                if (!reexchange(profileId)) throw error
                fetch()
            }
        }.onSuccess { payload ->
            val activity = if (append) (current.activity + payload.activity).distinctBy(RecentActivityRun::runId) else payload.activity
            val next = payload.activity.lastOrNull()?.let { SocialActivityCursor(it.lastEventTime, it.runId) }
            publish { it.copy(
                me = payload.me, friends = payload.friends, requests = payload.requests, partyInvites = payload.partyInvites,
                notifications = payload.notifications,
                watchingNow = payload.watchingNow.take(SocialHomeItemLimit), activity = activity,
                nextCursor = next, isLoading = false, isLoadingMore = false, isOfflineCache = false, errorMessage = null,
            ) }
            val owner = operation()
            synchronized(authorityLock) {
                checkAuthority(owner)
                SocialStorage.savePayload(profileId, json.encodeToString(payload.copy(activity = activity)))
            }
        }.onFailure { error ->
            // A caller leaving (a screen, an effect relaunching) is not a fault to show the user.
            if (error is CancellationException) {
                publish { it.copy(isLoading = false, isLoadingMore = false) }
                throw error
            }
            // When the token was refused and could not be replaced, the bridge knows why; the raw
            // PostgREST message ("JWT expired") does not help anyone.
            val message = if (shouldReexchangeZSession(error)) ZSessionBridge.lastFailure ?: error.message else error.message
            publish { it.copy(isLoading = false, isLoadingMore = false, errorMessage = message) }
        }
    }

    /**
     * ⚠ [profileId] exists for the setup wizard, which runs before `MainAppContent` has called
     * [activate] - so there is no active social profile yet, and relying on one failed every
     * wizard save with "No active social profile". Everywhere else leaves it at the default.
     */
    suspend fun setupHandle(
        handle: String,
        profileId: String? = activeProfileId,
    ): Result<SocialProfileSummary> = socialCall(profileId) {
        require(isValidSocialHandle(handle)) { "Handle must be 3–24 lowercase letters, numbers, or underscores" }
        requireNotNull(profileId) { "No active social profile" }
        val result = socialRpc("social_upsert_profile", buildJsonObject {
            put("p_profile_id", profileId); put("p_handle", normalizeSocialHandle(handle))
        }).decodeAs<SocialProfileSummary>()
        refresh(false)
        result
    }

    /**
     * Does this profile already have a social identity on the backend?
     *
     * Deliberately **not** [activate]: this opens no Realtime channel, publishes no presence,
     * flushes no outbox and writes nothing to [uiState]. It exists for one question - the
     * cache-cold half of the Phase 5 migration rule, where a second install or a cleared data
     * root makes an established social user look locally new - and it may run on a launch that
     * then decides the social layer is off, so it has to stay cheap and inert.
     *
     * Every call it makes already exists; there is no new RPC and no backend migration here.
     *
     * Answers [SocialIdentityProbe.Indeterminate] rather than [SocialIdentityProbe.Absent] for
     * anything that is not a real answer - signed out, no Z session, a failed call. The two must
     * not be confused: `Absent` is a fact about the account and `Indeterminate` is the absence of
     * one, and only the first may ever influence what gets written down.
     */
    suspend fun probeExistingIdentity(profileId: String): SocialIdentityProbe = owned { probeExistingIdentityOwned(profileId) }

    private suspend fun probeExistingIdentityOwned(profileId: String): SocialIdentityProbe {
        val authState = AuthRepository.state.value
        if (authState !is AuthState.Authenticated || authState.isAnonymous) {
            return SocialIdentityProbe.Indeterminate
        }
        if (!ensureSession(profileId)) return SocialIdentityProbe.Indeterminate

        val capabilities = runCatching {
            socialRpc("get_social_capabilities").decodeAs<SocialCapabilities>()
        }.getOrElse { return SocialIdentityProbe.Indeterminate }
        // A backend with no social layer deployed cannot be holding an identity. That is a real
        // answer, not a failure to reach one.
        if (!capabilities.socialEnabled) return SocialIdentityProbe.Absent

        val rpcName = if (capabilities.partyContractVersion >= PartySourceContractVersion) {
            "social_get_state_v2"
        } else {
            "social_get_state"
        }
        return runCatching {
            socialRpc(
                rpcName,
                buildJsonObject {
                    put("p_profile_id", profileId)
                    put("p_limit", 1)
                },
            ).decodeAs<SocialStatePayload>()
        }.fold(
            onSuccess = { payload ->
                if (payload.me != null) SocialIdentityProbe.Present else SocialIdentityProbe.Absent
            },
            onFailure = { SocialIdentityProbe.Indeterminate },
        )
    }

    suspend fun setPrivacy(shareWatchingNow: Boolean, shareRecentlyWatched: Boolean): Result<Unit> = socialCall {
        val profileId = requireActiveProfile()
        socialRpc("social_set_privacy", buildJsonObject {
            put("p_profile_id", profileId); put("p_share_watching_now", shareWatchingNow); put("p_share_recently_watched", shareRecentlyWatched)
        })
        // The server has the new values; reflect them in `me` now rather than on the next refresh,
        // so a switch bound to `me` does not snap back to the old value in between (setup polish).
        val current = _uiState.value
        current.me?.let { me ->
            publish { it.copy(me = me.copy(shareWatchingNow = shareWatchingNow, shareRecentlyWatched = shareRecentlyWatched)) }
        }
        Unit
    }

    suspend fun setDefaultJoinPolicy(policy: WatchJoinPolicy): Result<Unit> = socialMutation("social_set_default_join_policy") {
        put("p_profile_id",requireActiveProfile());put("p_policy",policy.name)
    }

    suspend fun setPresenceJoinPolicy(deviceId:String,sessionId:String,policy:WatchJoinPolicy): Result<Unit> =
        socialMutation("social_set_presence_join_policy") {
            put("p_profile_id",requireActiveProfile());put("p_device_id",deviceId);put("p_session_id",sessionId);put("p_policy",policy.name)
        }

    suspend fun joinWatching(item:WatchingNowItem): Result<SocialActionResult> = socialCall {
        require(_uiState.value.capabilities.partyContractVersion>=PartySourceContractVersion) { "Watch Together update required" }
        socialRpc("social_join_watching",buildJsonObject {
            put("p_requester_profile_id",requireActiveProfile());put("p_receiver_profile_id",item.profile.profileId)
            put("p_presence_session_id",item.sessionId);put("p_contract_version",PartySourceContractVersion)
        }).decodeAs<SocialActionResult>().also { refresh(false) }
    }

    /** The requester's own view of a join request. Scoped server-side to the caller. */
    suspend fun joinRequestStatus(requestId: String): Result<JoinRequestStatusResult> = socialCall {
        socialRpc("social_join_request_status", buildJsonObject {
            put("p_profile_id", requireActiveProfile()); put("p_request_id", requestId)
        }).decodeAs<JoinRequestStatusResult>()
    }

    /**
     * Cancel a join request **as [profileId]**, which may no longer be the active profile.
     *
     * Deliberately not through [socialCall]: at an identity boundary the active profile has already
     * moved on, and re-exchanging a session for it would authenticate the cancel as the wrong person.
     * This goes out on whatever token the client holds at that instant - the previous profile's, when
     * it is called first thing in [activate] - the same way the presence clear below it does.
     */
    suspend fun cancelJoinRequestAs(profileId: String, requestId: String): Result<JoinCancelResult> = runCatching {
        ZSupabaseProvider.client.postgrest.rpc("social_cancel_join_request", buildJsonObject {
            put("p_profile_id", profileId); put("p_request_id", requestId)
        }).decodeAs<JoinCancelResult>()
    }

    suspend fun notificationAction(id:String,action:SocialNotificationAction): Result<SocialActionResult> = socialCall {
        socialRpc("social_notification_action",buildJsonObject {
            put("p_profile_id",requireActiveProfile());put("p_notification_id",id);put("p_action",action.name.lowercase())
            put("p_contract_version",PartySourceContractVersion)
        }).decodeAs<SocialActionResult>().also { refresh(false) }
    }

    suspend fun markNotificationsRead(ids:Set<String>): Result<Unit> = socialMutation("social_mark_notifications_read") {
        put("p_profile_id",requireActiveProfile());put("p_notification_ids",json.encodeToJsonElement(ids.toList()))
    }

    suspend fun searchProfiles(query: String): Result<List<SocialProfileSummary>> = socialCall {
        socialRpc("social_search_profiles", buildJsonObject {
            put("p_profile_id", requireActiveProfile()); put("p_query", query); put("p_limit", 20)
        }).decodeList()
    }

    suspend fun sendFriendRequest(receiverProfileId: String): Result<Unit> = socialMutation("social_send_friend_request") {
        put("p_sender_profile_id", requireActiveProfile()); put("p_receiver_profile_id", receiverProfileId)
    }
    suspend fun cancelFriendRequest(requestId: String): Result<Unit> = socialMutation("social_cancel_friend_request") {
        put("p_profile_id", requireActiveProfile()); put("p_request_id", requestId)
    }
    suspend fun respondFriendRequest(requestId: String, accept: Boolean): Result<Unit> = socialMutation("social_respond_friend_request") {
        put("p_profile_id", requireActiveProfile()); put("p_request_id", requestId); put("p_accept", accept)
    }
    suspend fun removeFriend(friendProfileId: String): Result<Unit> = socialMutation("social_remove_friend") {
        put("p_profile_id", requireActiveProfile()); put("p_friend_profile_id", friendProfileId)
    }

    fun selectFriend(profileId: String?) = synchronized(authorityLock) {
        _uiState.value = _uiState.value.copy(selectedFriendId = profileId, activity = emptyList(), nextCursor = null)
        scope.launch(authority) { refresh(false) }
    }

    suspend fun publishPresence(deviceId: String, entry: SocialPresencePublish): Result<Unit> = socialCall {
        socialRpc("social_publish_presence", buildJsonObject {
            put("p_profile_id", requireActiveProfile()); put("p_device_id", deviceId); put("p_entry", json.encodeToJsonElement(entry))
        })
        val owner = operation()
        synchronized(authorityLock) { checkAuthority(owner); publishedPresenceDeviceIds += deviceId }
    }

    suspend fun clearPresence(deviceId: String): Result<Unit> = socialCall {
        socialRpc("social_clear_presence", buildJsonObject {
            put("p_profile_id", requireActiveProfile()); put("p_device_id", deviceId)
        })
        val owner = operation()
        synchronized(authorityLock) { checkAuthority(owner); publishedPresenceDeviceIds -= deviceId }
    }

    suspend fun publishWatched(event: SocialWatchedPublish) = enqueueOutbox(SocialOutboxEntry.Publish(event))
    suspend fun removeWatched(originKey: String) = enqueueOutbox(SocialOutboxEntry.Remove(originKey))

    suspend fun flushOutbox() = owned { flushOutboxOwned() }

    private suspend fun flushOutboxOwned() {
        val profileId = activeProfileId ?: return
        val pending = loadOutbox(profileId).toMutableList()
        if (pending.isEmpty()) return
        val remaining = mutableListOf<SocialOutboxEntry>()
        pending.forEach { entry ->
            val result = runCatching {
                when (entry) {
                    is SocialOutboxEntry.Publish -> socialRpc("social_publish_watched", buildJsonObject {
                        put("p_profile_id", profileId); put("p_event", json.encodeToJsonElement(entry.event))
                    })
                    is SocialOutboxEntry.Remove -> socialRpc("social_remove_watched", buildJsonObject {
                        put("p_profile_id", profileId); put("p_origin_key", entry.originKey)
                    })
                }
            }
            checkAuthority()
            if (result.isFailure) remaining += entry
        }
        val owner = operation()
        synchronized(authorityLock) { checkAuthority(owner); saveOutbox(profileId, remaining) }
        if (remaining.size != pending.size) refresh(false)
    }

    private suspend fun refreshCapabilities() {
        runCatching { socialRpc("get_social_capabilities").decodeAs<SocialCapabilities>() }
            .onSuccess { capabilities -> publish { capabilitiesLoaded = true; it.copy(capabilities = capabilities) } }
            .onFailure { error ->
                if (error is CancellationException) throw error
                publish { capabilitiesLoaded = false; it.copy(capabilities = SocialCapabilities(), isLoading = false) }
            }
    }

    private suspend fun openRealtime(profileId: String) {
        if (!_uiState.value.capabilities.socialEnabled) return
        // The social topic is a private channel authorized by RLS on realtime.messages, so the
        // socket has to carry the Z token rather than the publishable key.
        if (!ensureSession(profileId)) return
        runCatching {
            ZSupabaseProvider.client.realtime.setAuth()
            checkAuthority()
            val owner = operation()
            val channel = synchronized(authorityLock) {
                checkAuthority(owner)
                val created = ZSupabaseProvider.client.channel("social:$profileId") { isPrivate = true }
                val collector = created.broadcastFlow<JsonObject>("invalidate").onEach { payload ->
                checkAuthority(owner)
                // A join request changed on either side: the requester reads its status now rather
                // than on the next poll.
                if (payload["reason"]?.jsonPrimitive?.contentOrNull == "join_request") {
                    OutgoingJoinRequestStore.onJoinRequestInvalidated()
                }
                refresh(false)
            }.launchIn(CoroutineScope(scope.coroutineContext + owner))
                realtimeCollector = collector
                realtimeChannel = created
                created
            }
            // A topic the server refuses is retried in the background and never reports itself
            // subscribed, so an unbounded wait here is not a wait - it is a coroutine parked for the
            // life of the app, with the refresh below it never reached.
            withTimeout(SocialChannelSubscribeTimeoutMs) { channel.subscribe(blockUntilSubscribed = true) }
            checkAuthority(owner)
            refresh(false)
        }
    }

    private suspend fun enqueueOutbox(entry: SocialOutboxEntry) = owned { enqueueOutboxOwned(entry) }

    private suspend fun enqueueOutboxOwned(entry: SocialOutboxEntry) {
        val profileId = activeProfileId ?: return
        val pending = loadOutbox(profileId).toMutableList()
        when (entry) {
            is SocialOutboxEntry.Publish -> {
                pending.removeAll { it.originKey == entry.originKey }
                pending += entry
            }
            is SocialOutboxEntry.Remove -> {
                pending.removeAll { it.originKey == entry.originKey }
                pending += entry
            }
        }
        val owner = operation()
        synchronized(authorityLock) { checkAuthority(owner); saveOutbox(profileId, pending) }
        flushOutbox()
    }

    private fun loadOutbox(profileId: String): List<SocialOutboxEntry> = SocialStorage.loadOutbox(profileId)
        ?.let { runCatching { json.decodeFromString<List<SocialOutboxEntry>>(it) }.getOrNull() }.orEmpty()
    private fun saveOutbox(profileId: String, entries: List<SocialOutboxEntry>) = SocialStorage.saveOutbox(profileId, json.encodeToString(entries))
    private fun requireActiveProfile(): String = requireNotNull(activeProfileId) { "No active social profile" }
    private suspend fun socialMutation(rpc: String, params: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): Result<Unit> = socialCall {
        socialRpc(rpc, buildJsonObject(params)); refresh(false)
    }
    private suspend fun <T> socialCall(
        callProfileId: String? = activeProfileId,
        block: suspend () -> T,
    ): Result<T> = runCatching {
        owned {
            val profileId = callProfileId
            if (profileId == null) return@owned block()
            check(profileId == operation().profileId || operation().profileId == null) { "Social profile changed" }
            check(ensureSession(profileId)) { ZSessionBridge.lastFailure ?: "Nuvio Z social is unavailable" }
            val first = runCatching { block() }
            checkAuthority()
            if (first.isSuccess || first.exceptionOrNull()?.let(::shouldReexchangeZSession) != true) {
                return@owned first.getOrThrow()
            }
            if (!reexchange(profileId)) return@owned first.getOrThrow()
            block()
        }
    }

}

@Serializable
data class SocialActionResult(
    val outcome:String,
    @SerialName("request_id") val requestId:String?=null,
    @SerialName("expires_at") val expiresAt:String?=null,
    val party:WatchPartyState?=null,
)

@Serializable
data class JoinRequestStatusResult(
    val status: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("party_id") val partyId: String? = null,
    val party: WatchPartyState? = null,
)

@Serializable
data class JoinCancelResult(
    val outcome: String,
    val status: String? = null,
    @SerialName("party_id") val partyId: String? = null,
    val party: WatchPartyState? = null,
)

@Serializable
private sealed class SocialOutboxEntry {
    abstract val originKey: String
    @Serializable @SerialName("publish") data class Publish(val event: SocialWatchedPublish) : SocialOutboxEntry() { override val originKey get() = event.originKey }
    @Serializable @SerialName("remove") data class Remove(override val originKey: String) : SocialOutboxEntry()
}
