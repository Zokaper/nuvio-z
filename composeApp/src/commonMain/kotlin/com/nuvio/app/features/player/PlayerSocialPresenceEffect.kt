package com.nuvio.app.features.player

import com.nuvio.app.features.social.OutgoingJoinRequestStore
import co.touchlab.kermit.Logger
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.nuvio.app.core.sync.SyncClientIdentity
import com.nuvio.app.features.social.SocialPlaybackState
import com.nuvio.app.features.social.SocialPresenceHeartbeatMs
import com.nuvio.app.features.social.SocialPresencePublish
import com.nuvio.app.features.social.SocialPresenceSession
import com.nuvio.app.features.social.SocialRepository
import com.nuvio.app.features.social.shouldDiscoverPromotedParty
import com.nuvio.app.features.social.shouldPollForJoinRequests
import com.nuvio.app.features.watchparty.ActivePlaybackContext
import com.nuvio.app.features.watchparty.WatchPartyRepository
import com.nuvio.app.features.watchparty.WatchPartySessionCoordinator
import kotlinx.coroutines.delay
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Why presence did or did not reach the backend.
 *
 * Watching Now showed nobody through an entire two-client friend test while the same session
 * published watched activity correctly, and nothing in the app could say which half of this file
 * declined: the readiness guard returns silently, and the publish `Result` was dropped on the
 * floor. Both are named here now, because "no row in `watch_presence`" is indistinguishable from
 * "never asked" without them.
 */
private val presenceLog = Logger.withTag("SocialPresence")

/** Z-owned player seam. It deliberately never reads or sends the active source URL or headers. */
@OptIn(ExperimentalUuidApi::class)
@Composable
internal fun PlayerScreenRuntime.BindSocialPresenceEffect() {
    val deviceId = remember { SyncClientIdentity.currentClientId() }
    val sessionId = remember { Uuid.random().toString() }
    val attachmentId = remember { Uuid.random().toString() }
    val videoKey = "$parentMetaType:$parentMetaId:$activeVideoId:$activeSeasonNumber:$activeEpisodeNumber"
    val defaultJoinPolicy = SocialRepository.uiState.value.me?.defaultJoinPolicy
        ?: com.nuvio.app.features.social.WatchJoinPolicy.approval

    LaunchedEffect(deviceId, sessionId, defaultJoinPolicy) {
        SocialPresenceSession.attach(deviceId, sessionId, defaultJoinPolicy)
    }

    /**
     * The host half of joining from Watching Now - see `WatchingNowJoin.kt`.
     *
     * Nothing on the backend tells a host that a friend asked to join, or that a direct join has
     * just turned its playback into a party. This player is the one thing guaranteed to be running
     * while either can happen, and it already calls the server on a heartbeat, so the heartbeat is
     * the delivery: a pending request is at most one heartbeat from the in-player prompt, and a party
     * built from this presence is adopted in place - the same promotion as "Start Watch Together",
     * with no restart of the video.
     */
    suspend fun deliverJoinsToHost() {
        val policy = SocialPresenceSession.state.value.effectivePolicy
        if (shouldPollForJoinRequests(policy)) SocialRepository.refresh(forceLoading = false)
        if (shouldDiscoverPromotedParty(policy, WatchPartyRepository.uiState.value.party)) {
            WatchPartySessionCoordinator.discoverPromotedParty()
        }
    }

    suspend fun publishCurrent() {
        val snapshot = playbackSnapshot
        // Presence describes something a friend could join, so a session that has ended, has not
        // yet produced a frame, or has no known duration is deliberately not published. Named
        // rather than silent: this early return is the first thing to rule out when Watching Now
        // is empty, and it is invisible from the backend.
        if (snapshot.isEnded || snapshot.isLoading || snapshot.durationMs <= 0L) {
            presenceLog.d {
                "skip publish - ended=${snapshot.isEnded} loading=${snapshot.isLoading} " +
                    "durationMs=${snapshot.durationMs}"
            }
            return
        }
        val result = SocialRepository.publishPresence(
            deviceId = deviceId,
            entry = SocialPresencePublish(
                sessionId = sessionId,
                contentId = parentMetaId,
                contentType = parentMetaType,
                videoId = playbackSession.videoId,
                title = title,
                poster = poster,
                background = background,
                episodeThumbnail = activeEpisodeThumbnail,
                season = activeSeasonNumber,
                episode = activeEpisodeNumber,
                episodeTitle = activeEpisodeTitle,
                positionMs = snapshot.positionMs.coerceAtLeast(0L),
                durationMs = snapshot.durationMs,
                playbackSpeed = snapshot.playbackSpeed,
                state = if (snapshot.isPlaying) SocialPlaybackState.playing else SocialPlaybackState.paused,
                effectiveJoinPolicy = SocialPresenceSession.state.value.effectivePolicy,
                sourceFingerprint = activePartySourceDescriptor,
            ),
        )
        // ⚠ This Result used to be discarded. A rejected RPC - an expired token, a missing social
        // profile, a sanitizer that refused the descriptor - then looked exactly like a healthy
        // publish from inside the app, and the only symptom was a friend seeing nobody.
        result.onFailure { presenceLog.w(it) { "presence publish failed" } }
        if (result.isSuccess) deliverJoinsToHost()
    }

    LaunchedEffect(videoKey,activePartySourceDescriptor,playbackSnapshot.positionMs,playbackSnapshot.durationMs,playbackSnapshot.playbackSpeed) {
        activePartySourceDescriptor?.let { descriptor ->
            WatchPartySessionCoordinator.registerPlayback(
                ActivePlaybackContext(
                    attachmentId=attachmentId,contentId=parentMetaId,videoId=playbackSession.videoId,
                    descriptor=descriptor,positionMs=playbackSnapshot.positionMs,durationMs=playbackSnapshot.durationMs,
                    playbackSpeed=playbackSnapshot.playbackSpeed,
                ),sessionId,deviceId,
            )
        }
    }

    // Immediate updates for play/pause, item/source transition, and first known duration.
    LaunchedEffect(videoKey, playbackSnapshot.isPlaying, playbackSnapshot.isLoading, playbackSnapshot.isEnded, playbackSnapshot.durationMs) {
        publishCurrent()
    }
    LaunchedEffect(videoKey) {
        while (true) {
            delay(SocialPresenceHeartbeatMs)
            publishCurrent()
        }
    }
    DisposableEffect(deviceId) {
        // An accepted join request waits for an explicit Join / Not now while the viewer is in their
        // own player, instead of counting down and pulling them out of the film.
        OutgoingJoinRequestStore.setInOwnPlayer(true)
        onDispose {
            OutgoingJoinRequestStore.setInOwnPlayer(false)
            WatchPartySessionCoordinator.unregisterPlayback(attachmentId)
            // ⚠ **Not `scope.launch` here.** `runtime.scope` is a `rememberCoroutineScope()`, so
            // the departure that runs this dispose is the same departure that cancels it - the
            // clear never reached the backend and a friend kept seeing this player's last Paused
            // publish until the row aged out. `detachAndClear` owns the process-scoped clear.
            SocialPresenceSession.detachAndClear(deviceId, sessionId)
        }
    }
}
