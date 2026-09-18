package com.nuvio.app.features.player

import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import com.nuvio.app.features.watchparty.currentEpochMs
import com.nuvio.app.features.watchparty.PartyClientPhase
import com.nuvio.app.features.social.parseSocialTimestampMs
import com.nuvio.app.features.social.OutgoingJoinRequestStore
import com.nuvio.app.features.social.OutgoingJoinRequestState
import com.nuvio.app.features.watchparty.partyPossessive
import com.nuvio.app.features.watchparty.PartyJoinHandoff
import com.nuvio.app.features.watchparty.PartyStatusPerson
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.watchparty.PartyContent
import com.nuvio.app.features.watchparty.PartyConnectionState
import com.nuvio.app.features.watchparty.WatchPartyControlMode
import com.nuvio.app.features.watchparty.memberMayControl
import com.nuvio.app.features.watchparty.PartyReadyTone
import com.nuvio.app.features.watchparty.PartyPresentationProjector
import com.nuvio.app.features.watchparty.readyCount
import com.nuvio.app.features.watchparty.readyLabel
import com.nuvio.app.features.watchparty.readyTone
import com.nuvio.app.features.watchparty.WatchPartyRepository
import com.nuvio.app.features.watchparty.WatchPartyStatus
import com.nuvio.app.features.watchparty.WatchPartySync
import com.nuvio.app.features.watchparty.displayName
import com.nuvio.app.features.watchparty.ExistingPartyJoinOutcome
import com.nuvio.app.features.watchparty.existingPartyJoinOutcome
import com.nuvio.app.features.watchparty.matchesPlayback
import androidx.compose.ui.layout.onSizeChanged
import co.touchlab.kermit.Logger
import com.nuvio.app.core.format.formatReleaseDateForDisplay
import com.nuvio.app.core.i18n.localizedByteUnit
import com.nuvio.app.core.ui.AppPresenceState
import com.nuvio.app.core.ui.PresenceSnapshot
import com.nuvio.app.core.debug.PlaybackDebugSettings
import com.nuvio.app.core.debug.isDebugBuild
import com.nuvio.app.core.network.NetworkQualityRepository
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.downloads.SourceFactsExtractor
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.p2p.P2pStreamingState
import com.nuvio.app.features.p2p.formatP2pMegabytes
import com.nuvio.app.features.p2p.formatP2pSpeed
import com.nuvio.app.features.playback.PlaybackLoadingController
import com.nuvio.app.features.playback.PlaybackLoadingFacts
import com.nuvio.app.features.playback.PlaybackLoadingState
import com.nuvio.app.features.playback.PlaybackProgressStep
import com.nuvio.app.features.playback.PlaybackQualityOptions
import com.nuvio.app.features.playback.PlaybackQualitySheet
import com.nuvio.app.features.playback.PlaybackSelectionResult
import com.nuvio.app.features.playback.PlaybackSourceCandidate
import com.nuvio.app.features.playback.PlaybackSourceSelector
import com.nuvio.app.features.playback.playbackSelectionContextOf
import com.nuvio.app.features.playback.playbackFactSlotLabelRes
import com.nuvio.app.features.playback.rememberLanguageNamer
import com.nuvio.app.features.player.skip.SkipIntroRepository
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamBadgeSettingsRepository
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.features.streams.isSelectableForPlayback
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import com.nuvio.app.features.watching.application.WatchingState
import com.nuvio.app.isDesktop
import com.nuvio.app.isIos
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalDensity
import com.nuvio.app.core.ui.LocalNuvioPlatformDensity
import com.nuvio.app.features.playback.PlaybackHandover
import com.nuvio.app.features.playback.PlaybackLoadingActions
import com.nuvio.app.features.updater.formatFileSize
import com.nuvio.app.features.social.SocialNotificationAction
import com.nuvio.app.features.social.SocialNotificationKind
import com.nuvio.app.features.social.SocialRepository
import com.nuvio.app.features.social.SocialPresenceSession
import com.nuvio.app.features.watchparty.WatchPartySessionCoordinator
import com.nuvio.app.features.social.rememberSocialEnabled
import com.nuvio.app.core.ui.NuvioToastController
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.watch_party_cannot_share_source
import org.jetbrains.compose.resources.getString

private val playerControlsLog = Logger.withTag("PlayerControls")

