package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.nuvio.app.core.sync.SyncClientIdentity
import com.nuvio.app.features.social.SocialPlaybackState
import com.nuvio.app.features.social.SocialPresenceHeartbeatMs
import com.nuvio.app.features.social.SocialPresencePublish
import com.nuvio.app.features.social.SocialRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Z-owned player seam. It deliberately never reads or sends the active source URL or headers. */
@OptIn(ExperimentalUuidApi::class)
@Composable
internal fun PlayerScreenRuntime.BindSocialPresenceEffect() {
    val deviceId = remember { SyncClientIdentity.currentClientId() }
    val sessionId = remember { Uuid.random().toString() }
    val videoKey = "$parentMetaType:$parentMetaId:$activeVideoId:$activeSeasonNumber:$activeEpisodeNumber"

    suspend fun publishCurrent() {
        val snapshot = playbackSnapshot
        if (snapshot.isEnded || snapshot.isLoading || snapshot.durationMs <= 0L) return
        SocialRepository.publishPresence(
            deviceId = deviceId,
            entry = SocialPresencePublish(
                sessionId = sessionId,
                contentId = parentMetaId,
                contentType = parentMetaType,
                videoId = playbackSession.videoId,
                title = title,
                poster = poster,
                season = activeSeasonNumber,
                episode = activeEpisodeNumber,
                episodeTitle = activeEpisodeTitle,
                positionMs = snapshot.positionMs.coerceAtLeast(0L),
                durationMs = snapshot.durationMs,
                playbackSpeed = snapshot.playbackSpeed,
                state = if (snapshot.isPlaying) SocialPlaybackState.playing else SocialPlaybackState.paused,
            ),
        )
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
        onDispose { scope.launch { SocialRepository.clearPresence(deviceId) } }
    }
}
