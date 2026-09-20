package com.nuvio.app.features.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.network.NetworkThroughputMeter
import com.nuvio.app.features.addons.AddonsUiState
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsUiState
import com.nuvio.app.features.details.MetaScreenSettingsUiState
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.p2p.P2pSettingsUiState
import com.nuvio.app.features.p2p.P2pStreamingState
import com.nuvio.app.features.player.skip.NextEpisodeInfo
import com.nuvio.app.features.player.skip.SkipInterval
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsUiState
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.watched.WatchedUiState
import com.nuvio.app.features.watchparty.PartyLifecycleFacts
import com.nuvio.app.features.watchparty.PartyPendingResume
import com.nuvio.app.features.watchparty.PartyPresenceState
import com.nuvio.app.features.watchparty.PartySourceMatch
import com.nuvio.app.features.watchparty.PartySourceTimelineDecision
import com.nuvio.app.features.watchparty.PartyStartupHold
import com.nuvio.app.features.watchparty.PendingPartySeek
import com.nuvio.app.features.watchparty.StallHoldBudget
import com.nuvio.app.features.watchparty.WatchPartyStatus
import com.nuvio.app.features.watchprogress.WatchProgressUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.time.TimeSource

internal data class PlayerSurfaceSource(
    val sourceUrl: String,
    val sourceAudioUrl: String?,
    val sourceHeaders: Map<String, String>,
    val sourceResponseHeaders: Map<String, String>,
    val externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle>,
    val streamType: String?,
    val initialPositionMs: Long?,
    val initialPositionRequestKey: String?,
)

internal fun shouldRenderPlayerSurface(
    hasCurrentSource: Boolean,
    hasLifecycleController: Boolean,
    releaseInFlight: Boolean,
    desktop: Boolean,
): Boolean = hasCurrentSource || (desktop && hasLifecycleController && releaseInFlight)

internal class PlayerReleaseSurfaceRetention {
    private var nextAttemptId = 0L
    private var activeAttemptId: Long? = null

    var inFlight by mutableStateOf(false)
        private set

    fun begin(): Long {
        val attemptId = ++nextAttemptId
        activeAttemptId = attemptId
        inFlight = true
        return attemptId
    }

    fun finish(attemptId: Long): Boolean {
        if (activeAttemptId != attemptId) return false
        activeAttemptId = null
        inFlight = false
        return true
    }
}