@Composable
internal fun PlayerScreenRuntime.RenderPlayerRuntimeUi() {
    val runtime = this
    val watchPartyUiState by WatchPartyRepository.uiState.collectAsStateWithLifecycle()
    val watchPartySyncState by WatchPartySync.state.collectAsStateWithLifecycle()
    val socialUiState by SocialRepository.uiState.collectAsStateWithLifecycle()
    val socialEnabled = rememberSocialEnabled()
    val socialPresenceSession by SocialPresenceSession.state.collectAsStateWithLifecycle()
    val partySessionState by WatchPartySessionCoordinator.state.collectAsStateWithLifecycle()
    val systemBackRegistration = args.onSystemBackHandlerChanged
    DisposableEffect(runtime, systemBackRegistration) {
        systemBackRegistration { runtime.requestBack() }
        onDispose { systemBackRegistration(null) }
    }
    val isInPip = rememberIsInPictureInPicture()
    val displayedPositionMs = scrubbingPositionMs ?: playbackSnapshot.positionMs
    val seasonNumber = activeSeasonNumber
    val episodeNumber = activeEpisodeNumber
    val episodeTitle = activeEpisodeTitle
    val isEpisode = seasonNumber != null && episodeNumber != null

    LaunchedEffect(runtime.title, runtime.poster, seasonNumber, episodeNumber, episodeTitle, playbackSnapshot.isPlaying) {
        val episodeLabel = if (isEpisode) {
            val base = "S${seasonNumber}E${episodeNumber}"
            if (!episodeTitle.isNullOrBlank()) "$base - $episodeTitle" else base
        } else {
            null
        }
        AppPresenceState.publish(
            PresenceSnapshot.Player(
                title = runtime.title,
                episodeLabel = episodeLabel,
                posterUrl = runtime.poster,
                isPlaying = playbackSnapshot.isPlaying,
                positionMs = playbackSnapshot.positionMs,
                durationMs = playbackSnapshot.durationMs,
            ),
        )
    }

    // The join hand-off ends at the first frame; after that the player speaks for itself.
    LaunchedEffect(firstFrameReached) {
        if (firstFrameReached) PartyJoinHandoff.finish()
    }
    val activeParty = watchPartyUiState.party?.takeIf {
        it.matchesPlayback(parentMetaId, playbackSession.videoId)
    }
    val partyPresentation = PartyPresentationProjector.project(
        party = activeParty,
        selfProfileId = watchPartyUiState.activeProfileId,
        health = watchPartyUiState.health,
        realtime = watchPartySyncState,
        partyNowMs = WatchPartySync.partyNowMs(),
        localPlaybackStatus = when {
            playbackSnapshot.isLoading -> WatchPartyStatus.buffering
            playbackSnapshot.isPlaying -> WatchPartyStatus.playing
            else -> WatchPartyStatus.paused
        },
    )
    // ⚠ **The panel no longer closes when the party goes away.** It used to (`activeParty == null`
    // reset `partyRoomOpen`), which is exactly why there was no "not in a party" state: the room could
    // only exist around a party. Idle → Starting → Active now happens in place, and an ended party
    // opens the panel onto its Ended state rather than a centred modal.
    LaunchedEffect(activeParty?.id) {
        if (activeParty != null) partyPromotion = PartyPromotionProgress.Idle
        partyEndConfirm = false
        // "Invited" is about one party; a friend invited to the last one can be invited to this one.
        partyInvitedProfileIds = emptySet()
    }
    LaunchedEffect(partySessionState.guestPostEndChoice) {
        if (partySessionState.guestPostEndChoice) {
            partyRoomOpen = true
            controlsVisible = true
        }
    }
    // A promotion nothing answers is a failure, not a spinner forever.
    LaunchedEffect(partyPromotion) {
        if (partyPromotion is PartyPromotionProgress.Starting) {
            kotlinx.coroutines.delay(PartyPromotionAnswerTimeoutMs)
            if (partyPromotion is PartyPromotionProgress.Starting && activeParty == null) {
                partyPromotion = PartyPromotionProgress.Failed(com.nuvio.app.features.watchparty.PartyPromotionFailure.Refused)
            }
        }
    }
    // The repository is already empty when the layer is off, but stating the gate here means a
    // stale emission during the teardown frame cannot flash a friend request over the player.
    // A request to join *this* playback is the panel's row and the status pill's, not this card's.
    val activeSocialNotification = socialUiState.notifications.firstOrNull {
        socialEnabled && it.readAt == null && it.availableActions.isNotEmpty() &&
            it.kind != SocialNotificationKind.WatchingNowJoinRequest
    }
    val incomingJoinRequest = socialUiState.notifications.firstOrNull {
        socialEnabled && it.readAt == null && it.kind == SocialNotificationKind.WatchingNowJoinRequest &&
            SocialNotificationAction.Accept in it.availableActions
    }
    val outgoingJoinRequest by OutgoingJoinRequestStore.state.collectAsStateWithLifecycle()
    // The one line about the party. Replaces the banner, which covered three conditions and said
    // nothing through a stall hold, a source handoff, a barrier park or a party that stayed paused.
    val partyStatusLine = rememberPartyStatusLine(
        incomingRequester = incomingJoinRequest?.actor?.let { actor ->
            PartyStatusPerson(
                profileId = actor.profileId,
                name = actor.displayName.ifBlank { actor.handle },
                avatarUrl = actor.avatarUrl,
                avatarColorHex = actor.avatarColorHex,
            )
        },
        // Lowest priority, and only while still waiting: an accepted request asks Join / Not now in
        // the panel, never from a pill.
        outgoingTarget = when (val request = outgoingJoinRequest) {
            is OutgoingJoinRequestState.Pending -> request.target
            is OutgoingJoinRequestState.Sending -> request.target
            else -> null
        }?.let { target ->
            PartyStatusPerson(target.profileId, target.displayName, target.avatarUrl, target.avatarColorHex)
        },
        panelOpen = partyRoomOpen,
    )
    // When realtime entered its current state, for the connection chip's grace periods.
    var realtimeSinceMs by remember { mutableStateOf(currentEpochMs()) }
    LaunchedEffect(watchPartyUiState.health.realtime) { realtimeSinceMs = currentEpochMs() }
    val panelNowMs by produceState(currentEpochMs(), activeParty != null, partyRoomOpen) {
        while (activeParty != null) {
            value = currentEpochMs()
            kotlinx.coroutines.delay(1_000)
        }
        value = currentEpochMs()
    }
    // The last host name seen, so "Seraph ended the party" can still name them after the snapshot is gone.
    var lastPartyHostName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(activeParty?.hostProfileId, activeParty?.members) {
        activeParty?.let { party ->
            party.members.firstOrNull { it.profileId == party.hostProfileId }
                ?.displayName(viewerProfileId = null)
                ?.let { lastPartyHostName = it }
        }
    }
    val watchTogetherPanel = projectWatchTogetherPanel(
        WatchTogetherPanelInputs(
            shareable = args.onStartWatchTogether != null && activePartySourceDescriptor != null,
            playbackContentId = parentMetaId,
            playbackVideoId = playbackSession.videoId,
            playbackTitle = listOfNotNull(
                title,
                if (activeSeasonNumber != null && activeEpisodeNumber != null) "S${activeSeasonNumber}E$activeEpisodeNumber" else null,
            ).joinToString(" · "),
            viewerProfileId = watchPartyUiState.activeProfileId,
            party = watchPartyUiState.party,
            health = watchPartyUiState.health,
            realtimeSinceMs = realtimeSinceMs,
            nowMs = panelNowMs,
            members = partyPresentation.members,
            promotion = partyPromotion,
            connecting = watchPartyUiState.party == null && partySessionState.phase == PartyClientPhase.Connecting,
            postEndChoice = partySessionState.guestPostEndChoice,
            endedByName = lastPartyHostName,
            joinPolicy = JoinPolicyControl(
                selected = joinPolicyPending ?: socialPresenceSession.effectivePolicy,
                saving = joinPolicyPending != null,
                errorMessage = joinPolicyError,
            ),
            incomingRequest = incomingJoinRequest?.let { notification ->
                IncomingJoinRequestRow(
                    requestId = notification.id,
                    profileId = notification.actor.profileId,
                    name = notification.actor.displayName.ifBlank { notification.actor.handle },
                    avatarUrl = notification.actor.avatarUrl,
                    avatarColorHex = notification.actor.avatarColorHex,
                    expiresAtMs = notification.expiresAt?.let(::parseSocialTimestampMs),
                )
            },
            waitForEveryone = watchPartyUiState.waitForEveryone,
            errorMessage = partyPanelError ?: watchPartyUiState.errorMessage,
            sourceMatch = activeParty?.members?.firstOrNull { it.profileId == watchPartyUiState.activeProfileId }?.sourceMatch,
            releaseName = activeStreamTitle.takeIf { it.isNotBlank() },
        ),
    )
    val watchTogetherBridge = watchTogetherBridgeState(
        panel = watchTogetherPanel,
        open = partyRoomOpen && !playerControlsLocked,
        inviteTargets = socialUiState.friends
            .filter { friend -> activeParty?.members?.none { it.profileId == friend.profileId } == true }
            .mapIndexed { index, friend ->
                WatchTogetherBridgeInvite(
                    index = index,
                    name = friend.displayName,
                    avatarUrl = friend.avatarUrl.orEmpty(),
                    invited = friend.profileId in partyInvitedProfileIds,
                )
            },
        inviteCode = watchPartyUiState.inviteCode.orEmpty(),
        endConfirm = partyEndConfirm,
        outgoing = (outgoingJoinRequest as? OutgoingJoinRequestState.Bound)?.let { request ->
            val phase = when (request) {
                is OutgoingJoinRequestState.Pending, is OutgoingJoinRequestState.Cancelling -> "pending"
                is OutgoingJoinRequestState.Accepted -> "accepted"
                is OutgoingJoinRequestState.Joining -> "joining"
                else -> null
            } ?: return@let null
            WatchTogetherOutgoingMirror(
                name = request.target.displayName,
                avatarUrl = request.target.avatarUrl,
                colorHex = request.target.avatarColorHex,
                phase = phase,
                expiresAtMs = (request as? OutgoingJoinRequestState.Pending)?.expiresAtMs ?: 0L,
            )
        },
    )
    // The end of a party is observed by `WatchPartySessionCoordinator` from the snapshot itself
    // (`observePartySnapshot`), not from here. This effect used to be the only thing that reacted
    // to an ended party, so a guest who was not in the player never learned the party was over - and
    // because it ran on first composition, an ended party still held would raise the end-of-party
    // choice again over any player opened later.
    // Through the shared rule rather than spelled out again: this value is what disables the
    // controls, and the transport is what refuses the press, so the two disagreeing is a guest with
    // live-looking buttons that do nothing - or dim ones that still move its player.
    val partyMayControl = activeParty == null ||
        activeParty.memberMayControl(watchPartyUiState.activeProfileId)
    val currentGestureFeedback = liveGestureFeedback ?: gestureFeedback
    val isP2pPlaybackActive = activeTorrentInfoHash != null
    val p2pConnecting = p2pStreamingState as? P2pStreamingState.Connecting
    val p2pStats = p2pStreamingState as? P2pStreamingState.Streaming
    val p2pPeerInfo = p2pStats?.let { stats ->
        org.jetbrains.compose.resources.stringResource(
            nuvio.composeapp.generated.resources.Res.string.player_torrent_peer_info,
            stats.seeds,
            stats.peers,
        )
    }
    val p2pDownloadSpeed = p2pStats?.let { formatP2pSpeed(it.downloadSpeed) }
    val p2pLoadingBytes = p2pStats?.let { maxOf(it.downloadedBytes, it.deliveredBytes) } ?: 0L
    val connectingPeerInfo = p2pConnecting?.let { state ->
        org.jetbrains.compose.resources.stringResource(
            nuvio.composeapp.generated.resources.Res.string.player_torrent_peer_info,
            state.seeds,
            state.peers,
        )
    }
    val p2pInitialLoadingMessage = when {
        !isP2pPlaybackActive || initialLoadCompleted -> null
        p2pConnecting != null -> {
            if (p2pSettingsUiState.hideTorrentStats) {
                p2pConnectingPhaseLabel(p2pConnecting.phase)
            } else {
                org.jetbrains.compose.resources.stringResource(
                    nuvio.composeapp.generated.resources.Res.string.player_torrent_connecting_status,
                    p2pConnectingPhaseLabel(p2pConnecting.phase),
                    connectingPeerInfo.orEmpty(),
                    formatP2pSpeed(p2pConnecting.downloadSpeed),
                )
            }
        }
        p2pStats != null -> {
            if (p2pSettingsUiState.hideTorrentStats) {
                null
            } else {
                org.jetbrains.compose.resources.stringResource(
                    nuvio.composeapp.generated.resources.Res.string.player_torrent_loading_status,
                    formatP2pMegabytes(p2pLoadingBytes),
                    p2pPeerInfo.orEmpty(),
                    p2pDownloadSpeed.orEmpty(),
                )
            }
        }
        else -> org.jetbrains.compose.resources.stringResource(
            nuvio.composeapp.generated.resources.Res.string.player_torrent_starting_engine,
        )
    }
    val bufferedAheadMs = (playbackSnapshot.bufferedPositionMs - playbackSnapshot.positionMs)
        .coerceAtLeast(0L)
    val p2pInitialLoadingProgress = when {
        !isP2pPlaybackActive || initialLoadCompleted || p2pStats == null -> null
        else -> p2pInitialLoadingProgress(
            bufferedAheadMs = bufferedAheadMs,
            downloadedBytes = p2pStats.downloadedBytes,
            deliveredBytes = p2pStats.deliveredBytes,
        )
    }
    val showP2pRebufferStats = isP2pPlaybackActive &&
        initialLoadCompleted &&
        playbackSnapshot.isLoading &&
        p2pStats != null &&
        !p2pSettingsUiState.hideTorrentStats
    val p2pRebufferMessage = when {
        !showP2pRebufferStats -> null
        else -> {
            val bufferedSeconds = ((playbackSnapshot.bufferedPositionMs - playbackSnapshot.positionMs) / 1000L)
                .coerceAtLeast(0L)
            "${bufferedSeconds}s buffered · ${p2pPeerInfo.orEmpty()} · ${p2pDownloadSpeed.orEmpty()}"
        }
    }
    val p2pRebufferProgress = when {
        !showP2pRebufferStats -> null
        else -> {
            val bufferedSeconds = ((playbackSnapshot.bufferedPositionMs - playbackSnapshot.positionMs) / 1000f)
                .coerceAtLeast(0f)
            (bufferedSeconds / 10f).coerceIn(0f, 1f)
        }
    }
    val playerSurfaceSourceUrl = if (isP2pPlaybackActive) p2pResolvedSourceUrl else activeSourceUrl
    val initialPositionRequestKey = currentInitialPositionRequestKey()
    val currentPlayerSurfaceSource = playerSurfaceSourceUrl?.let { sourceUrl ->
        PlayerSurfaceSource(
            sourceUrl = sourceUrl,
            sourceAudioUrl = activeSourceAudioUrl,
            sourceHeaders = activeSourceHeaders,
            sourceResponseHeaders = activeSourceResponseHeaders,
            externalSubtitles = externalSubtitles,
            streamType = activeStreamType,
            initialPositionMs = activeInitialPositionMs.takeIf { it > 0L },
            initialPositionRequestKey = initialPositionRequestKey,
        )
    }
    val renderPlayerSurface = shouldRenderPlayerSurface(
        hasCurrentSource = currentPlayerSurfaceSource != null,
        hasLifecycleController = playerLifecycleController != null,
        releaseInFlight = playerReleaseSurfaceRetention.inFlight,
        desktop = isDesktop,
    )
    // ⚠ **`initialLoadCompleted` is not a first frame.** The engine drops `isLoading` once it has
    // opened the media, which is before it has decoded anything, so an overlay that left on this
    // signal alone dissolved onto a black video plane. `firstFrameReached` is the stronger one -
    // see `PlaybackHandover.hasFirstFrame`. `initialLoadCompleted` is left exactly as it was
    // because the seek, subtitle and watchdog paths all read it and mean the weaker thing.
    val openingOverlayWanted = playerSettingsUiState.showLoadingOverlay &&
        !firstFrameReached &&
        (errorMessage == null || args.onFatalPlaybackError != null)
    val openingLoadingState = PlaybackLoadingState(
        step = PlaybackProgressStep.StartingPlayback,
        attempt = args.playbackAttempt,
        facts = args.sourceFacts,
        contentLanguage = args.contentLanguage,
        preferredAudioLanguage = preferredAudioLanguageTargets.firstOrNull()
            ?: playerSettingsUiState.preferredAudioLanguage,
    )

    val episodeText = if (seasonNumber != null && episodeNumber != null && !episodeTitle.isNullOrBlank()) {
        stringResource(
            Res.string.compose_player_episode_title_format,
            seasonNumber,
            episodeNumber,
            episodeTitle.orEmpty(),
        )
    } else {
        ""
    }
    val allFilterLabel = stringResource(Res.string.collections_tab_all)
    val playingLabel = stringResource(Res.string.compose_player_playing)
    val sourceFilters = buildPlayerControlFilters(
        allLabel = allFilterLabel,
        selectedFilter = null,
    )
    val sourceItems = buildPlayerControlSourceItems()
    val episodeItems = buildPlayerControlEpisodeItems()
    val episodeSeasons = buildPlayerControlSeasonItems(episodeItems)
    val episodeStreamFilters = if (episodeStreamsPanelState.qualityChooser) {
        emptyList()
    } else {
        buildPlayerControlEpisodeStreamFilters(
            allLabel = allFilterLabel,
            selectedFilter = null,
        )
    }
    val episodeStreamItems = if (episodeStreamsPanelState.qualityChooser) {
        buildPlayerControlEpisodeQualityItems()
    } else {
        buildPlayerControlEpisodeStreamItems()
    }
    val playerControlAddonSubtitles = buildPlayerControlAddonSubtitleItems()
    val playerControlSubtitleSelection = buildPlayerControlSubtitleSelection()
    val playerControlAutoSyncCues = buildPlayerControlSubtitleCueItems()
    val themeColors = MaterialTheme.nuvio.colors
    val selectedEpisodeCodeAndTitle = episodeStreamsPanelState.selectedEpisode?.let { selected ->
        val selectedCode = selected.playerControlsEpisodeCode()
        buildString {
            append(selectedCode)
            if (selected.title.isNotBlank()) {
                if (isNotEmpty()) append(" • ")
                append(selected.title)
            }
        }
    }.orEmpty()
    val automaticSelectionFailureLabel = when (episodeStreamsPanelState.automaticSelectionFailure) {
        PlayerNextEpisodeFailureReason.TIMED_OUT ->
            stringResource(Res.string.player_next_episode_timed_out)
        PlayerNextEpisodeFailureReason.EMPTY_RESULTS ->
            stringResource(Res.string.player_next_episode_no_sources)
        PlayerNextEpisodeFailureReason.NO_SAFE_CANDIDATE ->
            stringResource(Res.string.player_next_episode_choose_source)
        null -> ""
    }
    val qualityChooserLabel = stringResource(Res.string.playback_quality_title)
    val selectedEpisodeLabel = if (episodeStreamsPanelState.qualityChooser) {
        listOf(qualityChooserLabel, selectedEpisodeCodeAndTitle).filter { it.isNotBlank() }.joinToString(" • ")
    } else if (automaticSelectionFailureLabel.isNotBlank()) {
        listOf(
            automaticSelectionFailureLabel,
            selectedEpisodeCodeAndTitle,
        ).filter { it.isNotBlank() }.joinToString(" • ")
    } else {
        selectedEpisodeCodeAndTitle
    }
    val nativeSkipInterval = activeSkipInterval.takeIf {
        initialLoadCompleted && !pausedOverlayVisible && !skipIntervalDismissed
    }
    val nextEpisodeForControls = nextEpisodeInfo.takeIf { isSeries && showNextEpisodeCard }
    val startingEpisode = nextEpisodeTransition
        .takeIf { it.phase == PlayerNextEpisodePhase.STARTING }
        ?.targetVideoId
        ?.let { targetId -> playerMetaVideos.firstOrNull { it.id == targetId } }
    val openingPresentation = playerOpeningPresentation(
        showLogo = logo,
        showTitle = title,
        background = background,
        poster = poster,
        startingEpisode = startingEpisode,
    )
    // The loading surface is drawn by `PlaybackLoadingHost`, above `NavDisplay`. In the automatic
    // modes the stream route already opened the session and handed it over, and this must not
    // disturb it - re-opening would restart the entrance and the escape clock at exactly the
    // route change the whole design exists to make invisible. Opening here covers the paths that
    // reach the player with no stream route behind them at all: Continue Watching, the next
    // episode, and a resumed download.
    LaunchedEffect(openingOverlayWanted, args.sourceUrl) {
        if (openingOverlayWanted) {
            if (PlaybackLoadingController.activeToken == null) {
                val token = PlaybackLoadingController.open(
                    step = PlaybackProgressStep.StartingPlayback,
                    artwork = openingPresentation.artwork,
                    logo = openingPresentation.logo,
                    title = openingPresentation.title,
                    attempt = args.playbackAttempt,
                    facts = args.sourceFacts,
                    contentLanguage = args.contentLanguage,
                )
                PlaybackLoadingController.handOff(token)
                PlaybackLoadingController.registerActions(
                    token = token,
                    actions = PlaybackLoadingActions(onBack = { requestBack() }),
                )
            }
        } else {
            PlaybackLoadingController.closeAfterHandOff()
        }
    }
    val nextEpisodeStatus = when {
        nextEpisodeForControls == null -> ""
        !nextEpisodeForControls.hasAired && !nextEpisodeForControls.unairedMessage.isNullOrBlank() ->
            nextEpisodeForControls.unairedMessage.orEmpty()
        nextEpisodeTransition.phase == PlayerNextEpisodePhase.RESOLVING ->
            stringResource(Res.string.player_next_episode_finding_source)
        nextEpisodeTransition.phase == PlayerNextEpisodePhase.STARTING ->
            stringResource(Res.string.player_next_episode_starting)
        !nextEpisodeTransition.sourceName.isNullOrBlank() && nextEpisodeTransition.countdownSeconds != null ->
            stringResource(
                Res.string.player_next_episode_playing_via_countdown,
                nextEpisodeTransition.sourceName.orEmpty(),
                nextEpisodeTransition.countdownSeconds ?: 0,
            )
        nextEpisodeTransition.countdownSeconds != null ->
            stringResource(
                Res.string.player_next_episode_playing_countdown,
                nextEpisodeTransition.countdownSeconds ?: 0,
            )
        else -> ""
    }
    val openingNamer = rememberLanguageNamer(openingLoadingState.facts, openingLoadingState.contentLanguage)
    val playerControlsState = PlayerControlsState(
        title = title,
        episodeText = episodeText,
        streamTitle = activeStreamTitle,
        providerName = activeProviderName,
        pauseOverlayWatchingLabel = stringResource(Res.string.compose_player_youre_watching),
        pauseOverlayLogo = logo,
        pauseOverlayEpisodeInfo = if (seasonNumber != null && episodeNumber != null) {
            stringResource(Res.string.compose_player_episode_code_full, seasonNumber, episodeNumber)
        } else {
            activeProviderName
        },
        pauseOverlayEpisodeTitle = activeEpisodeTitle.orEmpty(),
        pauseOverlayDescription = (activePauseDescription ?: activeStreamSubtitle).orEmpty(),
        resizeModeLabel = stringResource(resizeMode.labelRes),
        playbackSpeedLabel = formatPlaybackSpeedLabel(playbackSnapshot.playbackSpeed),
        subtitlesLabel = stringResource(Res.string.compose_player_subs),
        audioLabel = stringResource(Res.string.compose_player_audio),
        sourcesLabel = stringResource(Res.string.compose_player_sources),
        episodesLabel = stringResource(Res.string.compose_player_episodes),
        nextEpisodeLabel = stringResource(Res.string.player_next_episode),
        externalPlayerLabel = stringResource(Res.string.streams_open_external_player),
        playLabel = stringResource(Res.string.detail_btn_play),
        pauseLabel = stringResource(Res.string.compose_action_pause),
        closeLabel = stringResource(Res.string.compose_player_close),
        mutedLabel = stringResource(Res.string.compose_player_muted),
        volumeLevelLabelFormat = stringResource(Res.string.compose_player_volume_level, "%s"),
        lockLabel = stringResource(Res.string.compose_player_lock_controls),
        unlockLabel = stringResource(Res.string.compose_player_unlock_controls),
        submitIntroLabel = stringResource(Res.string.submit_intro_action),
        videoSettingsLabel = stringResource(Res.string.player_action_video_settings),
        watchTogetherLabel = stringResource(Res.string.watch_party_title),
        tapToUnlockLabel = stringResource(Res.string.compose_player_tap_to_unlock),
        playbackErrorTitle = stringResource(Res.string.compose_player_playback_error),
        playbackErrorMessage = errorMessage.orEmpty(),
        playbackErrorActionLabel = stringResource(Res.string.compose_player_go_back),
        sourcesPanelTitle = stringResource(Res.string.compose_player_panel_sources),
        episodesPanelTitle = stringResource(Res.string.compose_player_panel_episodes),
        streamsPanelTitle = stringResource(Res.string.compose_player_panel_streams),
        allFilterLabel = allFilterLabel,
        reloadLabel = stringResource(Res.string.compose_action_reload),
        backLabel = stringResource(Res.string.action_back),
        panelCloseLabel = stringResource(Res.string.action_close),
        cancelLabel = stringResource(Res.string.action_cancel),
        playingLabel = playingLabel,
        noStreamsLabel = stringResource(Res.string.compose_player_no_streams_found),
        noEpisodesLabel = stringResource(Res.string.compose_player_no_episodes_available),
        submitIntroPanelTitle = stringResource(Res.string.submit_intro_title),
        submitIntroSegmentTypeLabel = stringResource(Res.string.submit_intro_segment_type_label),
        submitIntroSegmentIntroLabel = stringResource(Res.string.submit_intro_segment_intro),
        submitIntroSegmentRecapLabel = stringResource(Res.string.submit_intro_segment_recap),
        submitIntroSegmentOutroLabel = stringResource(Res.string.submit_intro_segment_outro),
        submitIntroStartTimeLabel = stringResource(Res.string.submit_intro_start_time_label),
        submitIntroEndTimeLabel = stringResource(Res.string.submit_intro_end_time_label),
        submitIntroCaptureLabel = stringResource(Res.string.submit_intro_capture_button),
        submitIntroSubmitLabel = stringResource(Res.string.submit_intro_button_submit),
        p2pConsentTitle = stringResource(Res.string.p2p_consent_title),
        p2pConsentBody = stringResource(Res.string.p2p_consent_body),
        p2pConsentEnableLabel = stringResource(Res.string.p2p_consent_enable),
        p2pConsentCancelLabel = stringResource(Res.string.p2p_consent_cancel),
        speedPanelTitle = stringResource(Res.string.compose_player_playback_speed),
        audioTracksPanelTitle = stringResource(Res.string.compose_player_audio_tracks),
        noAudioTracksLabel = stringResource(Res.string.compose_player_no_audio_tracks_available),
        subtitlesPanelTitle = stringResource(Res.string.compose_player_subtitles),
        subtitleLanguagesLabel = stringResource(Res.string.compose_player_languages),
        subtitleBuiltInTabLabel = stringResource(Res.string.compose_player_built_in),
        subtitleAddonsTabLabel = stringResource(Res.string.addon_title),
        subtitleStyleTabLabel = stringResource(Res.string.compose_player_style),
        customSubtitleStyleLabel = stringResource(Res.string.compose_player_use_custom_styling),
        forcedLabel = stringResource(Res.string.settings_playback_option_forced),
        noneLabel = stringResource(Res.string.compose_player_none),
        fetchSubtitlesLabel = stringResource(Res.string.compose_player_fetch_subtitles),
        subtitleDelayLabel = stringResource(Res.string.compose_player_subtitle_delay),
        resetLabel = stringResource(Res.string.compose_player_reset),
        autoSyncLabel = stringResource(Res.string.compose_player_auto_sync),
        reloadSmallLabel = stringResource(Res.string.compose_player_reload),
        captureLineLabel = stringResource(Res.string.compose_player_capture_line),
        selectAddonSubtitleFirstLabel = stringResource(Res.string.compose_player_select_addon_subtitle_first),
        loadingSubtitleLinesLabel = stringResource(Res.string.compose_player_loading_lines),
        fontSizeLabel = stringResource(Res.string.compose_player_font_size),
        outlineLabel = stringResource(Res.string.compose_player_outline),
        boldLabel = stringResource(Res.string.compose_player_bold),
        bottomOffsetLabel = stringResource(Res.string.compose_player_bottom_offset),
        colorLabel = stringResource(Res.string.compose_player_color),
        textOpacityLabel = stringResource(Res.string.compose_player_text_opacity),
        outlineColorLabel = stringResource(Res.string.compose_player_outline_color),
        noSubtitleLinesFoundLabel = stringResource(Res.string.compose_player_no_subtitle_lines_found),
        resetDefaultsLabel = stringResource(Res.string.compose_player_reset_defaults),
        onLabel = stringResource(Res.string.compose_action_on),
        offLabel = stringResource(Res.string.compose_action_off),
        themeAccentColor = themeColors.accent.toCssColorString(),
        themeAccentStrongColor = themeColors.accentStrong.toCssColorString(),
        themeOnAccentColor = themeColors.onAccent.toCssColorString(),
        themeFocusColor = themeColors.focusRing.toCssColorString(),
        themeSelectedSurfaceColor = themeColors.accent.copy(alpha = 0.24f).toCssColorString(),
        themeSelectedSurfaceHoverColor = themeColors.accent.copy(alpha = 0.34f).toCssColorString(),
        themeSelectedRingColor = themeColors.accent.copy(alpha = 0.35f).toCssColorString(),
        themeTimelineFillColor = themeColors.playerTimelineFill.toCssColorString(),
        themeTimelineTrackColor = themeColors.playerTimelineTrack.toCssColorString(),
        themeBufferingColor = themeColors.playerBuffering.toCssColorString(),
        themeBufferingTrackColor = themeColors.playerBuffering.copy(alpha = 0.28f).toCssColorString(),
        themeControlForegroundColor = themeColors.playerControlsForeground.toCssColorString(),
        themeSurfaceElevatedColor = themeColors.surfaceElevated.toCssColorString(),
        themeSurfaceCardColor = themeColors.surfaceCard.toCssColorString(),
        themeSurfacePopoverColor = themeColors.surfacePopover.toCssColorString(),
        themeTextPrimaryColor = themeColors.textPrimary.toCssColorString(),
        themeTextSecondaryColor = themeColors.textSecondary.toCssColorString(),
        themeTextMutedColor = themeColors.textMuted.toCssColorString(),
        themeBorderDefaultColor = themeColors.borderDefault.toCssColorString(),
        isPlaying = playbackSnapshot.isPlaying,
        isLoading = playbackSnapshot.isLoading,
        isLocked = playerControlsLocked,
        lockedOverlayVisible = lockedOverlayVisible,
        controlsVisible = controlsVisible && !playerControlsLocked,
        parentalWarnings = parentalWarnings,
        showParentalGuide = showParentalGuide,
        showSubmitIntro = isSeries &&
            playerSettingsUiState.introSubmitEnabled &&
            playerSettingsUiState.introDbApiKey.isNotBlank() &&
            !activeSubmitIntroImdbId().isNullOrBlank(),
        showVideoSettings = isIos,
        // ⚠ Gated, and `activeParty` is deliberately still part of the test rather than replaced
        // by it. With the layer off there is no party - `shutdownSocialLayer` departs it before
        // the preference flips - so this reads false either way; keeping the original condition
        // means the control never vanishes from *underneath* a party that somehow still exists.
        // ⚠ **A party needs a source a guest could also open.** Offering the control over a local
        // download, a cloud file or anything else with no shareable descriptor gave the user a
        // button that silently did nothing - `startWatchTogetherFromCurrentPlayback` returned at
        // its descriptor guard and said so to no one. Those sources are not a bug to fix; a guest
        // genuinely cannot obtain the host's local file. So the affordance is not offered, and if
        // the action arrives anyway it now explains itself rather than dropping.
        // Unshareable is a panel state now, not a hidden button: the reason has to be findable.
        showWatchTogether = socialEnabled && args.onStartWatchTogether != null,
        showSources = activeVideoId != null,
        showEpisodes = isSeries,
        // Hidden for a guest: the host advances the party's episode, and a control that is
        // refused when pressed is worse than one that is not offered.
        showNextEpisode = nextEpisodeInfo?.hasAired == true && ownsNextEpisode,
        showExternalPlayer = args.onOpenInExternalPlayer != null,
        durationMs = playbackSnapshot.durationMs,
        positionMs = displayedPositionMs,
        sourceIsLoading = sourceStreamsState.isAnyLoading,
        sourceFilters = sourceFilters,
        sourceItems = sourceItems,
        episodeItems = episodeItems,
        episodeSeasons = episodeSeasons,
        episodeStreamsVisible = episodeStreamsPanelState.showStreams,
        episodeStreamsIsLoading = episodeStreamsRepoState.isAnyLoading,
        selectedEpisodeLabel = selectedEpisodeLabel,
        episodeStreamFilters = episodeStreamFilters,
        episodeStreamItems = episodeStreamItems,
        blurUnwatchedEpisodes = metaScreenSettingsUiState.blurUnwatchedEpisodes,
        submitIntroSegmentType = submitIntroSegmentType,
        submitIntroContentKey = activeSubmitIntroContentKey(),
        submitIntroStartTime = submitIntroStartTimeStr,
        submitIntroEndTime = submitIntroEndTimeStr,
        isSubmitIntroSubmitting = isSubmitIntroSubmitting,
        submitIntroStatusMessage = submitIntroStatusMessage.orEmpty(),
        showP2pConsent = playerControlsPendingP2pSwitch != null,
        subtitleActiveTab = activeSubtitleTab.name,
        subtitleLanguageItems = playerControlSubtitleSelection.languages,
        subtitleOptionItems = playerControlSubtitleSelection.options,
        selectedSubtitleLanguageKey = playerControlSubtitleSelection.selectedLanguageKey,
        selectedSubtitleOptionId = playerControlSubtitleSelection.selectedOptionId,
        addonSubtitleItems = playerControlAddonSubtitles,
        isLoadingAddonSubtitles = isLoadingAddonSubtitles,
        selectedAddonSubtitleId = selectedAddonSubtitleId.orEmpty(),
        useCustomSubtitles = useCustomSubtitles,
        customSubtitleStylingEnabled = !playerSettingsUiState.useLibass,
        subtitleStyle = subtitleStyle,
        subtitleDelayMs = subtitleDelayMs,
        hasSelectedAddonSubtitle = selectedAddonSubtitle != null,
        subtitleAutoSyncCapturedPositionMs = subtitleAutoSyncState.capturedPositionMs ?: -1L,
        subtitleAutoSyncCues = playerControlAutoSyncCues,
        subtitleAutoSyncIsLoading = subtitleAutoSyncState.isLoading,
        subtitleAutoSyncErrorMessage = subtitleAutoSyncState.errorMessage.orEmpty(),
        closeModalsToken = playerControlsCloseModalsToken,
        submitIntroSuccessToken = playerControlsSubmitIntroSuccessToken,
        notificationMessage = playerNotificationMessage,
        notificationToken = playerNotificationToken,
        showOpeningOverlay = openingOverlayWanted,
        openingArtwork = openingPresentation.artwork,
        openingLogo = openingPresentation.logo,
        openingTitle = openingPresentation.title,
        openingMessage = if (startingEpisode != null) {
            stringResource(Res.string.player_next_episode_starting)
        } else {
            // The last step of an accepted join request's hand-off names whose party this is.
            PartyJoinHandoff.forParty(activeParty?.id)?.let { "Joining ${partyPossessive(it.hostName)} party" }
                ?: p2pInitialLoadingMessage
        },
        openingProgress = p2pInitialLoadingProgress,
        // `LocalDensity` here is already `platformDensity x effectiveDesktopUiScale` - see
        // `NuvioTheme` - so dividing the two recovers the scale the browser needs to match.
        openingScale = LocalDensity.current.density / LocalNuvioPlatformDensity.current.density,
        openingStageLabel = p2pInitialLoadingMessage
            ?: stringResource(Res.string.playback_progress_starting),
        openingAttemptLabel = if (openingLoadingState.showsAttempt) {
            stringResource(
                Res.string.playback_progress_attempt,
                openingLoadingState.displayAttempt,
                openingLoadingState.maxAttempts,
            )
        } else {
            ""
        },
        openingFacts = PlaybackLoadingFacts
            .facts(
                facts = openingLoadingState.facts,
                formatSize = ::formatFileSize,
                contentLanguage = openingLoadingState.contentLanguage,
                languageName = openingNamer,
            )
            .map { fact ->
                PlayerOpeningFact(
                    label = stringResource(playbackFactSlotLabelRes(fact.slot)).uppercase(),
                    value = fact.value ?: PlaybackLoadingFacts.UNKNOWN,
                )
            },
        openingOffersManualEscape = PlaybackLoadingController.session?.offersManualEscape == true &&
            PlaybackLoadingController.actions?.onChooseManually != null,
        openingManualEscapeLabel = stringResource(Res.string.playback_quality_manual),
        openingProviderLine = PlaybackLoadingFacts
            .providerLine(openingLoadingState.facts)
            .orEmpty(),
        openingReleaseName = openingLoadingState.releaseName.orEmpty(),
        partyStatus = partyStatusBridgeState(partyStatusLine, suppressed = playerControlsLocked),
        watchTogether = watchTogetherBridge,
        partyTransportLocked = activeParty != null && !partyMayControl,
        partyHostName = activeParty?.let { party ->
            party.members.firstOrNull { it.profileId == party.hostProfileId }?.displayName(viewerProfileId = null)
        }.orEmpty(),
        socialNotificationVisible = activeSocialNotification != null && !playerControlsLocked,
        socialNotificationActor = activeSocialNotification?.actor?.displayName.orEmpty(),
        socialNotificationMessage = when (activeSocialNotification?.kind) {
            SocialNotificationKind.FriendRequest -> "sent you a friend request"
            SocialNotificationKind.PartyInvitation -> {
                val mediaTitle = activeSocialNotification.contentSummary?.title
                if (!mediaTitle.isNullOrBlank()) "invited you to Watch Together · $mediaTitle"
                else "invited you to Watch Together"
            }
            SocialNotificationKind.WatchingNowJoinRequest -> "asked to join your playback"
            null -> ""
        },
        socialNotificationActions = activeSocialNotification?.availableActions.orEmpty()
            .map { it.name.lowercase() }
            .sorted(),
        skipPromptVisible = nativeSkipInterval != null && !playerControlsLocked,
        skipPromptLabel = skipPromptLabel(nativeSkipInterval?.type),
        skipPromptStartMs = ((nativeSkipInterval?.startTime ?: 0.0) * 1000).toLong().coerceAtLeast(0L),
        skipPromptEndMs = ((nativeSkipInterval?.endTime ?: 0.0) * 1000).toLong().coerceAtLeast(0L),
        skipPromptDismissed = skipIntervalDismissed,
        nextEpisodeVisible = nextEpisodeForControls != null && !playerControlsLocked,
        nextEpisodeHeaderLabel = stringResource(Res.string.player_next_episode),
        nextEpisodeTitle = nextEpisodeForControls?.let {
            stringResource(
                Res.string.compose_player_episode_title_format,
                it.season,
                it.episode,
                it.title,
            )
        }.orEmpty(),
        nextEpisodeThumbnail = nextEpisodeForControls?.thumbnail.orEmpty(),
        nextEpisodeStatus = nextEpisodeStatus,
        nextEpisodeActionLabel = if (nextEpisodeForControls?.hasAired == true) {
            stringResource(Res.string.detail_btn_play)
        } else {
            stringResource(Res.string.player_next_episode_unaired)
        },
        nextEpisodePlayable =
            nextEpisodeForControls?.hasAired == true && nextEpisodeTransition.canAcceptManualTap(),
    )
    val gestureCallbacks = rememberSurfaceGestureCallbacks()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { layoutSize = it }
            .playerSurfaceTapGestures(
                layoutSize = layoutSize,
                playerControlsLockedState = gestureCallbacks.playerControlsLocked,
                onSurfaceTap = gestureCallbacks.onSurfaceTap,
                onSurfaceDoubleTap = gestureCallbacks.onSurfaceDoubleTap,
                activateHoldToSpeedState = gestureCallbacks.activateHoldToSpeed,
                deactivateHoldToSpeedState = gestureCallbacks.deactivateHoldToSpeed,
                revealLockedOverlayState = gestureCallbacks.revealLockedOverlay,
            )
            .playerSurfaceDragGestures(
                gestureController = gestureController,
                layoutSize = layoutSize,
                sideGestureSystemEdgeExclusionPx = sideGestureSystemEdgeExclusionPx,
                playerControlsLockedState = gestureCallbacks.playerControlsLocked,
                touchGesturesEnabledState = gestureCallbacks.touchGesturesEnabled,
                isHoldToSpeedGestureActiveState = gestureCallbacks.isHoldToSpeedGestureActive,
                currentPositionMsState = gestureCallbacks.currentPositionMs,
                currentDurationMsState = gestureCallbacks.currentDurationMs,
                deactivateHoldToSpeedState = gestureCallbacks.deactivateHoldToSpeed,
                showHorizontalSeekPreviewState = gestureCallbacks.showHorizontalSeekPreview,
                showBrightnessFeedbackState = gestureCallbacks.showBrightnessFeedback,
                showVolumeFeedbackState = gestureCallbacks.showVolumeFeedback,
                clearLiveGestureFeedbackState = gestureCallbacks.clearLiveGestureFeedback,
                revealLockedOverlayState = gestureCallbacks.revealLockedOverlay,
                commitHorizontalSeekState = gestureCallbacks.commitHorizontalSeek,
            ),
    ) {
        if (renderPlayerSurface) {
            val surfaceSource = currentPlayerSurfaceSource
            val sourceAvailable = surfaceSource != null
            PlatformPlayerSurface(
                sourceUrl = surfaceSource?.sourceUrl.orEmpty(),
                sourceAvailable = sourceAvailable,
                sourceAudioUrl = surfaceSource?.sourceAudioUrl,
                sourceHeaders = surfaceSource?.sourceHeaders.orEmpty(),
                sourceResponseHeaders = surfaceSource?.sourceResponseHeaders.orEmpty(),
                externalSubtitles = surfaceSource?.externalSubtitles.orEmpty(),
                streamType = surfaceSource?.streamType,
                modifier = Modifier.fillMaxSize(),
                playWhenReady = shouldPlay && sourceAvailable,
                initialPositionMs = surfaceSource?.initialPositionMs,
                initialPositionRequestKey = surfaceSource?.initialPositionRequestKey,
                resizeMode = resizeMode,
                playerControlsState = playerControlsState,
                onPlayerControlsAction = { action -> handlePlayerControlsAction(action) },
                onPlayerControlsEvent = { type, value -> handlePlayerControlsEvent(type, value) },
                onPlayerControlsScrubChange = { positionMs ->
                    handlePlayerControlsScrubChange(positionMs)
                    true
                },
                onPlayerControlsScrubFinished = { positionMs ->
                    handlePlayerControlsScrubFinished(positionMs)
                    true
                },
                onInitialPositionHandled = { key, handled ->
                    if (key == currentInitialPositionRequestKey()) {
                        initialSeekApplied = handled
                    }
                },
                onControllerReady = { controller ->
                    playerController = controller.takeIf { sourceAvailable }
                    playerLifecycleController = controller
                    playerControllerSourceUrl = surfaceSource?.sourceUrl
                },
                onSnapshot = { snapshot ->
                    val wasPlaying = playbackSnapshot.isPlaying
                    playbackSnapshot = snapshot
                    // Stamped where it arrives, because nothing downstream can recover the age of a
                    // sample once it has been handed on without one.
                    playbackSnapshotAtMs = kotlin.time.Clock.System.now().toEpochMilliseconds()
                    if (!wasPlaying && snapshot.isPlaying) args.onPlaybackStarted?.invoke()
                    if (!snapshot.isLoading) {
                        initialLoadCompleted = true
                        if (snapshot.isPlaying) {
                            completeNextEpisodeTransitionIfStarted()
                        }
                    }
                    if (
                        PlaybackHandover.hasFirstFrame(
                            isLoading = snapshot.isLoading,
                            isPlaying = snapshot.isPlaying,
                            positionMs = snapshot.positionMs,
                            videoWidth = snapshot.videoWidth,
                            videoHeight = snapshot.videoHeight,
                        )
                    ) {
                        firstFrameReached = true
                    }
                    refreshAudioTracksIfChanged()
                    if (snapshot.isEnded) {
                        shouldPlay = false
                        controlsVisible = !playerControlsLocked
                    }
                    observePlaybackForNetworkEstimate()
                    observePlaybackForThroughput()
                },
                onError = { message ->
                    if (message != null && tryRefreshCredentialedSourceAfterError(message)) {
                        return@PlatformPlayerSurface
                    }
                    // The whole tail lives in `failPlaybackFatally` so that the credential
                    // refresh can reach it too: that path answers `true` here before it knows
                    // whether a replacement exists, so its own dead ends are the only thing
                    // left that can advance the chain.
                    failPlaybackFatally(message)
                },
            )
        }

        AnimatedVisibility(
            visible = pausedOverlayVisible && !controlsVisible && !playerControlsLocked,
            enter = fadeIn(animationSpec = tween(durationMillis = 220)),
            exit = fadeOut(animationSpec = tween(durationMillis = 180)),
        ) {
            PauseMetadataOverlay(
                title = title,
                logo = logo,
                isEpisode = isEpisode,
                seasonNumber = activeSeasonNumber,
                episodeNumber = activeEpisodeNumber,
                episodeTitle = activeEpisodeTitle,
                pauseDescription = activePauseDescription ?: activeStreamSubtitle,
                providerName = activeProviderName,
                metrics = metrics,
                horizontalSafePadding = horizontalSafePadding,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (!isDesktop) {
            RenderPlayerControls(displayedPositionMs = displayedPositionMs, isEpisode = isEpisode)
        }
        RenderPlaybackOverlays(
            runtime = runtime,
            displayedPositionMs = displayedPositionMs,
            currentGestureFeedback = currentGestureFeedback,
            p2pInitialLoadingMessage = p2pInitialLoadingMessage,
            p2pInitialLoadingProgress = p2pInitialLoadingProgress,
            showP2pRebufferStats = showP2pRebufferStats,
            p2pRebufferMessage = p2pRebufferMessage,
            p2pRebufferProgress = p2pRebufferProgress,
            suppressOpeningOverlay = false,
            // Desktop draws its chrome in the native controls layer above the video surface, where
            // a Compose overlay would be invisible; there the banner is carried by
            // PlayerControlsState instead.
            watchPartyBanner = partyStatusLine?.text.takeUnless { isDesktop },
        )
        RenderPlaybackDiagnosticsHud()
        RenderPlayerModals(displayedPositionMs = displayedPositionMs)
    }
}

@Composable
private fun p2pConnectingPhaseLabel(phase: String): String = when (phase) {
    "add_magnet" -> org.jetbrains.compose.resources.stringResource(
        nuvio.composeapp.generated.resources.Res.string.player_torrent_fetching_metadata,
    )
    "prepare_stream", "attach_route" -> org.jetbrains.compose.resources.stringResource(
        nuvio.composeapp.generated.resources.Res.string.player_torrent_preparing_stream,
    )
    else -> org.jetbrains.compose.resources.stringResource(
        nuvio.composeapp.generated.resources.Res.string.player_torrent_starting_engine,
    )
}

private fun PlayerScreenRuntime.currentInitialPositionRequestKey(): String? {
    val positionMs = activeInitialPositionMs.takeIf { it > 0L } ?: return null
    return "$activePlaybackIdentity:${activeVideoId.orEmpty()}:$positionMs"
}

@Composable
private fun PlayerScreenRuntime.RenderPlayerControls(displayedPositionMs: Long, isEpisode: Boolean) {
    val isInPip = rememberIsInPictureInPicture()
    AnimatedVisibility(
        visible = (controlsVisible || showParentalGuide) && !playerControlsLocked && !isInPip,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        PlayerControlsShell(
            title = title,
            streamTitle = activeStreamTitle,
            providerName = activeProviderName,
            seasonNumber = activeSeasonNumber,
            episodeNumber = activeEpisodeNumber,
            episodeTitle = activeEpisodeTitle,
            playbackSnapshot = playbackSnapshot,
            displayedPositionMs = displayedPositionMs,
            metrics = metrics,
            resizeMode = resizeMode,
            isLocked = playerControlsLocked,
            showPlaybackControls = controlsVisible,
            onLockToggle = {
                if (playerControlsLocked) unlockPlayerControls() else lockPlayerControls()
            },
            onBack = { requestBack() },
            onTogglePlayback = { togglePlayback() },
            onSeekBack = { seekBy(-10_000L) },
            onSeekForward = { seekBy(10_000L) },
            onResizeModeClick = { cycleResizeMode() },
            onSpeedClick = { cyclePlaybackSpeed() },
            onSubtitleClick = {
                refreshTracks()
                showSubtitleModal = true
            },
            onAudioClick = {
                refreshTracks()
                showAudioModal = true
            },
            onVideoSettingsClick = if (isIos) {
                {
                    showVideoSettingsModal = true
                    controlsVisible = true
                }
            } else {
                null
            },
            onSourcesClick = if (activeVideoId != null) { { openSourcesPanel() } } else null,
            onEpisodesClick = if (isSeries) { { openEpisodesPanel() } } else null,
            onNextEpisodeClick = if (nextEpisodeInfo?.hasAired == true) { { playNextEpisodeFromControls() } } else null,
            onOpenInExternalPlayer = args.onOpenInExternalPlayer?.let { openExternal ->
                {
                    val loadedSubtitles = addonSubtitles
                        .takeIf { it.isNotEmpty() }
                        ?.map { sub ->
                            SubtitleInput(
                                url = sub.url,
                                name = buildString {
                                    if (!sub.addonName.isNullOrBlank()) append("[${sub.addonName}] ")
                                    append(sub.display)
                                },
                                lang = sub.language,
                            )
                        }
                    openExternal(
                        ExternalPlayerPlaybackRequest(
                            sourceUrl = activeSourceUrl,
                            title = title,
                            streamTitle = activeStreamTitle,
                            sourceHeaders = activeSourceHeaders,
                            resumePositionMs = playbackSnapshot.positionMs,
                            subtitles = loadedSubtitles,
                            season = activeSeasonNumber,
                            episode = activeEpisodeNumber,
                            episodeTitle = activeEpisodeTitle,
                        ),
                    )
                }
            },
            onSubmitIntroClick = if (
                isSeries &&
                playerSettingsUiState.introSubmitEnabled &&
                playerSettingsUiState.introDbApiKey.isNotBlank()
            ) {
                { showSubmitIntroModal = true }
            } else {
                null
            },
            parentalWarnings = parentalWarnings,
            showParentalGuide = showParentalGuide,
            onParentalGuideAnimationComplete = { showParentalGuide = false },
            onScrubChange = { positionMs ->
                isScrubbingTimeline = true
                scrubbingPositionMs = positionMs
            },
            onScrubFinished = { positionMs ->
                isScrubbingTimeline = false
                scrubbingPositionMs = null
                // The party seeks everyone at one instant, this player included, so performing the
                // seek here as well would move it twice - once to the scrub target and once to
                // wherever the barrier says the party will be.
                if (!submitPartySeek(positionMs)) playerController?.seekTo(positionMs)
                scheduleProgressSyncAfterSeek()
            },
            horizontalSafePadding = horizontalSafePadding,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

internal fun releasePlayerBeforeNavigation(
    releasePlayer: (
        onReleased: () -> Unit,
        onReleaseFailed: (String) -> Unit,
    ) -> Unit,
    navigateBack: () -> Unit,
    onReleaseFailed: (String) -> Unit = {},
) {
    releasePlayer(navigateBack, onReleaseFailed)
}

internal fun releaseRetainedPlayerBeforeNavigation(
    controller: PlayerEngineController?,
    navigateBack: () -> Unit,
    onReleaseFailed: (String) -> Unit = {},
) {
    if (controller == null) {
        navigateBack()
    } else {
        controller.releaseBeforeNavigation(navigateBack, onReleaseFailed)
    }
}

internal enum class PartyRoomBackDecision { CloseRoom, ExitPlayer }

internal fun partyRoomBackDecision(roomOpen: Boolean): PartyRoomBackDecision =
    if (roomOpen) PartyRoomBackDecision.CloseRoom else PartyRoomBackDecision.ExitPlayer

private fun PlayerScreenRuntime.requestBack() {
    if (partyRoomBackDecision(partyRoomOpen) == PartyRoomBackDecision.CloseRoom) {
        partyRoomOpen = false
        controlsVisible = true
        return
    }
    PlayerExitDiagnostics.recordT0("requestBack")
    // ⚠ **The session ends here because the user said so, and nowhere else can say it.**
    //
    // Both owners close the session from the `else` branch of a `LaunchedEffect` - this file at
    // `openingOverlayWanted`, `StreamDestination` at `showLoadingSurface`. An effect is *cancelled*
    // on disposal and never runs its `else`, so tearing this screen down leaked the session every
    // time: `PlaybackLoadingHost` draws above `NavDisplay` and stops for nothing, leaving a loading
    // screen over the app that nothing alive could close. That is the "press Escape and you are
    // trapped on a loading screen that never loads" report, and it is why the surface survived
    // exactly the case its contract says ends it.
    //
    // Deliberately here rather than in an `onDispose`: this screen is also disposed by a *failover*,
    // which pops the player and re-enters the stream route, and the surface must survive that
    // untouched. An explicit back is the one teardown that is unambiguously the user leaving.
    PlaybackLoadingController.closeAfterHandOff()
    flushWatchProgress()
    val exitingController = playerLifecycleController
    args.onBack { afterRelease, releaseFailed ->
        val releaseAttemptId = playerReleaseSurfaceRetention.begin()
        try {
            releaseRetainedPlayerBeforeNavigation(
                controller = exitingController,
                navigateBack = {
                    if (!playerReleaseSurfaceRetention.finish(releaseAttemptId)) {
                        return@releaseRetainedPlayerBeforeNavigation
                    }
                    if (playerLifecycleController === exitingController) {
                        playerLifecycleController = null
                    }
                    if (playerController === exitingController) {
                        playerController = null
                    }
                    afterRelease()
                },
                onReleaseFailed = { message ->
                    if (!playerReleaseSurfaceRetention.finish(releaseAttemptId)) {
                        return@releaseRetainedPlayerBeforeNavigation
                    }
                    errorMessage = message
                    releaseFailed(message)
                },
            )
        } catch (failure: Throwable) {
            playerReleaseSurfaceRetention.finish(releaseAttemptId)
            throw failure
        }
    }
}

private fun PlayerScreenRuntime.handlePlayerControlsAction(action: PlayerControlsAction): Boolean {
    playerControlsLog.d { "action=$action ${playerControlLogContext()}" }
    when (action) {
        PlayerControlsAction.ToggleChrome -> {
            if (playerControlsLocked) {
                revealLockedOverlay()
            } else {
                controlsVisible = !controlsVisible
            }
        }
        PlayerControlsAction.RevealLockedOverlay -> revealLockedOverlay()
        PlayerControlsAction.Back -> requestBack()
        // ⚠ **Signalled and popped, never invoked in place.** Calling the route's
        // `onChooseManually` from here did nothing the user could see: in the automatic modes
        // `StreamRoute` has stopped composing while this player is on top, so the flags that
        // callback sets are written into state that has already been saved and are dropped when
        // the route is restored - and nothing left the player either, so the screen did not
        // change. Reported as "the select source manually button doesn't work", and it could not
        // have worked. The route consumes the request when the pop brings it back.
        PlayerControlsAction.ChooseManually -> {
            StreamsRepository.signalManualSourceRequest()
            requestBack()
        }
        // Returning true is what stops the native controls layer performing the transport itself
        // (`NativePlayerController.handleFallbackAction`). While a party owns this playback it has
        // to: a host must not start before the instant it just scheduled for everybody else, and a
        // guest without control must not move its own player at all.
        PlayerControlsAction.TogglePlayback -> {
            return prepareTogglePlaybackForNativeFallback()
        }
        PlayerControlsAction.KeyboardTogglePlayback -> {
            return prepareTogglePlaybackForNativeFallback(revealControls = false)
        }
        PlayerControlsAction.SeekBack -> {
            return prepareSeekByForNativeFallback(-10_000L)
        }
        PlayerControlsAction.KeyboardSeekBack -> {
            return prepareSeekByForNativeFallback(-10_000L, revealControls = false)
        }
        PlayerControlsAction.SeekForward -> {
            return prepareSeekByForNativeFallback(10_000L)
        }
        PlayerControlsAction.KeyboardSeekForward -> {
            return prepareSeekByForNativeFallback(10_000L, revealControls = false)
        }
        PlayerControlsAction.KeyboardVolumeDown,
        PlayerControlsAction.KeyboardVolumeUp -> {
            return false
        }
        PlayerControlsAction.ResizeMode -> cycleResizeMode()
        PlayerControlsAction.Speed -> cyclePlaybackSpeed()
        PlayerControlsAction.Subtitles -> {
            refreshTracks()
            showSubtitleModal = true
        }
        PlayerControlsAction.Audio -> {
            refreshTracks()
            showAudioModal = true
        }
        PlayerControlsAction.Sources -> {
            prepareSourcesForPlayerControls()
        }
        PlayerControlsAction.Episodes -> {
            prepareEpisodesForPlayerControls()
        }
        PlayerControlsAction.NextEpisode -> {
            playNextEpisodeFromControls()
        }
        PlayerControlsAction.OpenExternalPlayer -> openInExternalPlayer()
        PlayerControlsAction.SubmitIntro -> {
            submitIntroStatusMessage = null
        }
        // ⚠ **Only opens the panel.** Pressing this with no party used to promote the playback on the
        // spot. Starting a party is the panel's Start a party button and nothing else.
        PlayerControlsAction.WatchTogether -> {
            partyRoomOpen = !partyRoomOpen
            controlsVisible = true
        }
        PlayerControlsAction.LockToggle -> {
            if (playerControlsLocked) unlockPlayerControls() else lockPlayerControls()
        }
        PlayerControlsAction.VideoSettings -> {
            if (isIos) {
                showVideoSettingsModal = true
                controlsVisible = true
            }
        }
        PlayerControlsAction.DoubleTapSeekBack -> {
            return prepareDoubleTapSeekForNativeFallback(PlayerSeekDirection.Backward)
        }
        PlayerControlsAction.DoubleTapSeekForward -> {
            return prepareDoubleTapSeekForNativeFallback(PlayerSeekDirection.Forward)
        }
    }
    return true
}

private fun PlayerScreenRuntime.startWatchTogetherFromCurrentPlayback() {
    // ⚠ **Both of these used to be bare `?: return`.** The control was offered over sources that
    // can never host a party, and pressing it did nothing at all - no toast, no log, nothing to
    // tell the user or anyone reading a log which of the two guards had fired. The affordance is
    // now gated on the descriptor as well (see `showWatchTogether`), so reaching here without one
    // means something upstream changed; say so both ways rather than dropping it.
    val callback = args.onStartWatchTogether
    val descriptor = activePartySourceDescriptor
    if (callback == null || descriptor == null) {
        playerControlsLog.w {
            "start watch together refused - hasCallback=${callback != null} " +
                "hasDescriptor=${descriptor != null} videoId=${playbackSession.videoId}"
        }
        scope.launch {
            NuvioToastController.show(getString(Res.string.watch_party_cannot_share_source))
        }
        return
    }
    callback(
        PartyContent(
            contentId = parentMetaId,
            contentType = parentMetaType,
            videoId = playbackSession.videoId,
            title = title,
            poster = poster,
            season = activeSeasonNumber,
            episode = activeEpisodeNumber,
            episodeTitle = activeEpisodeTitle,
        ),
        descriptor,
        playbackSnapshot.positionMs,
        playbackSnapshot.playbackSpeed,
    )
}

private fun PlayerScreenRuntime.handlePlayerControlsEvent(type: String, value: Double): Boolean {
    if (type.shouldLogPlayerControlsEvent()) {
        playerControlsLog.d { "event type=$type value=$value ${playerControlLogContext()}" }
    }
    when (type) {
        "cursorActivity" -> {
            if (!playerControlsLocked) {
                controlsVisible = true
                controlsActivityTick += 1
            }
        }
        "hideChrome" -> {
            controlsVisible = false
        }
        "keepChromeVisible" -> {
            controlsVisible = true
            controlsActivityTick += 1
        }
        "setPlaybackState",
        "setPlaybackStateQuiet" -> {
            // This, not `PlayerControlsAction.TogglePlayback`, is where every desktop play/pause
            // actually arrives: `controls.js` sends these two for the play button, the surface click
            // and the spacebar, and they are consumed here before `toPlayerControlsAction` ever sees
            // them - which is why routing the *action* through the party left the desktop host
            // pausing silently, its guests learning about it only from the next timeline tick.
            val nextIsPlaying = value >= 0.5
            if (!submitPartyPlayPause(isPlaying = nextIsPlaying, positionMs = partyPositionNowMs())) {
                shouldPlay = nextIsPlaying
            }
            if (type == "setPlaybackState") {
                controlsVisible = true
            }
        }
        "reloadSources" -> {
            prepareSourcesForPlayerControls(forceRefresh = true)
        }
        "partyRoomClose" -> {
            partyRoomOpen = false
            partyEndConfirm = false
        }
        "wtStartParty", "wtRetry" -> {
            partyPanelError = null
            partyPromotion = PartyPromotionProgress.Starting
            startWatchTogetherFromCurrentPlayback()
        }
        "wtOpenExisting" -> {
            val party = WatchPartyRepository.uiState.value.party?.takeIf { it.status != WatchPartyStatus.ended } ?: return true
            partyPromotion = PartyPromotionProgress.Idle
            args.onPartyLobbyRequested?.invoke(party.id)
            requestBack()
        }
        "wtLeaveElsewhere" -> {
            partyPromotion = PartyPromotionProgress.Idle
            WatchPartySessionCoordinator.leave()
        }
        "wtSetJoinPolicy" -> {
            val policy = joinPolicyForSegment(value.toInt()) ?: return true
            joinPolicyError = null
            joinPolicyPending = policy
            scope.launch {
                SocialPresenceSession.setPolicy(policy)
                    .onFailure { if (joinPolicyPending == policy) joinPolicyError = "Couldn't change who can join. Try again." }
                // Only this press's own pending value: a later press is still in flight.
                if (joinPolicyPending == policy) joinPolicyPending = null
            }
        }
        "wtSetGuestControl" -> {
            val party = WatchPartyRepository.uiState.value.party ?: return true
            if (party.hostProfileId != WatchPartyRepository.uiState.value.activeProfileId) return true
            val mode = if (value >= 0.5) WatchPartyControlMode.collaborative else WatchPartyControlMode.host_only
            if (mode == party.controlMode) return true
            scope.launch {
                WatchPartyRepository.setControlMode(mode).onFailure {
                    partyPanelError = "Couldn't change who controls playback"
                }
            }
        }
        "wtSetWaitForEveryone" -> WatchPartyRepository.setWaitForEveryone(value >= 0.5)
        "wtInviteFriend", "partyInvite" -> {
            val party = WatchPartyRepository.uiState.value.party ?: return true
            val targets = SocialRepository.uiState.value.friends
                .filter { friend -> party.members.none { it.profileId == friend.profileId } }
            val target = targets.getOrNull(value.toInt()) ?: return true
            if (target.profileId in partyInvitedProfileIds) return true
            partyInvitedProfileIds = partyInvitedProfileIds + target.profileId
            scope.launch {
                WatchPartyRepository.invite(target.profileId).onFailure { failure ->
                    partyInvitedProfileIds = partyInvitedProfileIds - target.profileId
                    partyPanelError = failure.message ?: "Couldn't invite ${target.displayName}"
                }
            }
        }
        // The page copies the code itself; this only records that it happened.
        "wtCopyInviteCode" -> playerControlsLog.d { "invite code copied" }
        "wtAcceptRequest", "wtDeclineRequest" -> {
            val notification = SocialRepository.uiState.value.notifications.firstOrNull {
                it.readAt == null && it.kind == SocialNotificationKind.WatchingNowJoinRequest &&
                    SocialNotificationAction.Accept in it.availableActions
            } ?: return true
            handleSocialNotificationAction(
                if (type == "wtAcceptRequest") SocialNotificationAction.Accept else SocialNotificationAction.Decline,
                notificationId = notification.id,
            )
        }
        "wtCancelOutgoing" -> OutgoingJoinRequestStore.cancel()
        "wtJoinAccepted" -> OutgoingJoinRequestStore.joinNow()
        "wtDismissAccepted" -> OutgoingJoinRequestStore.notNow()
        "wtDismissError" -> {
            partyPanelError = null
            WatchPartyRepository.clearError()
        }
        "wtEndConfirm" -> partyEndConfirm = value >= 0.5
        // The status pill's actions.
        "wtChooseSource" -> {
            controlsVisible = true
            openSourcesPanel()
        }
        // Exactly what pressing play does while the start gate holds: the force start.
        "wtStartAnyway" -> {
            if (!submitPartyPlayPause(isPlaying = true, positionMs = partyPositionNowMs())) {
                shouldPlay = true
            }
        }
        "wtDontWait" -> stopWaitingForStalledGuests()
        "partyLeave" -> {
            partyEndConfirm = false
            WatchPartySessionCoordinator.leave()
        }
        "partyEnd" -> {
            partyEndConfirm = false
            WatchPartySessionCoordinator.end()
        }
        "partyEndContinue" -> {
            WatchPartySessionCoordinator.continueAfterPartyEnd()
            partyRoomOpen = false
        }
        "partyEndExit" -> {
            WatchPartySessionCoordinator.continueAfterPartyEnd()
            partyRoomOpen = false
            requestBack()
        }
        "socialNotificationDismiss" -> {
            val notification = SocialRepository.uiState.value.notifications.firstOrNull {
                it.readAt == null && it.availableActions.isNotEmpty()
            } ?: return true
            scope.launch { SocialRepository.markNotificationsRead(setOf(notification.id)) }
        }
        "socialNotificationAccept",
        "socialNotificationDecline",
        "socialNotificationJoin" -> handleSocialNotificationAction(
            when (type) {
                "socialNotificationAccept" -> SocialNotificationAction.Accept
                "socialNotificationJoin" -> SocialNotificationAction.Join
                else -> SocialNotificationAction.Decline
            },
        )
        "selectSource" -> {
            val streams = sourceStreamsState.groups.flatMap { it.streams }
            val stream = streams.getOrNull(value.toInt()) ?: return true
            if (requestP2pConsentForPlayerControls(stream = stream, episode = null)) return true
            switchToSource(stream)
            playerControlsCloseModalsToken += 1
        }
        "selectEpisode" -> {
            val episode = playerMetaVideos.getOrNull(value.toInt()) ?: return true
            if (selectDownloadedEpisodeForPlayback(
                    parentMetaId = parentMetaId,
                    episode = episode,
                    onDownloadedEpisodeSelected = { item, video -> switchToDownloadedEpisode(item, video) },
                )
            ) {
                playerControlsCloseModalsToken += 1
            } else {
                requestEpisodeStreamsForPlayerControls(episode)
            }
        }
        "selectEpisodeStream" -> {
            val episode = episodeStreamsPanelState.selectedEpisode ?: return true
            val stream = if (episodeStreamsPanelState.qualityChooser) {
                resolveEpisodeQualityChoice(value.toInt()) ?: return true
            } else {
                episodeStreamsRepoState.groups.flatMap { it.streams }.getOrNull(value.toInt()) ?: return true
            }
            if (requestP2pConsentForPlayerControls(stream = stream, episode = episode)) return true
            switchToEpisodeStream(stream, episode)
            playerControlsCloseModalsToken += 1
        }
        "backToEpisodes" -> {
            cancelNextEpisodeTransition(suppressForCurrentEpisode = false)
            episodeStreamsPanelState = EpisodeStreamsPanelState()
            PlayerStreamsRepository.clearEpisodeStreams()
        }
        "reloadEpisodeStreams" -> {
            val chooser = episodeStreamsPanelState.qualityChooser
            episodeStreamsPanelState.selectedEpisode?.let { requestEpisodeStreamsForPlayerControls(it, forceRefresh = true) }
            // A reload refetches the catalogue; it does not change which question is being asked.
            if (chooser) episodeStreamsPanelState = episodeStreamsPanelState.copy(qualityChooser = true)
        }
        "submitIntroSegment" -> {
            submitIntroSegmentType = when (value.toInt()) {
                1 -> "recap"
                2 -> "outro"
                else -> "intro"
            }
            submitIntroStatusMessage = null
        }
        "submitIntroStart" -> {
            val seconds = value.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            submitIntroStartTimeSec = seconds
            submitIntroStartTimeStr = formatPlayerControlsSeconds(seconds)
            submitIntroStatusMessage = null
        }
        "submitIntroEnd" -> {
            val seconds = value.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            submitIntroEndTimeSec = seconds
            submitIntroEndTimeStr = formatPlayerControlsSeconds(seconds)
            submitIntroStatusMessage = null
        }
        "submitIntroCommit" -> submitIntroFromPlayerControls()
        "skipInterval" -> {
            val interval = activeSkipInterval ?: return true
            // Clamped, like the shared overlay's own skip handler has always been. Marker data comes
            // from a different cut often enough that an end time past this file is ordinary, and
            // seeking past the end lands on the last frame, which is where playback stops.
            val rawMs = (interval.endTime * 1000).toLong()
            val durationMs = playbackSnapshot.durationMs
            val targetPositionMs = if (durationMs > 0L) rawMs.coerceAtMost(durationMs - 1) else rawMs
            if (!submitPartySeek(targetPositionMs)) playerController?.seekTo(targetPositionMs)
            scheduleProgressSyncAfterSeek()
            skipIntervalDismissed = true
        }
        "playNextEpisode" -> {
            if (nextEpisodeInfo?.hasAired == true) {
                playNextEpisodeFromControls()
            }
        }
        "dismissNextEpisode" ->
            cancelNextEpisodeTransition(suppressForCurrentEpisode = true)
        "enableP2pForPlayerControls" -> enableP2pForPlayerControls()
        "cancelP2pForPlayerControls" -> {
            playerControlsPendingP2pSwitch = null
            cancelNextEpisodeTransition(suppressForCurrentEpisode = false)
        }
        "subtitleTab" -> {
            activeSubtitleTab = when (value.toInt()) {
                1 -> SubtitleTab.Addons
                2 -> SubtitleTab.Style
                else -> SubtitleTab.BuiltIn
            }
        }
        "selectBuiltInSubtitleTrack" -> {
            val index = value.toInt()
            val wasCustom = useCustomSubtitles
            playerControlsLog.d {
                "selectBuiltInSubtitleTrack index=$index wasCustom=$wasCustom tracks=${subtitleTracks.size} ${playerControlLogContext()}"
            }
            selectedSubtitleIndex = index
            selectedAddonSubtitleId = null
            useCustomSubtitles = false
            persistInternalSubtitlePreference(subtitleTracks.firstOrNull { it.index == index })
            if (wasCustom) {
                playerController?.clearExternalSubtitleAndSelect(index)
            } else {
                playerController?.selectSubtitleTrack(index)
            }
        }
        "selectAudioTrack" -> {
            // The controls webview sends the track id (trackIdValue); map it back
            // to the logical index that selectAudioTrack() expects (falling back to
            // treating the value as an index if no id matches).
            val requestedId = value.toInt()
            val index = audioTracks.firstOrNull { it.id == requestedId.toString() }?.index
                ?: audioTracks.firstOrNull { it.index == requestedId }?.index
                ?: requestedId
            playerControlsLog.d {
                "selectAudioTrack id=$requestedId index=$index tracks=${audioTracks.size} ${playerControlLogContext()}"
            }
            selectedAudioIndex = index
            persistAudioPreference(audioTracks.firstOrNull { it.index == index })
            playerController?.selectAudioTrack(index)
        }
        "fetchAddonSubtitles" -> fetchAddonSubtitlesForActiveItem()
        "selectAddonSubtitle" -> {
            val addon = visibleAddonSubtitles.getOrNull(value.toInt()) ?: return true
            playerControlsLog.d {
                "selectAddonSubtitle index=${value.toInt()} addonId=${addon.id} language=${addon.language} ${playerControlLogContext()}"
            }
            selectedAddonSubtitleId = addon.id
            selectedSubtitleIndex = -1
            useCustomSubtitles = true
            persistAddonSubtitlePreference(addon)
            playerController?.setSubtitleUri(addon.url)
        }
        "subtitleDelayDelta" -> setSubtitleDelay((subtitleDelayMs + value.toInt()).coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS))
        "subtitleDelayReset" -> setSubtitleDelay(0)
        "subtitleAutoSyncCapture" -> captureSubtitleAutoSyncTime()
        "subtitleAutoSyncReload" -> loadSubtitleAutoSyncCues(force = true)
        "subtitleAutoSyncCue" -> {
            val cue = playerControlsNearestSubtitleCues().getOrNull(value.toInt()) ?: return true
            applySubtitleAutoSyncCue(cue)
        }
        "subtitleCustomStyleToggle" -> {
            PlayerSettingsRepository.setUseLibass(!playerSettingsUiState.useLibass)
        }
        "subtitleFontSizeDelta" -> {
            PlayerSettingsRepository.setSubtitleStyle(
                subtitleStyle.copy(fontSizeSp = (subtitleStyle.fontSizeSp + value.toInt()).coerceIn(subtitleFontSizeRangeSp)),
            )
        }
        "subtitleOutlineToggle" -> {
            PlayerSettingsRepository.setSubtitleStyle(subtitleStyle.copy(outlineEnabled = !subtitleStyle.outlineEnabled))
        }
        "subtitleBoldToggle" -> {
            PlayerSettingsRepository.setSubtitleStyle(subtitleStyle.copy(bold = !subtitleStyle.bold))
        }
        "subtitleBottomOffsetDelta" -> {
            PlayerSettingsRepository.setSubtitleStyle(
                subtitleStyle.copy(bottomOffset = (subtitleStyle.bottomOffset + value.toInt()).coerceIn(0, 200)),
            )
        }
        "subtitleTextColor" -> {
            SubtitleColorSwatches.getOrNull(value.toInt())?.let { color ->
                PlayerSettingsRepository.setSubtitleStyle(subtitleStyle.copy(textColor = color.copy(alpha = subtitleStyle.textColor.alpha)))
            }
        }
        "subtitleOutlineColor" -> {
            SubtitleOutlineColorSwatches.getOrNull(value.toInt())?.let { color ->
                PlayerSettingsRepository.setSubtitleStyle(
                    subtitleStyle.copy(outlineEnabled = true, outlineColor = color),
                )
            }
        }
        "subtitleTextOpacity" -> {
            val alpha = (value.toFloat() / 100f).coerceIn(0f, 1f)
            PlayerSettingsRepository.setSubtitleStyle(subtitleStyle.copy(textColor = subtitleStyle.textColor.copy(alpha = alpha)))
        }
        "subtitleStyleReset" -> PlayerSettingsRepository.setSubtitleStyle(SubtitleStyleState.DEFAULT)
        "parentalGuideComplete" -> {
            showParentalGuide = false
        }
        else -> return false
    }
    return true
}

private fun PlayerScreenRuntime.handleSocialNotificationAction(
    action: SocialNotificationAction,
    notificationId: String? = null,
) {
    val notification = SocialRepository.uiState.value.notifications.firstOrNull {
        it.readAt == null && action in it.availableActions && (notificationId == null || it.id == notificationId)
    } ?: return
    scope.launch {
        SocialRepository.notificationAction(notification.id, action)
            .onFailure { failure ->
                playerNotificationMessage = failure.message ?: "That request could not be completed"
                playerNotificationToken += 1
            }
            .onSuccess { result ->
                val party = result.party
                if (party != null) WatchPartySessionCoordinator.installAuthorizedParty(party)
                if (result.outcome == "stale") {
                    playerNotificationMessage = "This request is no longer available."
                    playerNotificationToken += 1
                }
                if (result.outcome == "full") {
                    playerNotificationMessage = "The party is full."
                    playerNotificationToken += 1
                }
                // An accepted join request made this player the host of a party built from its own
                // presence. The install above is the whole transition: the player keeps playing and
                // becomes the party's on its next registration, exactly as a promotion does.
                if (
                    notification.kind == SocialNotificationKind.PartyInvitation &&
                    action == SocialNotificationAction.Join &&
                    party != null
                ) {
                    when (val outcome = existingPartyJoinOutcome(party)) {
                        is ExistingPartyJoinOutcome.OpenPrePlaybackLobby -> {
                            args.onPartyLobbyRequested?.invoke(outcome.partyId)
                            requestBack()
                        }
                    }
                }
            }
    }
}

private fun PlayerScreenRuntime.requestP2pConsentForPlayerControls(
    stream: StreamItem,
    episode: MetaVideo?,
): Boolean {
    val shouldRequestConsent = shouldRequestP2pConsentForPlayerControls(
        isP2pStream = isP2pStream(stream),
        shouldResolveToPlayableStream = DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream),
        p2pSettingsVisible = P2pSettingsRepository.isVisible,
        p2pEnabled = P2pSettingsRepository.uiState.value.p2pEnabled,
    )
    if (!shouldRequestConsent) return false
    playerControlsPendingP2pSwitch = PendingPlayerP2pSwitch(
        stream = stream,
        episode = episode,
        isAutoPlay = false,
    )
    return true
}

internal fun shouldRequestP2pConsentForPlayerControls(
    isP2pStream: Boolean,
    shouldResolveToPlayableStream: Boolean,
    p2pSettingsVisible: Boolean,
    p2pEnabled: Boolean,
): Boolean =
    isP2pStream &&
        !shouldResolveToPlayableStream &&
        p2pSettingsVisible &&
        !p2pEnabled

private fun PlayerScreenRuntime.enableP2pForPlayerControls() {
    val pending = playerControlsPendingP2pSwitch ?: return
    playerControlsPendingP2pSwitch = null
    P2pSettingsRepository.setP2pEnabled(true)
    val episode = pending.episode
    if (episode != null) {
        switchToP2pEpisodeStream(pending.stream, episode, pending.isAutoPlay)
    } else {
        switchToP2pSourceStream(pending.stream)
    }
    playerControlsCloseModalsToken += 1
}

private fun PlayerScreenRuntime.prepareSourcesForPlayerControls(forceRefresh: Boolean = false) {
    val vid = activeVideoId
    if (vid == null) {
        return
    }
    val requestType = contentType ?: parentMetaType
    PlayerStreamsRepository.loadSources(
        type = requestType,
        videoId = vid,
        season = activeSeasonNumber,
        episode = activeEpisodeNumber,
        forceRefresh = forceRefresh,
    )
}

private fun Color.toCssColorString(): String {
    val redInt = (red * 255f).roundToInt().coerceIn(0, 255)
    val greenInt = (green * 255f).roundToInt().coerceIn(0, 255)
    val blueInt = (blue * 255f).roundToInt().coerceIn(0, 255)
    val alphaValue = alpha.coerceIn(0f, 1f)
    return "rgba($redInt, $greenInt, $blueInt, ${alphaValue.toCssAlphaString()})"
}

private fun Float.toCssAlphaString(): String {
    val rounded = (this * 1000f).roundToInt() / 1000f
    return rounded.toString().trimEnd('0').trimEnd('.').ifEmpty { "0" }
}

private fun PlayerScreenRuntime.prepareEpisodesForPlayerControls() {
    if (!isSeries) return
    if (playerMetaVideos.isEmpty()) {
        scope.launch {
            playerMetaVideos = MetaDetailsRepository.fetch(parentMetaType, parentMetaId)?.videos ?: emptyList()
        }
    }
}

private fun PlayerScreenRuntime.requestEpisodeStreamsForPlayerControls(
    episode: MetaVideo,
    forceRefresh: Boolean = false,
) {
    PlayerStreamsRepository.loadEpisodeStreams(
        type = contentType ?: parentMetaType,
        videoId = episode.id,
        season = episode.season,
        episode = episode.episode,
        forceRefresh = forceRefresh,
    )
    episodeStreamsPanelState = EpisodeStreamsPanelState(showStreams = true, selectedEpisode = episode)
}

private fun PlayerScreenRuntime.submitIntroFromPlayerControls() {
    if (isSubmitIntroSubmitting) return
    val imdbId = activeSubmitIntroImdbId()
    val season = activeSeasonNumber
    val episode = activeEpisodeNumber
    val start = submitIntroStartTimeSec
    val end = submitIntroEndTimeSec
    if (imdbId.isNullOrBlank() || season == null || episode == null || start == null || end == null || end <= start) {
        submitIntroStatusMessage = "Check the start and end times."
        return
    }
    isSubmitIntroSubmitting = true
    submitIntroStatusMessage = null
    scope.launch {
        val result = SkipIntroRepository.submitIntro(
            imdbId = imdbId,
            season = season,
            episode = episode,
            startSec = start,
            endSec = end,
            segmentType = submitIntroSegmentType,
        )
        isSubmitIntroSubmitting = false
        if (result) {
            submitIntroStartTimeSec = 0.0
            submitIntroEndTimeSec = 0.0
            submitIntroStartTimeStr = "00:00"
            submitIntroEndTimeStr = "00:00"
            submitIntroSegmentType = "intro"
            submitIntroStatusMessage = null
            playerControlsCloseModalsToken += 1
            playerControlsSubmitIntroSuccessToken += 1
        } else {
            submitIntroStatusMessage = "Unable to submit timestamps."
        }
    }
}

private fun PlayerScreenRuntime.activeSubmitIntroContentKey(): String {
    val imdbId = activeSubmitIntroImdbId()?.takeIf { it.isNotBlank() } ?: return ""
    return "$imdbId:$activeSeasonNumber:$activeEpisodeNumber"
}

private fun PlayerScreenRuntime.activeSubmitIntroImdbId(): String? =
    activeVideoId?.split(":")?.firstOrNull()?.takeIf { it.startsWith("tt") }
        ?: parentMetaId.takeIf { it.startsWith("tt") }
        ?: metaUiState.meta?.id?.takeIf { it.startsWith("tt") }

@Composable
private fun skipPromptLabel(type: String?): String =
    when (type?.lowercase()) {
        "intro", "op", "mixed-op" -> stringResource(Res.string.player_skip_intro)
        "outro", "ed", "mixed-ed", "credits" -> stringResource(Res.string.player_skip_outro)
        "recap" -> stringResource(Res.string.player_skip_recap)
        else -> stringResource(Res.string.player_skip)
    }

private fun formatPlayerControlsSeconds(seconds: Double): String {
    val totalSeconds = seconds
        .takeIf { it.isFinite() && it >= 0.0 }
        ?.toLong()
        ?: 0L
    val minutes = totalSeconds / 60L
    val remainder = totalSeconds % 60L
    return "${minutes.toString().padStart(2, '0')}:${remainder.toString().padStart(2, '0')}"
}

private fun PlayerScreenRuntime.handlePlayerControlsScrubChange(positionMs: Long) {
    playerControlsLog.d { "scrubChange positionMs=$positionMs ${playerControlLogContext()}" }
    isScrubbingTimeline = true
    scrubbingPositionMs = positionMs
}

private fun PlayerScreenRuntime.handlePlayerControlsScrubFinished(positionMs: Long) {
    playerControlsLog.d { "scrubFinished positionMs=$positionMs controller=${playerController != null} ${playerControlLogContext()}" }
    isScrubbingTimeline = false
    scrubbingPositionMs = null
    if (!submitPartySeek(positionMs)) playerController?.seekTo(positionMs)
    scheduleProgressSyncAfterSeek()
}

private fun PlayerScreenRuntime.playerControlLogContext(): String =
    "video=${activeVideoId ?: "none"} s=${activeSeasonNumber ?: "-"} e=${activeEpisodeNumber ?: "-"} " +
        "pos=${playbackSnapshot.positionMs} duration=${playbackSnapshot.durationMs} " +
        "speed=${playbackSnapshot.playbackSpeed} controller=${playerController != null}"

private fun String.shouldLogPlayerControlsEvent(): Boolean {
    val normalized = lowercase()
    return normalized.contains("audio") ||
        normalized.contains("subtitle") ||
        normalized.contains("speed") ||
        normalized.contains("scrub") ||
        normalized.contains("seek") ||
        normalized.contains("episode") ||
        normalized == "resize" ||
        normalized == "toggle"
}

private fun PlayerScreenRuntime.openInExternalPlayer() {
    val openExternal = args.onOpenInExternalPlayer ?: return
    val loadedSubtitles = addonSubtitles
        .takeIf { it.isNotEmpty() }
        ?.map { sub ->
            SubtitleInput(
                url = sub.url,
                name = buildString {
                    if (!sub.addonName.isNullOrBlank()) append("[${sub.addonName}] ")
                    append(sub.display)
                },
                lang = sub.language,
            )
        }
    openExternal(
        ExternalPlayerPlaybackRequest(
            sourceUrl = activeSourceUrl,
            title = title,
            streamTitle = activeStreamTitle,
            sourceHeaders = activeSourceHeaders,
            resumePositionMs = playbackSnapshot.positionMs,
            subtitles = loadedSubtitles,
        ),
    )
}

private fun PlayerScreenRuntime.buildPlayerControlFilters(
    groups: List<AddonStreamGroup> = sourceStreamsState.groups,
    allLabel: String,
    selectedFilter: String?,
): List<PlayerControlFilterItem> {
    if (groups.size <= 1) return emptyList()
    return buildList {
        add(PlayerControlFilterItem(id = "", label = allLabel, isSelected = selectedFilter == null))
        groups.distinctBy { it.addonId }.forEach { group ->
            add(
                PlayerControlFilterItem(
                    id = group.addonId,
                    label = group.addonName,
                    isSelected = selectedFilter == group.addonId,
                    isLoading = group.isLoading,
                    hasError = group.error != null,
                ),
            )
        }
    }
}

private fun PlayerScreenRuntime.buildPlayerControlEpisodeStreamFilters(
    allLabel: String,
    selectedFilter: String?,
): List<PlayerControlFilterItem> =
    buildPlayerControlFilters(
        groups = episodeStreamsRepoState.groups,
        allLabel = allLabel,
        selectedFilter = selectedFilter,
    )

@Composable
private fun PlayerScreenRuntime.buildPlayerControlSourceItems(): List<PlayerControlSourceItem> {
    val canResolveDebrid = DebridSettingsRepository.uiState.value.canResolvePlayableLinks
    val streamBadgeState = StreamBadgeSettingsRepository.uiState.value
    val showFileSizeBadges = streamBadgeState.showFileSizeBadges
    val showAddonLogo = streamBadgeState.showAddonLogo
    val badgePlacement = streamBadgeState.badgePlacement.name
    return sourceStreamsState.groups.flatMap { group ->
        group.streams.map { stream -> group.addonId to stream }
    }.mapIndexed { index, (filterId, stream) ->
        PlayerControlSourceItem(
            index = index,
            filterId = filterId,
            label = stream.streamLabel,
            subtitle = stream.streamSubtitle.orEmpty(),
            addonName = stream.addonName,
            addonLogo = stream.addonLogo.orEmpty(),
            showAddonLogo = showAddonLogo,
            isCurrent = isCurrentPlayerControlStream(stream),
            isEnabled = stream.isSelectableForPlayback(canResolveDebrid),
            badges = stream.badges.map {
                PlayerControlSourceBadgeItem(
                    name = it.name,
                    imageURL = it.imageURL,
                    tagColor = it.tagColor,
                    tagStyle = it.tagStyle,
                    borderColor = it.borderColor,
                )
            },
            formattedSize = if (showFileSizeBadges) formatStreamVideoSize(stream.behaviorHints.videoSize) else "",
            badgePlacement = badgePlacement,
        )
    }
}

@Composable
private fun PlayerScreenRuntime.buildPlayerControlEpisodeStreamItems(): List<PlayerControlSourceItem> {
    val canResolveDebrid = DebridSettingsRepository.uiState.value.canResolvePlayableLinks
    val streamBadgeState = StreamBadgeSettingsRepository.uiState.value
    val showFileSizeBadges = streamBadgeState.showFileSizeBadges
    val showAddonLogo = streamBadgeState.showAddonLogo
    val badgePlacement = streamBadgeState.badgePlacement.name
    return episodeStreamsRepoState.groups.flatMap { group ->
        group.streams.map { stream -> group.addonId to stream }
    }.mapIndexed { index, (filterId, stream) ->
        PlayerControlSourceItem(
            index = index,
            filterId = filterId,
            label = stream.streamLabel,
            subtitle = stream.streamSubtitle.orEmpty(),
            addonName = stream.addonName,
            addonLogo = stream.addonLogo.orEmpty(),
            showAddonLogo = showAddonLogo,
            isCurrent = false,
            isEnabled = stream.isSelectableForPlayback(canResolveDebrid),
            badges = stream.badges.map {
                PlayerControlSourceBadgeItem(
                    name = it.name,
                    imageURL = it.imageURL,
                    tagColor = it.tagColor,
                    tagStyle = it.tagStyle,
                    borderColor = it.borderColor,
                )
            },
            formattedSize = if (showFileSizeBadges) formatStreamVideoSize(stream.behaviorHints.videoSize) else "",
            badgePlacement = badgePlacement,
        )
    }
}

/**
 * Streamlined's quality rows for the native episode list, indexed exactly as
 * [resolveEpisodeQualityChoice] reads them back. See `PlayerEpisodeQualityChooser.kt`.
 */
@Composable
private fun PlayerScreenRuntime.buildPlayerControlEpisodeQualityItems(): List<PlayerControlSourceItem> {
    val episode = episodeStreamsPanelState.selectedEpisode ?: return emptyList()
    val context = streamlinedEpisodeSelectionContext(playerSettingsUiState, episode)
    return episodeQualityChoices(episodeStreamsRepoState, context).mapIndexed { index, choice ->
        when (choice) {
            is EpisodeQualityChoice.Option -> {
                val option = choice.option
                val needs = option.requiredMbps?.let { mbps ->
                    val rounded = kotlin.math.ceil(mbps).toInt()
                    if (option.isEstimateApproximate) {
                        stringResource(Res.string.playback_quality_needs_estimated, rounded)
                    } else {
                        stringResource(Res.string.playback_quality_needs, rounded)
                    }
                }.orEmpty()
                PlayerControlSourceItem(
                    index = index,
                    label = com.nuvio.app.features.playback.playbackQualityOptionLabel(option),
                    subtitle = needs,
                    formattedSize = formatStreamVideoSize(option.representativeSizeBytes),
                    // Kept open by the page and closed from here once a stream actually starts: a
                    // row with no safe source swaps this panel to the release list instead, and
                    // closing it would hide the answer to the row just pressed.
                    keepOpen = true,
                )
            }
            EpisodeQualityChoice.ChooseManually -> PlayerControlSourceItem(
                index = index,
                label = stringResource(Res.string.playback_quality_manual),
                keepOpen = true,
            )
        }
    }
}

@Composable
private fun formatStreamVideoSize(bytes: Long?): String {
    if (bytes == null || bytes <= 0L) return ""
    val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    val sizeLabel = if (gib >= 1.0) {
        val roundedGiB = kotlin.math.round(gib * 10.0) / 10.0
        "$roundedGiB ${localizedByteUnit("GB")}"
    } else {
        val mib = bytes.toDouble() / (1024.0 * 1024.0)
        "${kotlin.math.round(mib).toInt()} ${localizedByteUnit("MB")}"
    }
    return stringResource(Res.string.streams_size, sizeLabel)
}

private fun PlayerScreenRuntime.isCurrentPlayerControlStream(stream: StreamItem): Boolean {
    val activeKey = activeSourceIdentityKey
    val streamKey = stream.playerSourceIdentityKey()
    if (activeKey != null) {
        return streamKey == activeKey
    }
    val directUrl = stream.playableDirectUrl
    if (directUrl != null && directUrl == activeSourceUrl) return true
    val infoHash = stream.p2pInfoHash
    if (infoHash != null && infoHash == activeTorrentInfoHash) return true
    return false
}

@Composable
private fun PlayerScreenRuntime.buildPlayerControlAddonSubtitleItems(): List<PlayerControlAddonSubtitleItem> =
    visibleAddonSubtitles.mapIndexed { index, subtitle ->
        PlayerControlAddonSubtitleItem(
            index = index,
            id = subtitle.id,
            display = subtitle.display,
            language = subtitle.language,
            languageLabel = languageLabelForCode(subtitle.language),
            addonName = subtitle.addonName.orEmpty(),
            isSelected = subtitle.id == selectedAddonSubtitleId || subtitle.url == selectedAddonSubtitleId,
        )
    }

private data class PlayerControlSubtitleSelection(
    val languages: List<PlayerControlSubtitleLanguageItem>,
    val options: List<PlayerControlSubtitleOptionItem>,
    val selectedLanguageKey: String,
    val selectedOptionId: String,
)

@Composable
private fun PlayerScreenRuntime.buildPlayerControlSubtitleSelection(): PlayerControlSubtitleSelection {
    val selectedAddon = selectedAddonSubtitle
    val selectedLanguageKey = selectedSubtitleLanguageKey(
        subtitleTracks = subtitleTracks,
        selectedSubtitleIndex = selectedSubtitleIndex,
        selectedAddonSubtitle = selectedAddon,
    )
    val selectedOptionId = selectedSubtitleOptionId(
        subtitleTracks = subtitleTracks,
        selectedSubtitleIndex = selectedSubtitleIndex,
        selectedAddonSubtitle = selectedAddon,
    ).orEmpty()
    val languageItems = buildSubtitleLanguageItems(
        subtitleTracks = subtitleTracks,
        addonSubtitles = visibleAddonSubtitles,
        preferredLanguage = playerSettingsUiState.preferredSubtitleLanguage,
        secondaryPreferredLanguage = playerSettingsUiState.secondaryPreferredSubtitleLanguage,
        showOnlyPreferredLanguages = subtitleStyle.showOnlyPreferredLanguages,
        selectedLanguageKey = selectedLanguageKey,
    )
    val noneLabel = stringResource(Res.string.compose_player_none)
    val unknownLabel = stringResource(Res.string.subtitle_language_unknown)
    val builtInLabel = stringResource(Res.string.compose_player_built_in)
    val addonLabel = stringResource(Res.string.addon_title)
    val forcedLabel = stringResource(Res.string.settings_playback_option_forced)
    val languages = languageItems.map { item ->
        PlayerControlSubtitleLanguageItem(
            key = item.key,
            label = when (item.key) {
                SubtitleOffLanguageKey -> noneLabel
                SubtitleUnknownLanguageKey -> unknownLabel
                else -> languageLabelForCode(item.key)
            },
            count = item.count,
            isSelected = item.key == selectedLanguageKey,
        )
    }
    val options = languageItems.flatMap { language ->
        buildSubtitleSelectionOptions(
            languageKey = language.key,
            subtitleTracks = subtitleTracks,
            addonSubtitles = visibleAddonSubtitles,
        ).map { option ->
            when (option) {
                is SubtitleSelectionOption.BuiltIn -> PlayerControlSubtitleOptionItem(
                    id = option.id,
                    languageKey = language.key,
                    kind = "builtIn",
                    index = option.track.index,
                    sourceLabel = builtInLabel,
                    title = localizedTrackDisplayName(
                        option.track.label,
                        option.track.language,
                        option.track.index,
                    ),
                    metadata = forcedLabel.takeIf { option.track.isForced }.orEmpty(),
                    isSelected = option.id == selectedOptionId,
                )

                is SubtitleSelectionOption.Addon -> {
                    val title = languageLabelForCode(option.subtitle.language)
                    PlayerControlSubtitleOptionItem(
                        id = option.id,
                        languageKey = language.key,
                        kind = "addon",
                        index = visibleAddonSubtitles.indexOf(option.subtitle).coerceAtLeast(0),
                        sourceLabel = option.subtitle.addonName ?: addonLabel,
                        title = title,
                        metadata = option.subtitle.display.takeIf {
                            it.isNotBlank() && it != title
                        }.orEmpty(),
                        isSelected = option.id == selectedOptionId,
                    )
                }
            }
        }
    }
    return PlayerControlSubtitleSelection(
        languages = languages,
        options = options,
        selectedLanguageKey = selectedLanguageKey,
        selectedOptionId = selectedOptionId,
    )
}

private fun PlayerScreenRuntime.buildPlayerControlSubtitleCueItems(): List<PlayerControlSubtitleCueItem> =
    playerControlsNearestSubtitleCues().mapIndexed { index, cue ->
        PlayerControlSubtitleCueItem(
            index = index,
            timeMs = cue.startTimeMs,
            timeLabel = formatPlayerControlsCueTimestamp(cue.startTimeMs),
            text = cue.text,
        )
    }

private fun PlayerScreenRuntime.playerControlsNearestSubtitleCues(): List<SubtitleSyncCue> {
    val capturedPositionMs = subtitleAutoSyncState.capturedPositionMs ?: return emptyList()
    return subtitleAutoSyncState.cues
        .sortedBy { abs(it.startTimeMs - capturedPositionMs) }
        .take(5)
}

private fun formatPlayerControlsCueTimestamp(timeMs: Long): String {
    val totalSeconds = (timeMs / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "${minutes}:${seconds.toString().padStart(2, '0')}"
}

@Composable
private fun PlayerScreenRuntime.buildPlayerControlEpisodeItems(): List<PlayerControlEpisodeItem> {
    val items = mutableListOf<PlayerControlEpisodeItem>()
    for ((index, video) in playerMetaVideos.withIndex()) {
        if (video.season == null && video.episode == null) continue
        val episodeVideoId = buildPlaybackVideoId(
            parentMetaId = parentMetaId,
            seasonNumber = video.season,
            episodeNumber = video.episode,
            fallbackVideoId = video.id,
        )
        val isWatched = watchProgressUiState.byVideoId[episodeVideoId]?.isEffectivelyCompleted == true ||
            WatchingState.isEpisodeWatched(
                watchedKeys = watchedUiState.watchedKeys,
                metaType = parentMetaType,
                metaId = parentMetaId,
                episode = video,
            )
        items.add(
            PlayerControlEpisodeItem(
                index = index,
                id = video.id,
                title = video.title,
                code = video.playerControlsEpisodeCode(),
                overview = video.overview.orEmpty(),
                thumbnail = video.thumbnail.orEmpty(),
                released = video.released
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::formatReleaseDateForDisplay)
                    .orEmpty(),
                season = video.season?.coerceAtLeast(0) ?: 0,
                episode = video.episode ?: 0,
                isCurrent = video.season == activeSeasonNumber && video.episode == activeEpisodeNumber,
                isWatched = isWatched,
            ),
        )
    }
    return items
}

@Composable
private fun PlayerScreenRuntime.buildPlayerControlSeasonItems(
    episodes: List<PlayerControlEpisodeItem>,
): List<PlayerControlSeasonItem> {
    val availableSeasons = episodes
        .map { it.season }
        .distinct()
        .let { seasons ->
            seasons.filter { it > 0 }.sorted() + seasons.filter { it == 0 }
        }
    val items = mutableListOf<PlayerControlSeasonItem>()
    for (season in availableSeasons) {
        val label = if (season == 0) {
            stringResource(Res.string.episodes_specials)
        } else {
            stringResource(Res.string.episodes_season, season)
        }
        items.add(
            PlayerControlSeasonItem(
                season = season,
                label = label,
                isSelected = activeSeasonNumber == season,
            ),
        )
    }
    return items
}

@Composable
private fun MetaVideo.playerControlsEpisodeCode(): String =
    when {
        season != null && episode != null -> stringResource(Res.string.compose_player_episode_code_full, season, episode)
        episode != null -> stringResource(Res.string.compose_player_episode_code_episode_only, episode)
        else -> ""
    }

@Composable
private fun BoxScope.RenderPlaybackOverlays(
    runtime: PlayerScreenRuntime,
    displayedPositionMs: Long,
    currentGestureFeedback: GestureFeedbackState?,
    p2pInitialLoadingMessage: String?,
    p2pInitialLoadingProgress: Float?,
    showP2pRebufferStats: Boolean,
    p2pRebufferMessage: String?,
    p2pRebufferProgress: Float?,
    suppressOpeningOverlay: Boolean,
    watchPartyBanner: String?,
) {
    runtime.run {
        val startingEpisode = nextEpisodeTransition
            .takeIf { it.phase == PlayerNextEpisodePhase.STARTING }
            ?.targetVideoId
            ?.let { targetId -> playerMetaVideos.firstOrNull { it.id == targetId } }
        val openingPresentation = playerOpeningPresentation(
            showLogo = logo,
            showTitle = title,
            background = background,
            poster = poster,
            startingEpisode = startingEpisode,
        )
        val playerClipboardManager = LocalClipboardManager.current
        PlayerPlaybackOverlays(
            playerControlsLocked = playerControlsLocked,
            lockedOverlayVisible = lockedOverlayVisible,
            playbackSnapshot = playbackSnapshot,
            displayedPositionMs = displayedPositionMs,
            metrics = metrics,
            horizontalSafePadding = horizontalSafePadding,
            onUnlock = { unlockPlayerControls() },
            // ⚠ **Always false: `PlaybackLoadingHost` draws this now**, above `NavDisplay`, so
            // that the same screen spans the route change and every failover. Rendering it here
            // as well would put a second, shorter-lived copy directly over the first.
            //
            // The desktop *native* overlay is unaffected and still needed - a `SwingPanel` paints
            // over all Compose content regardless of z-order, so once the video surface is
            // promoted the host is invisible and JCEF's copy is the only one left. That one is
            // fed by `PlayerControlsState.showOpeningOverlay`, not by this flag.
            showOpeningOverlay = false,
            backdropArtwork = openingPresentation.artwork,
            logo = openingPresentation.logo,
            title = openingPresentation.title,
            onBackWithProgress = { requestBack() },
            p2pInitialLoadingMessage = if (startingEpisode != null) {
                stringResource(Res.string.player_next_episode_starting)
            } else {
                p2pInitialLoadingMessage
            },
            p2pInitialLoadingProgress = p2pInitialLoadingProgress,
            showP2pRebufferStats = showP2pRebufferStats,
            p2pRebufferMessage = p2pRebufferMessage,
            p2pRebufferProgress = p2pRebufferProgress,
            currentGestureFeedback = currentGestureFeedback,
            renderedGestureFeedback = renderedGestureFeedback,
            initialLoadCompleted = initialLoadCompleted,
            pausedOverlayVisible = pausedOverlayVisible,
            watchPartyBanner = watchPartyBanner,
            activeSkipInterval = activeSkipInterval.takeUnless { isDesktop },
            skipIntervalDismissed = skipIntervalDismissed,
            controlsVisible = controlsVisible,
            onSkipInterval = { interval ->
                val rawMs = (interval.endTime * 1000.0).toLong()
                val durationMs = playbackSnapshot.durationMs
                val seekMs = if (durationMs > 0L) rawMs.coerceAtMost(durationMs - 1) else rawMs
                if (!submitPartySeek(seekMs)) playerController?.seekTo(seekMs)
                scheduleProgressSyncAfterSeek()
                skipIntervalDismissed = true
            },
            onDismissSkipInterval = { skipIntervalDismissed = true },
            sliderEdgePadding = sliderEdgePadding,
            overlayBottomPadding = overlayBottomPadding,
            isSeries = isSeries,
            nextEpisodeInfo = nextEpisodeInfo,
            showNextEpisodeCard = showNextEpisodeCard && !isDesktop,
            nextEpisodeResolving =
                nextEpisodeTransition.phase == PlayerNextEpisodePhase.RESOLVING,
            nextEpisodeSourceName = nextEpisodeTransition.sourceName,
            nextEpisodeCountdown = nextEpisodeTransition.countdownSeconds,
            nextEpisodeStarting = nextEpisodeTransition.phase == PlayerNextEpisodePhase.STARTING,
            nextEpisodeActionEnabled = nextEpisodeTransition.canAcceptManualTap(),
            nextEpisodeShowDismiss = showNextEpisodeCard,
            blurUnwatchedEpisodes = metaScreenSettingsUiState.blurUnwatchedEpisodes,
            onPlayNextEpisode = { playNextEpisodeFromControls() },
            onDismissNextEpisode = {
                cancelNextEpisodeTransition(suppressForCurrentEpisode = true)
            },
            errorMessage = errorMessage,
            onDismissError = { requestBack() },
            // The route's own state, carried through `PlayerScreenArgs` rather than rebuilt:
            // the band must say the same thing on both sides of the hand-off, and a second
            // derivation here is a second thing to drift.
            loadingState = PlaybackLoadingState(
                step = PlaybackProgressStep.StartingPlayback,
                attempt = args.playbackAttempt,
                facts = args.sourceFacts,
                contentLanguage = args.contentLanguage,
                preferredAudioLanguage = preferredAudioLanguageTargets.firstOrNull()
                    ?: playerSettingsUiState.preferredAudioLanguage,
            ),
            formatSize = ::formatFileSize,
            onCopyErrorDetails = errorMessage?.let { message ->
                {
                    val label = PlaybackSourceSelector.describe(args.sourceFacts)
                    playerClipboardManager.setText(
                        AnnotatedString(
                            listOf(label, args.streamTitle, message)
                                .filter { it.isNotBlank() }
                                .joinToString(" - "),
                        ),
                    )
                }
            },
        )
    }
}

@Composable
private fun PlayerScreenRuntime.RenderPlayerModals(displayedPositionMs: Long) {
    PlayerScreenModalHosts(
        pendingP2pSwitch = pendingP2pSwitch,
        onPendingP2pSwitchChanged = { pendingP2pSwitch = it },
        onP2pEpisodeStreamSelected = { stream, episode, isAutoPlay ->
            switchToP2pEpisodeStream(stream, episode, isAutoPlay)
        },
        onP2pSourceStreamSelected = { stream -> switchToP2pSourceStream(stream) },
        onNextEpisodeAutoPlayCancelled = {
            cancelNextEpisodeTransition(suppressForCurrentEpisode = false)
        },
        showAudioModal = showAudioModal,
        audioTracks = audioTracks,
        selectedAudioIndex = selectedAudioIndex,
        onAudioTrackSelected = { index ->
            selectedAudioIndex = index
            persistAudioPreference(audioTracks.firstOrNull { it.index == index })
            playerController?.selectAudioTrack(index)
            scope.launch {
                kotlinx.coroutines.delay(200)
                showAudioModal = false
            }
        },
        onAudioModalDismissed = { showAudioModal = false },
        showSubtitleModal = showSubtitleModal,
        subtitleTracks = subtitleTracks,
        selectedSubtitleIndex = selectedSubtitleIndex,
        addonSubtitles = visibleAddonSubtitles,
        selectedAddonSubtitleId = selectedAddonSubtitleId,
        isLoadingAddonSubtitles = isLoadingAddonSubtitles,
        subtitleStyle = subtitleStyle,
        subtitleDelayMs = subtitleDelayMs,
        selectedAddonSubtitle = selectedAddonSubtitle,
        subtitleAutoSyncState = subtitleAutoSyncState,
        onBuiltInSubtitleTrackSelected = { index ->
            val wasCustom = useCustomSubtitles
            isUserExplicitSubtitleSelection = true
            preferredSubtitleSelectionApplied = true
            selectedSubtitleIndex = index
            selectedAddonSubtitleId = null
            useCustomSubtitles = false
            persistInternalSubtitlePreference(subtitleTracks.firstOrNull { it.index == index })
            if (wasCustom) {
                playerController?.clearExternalSubtitleAndSelect(index)
            } else {
                playerController?.selectSubtitleTrack(index)
            }
        },
        onAddonSubtitleSelected = { addon ->
            isUserExplicitSubtitleSelection = true
            selectedAddonSubtitleId = addon.selectionKey
            selectedSubtitleIndex = -1
            useCustomSubtitles = true
            preferredSubtitleSelectionApplied = true
            persistAddonSubtitlePreference(addon)
            playerController?.setSubtitleUri(addon.url)
        },
        onFetchAddonSubtitles = { fetchAddonSubtitlesForActiveItem() },
        onSubtitleStyleChanged = PlayerSettingsRepository::setSubtitleStyle,
        onSubtitleDelayChanged = { delayMs -> setSubtitleDelay(delayMs) },
        onSubtitleDelayReset = { setSubtitleDelay(0) },
        onAutoSyncCapture = { captureSubtitleAutoSyncTime() },
        onAutoSyncCueSelected = { cue -> applySubtitleAutoSyncCue(cue) },
        onAutoSyncReload = { loadSubtitleAutoSyncCues(force = true) },
        onSubtitleModalDismissed = { showSubtitleModal = false },
        showVideoSettingsModal = showVideoSettingsModal,
        playerSettings = playerSettingsUiState,
        onVideoSettingsChanged = {
            playerController?.configureIosVideoOutput(PlayerSettingsRepository.uiState.value)
        },
        onVideoSettingsModalDismissed = { showVideoSettingsModal = false },
        showSourcesPanel = showSourcesPanel,
        sourceStreamsState = sourceStreamsState,
        contentTitle = title,
        activeEpisodeTitle = activeEpisodeTitle,
        activeSourceUrl = activeSourceUrl,
        activeStreamTitle = activeStreamTitle,
        onSourceFilterSelected = PlayerStreamsRepository::selectSourceFilter,
        onSourceStreamSelected = { stream -> switchToUserSelectedSource(stream) },
        onReloadSources = {
            val vid = activeVideoId
            if (vid != null) {
                PlayerStreamsRepository.loadSources(
                    type = contentType ?: parentMetaType,
                    videoId = vid,
                    season = activeSeasonNumber,
                    episode = activeEpisodeNumber,
                    forceRefresh = true,
                )
            }
        },
        onSourcesPanelDismissed = {
            showSourcesPanel = false
            controlsVisible = true
        },
        isSeries = isSeries,
        showEpisodesPanel = showEpisodesPanel,
        allEpisodes = playerMetaVideos,
        parentMetaType = parentMetaType,
        parentMetaId = parentMetaId,
        activeSeasonNumber = activeSeasonNumber,
        activeEpisodeNumber = activeEpisodeNumber,
        watchProgressByVideoId = watchProgressUiState.byVideoIdForContent(parentMetaId),
        watchedKeys = watchedUiState.watchedKeys,
        blurUnwatchedEpisodes = metaScreenSettingsUiState.blurUnwatchedEpisodes,
        episodeStreamsPanelState = episodeStreamsPanelState,
        episodeStreamsRepoState = episodeStreamsRepoState,
        onEpisodeSelectedForDownload = { episode ->
            playEpisodeFromPicker(episode)
            true
        },
        onEpisodeStreamsRequested = { episode ->
            PlayerStreamsRepository.loadEpisodeStreams(
                type = contentType ?: parentMetaType,
                videoId = episode.id,
                season = episode.season,
                episode = episode.episode,
            )
            episodeStreamsPanelState = EpisodeStreamsPanelState(showStreams = true, selectedEpisode = episode)
        },
        onEpisodeStreamFilterSelected = PlayerStreamsRepository::selectEpisodeStreamsFilter,
        onEpisodeStreamSelected = { stream, episode -> switchToEpisodeStream(stream, episode) },
        onBackToEpisodes = {
            cancelNextEpisodeTransition(suppressForCurrentEpisode = false)
            episodeStreamsPanelState = EpisodeStreamsPanelState()
            PlayerStreamsRepository.clearEpisodeStreams()
        },
        onReloadEpisodeStreams = {
            val episode = episodeStreamsPanelState.selectedEpisode
            if (episode != null) {
                PlayerStreamsRepository.loadEpisodeStreams(
                    type = contentType ?: parentMetaType,
                    videoId = episode.id,
                    season = episode.season,
                    episode = episode.episode,
                    forceRefresh = true,
                )
            }
        },
        onEpisodesPanelDismissed = {
            cancelNextEpisodeTransition(suppressForCurrentEpisode = false)
            showEpisodesPanel = false
            episodeStreamsPanelState = EpisodeStreamsPanelState()
            PlayerStreamsRepository.clearEpisodeStreams()
            controlsVisible = true
        },
        showSubmitIntroModal = showSubmitIntroModal,
        activeVideoId = activeVideoId,
        metaUiState = metaUiState,
        displayedPositionMs = displayedPositionMs,
        submitIntroSegmentType = submitIntroSegmentType,
        onSubmitIntroSegmentTypeChanged = { submitIntroSegmentType = it },
        submitIntroStartTimeStr = submitIntroStartTimeStr,
        onSubmitIntroStartTimeChanged = { submitIntroStartTimeStr = it },
        submitIntroEndTimeStr = submitIntroEndTimeStr,
        onSubmitIntroEndTimeChanged = { submitIntroEndTimeStr = it },
        onSubmitIntroDismissed = { showSubmitIntroModal = false },
        onSubmitIntroSuccess = {
            submitIntroStartTimeSec = 0.0
            submitIntroEndTimeSec = 0.0
            submitIntroStatusMessage = null
            submitIntroStartTimeStr = "00:00"
            submitIntroEndTimeStr = "00:00"
            submitIntroSegmentType = "intro"
            showSubmitIntroModal = false
        },
    )

    episodeQualitySheetEpisode?.let { episode ->
        // ⚠ Was six of thirteen fields, hand-built. The five it omitted - the quality ceiling,
        // the language requirement, the secondary audio language, the audio preference and the
        // display height - are exactly the ones the first episode of the same session had been
        // picked under, so episode 2 was chosen by a weaker rule than episode 1 with nothing on
        // screen to say so. Built by the shared factory now; see its KDoc.
        val selectionContext = playbackSelectionContextOf(
            settings = playerSettingsUiState,
            isEpisode = true,
            runtimeMinutes = episode.runtime,
            contentOriginalLanguage = resolveContentLanguage(
                language = metaUiState.meta?.language,
                country = metaUiState.meta?.country,
            ) ?: args.contentLanguage,
        )
        val candidates = episodeStreamsRepoState.groups.flatMapIndexed { addonOrder, group ->
            group.streams.map { stream ->
                PlaybackSourceCandidate(
                    stream = stream,
                    facts = SourceFactsExtractor.extract(stream),
                    addonOrder = addonOrder,
                )
            }
        }
        val options = PlaybackQualityOptions.build(candidates, selectionContext)
        val network = NetworkQualityRepository.current()
        PlaybackQualitySheet(
            options = options,
            isLoading = episodeStreamsRepoState.isAnyLoading,
            isSelecting = false,
            selectionContext = selectionContext,
            estimatedMbps = network.estimatedMbps,
            isConnectionMeasured = network.isMeasured,
            isConnectionStale = false,
            isMeasuringConnection = false,
            onRetestConnection = null,
            onOptionSelected = { option ->
                when (val result = PlaybackSourceSelector.select(option, selectionContext)) {
                    is PlaybackSelectionResult.Play -> {
                        episodeQualitySheetEpisode = null
                        controlsVisible = true
                        switchToEpisodeStream(result.stream, episode)
                    }
                    is PlaybackSelectionResult.AskUncached,
                    is PlaybackSelectionResult.NeedsManual -> openEpisodeSourceList(episode)
                }
            },
            onChooseManually = { openEpisodeSourceList(episode) },
            onAdjustPreferences = null,
            onDismiss = {
                cancelNextEpisodeTransition(suppressForCurrentEpisode = false)
                episodeQualitySheetEpisode = null
                showEpisodesPanel = true
                controlsVisible = false
            },
        )
    }
}

/** How long Starting a party may wait for the coordinator to answer before the panel calls it failed. */
private const val PartyPromotionAnswerTimeoutMs = 20_000L
