package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.debug.PlaybackDebugSettings
import com.nuvio.app.core.debug.isDebugBuild
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.p2p.P2pStreamRequest
import com.nuvio.app.features.p2p.P2pStreamingEngine
import com.nuvio.app.features.p2p.P2pStreamingState
import com.nuvio.app.core.network.NetworkThroughputMeter
import com.nuvio.app.features.playback.PlaybackAttemptLog
import com.nuvio.app.features.playback.PlaybackDurationPlausibility
import com.nuvio.app.features.playback.PlaybackProbeOutcome
import com.nuvio.app.features.playback.PlaybackProbeVerdict
import com.nuvio.app.features.playback.logKey
import com.nuvio.app.features.playback.probePlaybackSource
import com.nuvio.app.features.playback.PlaybackPosition
import com.nuvio.app.features.playback.PlaybackStartupWatchdog
import com.nuvio.app.features.watchparty.toPartySourceDescriptor
import com.nuvio.app.features.player.skip.AutoSkipSegmentType
import com.nuvio.app.features.player.skip.NextEpisodeInfo
import com.nuvio.app.features.player.skip.PlayerNextEpisodeRules
import com.nuvio.app.features.player.skip.SkipIntroRepository
import com.nuvio.app.features.player.skip.SkipIntervalLookup
import com.nuvio.app.features.player.skip.autoSkipKey
import com.nuvio.app.features.player.skip.autoSkipKeysCompletedBy
import com.nuvio.app.features.player.skip.resolveSkipIntervalLookup
import com.nuvio.app.features.streams.CredentialRefreshDecision
import com.nuvio.app.features.streams.credentialRefreshDecision
import com.nuvio.app.features.streams.BingeGroupCacheRepository
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.features.streams.hasLikelyExpiringPlaybackCredentials
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.isDesktop
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import com.nuvio.app.features.watching.application.WatchingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import kotlin.time.TimeSource
import com.nuvio.app.features.social.rememberSocialEnabled

/**
 * The startup watchdog's own tag, because it is the one thing here that ends a play by itself.
 *
 * `adb logcat -s PlaybackStartup` is the whole diagnosis for "it loads, then it tries again":
 * three lines means the chain burned three sources, and `reason=` says whether the host answered
 * at all. There was no line at all before, which is how the fault survived three releases.
 */
private val startupLog = Logger.withTag("PlaybackStartup")

/** How long a fresh source waits before a duration on the shared snapshot is believed. */
private const val PROBE_SNAPSHOT_SETTLE_MS = 750L
private const val PROBE_ARM_POLL_MS = 250L

