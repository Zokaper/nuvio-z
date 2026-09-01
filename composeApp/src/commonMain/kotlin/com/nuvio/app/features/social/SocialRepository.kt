package com.nuvio.app.features.social

import com.nuvio.app.core.network.SupabaseProvider
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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

object SocialRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(SocialUiState())
    val uiState: StateFlow<SocialUiState> = _uiState.asStateFlow()

    private var realtimeChannel: RealtimeChannel? = null
    private var realtimeCollector: Job? = null
    private var activeProfileId: String? = null
    private val publishedPresenceDeviceIds = mutableSetOf<String>()

    fun activate(profileId: String?) {
        if (activeProfileId == profileId) return
        val previousProfileId = activeProfileId
        activeProfileId = profileId
        scope.launch {
            if (previousProfileId != null) {
                publishedPresenceDeviceIds.toList().forEach { deviceId ->
                    runCatching {
                        SupabaseProvider.client.postgrest.rpc("social_clear_presence", buildJsonObject {
                            put("p_profile_id", previousProfileId); put("p_device_id", deviceId)
                        })
                    }
                }
                publishedPresenceDeviceIds.clear()
            }
            closeRealtime()
            if (profileId == null) {
                _uiState.value = SocialUiState()
                return@launch
            }
            val cached = SocialStorage.loadPayload(profileId)?.let { runCatching { json.decodeFromString<SocialStatePayload>(it) }.getOrNull() }
            _uiState.value = SocialUiState(
                activeProfileId = profileId,
                me = cached?.me,
                friends = cached?.friends.orEmpty(),
                requests = cached?.requests.orEmpty(),
                partyInvites = cached?.partyInvites.orEmpty(),
                watchingNow = cached?.watchingNow.orEmpty(),
                activity = cached?.activity.orEmpty(),
                isOfflineCache = cached != null,
                isLoading = true,
            )
            refreshCapabilities()
            refresh(forceLoading = false)
            flushOutbox()
            openRealtime(profileId)
        }
    }

    suspend fun refresh(forceLoading: Boolean = true, append: Boolean = false) {
        val profileId = activeProfileId ?: return
        val current = _uiState.value
        if (!current.capabilities.socialEnabled) {
            _uiState.value = current.copy(isLoading = false, isLoadingMore = false)
            return
        }
        val cursor = if (append) current.nextCursor else null
        if (forceLoading) _uiState.value = current.copy(isLoading = !append, isLoadingMore = append, errorMessage = null)
        runCatching {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                cursor?.let { put("p_before_time", it.lastEventTime); put("p_before_run_id", it.runId) }
                put("p_limit", SocialPageSize)
                current.selectedFriendId?.let { put("p_filter_profile_id", it) }
            }
            SupabaseProvider.client.postgrest.rpc("social_get_state", params).decodeAs<SocialStatePayload>()
        }.onSuccess { payload ->
            val activity = if (append) (current.activity + payload.activity).distinctBy(RecentActivityRun::runId) else payload.activity
            val next = payload.activity.lastOrNull()?.let { SocialActivityCursor(it.lastEventTime, it.runId) }
            _uiState.value = _uiState.value.copy(
                me = payload.me, friends = payload.friends, requests = payload.requests, partyInvites = payload.partyInvites,
                watchingNow = payload.watchingNow.take(SocialHomeItemLimit), activity = activity,
                nextCursor = next, isLoading = false, isLoadingMore = false, isOfflineCache = false, errorMessage = null,
            )
            SocialStorage.savePayload(profileId, json.encodeToString(payload.copy(activity = activity)))
        }.onFailure { error ->
            _uiState.value = _uiState.value.copy(isLoading = false, isLoadingMore = false, errorMessage = error.message)
        }
    }

    suspend fun setupHandle(handle: String): Result<SocialProfileSummary> = socialCall {
        require(isValidSocialHandle(handle)) { "Handle must be 3–24 lowercase letters, numbers, or underscores" }
        val profileId = requireActiveProfile()
        val result = SupabaseProvider.client.postgrest.rpc("social_upsert_profile", buildJsonObject {
            put("p_profile_id", profileId); put("p_handle", normalizeSocialHandle(handle))
        }).decodeAs<SocialProfileSummary>()
        refresh(false)
        result
    }

    suspend fun setPrivacy(shareWatchingNow: Boolean, shareRecentlyWatched: Boolean): Result<Unit> = socialCall {
        val profileId = requireActiveProfile()
        SupabaseProvider.client.postgrest.rpc("social_set_privacy", buildJsonObject {
            put("p_profile_id", profileId); put("p_share_watching_now", shareWatchingNow); put("p_share_recently_watched", shareRecentlyWatched)
        })
    }

    suspend fun searchProfiles(query: String): Result<List<SocialProfileSummary>> = socialCall {
        SupabaseProvider.client.postgrest.rpc("social_search_profiles", buildJsonObject {
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

    fun selectFriend(profileId: String?) {
        _uiState.value = _uiState.value.copy(selectedFriendId = profileId, activity = emptyList(), nextCursor = null)
        scope.launch { refresh(false) }
    }

    suspend fun publishPresence(deviceId: String, entry: SocialPresencePublish): Result<Unit> = socialCall {
        SupabaseProvider.client.postgrest.rpc("social_publish_presence", buildJsonObject {
            put("p_profile_id", requireActiveProfile()); put("p_device_id", deviceId); put("p_entry", json.encodeToJsonElement(entry))
        })
        publishedPresenceDeviceIds += deviceId
    }

    suspend fun clearPresence(deviceId: String): Result<Unit> = socialCall {
        SupabaseProvider.client.postgrest.rpc("social_clear_presence", buildJsonObject {
            put("p_profile_id", requireActiveProfile()); put("p_device_id", deviceId)
        })
        publishedPresenceDeviceIds -= deviceId
    }

    suspend fun publishWatched(event: SocialWatchedPublish) = enqueueOutbox(SocialOutboxEntry.Publish(event))
    suspend fun removeWatched(originKey: String) = enqueueOutbox(SocialOutboxEntry.Remove(originKey))

    suspend fun flushOutbox() {
        val profileId = activeProfileId ?: return
        val pending = loadOutbox(profileId).toMutableList()
        if (pending.isEmpty()) return
        val remaining = mutableListOf<SocialOutboxEntry>()
        pending.forEach { entry ->
            val result = runCatching {
                when (entry) {
                    is SocialOutboxEntry.Publish -> SupabaseProvider.client.postgrest.rpc("social_publish_watched", buildJsonObject {
                        put("p_profile_id", profileId); put("p_event", json.encodeToJsonElement(entry.event))
                    })
                    is SocialOutboxEntry.Remove -> SupabaseProvider.client.postgrest.rpc("social_remove_watched", buildJsonObject {
                        put("p_profile_id", profileId); put("p_origin_key", entry.originKey)
                    })
                }
            }
            if (result.isFailure) remaining += entry
        }
        saveOutbox(profileId, remaining)
        if (remaining.size != pending.size) refresh(false)
    }

    private suspend fun refreshCapabilities() {
        runCatching { SupabaseProvider.client.postgrest.rpc("get_social_capabilities").decodeAs<SocialCapabilities>() }
            .onSuccess { _uiState.value = _uiState.value.copy(capabilities = it) }
            .onFailure { _uiState.value = _uiState.value.copy(capabilities = SocialCapabilities(), isLoading = false) }
    }

    private suspend fun openRealtime(profileId: String) {
        if (!_uiState.value.capabilities.socialEnabled) return
        runCatching {
            SupabaseProvider.client.realtime.setAuth()
            val channel = SupabaseProvider.client.channel("social:$profileId") { isPrivate = true }
            realtimeCollector = channel.broadcastFlow<JsonObject>("invalidate").onEach { refresh(false) }.launchIn(scope)
            channel.subscribe(blockUntilSubscribed = true)
            realtimeChannel = channel
            refresh(false)
        }
    }

    private suspend fun closeRealtime() {
        realtimeCollector?.cancel(); realtimeCollector = null
        realtimeChannel?.let { runCatching { SupabaseProvider.client.realtime.removeChannel(it) } }
        realtimeChannel = null
    }

    private suspend fun enqueueOutbox(entry: SocialOutboxEntry) {
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
        saveOutbox(profileId, pending)
        flushOutbox()
    }

    private fun loadOutbox(profileId: String): List<SocialOutboxEntry> = SocialStorage.loadOutbox(profileId)
        ?.let { runCatching { json.decodeFromString<List<SocialOutboxEntry>>(it) }.getOrNull() }.orEmpty()
    private fun saveOutbox(profileId: String, entries: List<SocialOutboxEntry>) = SocialStorage.saveOutbox(profileId, json.encodeToString(entries))
    private fun requireActiveProfile(): String = requireNotNull(activeProfileId) { "No active social profile" }
    private suspend fun socialMutation(rpc: String, params: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): Result<Unit> = socialCall {
        SupabaseProvider.client.postgrest.rpc(rpc, buildJsonObject(params)); refresh(false)
    }
    private suspend inline fun <T> socialCall(crossinline block: suspend () -> T): Result<T> = runCatching { block() }
}

@Serializable
private sealed class SocialOutboxEntry {
    abstract val originKey: String
    @Serializable @SerialName("publish") data class Publish(val event: SocialWatchedPublish) : SocialOutboxEntry() { override val originKey get() = event.originKey }
    @Serializable @SerialName("remove") data class Remove(override val originKey: String) : SocialOutboxEntry()
}