internal class PlayerScreenRuntime(
    args: PlayerScreenArgs,
) {
    var args by mutableStateOf(args)

    val title: String get() = args.title
    val profileId: Int get() = args.profileId
    val sourceUrl: String get() = args.sourceUrl
    val sourceAudioUrl: String? get() = args.sourceAudioUrl
    val sourceHeaders: Map<String, String> get() = args.sourceHeaders
    val sourceResponseHeaders: Map<String, String> get() = args.sourceResponseHeaders
    val streamType: String? get() = args.streamType
    val providerName: String get() = args.providerName
    val streamTitle: String get() = args.streamTitle
    val streamSubtitle: String? get() = args.streamSubtitle
    val initialBingeGroup: String? get() = args.initialBingeGroup
    val pauseDescription: String? get() = args.pauseDescription
    val logo: String? get() = args.logo
    val poster: String? get() = args.poster
    val background: String? get() = args.background
    val seasonNumber: Int? get() = args.seasonNumber
    val episodeNumber: Int? get() = args.episodeNumber
    val episodeTitle: String? get() = args.episodeTitle
    val episodeThumbnail: String? get() = args.episodeThumbnail
    val contentType: String? get() = args.contentType
    val videoId: String? get() = args.videoId
    val parentMetaId: String get() = args.parentMetaId
    val parentMetaType: String get() = args.parentMetaType
    val providerAddonId: String? get() = args.providerAddonId
    val torrentInfoHash: String? get() = args.torrentInfoHash
    val torrentFileIdx: Int? get() = args.torrentFileIdx
    val torrentFilename: String? get() = args.torrentFilename
    val torrentTrackers: List<String> get() = args.torrentTrackers
    val initialPositionMs: Long get() = args.initialPositionMs
    val initialProgressFraction: Float? get() = args.initialProgressFraction
    val externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle> get() = args.externalSubtitles
    val isSeries: Boolean get() = parentMetaType == "series"
    val initialPartySourceDescriptor get() = args.partySourceDescriptor

    lateinit var scope: CoroutineScope
    lateinit var hapticFeedback: HapticFeedback

    var playerSettingsUiState by mutableStateOf(PlayerSettingsUiState())
    var p2pSettingsUiState by mutableStateOf(P2pSettingsUiState())
    var p2pStreamingState by mutableStateOf<P2pStreamingState>(P2pStreamingState.Idle)
    var metaScreenSettingsUiState: MetaScreenSettingsUiState = MetaScreenSettingsUiState()
    var watchedUiState: WatchedUiState = WatchedUiState()
    var watchProgressUiState: WatchProgressUiState = WatchProgressUiState()
    var sourceStreamsState by mutableStateOf(StreamsUiState())
    var episodeStreamsRepoState by mutableStateOf(StreamsUiState())
    var metaUiState: MetaDetailsUiState = MetaDetailsUiState()
    var addonsUiState: AddonsUiState = AddonsUiState()
    var addonSubtitles: List<AddonSubtitle> = emptyList()
    var isLoadingAddonSubtitles: Boolean = false

    var horizontalSafePadding: Dp = 0.dp
    var metrics: PlayerLayoutMetrics = PlayerLayoutMetrics.fromWidth(0.dp)
    var sliderEdgePadding: Dp = 0.dp
    var overlayBottomPadding: Dp = 0.dp
    var sideGestureSystemEdgeExclusionPx: Float = 0f
    var resizeModeFitLabel: String = ""
    var resizeModeFillLabel: String = ""
    var resizeModeZoomLabel: String = ""
    var resizeModeStretchLabel: String = ""
    var downloadedLabel: String = ""
    var airsPrefix: String = ""
    var tbaLabel: String = ""
    var genericUnknownLabel: String = ""
    var parentalGuideLabels: ParentalGuideLabels = ParentalGuideLabels("", "", "", "", "", "", "", "")

    var gestureController: PlayerGestureController? = null

    var controlsVisible by mutableStateOf(false)
    var controlsActivityTick by mutableStateOf(0)
    var playerControlsLocked by mutableStateOf(false)
    /** Player-owned presentation state; opening the room never changes route or party identity. */
    var partyRoomOpen by mutableStateOf(false)
    /** Starting a party from this playback, for the panel's Starting / StartFailed states. */
    var partyPromotion by mutableStateOf<PartyPromotionProgress>(PartyPromotionProgress.Idle)
    /** A join-policy change sent and not yet confirmed; the panel shows it optimistically. */
    var joinPolicyPending by mutableStateOf<com.nuvio.app.features.social.WatchJoinPolicy?>(null)
    var joinPolicyError by mutableStateOf<String?>(null)
    /** "End for everyone?" asked inline. */
    var partyEndConfirm by mutableStateOf(false)
    /** One inline, dismissible error row under the panel header, replacing party toasts. */
    var partyPanelError by mutableStateOf<String?>(null)
    /** Friends invited from this panel, so a second press says Invited instead of sending again. */
    var partyInvitedProfileIds by mutableStateOf<Set<String>>(emptySet())
    var activeSourceUrl by mutableStateOf(sourceUrl)
    var activeSourceAudioUrl by mutableStateOf(sourceAudioUrl)
    var activeSourceHeaders by mutableStateOf(sanitizePlaybackHeaders(sourceHeaders))
    var activeSourceResponseHeaders by mutableStateOf(sanitizePlaybackResponseHeaders(sourceResponseHeaders))
    var activeStreamType by mutableStateOf(streamType)
    var activeTorrentInfoHash by mutableStateOf(torrentInfoHash)
    var activeTorrentFileIdx by mutableStateOf(torrentFileIdx)
    var activeTorrentFilename by mutableStateOf(torrentFilename)
    var activeTorrentTrackers by mutableStateOf(torrentTrackers)
    var p2pResolvedSourceUrl by mutableStateOf<String?>(null)
    var activeSourceIdentityKey by mutableStateOf(
        torrentInfoHash?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { hash ->
            "torrent:$hash:${torrentFileIdx ?: -1}"
        } ?: sourceUrl.trim().takeIf { it.isNotBlank() }?.let { url -> "url:$url" },
    )
    var activeStreamTitle by mutableStateOf(streamTitle)
    var activeStreamSubtitle by mutableStateOf(streamSubtitle)
    var activeProviderName by mutableStateOf(providerName)
    var activeProviderAddonId by mutableStateOf(providerAddonId)
    var activePartySourceDescriptor by mutableStateOf(args.partySourceDescriptor)
    var currentStreamBingeGroup by mutableStateOf(initialBingeGroup)
    var activeSeasonNumber by mutableStateOf(seasonNumber)
    var activeEpisodeNumber by mutableStateOf(episodeNumber)
    var activeEpisodeTitle by mutableStateOf(episodeTitle)
    var activeEpisodeThumbnail by mutableStateOf(episodeThumbnail)
    var activePauseDescription by mutableStateOf(pauseDescription)
    var activeVideoId by mutableStateOf(videoId)
    var activeInitialPositionMs by mutableStateOf(initialPositionMs)
    var activeInitialProgressFraction by mutableStateOf(initialProgressFraction)
    var shouldPlay by mutableStateOf(true)
    var resizeMode by mutableStateOf(playerSettingsUiState.resizeMode.supportedOnCurrentPlatform())
    var layoutSize by mutableStateOf(IntSize.Zero)
    var playbackSnapshot by mutableStateOf(PlayerPlaybackSnapshot())

    /**
     * When [playbackSnapshot] was received, in epoch milliseconds.
     *
     * The snapshot itself does not know its own age, and for Watch Together that age *is* the sync
     * error: a position sampled up to a polling interval ago and stamped with the current time
     * puts the whole of that interval into every guest's arithmetic. Engines that can be asked
     * directly use `PlayerEngineController.samplePositionMs`; this is what the rest pair with.
     */
    var playbackSnapshotAtMs by mutableStateOf(0L)

    /** Monotonic clock shared by the passive playback network measurements. */
    var playbackObservationClock by mutableStateOf(TimeSource.Monotonic.markNow())
    var debugStatusMessage by mutableStateOf<String?>(null)

    /** Per-source state for the passive network measurement; see `observePlaybackForNetworkEstimate`. */
    var networkEstimateStartPositionMs by mutableStateOf<Long?>(null)
    var networkEstimateStalled by mutableStateOf(false)
    var networkEstimateRecorded by mutableStateOf(false)

    /** Per-source state for the buffer-fill throughput measurement; see [NetworkThroughputMeter]. */
    var networkThroughputState by mutableStateOf(NetworkThroughputMeter.initial())
    var playerController by mutableStateOf<PlayerEngineController?>(null)
    var playerLifecycleController by mutableStateOf<PlayerEngineController?>(null)
    val playerReleaseSurfaceRetention = PlayerReleaseSurfaceRetention()
    var playerControllerSourceUrl by mutableStateOf<String?>(null)
    var errorMessage by mutableStateOf<String?>(null)
    var isScrubbingTimeline by mutableStateOf(false)
    var scrubbingPositionMs by mutableStateOf<Long?>(null)
    var pausedOverlayVisible by mutableStateOf(false)
    var gestureFeedback by mutableStateOf<GestureFeedbackState?>(null)
    var liveGestureFeedback by mutableStateOf<GestureFeedbackState?>(null)
    var renderedGestureFeedback by mutableStateOf<GestureFeedbackState?>(null)
    var lockedOverlayVisible by mutableStateOf(false)
    var gestureMessageJob by mutableStateOf<Job?>(null)
    var accumulatedSeekResetJob by mutableStateOf<Job?>(null)
    var seekProgressSyncJob by mutableStateOf<Job?>(null)
    var accumulatedSeekState by mutableStateOf<PlayerAccumulatedSeekState?>(null)
    var initialLoadCompleted by mutableStateOf(false)

    /**
     * A frame has actually been decoded - the stronger sibling of [initialLoadCompleted].
     *
     * ⚠ **Deliberately a second flag rather than a redefinition.** [initialLoadCompleted] means
     * "the engine has opened the media" and the seek, subtitle and startup-watchdog paths all
     * depend on that weaker meaning; tightening it in place would silently move all of them. Only
     * the loading surface reads this one, because it is the only thing that must not give way
     * until there is a picture behind it. See `PlaybackHandover.hasFirstFrame`.
     *
     * Cleared alongside [initialLoadCompleted] on every source change, so a failover puts the
     * surface back up.
     */
    var firstFrameReached by mutableStateOf(false)
    var speedBoostRestoreSpeed by mutableStateOf<Float?>(null)
    var isHoldToSpeedGestureActive by mutableStateOf(false)
    var initialSeekApplied by mutableStateOf(
        initialPositionMs <= 0L && ((initialProgressFraction ?: 0f) <= 0f),
    )
    var lastProgressPersistEpochMs by mutableStateOf(0L)
    var previousIsPlaying by mutableStateOf(false)
    var hasRequestedScrobbleStartForCurrentItem by mutableStateOf(false)
    var scrobbleStartRequestGeneration by mutableStateOf(0L)
    var pendingSeekScrobbleRestart by mutableStateOf(false)
    var hasSentCompletionScrobbleForCurrentItem by mutableStateOf(false)
    var currentTrackingMedia by mutableStateOf<TrackingMediaReference?>(null)

    var showSourcesPanel by mutableStateOf(false)
    var showEpisodesPanel by mutableStateOf(false)
    var showSubmitIntroModal by mutableStateOf(false)
    var submitIntroSegmentType by mutableStateOf("intro")
    var submitIntroStartTimeStr by mutableStateOf("00:00")
    var submitIntroEndTimeStr by mutableStateOf("00:00")
    var submitIntroStartTimeSec by mutableStateOf<Double?>(0.0)
    var submitIntroEndTimeSec by mutableStateOf<Double?>(0.0)
    var isSubmitIntroSubmitting by mutableStateOf(false)
    var submitIntroStatusMessage by mutableStateOf<String?>(null)
    var playerControlsPendingP2pSwitch by mutableStateOf<PendingPlayerP2pSwitch?>(null)
    var playerControlsCloseModalsToken by mutableStateOf(0L)
    var playerControlsSubmitIntroSuccessToken by mutableStateOf(0L)
    var playerNotificationMessage by mutableStateOf("")
    var playerNotificationToken by mutableStateOf(0L)
    var episodeStreamsPanelState by mutableStateOf(EpisodeStreamsPanelState())
    var episodeQualitySheetEpisode by mutableStateOf<MetaVideo?>(null)
    var playerMetaVideos by mutableStateOf<List<MetaVideo>>(emptyList())
    var playerMeta by mutableStateOf<MetaDetails?>(null)
    var skipIntervals by mutableStateOf<List<SkipInterval>>(emptyList())
    var activeSkipInterval by mutableStateOf<SkipInterval?>(null)
    var skipIntervalDismissed by mutableStateOf(false)
    val autoSkippedIntervalKeys = mutableSetOf<String>()
    var parentalWarnings by mutableStateOf<List<ParentalWarning>>(emptyList())
    var showParentalGuide by mutableStateOf(false)
    var parentalGuideHasShown by mutableStateOf(false)
    var playbackStartedForParentalGuide by mutableStateOf(false)
    var nextEpisodeInfo by mutableStateOf<NextEpisodeInfo?>(null)
    var showNextEpisodeCard by mutableStateOf(false)
    var nextEpisodeTransition by mutableStateOf(PlayerNextEpisodeTransition.Idle)
    var nextEpisodeDismissedForVideoId by mutableStateOf<String?>(null)
    var nextEpisodeAutoPlayJob by mutableStateOf<Job?>(null)

    /**
     * The ranked sources behind the one the next episode is playing, best first.
     *
     * The stream route's failure chain lives in `StreamsRepository` and is armed through
     * `PlayerLaunch.autoPickedWithFailureChain`. Neither reaches an auto-played next episode:
     * that path calls `switchToEpisodeStream` and swaps source inside the running player,
     * without a relaunch. So the chain for it is held here, beside the other `active*` state
     * it belongs to, and consumed by the fatal-error handler.
     *
     * Deliberately **not** routed through `StreamsRepository.seedAutoPlayCandidates`: that
     * store is owned by `StreamRoute`, which is not on the back stack in this flow, and two
     * owners on one chain is how a retry ends up relaunching the wrong video.
     *
     * Cleared whenever a new selection starts and whenever it is spent.
     */
    var nextEpisodeFallbacks by mutableStateOf<List<StreamItem>>(emptyList())
    var pendingP2pSwitch by mutableStateOf<PendingPlayerP2pSwitch?>(null)
    var credentialRefreshJob by mutableStateOf<Job?>(null)
    var credentialRefreshAttemptedSourceUrl by mutableStateOf<String?>(null)

    /**
     * The next `activeSourceUrl` change is a re-mint of the source already playing, not a new one.
     *
     * **What this fixes: the player appearing to load twice.** `LaunchedEffect(activeSourceUrl)`
     * resets `initialLoadCompleted` to false on every URL change, which is what puts the opening
     * overlay back up - correct for a *different* source, wrong for the same file behind a fresh
     * signature. `hasLikelyExpiringPlaybackCredentials` matches nearly every debrid URL, so any
     * transient error during startup spends the one permitted refresh and the user watches the
     * load complete, restart, and complete again before playback begins.
     *
     * Consumed and cleared by that effect, so it can only ever excuse the one change it was set
     * for. It is deliberately not a URL comparison: a re-mint returns a freshly signed URL every
     * time, which is the same reason `credentialRefreshDecision` stopped comparing them.
     */
    var isCredentialRefreshHandoff by mutableStateOf(false)

    /**
     * Re-mints spent on the item being watched.
     *
     * Scoped to the item rather than to the source URL, because a re-mint *is* a new source
     * URL - budgeting per URL gave every retry a fresh budget and the refresh could never run
     * out. See `credentialRefreshDecision`.
     */
    var credentialRefreshesUsed by mutableStateOf(0)

    var showAudioModal by mutableStateOf(false)
    var showSubtitleModal by mutableStateOf(false)
    var showVideoSettingsModal by mutableStateOf(false)
    var audioTracks by mutableStateOf<List<AudioTrack>>(emptyList())
    var subtitleTracks by mutableStateOf<List<SubtitleTrack>>(emptyList())
    var selectedAudioIndex by mutableStateOf(-1)
    var selectedSubtitleIndex by mutableStateOf(-1)
    var selectedAddonSubtitleId by mutableStateOf<String?>(null)
    var useCustomSubtitles by mutableStateOf(false)
    var preferredAudioSelectionApplied by mutableStateOf(false)
    var appliedAudioPreferences: AppliedAudioPreferences? = null
    var preferredSubtitleSelectionApplied by mutableStateOf(false)
    var activeSubtitleTab by mutableStateOf(SubtitleTab.BuiltIn)
    var isUserExplicitAudioSelection by mutableStateOf(false)
    var isUserExplicitSubtitleSelection by mutableStateOf(false)

    /**
     * The source now playing was picked by Streamlined/Instant rather than by the user.
     *
     * Starts from the route's answer and only ever turns false - when the user picks a source from
     * the in-player list. "Prefer built-in subtitles" reads it: a hand-picked source keeps exactly
     * the subtitle behaviour it always had.
     */
    var activeSourceAutoPicked by mutableStateOf(args.automaticSourceSelection)

    /** The source whose embedded-subtitle verification has already been logged, so it logs once. */
    var embeddedSubtitleVerificationLoggedFor by mutableStateOf<String?>(null)
    var hasScannedTextTracksOnce by mutableStateOf(false)
    var autoFetchedAddonSubtitlesForKey by mutableStateOf<String?>(null)
    var trackPreferenceRestoreApplied by mutableStateOf(false)
    var subtitleDelayMs by mutableStateOf(0)
    var subtitleAutoSyncState by mutableStateOf(SubtitleAutoSyncUiState())

    /**
     * The party generation whose start gate has already been released, or null while it still holds.
     *
     * Keyed by party and content generation rather than kept as a bare flag, so a next-episode
     * transition - which returns the party to a readiness lobby - closes the gate again instead of
     * inheriting the previous episode's start.
     */
    var partyStartReleasedKey by mutableStateOf<String?>(null)

    /**
     * The generation whose start barrier has captured this player's playback intent, and that intent.
     *
     * Captured once, the first time the barrier is evaluated for a generation - before the gate itself
     * pauses the player - and changed afterwards only by the user's own play or pause. See
     * `partyStartReleaseResumes`: nothing read later can tell a paused-by-the-barrier host from a host
     * who had paused.
     */
    var partyStartIntentKey: String? = null
    var partyStartIntentPlaying: Boolean? = null

    /**
     * The party source generation this player has already acted on, adopted or already playing.
     *
     * Deliberately not cleared by a failed adoption. A source the party moved to and this client
     * cannot realize stays failed for that generation: retrying it against a catalogue that has
     * already answered is a loop, and the party is told `choosing_fallback` instead. The next real
     * source change advances the generation and arms this again.
     */
    var partyHandledSourceGeneration by mutableStateOf<Int?>(null)

    /** True while this player is realizing the party's new source with the old one still playing. */
    var partySourceHandoffInFlight by mutableStateOf(false)

    /**
     * The party source generation this player has already published a source change for.
     *
     * A pick is one deliberate transition. Without this latch a recomposition, a retry, or a debrid
     * re-resolution of the same pick would advance the generation again, and every other member
     * would tear down a realization they had just finished building.
     */
    var partyPublishedSourceGeneration by mutableStateOf<Int?>(null)

    /**
     * The party content generation this player has already acted on.
     *
     * The content sibling of [partyHandledSourceGeneration], and separate from it on purpose:
     * `party_change_content_v2` advances *both* counters, so a client tracking one for both would
     * either re-adopt a source it already has or miss an episode change that happened to reuse a
     * descriptor. Not cleared by a failed adoption, for the same reason that one is not.
     */
    var partyHandledContentGeneration by mutableStateOf<Int?>(null)

    /** True while this player is realizing the party's new episode with the old one still playing. */
    var partyContentHandoffInFlight by mutableStateOf(false)

    /**
     * The party content generation this host has already published an episode change for.
     *
     * Every way of changing episode - Next episode, autoplay-next, the episode picker - converges
     * on one apply, so this is what keeps one advance to one publish across all three.
     */
    var partyPublishedContentGeneration by mutableStateOf<Int?>(null)

    /** The party generation whose shared position has already replaced this profile's resume point. */
    var partyStartPositionAppliedKey by mutableStateOf<String?>(null)

    /**
     * The party instant of the most recent barrier this client executed, zero when there has been
     * none.
     *
     * It answers two questions, and it is one field because they are one fact. While the instant is
     * still ahead, this player is parked for it and the drift correction stands off - a barrier is
     * already putting it exactly where it is meant to be. Once the instant has passed, it is the
     * line before which a timeline is about the past: the host's next tick is up to half a second
     * behind its own command, and obeying one captured before the barrier would undo it.
     */
    var partyBarrierAtMs by mutableStateOf(0L)

    /**
     * Whether the party is deliberately parking this player: waiting out a barrier, or waiting for a
     * corrective seek to land.
     *
     * Distinct from both "paused" and "starved", and it has to be, because two different things read
     * it. Desktop drives the engine off `shouldPlay` through a `LaunchedEffect`, so flipping that to
     * `true` at the top of a barrier started playback the moment the state changed rather than at the
     * instant the barrier named. And a client parked on purpose must not tell the party it is
     * buffering, because the host's stall guard would then hold the party for a client that is doing
     * exactly what it was told.
     */
    var partyHoldingForBarrier by mutableStateOf(false)

    /**
     * Whether Watch Together is deliberately keeping this player still, and why.
     *
     * Published by `BindWatchPartyEffect` and read by the startup watchdog, which must not count
     * held time towards a stall deadline. Physically reproduced: a guest held at the readiness gate
     * and then paused by the host before its first frame settled was abandoned twelve seconds
     * later, failed over and popped back to the source list, out of a party that was working.
     */
    var partyStartupHold by mutableStateOf(PartyStartupHold.none)

    /** The seek this client has issued and is waiting to see land, or null. Expires on its own. */
    var partyPendingSeek: PendingPartySeek? = null

    /**
     * The speed the party is nominally playing at, while Watch Together is running the engine at a
     * slightly different one to close a drift gap; null the rest of the time.
     *
     * The engine's own rate is the wrong answer to "what speed is this" during a correction: it read
     * `1.0035x` on the S25's speed control on 2026-09-18, and - worse than the label - every party
     * command a guest sent while one was running carried that rate as the party's new speed.
     */
    var partyNominalSpeedDuringCorrection by mutableStateOf<Float?>(null)

    /**
     * The playback speed as the person watching chose it: what the controls show, what a speed
     * gesture steps from, and what this client tells anybody else. Never a transient correction.
     */
    val nominalPlaybackSpeed: Float
        get() = partyNominalSpeedDuringCorrection ?: playbackSnapshot.playbackSpeed

    /** How often this host has held the party for a stalled guest, per content generation. */
    var partyStallHoldBudget: StallHoldBudget = StallHoldBudget()

    /**
     * Whether the party is somewhere this client's own file does not reach.
     *
     * Its source is resolved independently of everyone else's, so it can genuinely be a shorter cut -
     * or a stream whose duration has not settled yet. Held as state because the answer is a banner:
     * a player parked while the rest of the party watches on is otherwise indistinguishable from a
     * player that has broken.
     */
    var partyPositionUnreachable by mutableStateOf(false)

    /** The last status this client told the party about itself, so only changes are published. */
    var partyReportedPeerStatus: WatchPartyStatus? = null

    /**
     * The last buffer-occupancy fact published beside [partyReportedPeerStatus].
     *
     * Held separately because it transitions while the status does not: a guest the host is holding
     * reports `paused` empty and `paused` full, and only the second ends the hold.
     */
    var partyReportedPeerStarved: Boolean = false

    /**
     * Where Watch Together has last commanded this player's playhead, or null when it has not.
     *
     * ⚠ **The startup watchdog measures progress from a baseline, and a party seek moves the
     * playhead without the source having fetched anything.** Left unsaid, the jump reads as
     * progress: the S25 run of 2026-09-19 had a guest aligned from 4339 ms to 12012 ms by the
     * host, which flipped its watchdog onto the shorter post-progress stall deadline and then
     * abandoned the party's only candidate. Published here so the sampler can rebase - see
     * `PlaybackStartupWatchdog.observe`.
     */
    var partyAlignedBaselineMs by mutableStateOf<Long?>(null)

    /**
     * The stalled guests this host paused the party for, empty when it did not.
     *
     * Non-empty *is* the retained playing intent, and that is the whole point of it. A stall hold
     * stops the engine without the party ever having decided to stop watching, so the intent has to
     * outlive the pause somewhere or the party needs a person to press play again to get out of a
     * state nobody chose. Held here so the resume is owned by the same rule that took the pause:
     * without it, a host that paused for a stalled guest and then had them recover would either
     * never start again or would start again over a pause the *user* had taken in the meantime.
     *
     * Cleared by any user transport command, which is how the intent is revoked: a person who
     * pauses during a hold has decided the party is stopped, and the guard must not undo that.
     */
    var partyAutoPausedForGuests: List<String> = emptyList()

    /**
     * The last accepted `pause` still in force, when somebody else issued it.
     *
     * Pause is a condition, not an event: a 900ms toast left a party that stayed paused with nothing
     * on screen saying why. The status pill's "Paused by" row reads this; a `play` clears it.
     */
    var partyLastPauseActor by mutableStateOf<PartyPauseAttribution?>(null)

    /** The generation the host pressed "Don't wait" in; the stall guard comes back on for the next one. */
    var partyDontWaitGenerationKey: String? = null

    /**
     * The last timeline verdict this player logged about its own source.
     *
     * The readiness effect re-runs on every duration and descriptor change, and the verdict is the
     * same one almost every time; a line each would bury the transition that matters - the one where
     * a chain step lands on a release the party is not on.
     */
    var partyReportedTimelineDecision by mutableStateOf<PartySourceTimelineDecision?>(null)

    /**
     * Whether this player is on the party's own release or on a compatible alternate.
     *
     * The same verdict that goes to the party as `source_match`, kept locally because the status
     * line has to say "Using a compatible source" about it and nothing else on this client knows.
     */
    var partyLocalSourceMatch by mutableStateOf<PartySourceMatch?>(null)

    /**
     * The seek this host has issued and not yet resumed the party from.
     *
     * The readiness barrier for deliberate buffering: a seek empties everybody's buffer by
     * construction, so the party parks on the target and the resume waits for positive readiness
     * instead of running on a lead and being pulled back by the stall guard afterwards. Compose
     * state because the effect that does the waiting is keyed on it.
     */
    var partyPendingResume by mutableStateOf<PartyPendingResume?>(null)

    /**
     * Who that resume is still waiting on, for the status pill and the tick the guests read.
     *
     * Kept beside [partyAutoPausedForGuests] rather than inside it: this is a wait the party chose
     * and the stall guard must not read it as a stall it took.
     */
    var partyAwaitingResumeReadiness by mutableStateOf<List<String>>(emptyList())

    /**
     * This client's own presence, and the facts it was decided from. See `PartyPresence.kt`.
     *
     * Held on the runtime rather than inside a `remember` in the effect because the return path
     * needs the presence that was in force when the app went away, and a value scoped to one
     * composition is exactly the thing a backgrounded Android process is least able to promise.
     */
    var partyPresence by mutableStateOf(PartyPresenceState())

    /**
     * How many lifecycle observations have been folded in, so a late one cannot overwrite a newer.
     *
     * Monotonic for the life of the player. See [PartyLifecycleFacts.seq]: Android delivers the
     * callbacks around picture-in-picture in an order that is not guaranteed, and a posted `ON_STOP`
     * landing after the foreground it was overtaken by would otherwise put an active viewer Away.
     */
    var partyLifecycleSeq: Long = 0L

    /**
     * The party generation this client was following when it went away, null when it is not away.
     *
     * The whole of the source-preservation rule lives on this comparison: unchanged on return means
     * the open stream is still the party's stream and nothing may be re-resolved. See
     * [partyReturnAction].
     */
    var partyAwayAtGenerationKey: String? = null

    /**
     * The play intent the party had for this client at the moment it went away.
     *
     * Away pauses the engine, so by the time the member comes back every local signal says "paused"
     * and only this still knows whether the party was running. The same argument
     * [partyStartReleaseResumes] makes about the start barrier, for the same reason.
     */
    var partyAwayIntentPlaying: Boolean = false

    /**
     * Whether this client is catching up after being away, and must not play at its stale position.
     *
     * Read by the peer publisher as well as by the transport: a member seeking minutes forward is
     * doing what the party asked, exactly like a barrier park, so it must not be reported to the
     * host as a stall.
     */
    var partyAwayReturning by mutableStateOf(false)

    /** The away members this host paused the party for, empty when it did not. */
    var partyAutoPausedForAway by mutableStateOf<List<String>>(emptyList())

    var lastSyncedSettingsResizeMode: PlayerResizeMode? = null
    var lastResetPlaybackIdentity: String? = null
    var lastResetVideoIdentity: String? = null
}