@Composable
internal fun PlayerScreenRuntime.BindPlayerRuntimeEffects() {
    // ⚠ **Both, or neither.** Presence is what tells friends what you are watching and the party
    // effect is what keeps a party in step; with the social layer off there is nobody to tell and
    // no party to keep. Skipping them here is what stops a heartbeat loop running for a feature
    // the user has switched off.
    //
    // Safe to drop mid-session: turning social off runs `shutdownSocialLayer` *before* the
    // preference flips, so any party has already been departed through the coordinator by the
    // time this stops being composed. Nothing here abandons a live session.
    if (rememberSocialEnabled()) {
        BindSocialPresenceEffect()
        BindWatchPartyEffect()
    }
    val currentFeedback = liveGestureFeedback ?: gestureFeedback
    LaunchedEffect(currentFeedback) {
        if (currentFeedback != null) {
            renderedGestureFeedback = currentFeedback
        }
    }

    // "Change" on the reused-link toast. Raised outside the player - the route that reused
    // the link pops itself on the way here - so it arrives as a request rather than a call.
    // Skips the value present on the first composition: that one is history, not a request,
    // and acting on it would open the panel every time the player is entered.
    val sourcePanelRequest by PlayerSourcePanelRequest.requests.collectAsStateWithLifecycle()
    var lastHandledSourcePanelRequest by remember { mutableStateOf(sourcePanelRequest) }
    LaunchedEffect(sourcePanelRequest) {
        if (sourcePanelRequest == lastHandledSourcePanelRequest) return@LaunchedEffect
        lastHandledSourcePanelRequest = sourcePanelRequest
        openSourcesPanel()
    }

    LaunchedEffect(parentMetaType, parentMetaId) {
        playerMeta = MetaDetailsRepository.peek(parentMetaType, parentMetaId)
        playerMetaVideos = playerMeta?.videos.orEmpty()
        if (playerMetaVideos.isEmpty()) {
            MetaDetailsRepository.fetch(parentMetaType, parentMetaId)?.let { meta ->
                playerMeta = meta
                playerMetaVideos = meta.videos
            }
        }
    }

    LaunchedEffect(metaUiState.meta, parentMetaType, parentMetaId) {
        val currentMeta = metaUiState.meta ?: return@LaunchedEffect
        if (currentMeta.type == parentMetaType && currentMeta.id == parentMetaId) {
            playerMeta = currentMeta
            playerMetaVideos = currentMeta.videos
        }
    }

    LaunchedEffect(currentStreamBingeGroup, parentMetaId) {
        val bg = currentStreamBingeGroup
        if (bg != null && parentMetaId.isNotBlank()) {
            BingeGroupCacheRepository.save(parentMetaId, bg)
        }
    }

    LaunchedEffect(activeSourceUrl, activeSourceAudioUrl, activeSourceHeaders, activeSourceResponseHeaders) {
        // A re-mint of the source already playing is a *continuation*, not a new item. Consumed
        // here so it can only excuse the one change it was set for.
        val isContinuation = isCredentialRefreshHandoff
        isCredentialRefreshHandoff = false
        errorMessage = null
        playerController = null
        playerControllerSourceUrl = null
        playbackSnapshot = PlayerPlaybackSnapshot()
        isScrubbingTimeline = false
        scrubbingPositionMs = null
        liveGestureFeedback = null
        renderedGestureFeedback = null
        lockedOverlayVisible = false
        credentialRefreshJob?.cancel()
        credentialRefreshJob = null
        // `credentialRefreshesUsed` and `credentialRefreshAttemptedSourceUrl` deliberately do
        // **not** reset here, for the same reason the swap budget below does not: a successful
        // refresh is itself what changes `activeSourceUrl`, so clearing the budget here handed
        // every re-mint a fresh one. A source that died a second after starting therefore
        // re-minted forever - new URL, `initialLoadCompleted = false` on the line below, the
        // opening overlay again, dead again - and because the refresh swallowed each error, the
        // player's fatal handler was never reached and the failure chain never ran.
        //
        // They reset where a new thing is genuinely being watched: `LaunchedEffect(activeVideoId)`.
        //
        // ⚠ **`initialLoadCompleted` is what puts the opening overlay back up**, so clearing it
        // for a re-mint of the file already playing is the "loads, restarts, loads again" the
        // user sees before a debrid stream begins. The controller above genuinely must be torn
        // down - the URL is different and a new engine instance is coming - but the *presentation*
        // should not start over for a file that never changed.
        if (!isContinuation) {
            initialLoadCompleted = false
            // A new source means a new wait; the surface must come back up for it.
            firstFrameReached = false
        }
        lastProgressPersistEpochMs = 0L
        previousIsPlaying = false
        pendingSeekScrobbleRestart = false
        seekProgressSyncJob?.cancel()
        seekProgressSyncJob = null
        accumulatedSeekResetJob?.cancel()
        accumulatedSeekResetJob = null
        accumulatedSeekState = null
        speedBoostRestoreSpeed = null
        preferredAudioSelectionApplied = false
        appliedAudioPreferences = null
        preferredSubtitleSelectionApplied = false
        isUserExplicitAudioSelection = false
        isUserExplicitSubtitleSelection = false
        hasScannedTextTracksOnce = false
        selectedSubtitleIndex = -1
        selectedAddonSubtitleId = null
        useCustomSubtitles = false
        showSourcesPanel = false
        showEpisodesPanel = false
        episodeStreamsPanelState = EpisodeStreamsPanelState()
        // Both describe the *content*, which a re-mint does not change. Clearing them made the
        // refresh throw away the source list it had just loaded to find the replacement, and
        // dropped subtitles the user had already chosen for a file that is still playing.
        if (!isContinuation) {
            PlayerStreamsRepository.clearEpisodeStreams()
            SubtitleRepository.clear()
        }
        WatchProgressRepository.ensureLoaded()
    }

    LaunchedEffect(
        activeTorrentInfoHash,
        activeTorrentFileIdx,
        activeTorrentFilename,
        activeTorrentTrackers,
        p2pSettingsUiState.p2pEnabled,
    ) {
        val infoHash = activeTorrentInfoHash
        if (infoHash == null) {
            p2pResolvedSourceUrl = null
            P2pStreamingEngine.stopStream()
            return@LaunchedEffect
        }
        if (!P2pSettingsRepository.isVisible || !p2pSettingsUiState.p2pEnabled) {
            p2pResolvedSourceUrl = null
            P2pStreamingEngine.stopStream()
            return@LaunchedEffect
        }

        p2pResolvedSourceUrl = null
        val requestedFileIdx = activeTorrentFileIdx
        val requestedFilename = activeTorrentFilename
        val requestedTrackers = activeTorrentTrackers
        errorMessage = null
        playerController = null
        playerControllerSourceUrl = null
        playbackSnapshot = PlayerPlaybackSnapshot()
        initialLoadCompleted = false
        firstFrameReached = false

        try {
            val localUrl = P2pStreamingEngine.startStream(
                P2pStreamRequest(
                    infoHash = infoHash,
                    fileIdx = requestedFileIdx,
                    filename = requestedFilename,
                    trackers = requestedTrackers,
                ),
            )
            if (activeTorrentInfoHash == infoHash && activeTorrentFileIdx == requestedFileIdx) {
                activeSourceAudioUrl = null
                activeSourceHeaders = emptyMap()
                activeSourceResponseHeaders = emptyMap()
                p2pResolvedSourceUrl = localUrl
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errorMessage = getString(
                Res.string.player_error_failed_start_torrent,
                error.message ?: genericUnknownLabel,
            )
            controlsVisible = !playerControlsLocked
            initialLoadCompleted = true
        }
    }

    LaunchedEffect(p2pStreamingState, activeTorrentInfoHash) {
        val state = p2pStreamingState
        if (activeTorrentInfoHash != null && state is P2pStreamingState.Error) {
            p2pResolvedSourceUrl = null
            playerController = null
            playerControllerSourceUrl = null
            playbackSnapshot = PlayerPlaybackSnapshot()
            initialLoadCompleted = true
            errorMessage = getString(Res.string.player_error_torrent, state.message)
            controlsVisible = !playerControlsLocked
        }
    }

    LaunchedEffect(playbackSession.videoId) {
        subtitleDelayMs = PlayerTrackPreferenceStorage.loadSubtitleDelayMs(playbackSession.videoId) ?: 0
        subtitleAutoSyncState = SubtitleAutoSyncUiState()
    }

    LaunchedEffect(playerController, subtitleDelayMs) {
        playerController?.setSubtitleDelayMs(subtitleDelayMs)
    }

    LaunchedEffect(selectedAddonSubtitleId, useCustomSubtitles, activeSourceUrl) {
        subtitleAutoSyncState = SubtitleAutoSyncUiState()
    }

    LaunchedEffect(playerController, subtitleStyle, playerSettingsUiState.useLibass) {
        playerController?.applySubtitleStyle(subtitleStyle, playerSettingsUiState.useLibass)
    }

    val subtitlePreferenceKey = listOf(
        playerSettingsUiState.preferredSubtitleLanguage,
        playerSettingsUiState.secondaryPreferredSubtitleLanguage.orEmpty(),
        subtitleStyle.useForcedSubtitles,
    ).joinToString("|")
    var lastSubtitlePreferenceKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(
        playerController,
        subtitlePreferenceKey,
        preferredSubtitleSelectionApplied,
        selectedSubtitleIndex,
        selectedAddonSubtitleId,
        useCustomSubtitles,
    ) {
        val controller = playerController ?: return@LaunchedEffect
        val preferenceChanged = lastSubtitlePreferenceKey != null &&
            lastSubtitlePreferenceKey != subtitlePreferenceKey
        lastSubtitlePreferenceKey = subtitlePreferenceKey

        controller.applySubtitlePreferences(
            preferredLanguage = playerSettingsUiState.preferredSubtitleLanguage,
            secondaryPreferredLanguage = playerSettingsUiState.secondaryPreferredSubtitleLanguage,
            useForcedSubtitles = subtitleStyle.useForcedSubtitles,
            autoSelectionApplied = preferredSubtitleSelectionApplied,
            hasActiveSubtitle = selectedSubtitleIndex >= 0 || selectedAddonSubtitleId != null,
            useCustomSubtitles = useCustomSubtitles,
        )

        if (preferenceChanged) {
            preferredSubtitleSelectionApplied = false
            refreshTracks()
        }
    }

    LaunchedEffect(
        playerController,
        playerControllerSourceUrl,
        activeSourceUrl,
        title,
        activeStreamTitle,
        activeSeasonNumber,
        activeEpisodeNumber,
        activeEpisodeTitle,
        poster,
        background,
    ) {
        val controller = playerController ?: return@LaunchedEffect
        if (playerControllerSourceUrl != activeSourceUrl) return@LaunchedEffect
        controller.updateNowPlayingMetadata(buildNowPlayingInfo())
    }

    LaunchedEffect(
        activeSourceUrl,
        addonSubtitleFetchKey,
        playerSettingsUiState.addonSubtitleStartupMode,
        playerController,
        playerControllerSourceUrl,
    ) {
        val fetchKey = addonSubtitleFetchKey ?: return@LaunchedEffect
        val playerInitialized = playerController != null && playerControllerSourceUrl == activeSourceUrl
        val canFetch = canAutomaticallyFetchAddonSubtitles(
            mode = playerSettingsUiState.addonSubtitleStartupMode,
            playerInitialized = playerInitialized,
        )
        if (!canFetch) return@LaunchedEffect
        if (autoFetchedAddonSubtitlesForKey == fetchKey) return@LaunchedEffect
        autoFetchedAddonSubtitlesForKey = fetchKey
        fetchAddonSubtitlesForActiveItem()
    }

    LaunchedEffect(playerController, playerControllerSourceUrl, activeSourceUrl, preferredAudioLanguageTargets) {
        if (playerControllerSourceUrl == activeSourceUrl) {
            applyPreferredAudioTrack(preferredAudioLanguageTargets)
        }
    }

    LaunchedEffect(playbackSnapshot.isLoading, playerController, preferredAudioLanguageTargets) {
        if (!playbackSnapshot.isLoading && playerController != null) {
            refreshTracks()
        }
    }

    // A different file starts fresh passive network measurements.
    LaunchedEffect(activeSourceUrl) {
        playbackObservationClock = TimeSource.Monotonic.markNow()
        // A different file is a different bitrate, so the measurement starts over.
        networkEstimateStartPositionMs = null
        networkEstimateStalled = false
        networkEstimateRecorded = false
        networkThroughputState = NetworkThroughputMeter.initial()
    }

    // A session is one thing being watched. Moving to the next episode earns a fresh candidate
    // chain and a fresh credential-refresh budget. Keyed on the *video*, not on the source URL,
    // because re-minting changes the URL and would otherwise refund the budget it just spent.
    LaunchedEffect(activeVideoId) {
        credentialRefreshesUsed = 0
        credentialRefreshAttemptedSourceUrl = null
    }

    LaunchedEffect(
        activeSourceUrl,
        args.onFatalPlaybackError,
        // An auto-played next episode carries its chain here rather than through
        // `PlayerLaunch`, so the budget has to be armed for it too - see
        // `nextEpisodeFallbacks`. Keying on it gives every source in the chain its own
        // deadline instead of sharing the first one's.
        nextEpisodeFallbacks,
        PlaybackDebugSettings.hudEnabled,
    ) {
        val hasChain = args.onFatalPlaybackError != null || nextEpisodeFallbacks.isNotEmpty()
        if (!hasChain) return@LaunchedEffect

        // ⚠ **Beside the attach, never before it.** `probePlaybackSource` is unbounded - see its
        // KDoc for why a timeout cannot be enforced over a blocking OkHttp call - so awaiting it on
        // the hand-off added eight seconds to every automatic play. Here it costs nothing: the
        // loading surface covers the player until the first frame, so a source rejected while the
        // probe is still running steps the chain with nothing on screen changing but the attempt
        // number, and a verdict that arrives after the first frame is simply ignored below.
        //
        // Deliberately inside the watchdog's own effect: it is keyed on the same source, it is
        // already gated on there being a chain to step, and the abandon machinery is right here
        // rather than duplicated.
        var probePassed = false
        val probeArmedAt = TimeSource.Monotonic.markNow()
        launch {
            // ⚠ **Only after the player has opened the file itself.** AIOStreams mints the debrid
            // link when the URL is first fetched, and the debrid host binds it to the fetching IP.
            // This probe is OkHttp and the player is mpv: two independent HTTP stacks that can
            // leave the machine from different addresses (IPv4 against IPv6, or a VPN that routes
            // by process). Racing mpv to that first fetch is how every automatic pick on one
            // machine intermittently came back "Wrong IP" while manual picks - which never probe -
            // never did. A duration means mpv has already fetched and opened the file, so the link
            // is mpv's. The settle interval keeps a snapshot from the previous source, delivered
            // after the reset above, from arming it early.
            while (
                playbackSnapshot.durationMs <= 0L ||
                probeArmedAt.elapsedNow().inWholeMilliseconds < PROBE_SNAPSHOT_SETTLE_MS
            ) {
                delay(PROBE_ARM_POLL_MS)
            }
            startupLog.d { "probe armed: player has opened the source" }
            val outcome = probePlaybackSource(
                url = activeSourceUrl,
                headers = activeSourceHeaders,
                expectedBytes = args.sourceFacts?.sizeBytes,
            )
            startupLog.i {
                val detail = when (outcome) {
                    is PlaybackProbeOutcome.NotApplicable -> "skipped=not_http"
                    is PlaybackProbeOutcome.Failed -> "failed=${outcome.reason}"
                    is PlaybackProbeOutcome.Completed ->
                        "${outcome.result.toLogFields()} verdict=${outcome.result.verdict.logKey()}" +
                            (outcome.result.bodyPreview?.let { " body=\"$it\"" } ?: "")
                }
                "probe $detail"
            }
            val verdict = (outcome as? PlaybackProbeOutcome.Completed)?.result?.verdict
            if (verdict is PlaybackProbeVerdict.Pass) {
                probePassed = true
            }
            // A dead verdict no longer steps the chain. The probe now runs only once mpv has
            // opened the file, so mpv has already proved the source answers; a refusal at this
            // point is a refusal of the probe (a second fetch of an IP-bound link), not of the
            // source. Dead sources are skipped by mpv's own end-file error instead - see
            // `NativePlayerController.handleMpvEndFile` - and by the watchdog.
            if (verdict is PlaybackProbeVerdict.Dead) return@launch
            val rejection = when (verdict) {
                is PlaybackProbeVerdict.Placeholder -> Res.string.playback_source_not_ready
                else -> null
            } ?: return@launch
            // A frame arrived while the probe was in flight. Whatever it thinks, the user is
            // watching something - abandoning it now would be the probe overruling the evidence.
            if (firstFrameReached) return@launch
            StreamsRepository.noteAutoPickFailureReason(getString(rejection))
            if (tryNextEpisodeFallback()) return@launch
            args.onFatalPlaybackError?.invoke()
        }
        // While diagnosing startup/buffering, abandoning the source hides the useful state, so
        // leave the player open for inspection.
        if (isDebugBuild && PlaybackDebugSettings.hudEnabled) return@LaunchedEffect
        // ⚠ **Armed only for automatic picks**, because `onFatalPlaybackError` is only passed by
        // Streamlined and Instant - the same file tapped by hand in Classic has no deadline at
        // all. That asymmetry is why the rule this loop replaced was reported as a mode fault
        // rather than as a player one: it abandoned any auto-picked source that had not started
        // in eight seconds, and it could not see a buffer, so a debrid mint or a large remux
        // doing exactly the right thing was killed, three candidates in a row, and blamed on the
        // catalogue. See `PlaybackStartupWatchdog` for the whole argument.
        val startedAt = TimeSource.Monotonic.markNow()
        var watch = PlaybackStartupWatchdog.initial()
        startupLog.i {
            "watchdog armed: attempt=${args.playbackAttempt} candidate=$activeStreamTitle " +
                "baselineMs=${args.initialPositionMs}ms"
        }
        var lastLoggedProgressMs = 0L
        var wasEvidenceOfLifeLogged = false
        var lastLoggedHold = partyStartupHold
        while (true) {
            delay(PlaybackStartupWatchdog.POLL_INTERVAL_MS)
            val snapshot = playbackSnapshot
            val sample = PlaybackStartupWatchdog.PlaybackStartupSample(
                elapsedMs = startedAt.elapsedNow().inWholeMilliseconds,
                isPlaying = snapshot.isPlaying,
                positionMs = snapshot.positionMs,
                bufferedPositionMs = snapshot.bufferedPositionMs,
                durationMs = snapshot.durationMs,
                // Where this play began. An engine reports a pending seek target as its
                // position immediately, so without this a resumed episode looked like 22
                // minutes of progress on its first sample and any dead source was declared
                // started - see `PlaybackStartupSample.baselineMs`.
                // ⚠ **The fraction path counts too.** This read `activeInitialPositionMs`
                // alone, so a resume carrying only a percentage - which `resolveEpisodeResume`
                // really can return - gave a baseline of 0 while the seek above jumped to
                // `duration × fraction`. The engine reports a pending seek target immediately,
                // so the first sample then read enormous progress against a baseline of zero,
                // `hasEvidenceOfLife` was true, and **a dead source was declared Started** -
                // the startup overlay up forever with the chain unrun.
                baselineMs = PlaybackPosition.resolveStartPositionMs(
                    initialPositionMs = activeInitialPositionMs,
                    progressFraction = activeInitialProgressFraction,
                    durationMs = snapshot.durationMs,
                ) ?: activeInitialPositionMs.coerceAtLeast(0L),
                hasExternalEvidenceOfLife = probePassed,
                // ⚠ **Watch Together parks this player on purpose, and a parked player does not
                // buffer.** Held time is frozen rather than exempted: see
                // `PlaybackStartupSample.isHeld` for the two-client failure this closes.
                isHeld = partyStartupHold.isHeld,
            )
            // ⚠ **Checked before the watchdog's verdict, because the watchdog would say Started.**
            // A provider's "being prepared" slate plays perfectly: position advances, the buffer
            // fills, and every signal the watchdog reads says this source is healthy. It is - it
            // is just not the film. *The Secret Woman* reported `duration=120960` against a
            // feature and the chain stopped there, satisfied. The only fact that disagrees is the
            // duration, so it has to be read before "it is playing" is allowed to end the check.
            if (
                PlaybackDurationPlausibility.isImplausiblyShort(
                    reportedDurationMs = snapshot.durationMs,
                    expectedRuntimeMinutes = args.expectedRuntimeMinutes,
                )
            ) {
                startupLog.w {
                    "abandoning $activeStreamTitle: reason=ImplausibleDuration " +
                        "attempt=${args.playbackAttempt} " +
                        "duration=${snapshot.durationMs}ms " +
                        "expectedMinutes=${args.expectedRuntimeMinutes} " +
                        "engine=${snapshot.engineName}"
                }
                StreamsRepository.noteAutoPickFailureReason(
                    getString(Res.string.playback_startup_wrong_length),
                )
                if (tryNextEpisodeFallback()) return@LaunchedEffect
                args.onFatalPlaybackError?.invoke()
                return@LaunchedEffect
            }
            watch = PlaybackStartupWatchdog.observe(watch, sample)
            // Every transition of the party's grip on this player, beside the deadlines it moves.
            // Without it a source that took thirty seconds to start looks identical in the log
            // whether the party was holding it for twenty of them or not.
            if (partyStartupHold != lastLoggedHold) {
                startupLog.i {
                    "watchdog hold: attempt=${args.playbackAttempt} held=${partyStartupHold.isHeld} " +
                        "reason=${partyStartupHold.reason} gateReason=${partyStartupHold.gateReason} " +
                        "elapsed=${sample.elapsedMs}ms effective=${watch.effectiveElapsedMs}ms " +
                        "heldTotal=${watch.holdMs}ms"
                }
                lastLoggedHold = partyStartupHold
            }
            if (!wasEvidenceOfLifeLogged && watch.hasEvidenceOfLife) {
                wasEvidenceOfLifeLogged = true
                startupLog.i {
                    "watchdog evidence of life: attempt=${args.playbackAttempt} candidate=$activeStreamTitle " +
                        "elapsed=${sample.elapsedMs}ms duration=${sample.durationMs}ms " +
                        "buffered=${sample.bufferedPositionMs}ms probePassed=$probePassed"
                }
            }
            if (watch.bestProgressMs > lastLoggedProgressMs && watch.bestProgressMs > 0L) {
                lastLoggedProgressMs = watch.bestProgressMs
                startupLog.d {
                    "watchdog progress: attempt=${args.playbackAttempt} candidate=$activeStreamTitle " +
                        "elapsed=${sample.elapsedMs}ms progress=${watch.bestProgressMs}ms " +
                        "buffered=${sample.bufferedPositionMs}ms position=${sample.positionMs}ms"
                }
            }
            when (watch.verdict) {
                PlaybackStartupWatchdog.Verdict.Waiting -> Unit
                PlaybackStartupWatchdog.Verdict.Started -> {
                    startupLog.i {
                        "watchdog started: attempt=${args.playbackAttempt} candidate=$activeStreamTitle " +
                            "elapsed=${sample.elapsedMs}ms progress=${watch.bestProgressMs}ms " +
                            "duration=${sample.durationMs}ms engine=${snapshot.engineName}"
                    }
                    return@LaunchedEffect
                }
                PlaybackStartupWatchdog.Verdict.Abandon -> {
                    val reason = watch.reason
                    // ⚠ **A source abandoned in silence is unfalsifiable from outside a device.**
                    // This is the same rule `NetworkStrengthProbe` carries: "cannot measure" and
                    // "measured badly" look identical on screen. Nothing logged this, so a chain
                    // burning three healthy sources looked exactly like three dead ones.
                    startupLog.w {
                        // The party context is the difference between "this source is dead" and
                        // "the party was holding it and the watchdog counted the hold". The run
                        // that produced the second of those had no line saying a party was
                        // involved at all, so the abandonment read as a source fault for a day.
                        "abandoning $activeStreamTitle: reason=$reason " +
                            "attempt=${args.playbackAttempt} " +
                            "elapsed=${sample.elapsedMs}ms effective=${watch.effectiveElapsedMs}ms " +
                            "heldTotal=${watch.holdMs}ms " +
                            "progress=${watch.bestProgressMs}ms " +
                            "lastAdvance=${watch.lastAdvanceMs}ms duration=${sample.durationMs}ms " +
                            "evidenceOfLife=${watch.hasEvidenceOfLife} " +
                            "party=${partyAbandonContext()} " +
                            "engine=${snapshot.engineName}"
                    }
                    StreamsRepository.noteAutoPickFailureReason(
                        when (reason) {
                            PlaybackStartupWatchdog.Reason.NeverStarted ->
                                getString(Res.string.playback_startup_never_started)
                            PlaybackStartupWatchdog.Reason.Stalled ->
                                getString(Res.string.playback_startup_stalled)
                            PlaybackStartupWatchdog.Reason.TooSlow ->
                                getString(Res.string.playback_startup_too_slow)
                            null -> null
                        },
                    )
                    if (tryNextEpisodeFallback()) return@LaunchedEffect
                    args.onFatalPlaybackError?.invoke()
                    return@LaunchedEffect
                }
            }
        }
    }

    LaunchedEffect(
        playerController,
        playbackSnapshot.isLoading,
        preferredAudioSelectionApplied,
        preferredSubtitleSelectionApplied,
        addonSubtitles,
        isLoadingAddonSubtitles,
    ) {
        if (playerController == null || playbackSnapshot.isLoading) {
            return@LaunchedEffect
        }
        if (preferredAudioSelectionApplied && preferredSubtitleSelectionApplied) {
            return@LaunchedEffect
        }

        repeat(10) {
            refreshTracks()
            if (preferredAudioSelectionApplied && preferredSubtitleSelectionApplied) {
                return@LaunchedEffect
            }
            delay(300)
        }
    }

    LaunchedEffect(
        playerController,
        playerControllerSourceUrl,
        playbackSnapshot.isLoading,
        playbackSnapshot.durationMs,
        activeInitialPositionMs,
        activeInitialProgressFraction,
        initialSeekApplied,
    ) {
        val controller = playerController ?: return@LaunchedEffect
        if (playerControllerSourceUrl != activeSourceUrl) return@LaunchedEffect
        if (initialSeekApplied || playbackSnapshot.isLoading) return@LaunchedEffect

        // ⚠ **Bounded, and gated on a duration worth believing.** This used to be
        // `durationMs * fraction` with no ceiling, while `PlayerScreenRuntimeUi` bounds the
        // identical computation with `coerceAtMost(durationMs - 1)` two files away. Because the
        // effect is keyed on `durationMs`, a duration that *changes* after playback began fires
        // this mid-play - which is "it plays, then it jumps to the end and sticks".
        val targetPositionMs = PlaybackPosition.resolveStartPositionMs(
            initialPositionMs = activeInitialPositionMs,
            progressFraction = activeInitialProgressFraction,
            durationMs = playbackSnapshot.durationMs,
        )
        if (targetPositionMs == null) {
            val refusal = PlaybackPosition.refusalReason(
                initialPositionMs = activeInitialPositionMs,
                progressFraction = activeInitialProgressFraction,
                durationMs = playbackSnapshot.durationMs,
            )
            if (refusal != null) {
                // Refused, and named. A seek silently not happening and a seek landing on the
                // credits look the same from outside the device.
                startupLog.w {
                    PlaybackAttemptLog.seek(
                        source = "resume",
                        positionMs = 0L,
                        durationMs = playbackSnapshot.durationMs.takeIf { it > 0L },
                        fraction = activeInitialProgressFraction,
                        accepted = false,
                        refusedReason = refusal,
                    )
                }
                // ⚠ **Latch only the refusals that no later duration can fix.** A duration
                // that is unknown, implausible or shorter than the resume point may all be
                // corrected on the next snapshot, so those keep retrying and must not cost the
                // user their position. A non-finite fraction is not going to become finite, and
                // because this effect is keyed on `durationMs`, leaving it unlatched re-entered
                // and re-logged on every duration revision for a seek that can never happen.
                if (refusal == "non_finite_fraction") initialSeekApplied = true
                return@LaunchedEffect
            }
            initialSeekApplied = true
            return@LaunchedEffect
        }
        if (isDesktop && activeInitialPositionMs > 0L) {
            initialSeekApplied = true
            return@LaunchedEffect
        }

        startupLog.i {
            PlaybackAttemptLog.seek(
                source = "resume",
                positionMs = targetPositionMs,
                durationMs = playbackSnapshot.durationMs.takeIf { it > 0L },
                fraction = activeInitialProgressFraction,
                accepted = true,
            )
        }
        controller.seekTo(targetPositionMs)
        initialSeekApplied = true
    }

    BindPlayerUiVisibilityEffects()
    BindPlayerMetadataAndSkipEffects()

    DisposableEffect(playbackSession.videoId, activeSourceUrl, activeSourceAudioUrl) {
        val effectVideoId = playbackSession.videoId
        val effectSourceUrl = activeSourceUrl
        val effectSourceAudioUrl = activeSourceAudioUrl
        onDispose {
            if (
                playbackSession.videoId == effectVideoId &&
                activeSourceUrl == effectSourceUrl &&
                activeSourceAudioUrl == effectSourceAudioUrl
            ) {
                flushWatchProgress()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            playerController?.clearNowPlayingInfo()
            P2pStreamingEngine.shutdown()
            PlayerStreamsRepository.clearAll()
        }
    }
}

@Composable
private fun PlayerScreenRuntime.BindPlayerUiVisibilityEffects() {
    LaunchedEffect(
        controlsVisible,
        controlsActivityTick,
        isScrubbingTimeline,
        playbackSnapshot.isPlaying,
        playbackSnapshot.isLoading,
        showParentalGuide,
        errorMessage,
    ) {
        if (
            !controlsVisible ||
            isScrubbingTimeline ||
            !playbackSnapshot.isPlaying ||
            playbackSnapshot.isLoading ||
            showParentalGuide ||
            errorMessage != null
        ) {
            return@LaunchedEffect
        }
        delay(3500)
        controlsVisible = false
    }

    LaunchedEffect(playerControlsLocked, lockedOverlayVisible) {
        if (!playerControlsLocked || !lockedOverlayVisible) return@LaunchedEffect
        delay(PlayerLockedOverlayDurationMs)
        lockedOverlayVisible = false
    }

    LaunchedEffect(playbackSnapshot.isPlaying, playbackSnapshot.isLoading, playbackSnapshot.durationMs, errorMessage) {
        pausedOverlayVisible = false
        if (playbackSnapshot.isPlaying || playbackSnapshot.isLoading || playbackSnapshot.durationMs <= 0L || errorMessage != null) {
            return@LaunchedEffect
        }
        delay(5000)
        pausedOverlayVisible = true
    }

    LaunchedEffect(
        playbackSnapshot.positionMs,
        playbackSnapshot.isPlaying,
        playbackSnapshot.isLoading,
        playbackSnapshot.isEnded,
        playbackSnapshot.durationMs,
    ) {
        if (playbackSnapshot.isEnded) {
            flushWatchProgress(TrackingScrobbleAction.STOP)
            previousIsPlaying = false
            pendingSeekScrobbleRestart = false
            return@LaunchedEffect
        }

        if (previousIsPlaying && !playbackSnapshot.isPlaying && !playbackSnapshot.isLoading) {
            pendingSeekScrobbleRestart = false
            flushWatchProgress(TrackingScrobbleAction.PAUSE)
        }

        if (playbackSnapshot.isPlaying && pendingSeekScrobbleRestart) {
            pendingSeekScrobbleRestart = false
            if (hasRequestedScrobbleStartForCurrentItem) {
                emitTrackingSeekScrobbleStart()
            } else {
                emitTrackingScrobbleStart()
            }
        } else if (!previousIsPlaying && playbackSnapshot.isPlaying) {
            emitTrackingScrobbleStart()
        }

        if (!playbackSnapshot.isLoading) {
            previousIsPlaying = playbackSnapshot.isPlaying
        }
        if (playbackSnapshot.isPlaying) {
            persistPlaybackProgressTick()
        }
    }
}

@Composable
private fun PlayerScreenRuntime.BindPlayerMetadataAndSkipEffects() {
    LaunchedEffect(activeVideoId, activeSeasonNumber, activeEpisodeNumber, parentMetaId, parentMetaType) {
        parentalWarnings = emptyList()
        showParentalGuide = false
        parentalGuideHasShown = false
        playbackStartedForParentalGuide = false

        val imdbId = resolveParentalGuideImdbId() ?: return@LaunchedEffect
        val guide = ParentalGuideRepository.getParentalGuide(imdbId) ?: return@LaunchedEffect
        parentalWarnings = buildParentalWarnings(guide, parentalGuideLabels)

        if (playbackSnapshot.isPlaying) {
            tryShowParentalGuide()
        }
    }

    LaunchedEffect(playbackSnapshot.isPlaying, parentalWarnings) {
        if (playbackSnapshot.isPlaying) {
            tryShowParentalGuide()
        }
    }

    LaunchedEffect(
        activeVideoId,
        activeSeasonNumber,
        activeEpisodeNumber,
        parentMetaId,
        playerSettingsUiState.skipIntroEnabled,
    ) {
        skipIntervals = emptyList()
        activeSkipInterval = null
        skipIntervalDismissed = false
        autoSkippedIntervalKeys.clear()
        if (!PlayerNextEpisodeTransitionPolicy.isPromptSuppressed(nextEpisodeDismissedForVideoId, activeVideoId)) {
            nextEpisodeDismissedForVideoId = null
        }
        if (
            nextEpisodeTransition.phase != PlayerNextEpisodePhase.STARTING ||
            nextEpisodeTransition.targetVideoId != activeVideoId
        ) {
            cancelNextEpisodeTransition(suppressForCurrentEpisode = false)
        }

        if (!playerSettingsUiState.skipIntroEnabled) return@LaunchedEffect

        val lookup = resolveSkipIntervalLookup(
            videoId = activeVideoId,
            season = activeSeasonNumber,
            episode = activeEpisodeNumber,
        ) ?: return@LaunchedEffect

        launch {
            val imdbFromContent = parentMetaId.takeIf { it.startsWith("tt") }
            val intervals = when (lookup) {
                is SkipIntervalLookup.Imdb -> SkipIntroRepository.getSkipIntervals(
                    imdbId = lookup.imdbId,
                    season = lookup.season,
                    episode = lookup.episode,
                )
                is SkipIntervalLookup.Mal -> SkipIntroRepository.getSkipIntervalsForMal(
                    malId = lookup.malId,
                    episode = lookup.episode,
                    imdbId = imdbFromContent,
                    // MAL/Kitsu episodes are absolute. Let the repository map them through TVDB
                    // before querying IMDb rather than short-circuiting with display S/E values.
                    imdbSeason = null,
                    imdbEpisode = null,
                )
                is SkipIntervalLookup.Kitsu -> SkipIntroRepository.getSkipIntervalsForKitsu(
                    kitsuId = lookup.kitsuId,
                    episode = lookup.episode,
                    imdbId = imdbFromContent,
                    imdbSeason = null,
                    imdbEpisode = null,
                )
            }
            skipIntervals = intervals
        }
    }

    LaunchedEffect(
        playbackSnapshot.positionMs,
        playbackSnapshot.isLoading,
        skipIntervals,
        playerSettingsUiState.autoSkipSegmentTypes,
        playerController,
        initialLoadCompleted,
        activeInitialPositionMs,
        activeInitialProgressFraction,
        playbackSnapshot.durationMs,
    ) {
        if (skipIntervals.isEmpty()) {
            activeSkipInterval = null
            return@LaunchedEffect
        }
        // The same bounded computation as the resume seek, from the same helper, so the two
        // cannot disagree about where this play began.
        val initialPlaybackPositionMs = PlaybackPosition.resolveStartPositionMs(
            initialPositionMs = activeInitialPositionMs,
            progressFraction = activeInitialProgressFraction,
            durationMs = playbackSnapshot.durationMs,
        ) ?: 0L
        autoSkippedIntervalKeys += skipIntervals.autoSkipKeysCompletedBy(initialPlaybackPositionMs)
        val positionSec = playbackSnapshot.positionMs / 1000.0
        val current = skipIntervals.firstOrNull { interval ->
            positionSec >= interval.startTime && positionSec < interval.endTime
        }
        if (current != activeSkipInterval) {
            activeSkipInterval = current
            if (current != null) skipIntervalDismissed = false
        }
        if (current != null) {
            val segmentType = AutoSkipSegmentType.fromSkipIntervalType(current.type)
            val intervalKey = current.autoSkipKey()
            val controller = playerController
            if (
                initialLoadCompleted &&
                !playbackSnapshot.isLoading &&
                controller != null &&
                segmentType != null &&
                segmentType in playerSettingsUiState.autoSkipSegmentTypes &&
                intervalKey !in autoSkippedIntervalKeys
            ) {
                val seekPositionMs = (current.endTime * 1000).toLong()
                // Resource lookup can suspend. Resolve it before seeking, because the seek changes
                // positionMs and cancels this keyed effect before post-seek work can run.
                val notification = getString(
                    when (segmentType) {
                        AutoSkipSegmentType.INTRO -> Res.string.player_auto_skip_intro_notification
                        AutoSkipSegmentType.RECAP -> Res.string.player_auto_skip_recap_notification
                        AutoSkipSegmentType.OUTRO -> Res.string.player_auto_skip_outro_notification
                    },
                    formatPlaybackTime(seekPositionMs),
                )
                if (!controller.trySeekTo(seekPositionMs)) return@LaunchedEffect
                autoSkippedIntervalKeys.add(intervalKey)
                scheduleProgressSyncAfterSeek()
                skipIntervalDismissed = true
                playerNotificationMessage = notification
                playerNotificationToken += 1L
            }
        }
    }

    LaunchedEffect(
        playerMetaVideos,
        activeSeasonNumber,
        activeEpisodeNumber,
        watchProgressUiState.entries,
        watchedUiState.watchedKeys,
    ) {
        if (!isSeries || playerMetaVideos.isEmpty()) {
            nextEpisodeInfo = null
            return@LaunchedEffect
        }
        val curSeason = activeSeasonNumber ?: return@LaunchedEffect
        val curEpisode = activeEpisodeNumber ?: return@LaunchedEffect
        val nextVideo = PlayerNextEpisodeRules.resolveNextEpisode(
            videos = playerMetaVideos,
            currentSeason = curSeason,
            currentEpisode = curEpisode,
        )
        val nextSeason = nextVideo?.season
        val nextEpisode = nextVideo?.episode
        nextEpisodeInfo = if (nextVideo != null && nextSeason != null && nextEpisode != null) {
            val playbackVideoId = buildPlaybackVideoId(
                parentMetaId = parentMetaId,
                seasonNumber = nextSeason,
                episodeNumber = nextEpisode,
                fallbackVideoId = nextVideo.id,
            )
            val isWatched = watchProgressUiState.progressForVideo(
                videoId = playbackVideoId,
                parentMetaId = parentMetaId,
                seasonNumber = nextSeason,
                episodeNumber = nextEpisode,
            )?.isEffectivelyCompleted == true || WatchingState.isEpisodeWatched(
                watchedKeys = watchedUiState.watchedKeys,
                metaType = parentMetaType,
                metaId = parentMetaId,
                episode = nextVideo,
            )
            NextEpisodeInfo(
                videoId = nextVideo.id,
                season = nextSeason,
                episode = nextEpisode,
                title = nextVideo.title,
                thumbnail = nextVideo.thumbnail,
                overview = nextVideo.overview,
                released = nextVideo.released,
                hasAired = PlayerNextEpisodeRules.hasEpisodeAired(nextVideo.released),
                isWatched = isWatched,
                unairedMessage = if (!PlayerNextEpisodeRules.hasEpisodeAired(nextVideo.released)) {
                    "$airsPrefix ${nextVideo.released ?: tbaLabel}"
                } else null,
            )
        } else null
    }

    LaunchedEffect(
        playbackSnapshot.positionMs,
        playbackSnapshot.durationMs,
        nextEpisodeInfo,
        skipIntervals,
        playerSettingsUiState.nextEpisodeThresholdMode,
        playerSettingsUiState.nextEpisodeThresholdPercent,
        playerSettingsUiState.nextEpisodeThresholdMinutesBeforeEnd,
        nextEpisodeDismissedForVideoId,
    ) {
        if (nextEpisodeInfo == null || playbackSnapshot.durationMs <= 0L) {
            if (!nextEpisodeTransition.isActive) showNextEpisodeCard = false
            return@LaunchedEffect
        }
        // A guest does not choose the episode, so it must not be shown a countdown it cannot
        // honour. The party's own loading and barrier feedback carries the transition instead.
        if (!ownsNextEpisode) {
            if (!nextEpisodeTransition.isActive) showNextEpisodeCard = false
            return@LaunchedEffect
        }
        if (PlayerNextEpisodeTransitionPolicy.isPromptSuppressed(nextEpisodeDismissedForVideoId, activeVideoId)) {
            if (!nextEpisodeTransition.isActive) showNextEpisodeCard = false
            return@LaunchedEffect
        }
        val shouldShow = PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
            positionMs = playbackSnapshot.positionMs,
            durationMs = playbackSnapshot.durationMs,
            skipIntervals = skipIntervals,
            thresholdMode = playerSettingsUiState.nextEpisodeThresholdMode,
            thresholdPercent = playerSettingsUiState.nextEpisodeThresholdPercent,
            thresholdMinutesBeforeEnd = playerSettingsUiState.nextEpisodeThresholdMinutesBeforeEnd,
        )
        if (shouldShow && !showNextEpisodeCard && !nextEpisodeTransition.isActive) {
            showNextEpisodeCard = true
            if (playerSettingsUiState.streamAutoPlayNextEpisodeEnabled && nextEpisodeInfo?.hasAired == true) {
                playNextEpisode()
            }
        } else if (!shouldShow && !nextEpisodeTransition.isActive) {
            showNextEpisodeCard = false
        }
    }

    LaunchedEffect(playbackSnapshot.isEnded, nextEpisodeInfo) {
        if (
            playbackSnapshot.isEnded &&
            nextEpisodeInfo != null &&
            // Same rule at the end of the episode: the host advances the party, guests follow.
            ownsNextEpisode &&
            !PlayerNextEpisodeTransitionPolicy.isPromptSuppressed(nextEpisodeDismissedForVideoId, activeVideoId) &&
            !showNextEpisodeCard &&
            !nextEpisodeTransition.isActive
        ) {
            showNextEpisodeCard = true
            if (playerSettingsUiState.streamAutoPlayNextEpisodeEnabled && nextEpisodeInfo?.hasAired == true) {
                playNextEpisode()
            }
        }
    }
}

private fun PlayerScreenRuntime.buildNowPlayingInfo(): PlayerNowPlayingInfo {
    val isEpisode = activeSeasonNumber != null && activeEpisodeNumber != null
    return PlayerNowPlayingInfo(
        title = title.ifBlank { activeStreamTitle },
        subtitle = buildNowPlayingSubtitle(
            isEpisode = isEpisode,
            seasonNumber = activeSeasonNumber,
            episodeNumber = activeEpisodeNumber,
            episodeTitle = activeEpisodeTitle,
        ),
        artworkUrl = firstNonBlankUrl(poster, background),
    )
}

private fun buildNowPlayingSubtitle(
    isEpisode: Boolean,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
): String? {
    if (!isEpisode) return null

    val episodeParts = buildList {
        if (seasonNumber != null && episodeNumber != null) {
            add("S${seasonNumber}E${episodeNumber}")
        }
        episodeTitle?.takeIf { it.isNotBlank() }?.let { add(it) }
    }

    return when (episodeParts.size) {
        0 -> null
        1 -> episodeParts.first()
        else -> "${episodeParts[0]} - ${episodeParts[1]}"
    }
}

private fun firstNonBlankUrl(vararg values: String?): String? =
    values.firstOrNull { !it.isNullOrBlank() }?.trim()

/**
 * Ends this play for good, and says so everywhere that has to hear it.
 *
 * ⚠ **Every fatal route has to go through here, including the ones that decide they are fatal
 * only later.** This used to live inline in the `onError` branch of the player surface, which
 * meant the credential refresh had no way to reach it: `tryRefreshCredentialedSourceAfterError`
 * returns `true` the moment it decides to refresh, spending the budget and telling its caller
 * the error is handled, and the work itself happens in a launched job. When that job came back
 * with no candidate - an addon that is down, an item that is gone - it painted `errorMessage`
 * and returned, so `onFatalPlaybackError` never fired and the ranked fallbacks sitting behind it
 * were never tried. A debrid link expiring mid-episode parked the player on a message with a
 * live chain behind it, which is the outcome the `Decline` branch was added to prevent.
 *
 * The debug-HUD guard is deliberate and must stay: diagnostics keeps the failure screen up so a
 * tester can read the engine's real error instead of being returned to details.
 *
 * A null [message] is the engine clearing a previous error rather than reporting one, and it is
 * not a failure at all - it only reaches here because the surface's `onError` hands both through
 * one callback.
 */
internal fun PlayerScreenRuntime.failPlaybackFatally(message: String?) {
    if (message == null) {
        errorMessage = null
        return
    }
    startupLog.w {
        "fatal player error: attempt=${args.playbackAttempt} candidate=$activeStreamTitle error=$message"
    }
    if (isDebugBuild && PlaybackDebugSettings.hudEnabled) {
        errorMessage = message
        controlsVisible = !playerControlsLocked
        return
    }
    // An auto-played next episode advances to its next ranked source rather than showing the
    // user an error mid-binge. The error is deliberately not painted first: the swap is meant
    // to be the only thing they notice.
    if (tryNextEpisodeFallback()) {
        errorMessage = null
        return
    }
    errorMessage = message
    controlsVisible = !playerControlsLocked
    // The engine's own words, carried to the progress overlay of the *next* attempt. This route
    // bumped the attempt counter in silence, and it is the one that covers the most visible
    // failure there is - a source that opens, plays a second and dies.
    StreamsRepository.noteAutoPickFailureReason(message)
    args.onFatalPlaybackError?.invoke()
}

internal fun PlayerScreenRuntime.tryRefreshCredentialedSourceAfterError(message: String?): Boolean {
    val failedUrl = activeSourceUrl
    when (
        credentialRefreshDecision(
            failedUrl = failedUrl,
            refreshesUsed = credentialRefreshesUsed,
            isRefreshInFlight = credentialRefreshJob?.isActive == true,
            lastAttemptedUrl = credentialRefreshAttemptedSourceUrl,
        )
    ) {
        CredentialRefreshDecision.AwaitInFlight -> return true
        // Returning false hands the error to `onFatalPlaybackError`, which is where the failure
        // chain lives. That is the fix: this used to swallow every error forever, so a dead
        // source could never be named, stepped past, or given up on.
        CredentialRefreshDecision.Decline -> return false
        CredentialRefreshDecision.Refresh -> Unit
    }

    val currentVideoId = activeVideoId ?: return false
    credentialRefreshesUsed += 1
    credentialRefreshAttemptedSourceUrl = failedUrl

    val savedPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L)
    val expectedProviderAddonId = activeProviderAddonId
    val expectedProviderName = activeProviderName
    val expectedStreamTitle = activeStreamTitle
    val expectedBingeGroup = currentStreamBingeGroup
    val type = contentType ?: parentMetaType
    val season = activeSeasonNumber
    val episode = activeEpisodeNumber

    errorMessage = null
    controlsVisible = !playerControlsLocked

    credentialRefreshJob = scope.launch {
        PlayerStreamsRepository.loadSources(
            type = type,
            videoId = currentVideoId,
            season = season,
            episode = episode,
            forceRefresh = true,
        )

        var refreshedStream: StreamItem? = null
        var pollCount = 0
        while (pollCount < CREDENTIAL_REFRESH_POLL_COUNT && refreshedStream == null) {
            val state = PlayerStreamsRepository.sourceState.value
            refreshedStream = findCredentialRefreshCandidate(
                streams = state.groups.flatMap { it.streams },
                failedUrl = failedUrl,
                expectedProviderAddonId = expectedProviderAddonId,
                expectedProviderName = expectedProviderName,
                expectedStreamTitle = expectedStreamTitle,
                expectedBingeGroup = expectedBingeGroup,
            )
            if (
                refreshedStream != null ||
                state.emptyStateReason != null ||
                (!state.isAnyLoading && state.groups.isNotEmpty())
            ) {
                break
            }
            delay(CREDENTIAL_REFRESH_POLL_INTERVAL_MS)
            pollCount++
        }

        // ⚠ **Both of these are fatal, not merely disappointing.** The caller was told `true`
        // before this job ran, so nothing else will treat this error as unhandled: if the
        // re-fetch found no candidate, or found only the URL that just died, the failure chain
        // has to be advanced from here or it never runs at all. Painting `errorMessage` and
        // returning left the player parked on a message with live ranked fallbacks behind it.
        val stream = refreshedStream
        if (stream == null) {
            failPlaybackFatally(message)
            return@launch
        }

        val refreshedUrl = stream.playableDirectUrl
        if (refreshedUrl.isNullOrBlank() || refreshedUrl == failedUrl) {
            failPlaybackFatally(message)
            return@launch
        }

        flushWatchProgress()
        stopActiveP2pStream()
        // Same file, new signature. Set before the assignment below, because that assignment is
        // what wakes the reset effect that reads it.
        isCredentialRefreshHandoff = true
        activeSourceUrl = refreshedUrl
        activeSourceAudioUrl = null
        activeSourceHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request)
        activeSourceResponseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response)
        activeStreamType = stream.streamType
        activeStreamTitle = stream.streamLabel
        activeStreamSubtitle = stream.streamSubtitle
        activeProviderName = stream.addonName
        activeProviderAddonId = stream.addonId
        activePartySourceDescriptor = stream.toPartySourceDescriptor()
        currentStreamBingeGroup = stream.behaviorHints.bingeGroup
        activeInitialPositionMs = savedPositionMs
        activeInitialProgressFraction = null
        showSourcesPanel = false
        controlsVisible = true
    }
    return true
}

private fun findCredentialRefreshCandidate(
    streams: List<StreamItem>,
    failedUrl: String,
    expectedProviderAddonId: String?,
    expectedProviderName: String,
    expectedStreamTitle: String,
    expectedBingeGroup: String?,
): StreamItem? =
    streams
        .asSequence()
        .mapNotNull { stream ->
            val refreshedUrl = stream.playableDirectUrl?.takeIf { it.isNotBlank() && it != failedUrl }
                ?: return@mapNotNull null
            val providerMatches = if (!expectedProviderAddonId.isNullOrBlank()) {
                stream.addonId == expectedProviderAddonId
            } else {
                stream.addonName == expectedProviderName
            }
            if (!providerMatches) return@mapNotNull null

            var score = 100
            if (stream.streamLabel == expectedStreamTitle) score += 40
            if (!expectedBingeGroup.isNullOrBlank() && stream.behaviorHints.bingeGroup == expectedBingeGroup) {
                score += 20
            }
            if (refreshedUrl.hasLikelyExpiringPlaybackCredentials()) score += 5
            score to stream
        }
        .maxByOrNull { (score, _) -> score }
        ?.second

private const val CREDENTIAL_REFRESH_POLL_COUNT = 30
private const val CREDENTIAL_REFRESH_POLL_INTERVAL_MS = 500L
