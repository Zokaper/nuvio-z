package com.nuvio.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import com.nuvio.app.features.player.PlayerExitDiagnostics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.network.MeteredPlaybackChoice
import com.nuvio.app.core.network.NetworkConnectionType
import com.nuvio.app.core.network.NetworkEstimateConfidence
import com.nuvio.app.core.network.NetworkQualityRepository
import com.nuvio.app.core.network.NetworkStrengthProbe
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioToastAction
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioConsumePointerEvents
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.debrid.toastMessage
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.core.language.AudioLanguageOption
import com.nuvio.app.core.language.normalizeLanguageCode
import com.nuvio.app.features.player.resolveContentLanguage
import com.nuvio.app.features.playback.playbackSelectionContextOf
import com.nuvio.app.features.downloads.SourceFacts
import com.nuvio.app.features.downloads.SourceFactsExtractor
import com.nuvio.app.features.downloads.SourceRanking
import com.nuvio.app.features.p2p.P2pConsentDialog
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.playback.ConnectionProbeSettlement
import com.nuvio.app.features.playback.PLAYBACK_PROGRESS_STALL_GRACE_MS
import com.nuvio.app.features.playback.PlaybackLoadingActions
import com.nuvio.app.features.playback.PlaybackLoadingBackdrop
import com.nuvio.app.features.playback.PlaybackLoadingController
import com.nuvio.app.features.playback.PlaybackLoadingState
import com.nuvio.app.features.playback.PlaybackMode
import com.nuvio.app.features.playback.PlaybackModeRouter
import com.nuvio.app.features.playback.PlaybackPreferencesDialog
import com.nuvio.app.features.playback.PlaybackProgress
import com.nuvio.app.features.playback.PlaybackProgressFailure
import com.nuvio.app.features.playback.PlaybackProgressInputs
import com.nuvio.app.features.playback.PlaybackProgressStep
import com.nuvio.app.features.playback.PlaybackQualityOption
import com.nuvio.app.features.playback.PlaybackQualityOptions
import com.nuvio.app.features.playback.PlaybackQualitySheet
import com.nuvio.app.features.playback.PlaybackRouteDecision
import com.nuvio.app.features.playback.PlaybackRouteInputs
import com.nuvio.app.features.playback.ContentIdentityGuard
import com.nuvio.app.features.playback.RequestedContent
import co.touchlab.kermit.Logger
import com.nuvio.app.features.playback.PlaybackAttemptLog
import com.nuvio.app.features.playback.PLAYBACK_MAX_ATTEMPTS
import com.nuvio.app.features.playback.hasSilentUncover
import com.nuvio.app.features.playback.PlaybackSelectionContext
import com.nuvio.app.features.playback.PlaybackSelectionResult
import com.nuvio.app.features.playback.PlaybackSourceCandidate
import com.nuvio.app.features.playback.PlaybackSourceSelector
import com.nuvio.app.features.playback.STREAMLINED_SELECTION_TIMEOUT_MS
import com.nuvio.app.features.playback.SourceLanguageInference
import com.nuvio.app.features.playback.automaticEmbeddedSubtitleLanguage
import com.nuvio.app.features.playback.StreamRouteSurface
import com.nuvio.app.features.playback.StreamRouteSurfaceInputs
import com.nuvio.app.features.playback.playbackChain
import com.nuvio.app.features.playback.playbackQualityOptionLabel
import com.nuvio.app.features.playback.qualityLabel
import com.nuvio.app.features.playback.streamRouteSurface
import com.nuvio.app.features.player.DeviceLanguagePreferences
import com.nuvio.app.features.player.ExternalPlaybackOutcome
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.resolvePreferredAudioLanguageTargets
import com.nuvio.app.features.player.PlayerLaunchStore
import com.nuvio.app.features.player.PartyPlayerLaunchKey
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.sanitizePlaybackHeaders
import com.nuvio.app.features.player.sanitizePlaybackResponseHeaders
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.p2pSentinelUrl
import com.nuvio.app.features.streams.StreamLaunch
import com.nuvio.app.features.streams.StreamLaunchStore
import com.nuvio.app.features.streams.PartyStreamLaunchContext
import com.nuvio.app.features.streams.PartyStreamLaunchPurpose
import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.features.streams.StreamsScreen
import com.nuvio.app.features.watchparty.PartySourceDescriptorV2
import com.nuvio.app.features.watchparty.toPartySourceDescriptor
import com.nuvio.app.features.watchparty.PartyRealizationDecision
import com.nuvio.app.features.watchparty.decidePartyRealization
import com.nuvio.app.features.watchparty.PartySourceRealizer
import com.nuvio.app.features.watchparty.WatchPartyRepository
import com.nuvio.app.features.watchparty.partyLaunchStillLive
import com.nuvio.app.features.watchparty.tierPartyPlaybackSources
import com.nuvio.app.features.updater.formatFileSize
import com.nuvio.app.navigation.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

private data class PendingP2pStreamOpen(
    val stream: StreamItem,
    val resumePositionMs: Long?,
    val resumeProgressFraction: Float?,
    val forceExternal: Boolean,
    val forceInternal: Boolean,
    val isAutoPlay: Boolean,
)

/**
 * The stream route's own line in the playback story.
 *
 * Same tag as `PlayerScreenRuntimeEffects`' startup log on purpose: an attempt starts
 * here and ends there, and one `adb logcat -s PlaybackStartup` should read both halves
 * without the reader having to know the code is split across two files.
 */
private val streamLog = Logger.withTag("PlaybackStartup")

internal fun buildP2pPlayerLaunch(
    launch: StreamLaunch,
    stream: StreamItem,
    infoHash: String,
    sentinelUrl: String,
    effectiveVideoId: String,
    pauseDescription: String?,
    resolvedResumePositionMs: Long?,
    resolvedResumeProgressFraction: Float?,
    autoPickedWithFailureChain: Boolean,
    autoPickAttempt: Int,
    contentLanguage: String? = null,
): PlayerLaunch = PlayerLaunch(
    profileId = launch.profileId,
    title = launch.title,
    sourceUrl = sentinelUrl,
    sourceHeaders = emptyMap(),
    sourceResponseHeaders = emptyMap(),
    externalSubtitles = stream.externalSubtitles,
    streamType = stream.streamType,
    logo = launch.logo,
    poster = launch.poster,
    background = launch.background,
    seasonNumber = launch.seasonNumber,
    episodeNumber = launch.episodeNumber,
    episodeTitle = launch.episodeTitle,
    episodeThumbnail = launch.episodeThumbnail,
    streamTitle = stream.streamLabel,
    streamSubtitle = stream.streamSubtitle,
    bingeGroup = stream.behaviorHints.bingeGroup,
    pauseDescription = pauseDescription,
    providerName = stream.addonName,
    providerAddonId = stream.addonId,
    contentType = launch.type,
    videoId = effectiveVideoId,
    parentMetaId = launch.parentMetaId ?: effectiveVideoId,
    parentMetaType = launch.parentMetaType ?: launch.type,
    torrentInfoHash = infoHash,
    torrentFileIdx = stream.p2pFileIdx,
    torrentFilename = stream.behaviorHints.filename,
    torrentTrackers = stream.p2pTrackers,
    initialPositionMs = resolvedResumePositionMs ?: 0L,
    initialProgressFraction = resolvedResumeProgressFraction,
    contentLanguage = contentLanguage,
    autoPickedWithFailureChain = autoPickedWithFailureChain,
    sourceFacts = SourceFactsExtractor.extract(stream),
    playbackAttempt = autoPickAttempt,
    expectedRuntimeMinutes = launch.runtimeMinutes,
    partySourceDescriptor = stream.toPartySourceDescriptor(),
)

@Composable
internal fun StreamDestination(
    route: StreamRoute,
    navController: NuvioNavigator,
    p2pEnabled: Boolean,
    openExternalPlayback: suspend (PlayerLaunch) -> ExternalPlaybackOutcome,
    openExternalStreamUrl: (String) -> Boolean,
) {
    val onBack = rememberGuardedPopBackStack(navController, route)
    val launch = remember(route.launchId) {
        StreamLaunchStore.get(route.launchId)
    }
    if (launch == null) {
        LaunchedEffect(route.launchId) {
            onBack()
        }
        return
    }
    val pauseDescription = launch.pauseDescription
    val partyResolutionContext = launch.partyContext?.takeIf {
        it.purpose == PartyStreamLaunchPurpose.RESOLVE_PLAYBACK && it.targetFingerprint != null
    }
    val isPartyResolution = partyResolutionContext != null

    /**
     * The host choosing what the party will watch - the other half of the party's source story.
     *
     * [isPartyResolution] is a guest realizing an authority that already exists; this is the one
     * member who gets asked a question. Which question is **not** this route's to invent: the
     * host's `PlaybackMode` decides it, through `PlaybackModeRouter` like every other play, so
     * Streamlined hosts get their quality step and Instant hosts get asked nothing at all.
     *
     * The *answer*, though, is party-shaped rather than playback-shaped. However the source is
     * chosen, this route stages the descriptor and returns to the lobby; it never opens a player
     * and never resolves a debrid link, because staging is a credential-free choice.
     */
    val partySourceSelection = launch.partyContext?.takeIf {
        it.purpose == PartyStreamLaunchPurpose.SELECT_SOURCE
    }
    /**
     * The exact authority this route is realizing for, or null when it is ordinary browsing.
     *
     * Derived up here with the launch it comes from, because every reporting call site - including
     * the give-up path, which is declared before the rest of the party plumbing - names it.
     */
    val partyRealizationKey = partyResolutionContext?.targetFingerprint?.let { descriptor ->
        PartyPlayerLaunchKey(
            partyId = partyResolutionContext.partyId,
            contentGeneration = partyResolutionContext.contentGeneration,
            sourceGeneration = partyResolutionContext.sourceGeneration,
            descriptor = descriptor,
        )
    }
    val streamRouteScope = rememberCoroutineScope()
    // In-flight coroutine state cannot survive process restoration. Restoring `true` would leave
    // the route permanently refusing every future resolve after Android killed it mid-request.
    var resolvingDebridStream by remember(route.launchId) { mutableStateOf(false) }
    var pendingP2pStreamOpen by remember { mutableStateOf<PendingP2pStreamOpen?>(null) }
    var pendingUncachedStream by remember { mutableStateOf<StreamItem?>(null) }
    var qualitySheetDismissed by rememberSaveable(route.launchId) { mutableStateOf(false) }
    var streamlinedSelectionPending by rememberSaveable(route.launchId) { mutableStateOf(false) }
    // Ids are resolution+variant, so they survive the refetch this round-trips through.
    var pendingStreamlinedOptionId by rememberSaveable(route.launchId) { mutableStateOf<String?>(null) }
    var manualSourceListRequested by rememberSaveable(route.launchId) { mutableStateOf(false) }
    // Deliberately **not** part of `awaitingUserAnswer`. That flag uncovers the
    // source list so a dialog has something usable behind it, which is right for
    // a question the app is asking; this one is drawn over the sheet the user is
    // already reading, and pulling the sheet out from under it would answer a
    // question nobody asked. The sheet outranks `awaitingUserAnswer` in
    // `streamRouteSurface`, so no change is needed there - only this comment,
    // because the next person to add a dialog here will have to decide the same
    // thing.
    var showPlaybackPreferences by rememberSaveable(route.launchId) { mutableStateOf(false) }
    // Set by the one exit that leaves rather than uncovering - see
    // `leaveToDetails`. Saved beside the other flags because that exit must
    // outlive the sheet, which leaves composition the moment `onDismiss` runs.
    var exitRequested by rememberSaveable(route.launchId) { mutableStateOf(false) }
    val noAutomaticSourceMessage = stringResource(Res.string.playback_quality_no_match)
    val hostSourceUnavailableMessage = stringResource(Res.string.watch_party_host_source_unavailable)

    /**
     * Which of the ways into the source list was taken, or null while none has been.
     *
     * Feeds `streamRouteSurface`, whose [hasSilentUncover] makes "the list appeared and nothing
     * said why" a failing test rather than a thing users have to notice and report.
     */
    var uncoverPath by rememberSaveable(route.launchId) { mutableStateOf<String?>(null) }
    var manualPlaybackStarting by rememberSaveable(route.launchId) { mutableStateOf(false) }
    var manualCandidateFacts by remember(route.launchId) { mutableStateOf<SourceFacts?>(null) }
    var loadingToken by rememberSaveable(route.launchId) { mutableStateOf<Long?>(null) }

    /**
     * Gives the screen back to the user, with a reason.
     *
     * Every automatic path can end without a source: the chain runs out, a
     * protocol is refused, a consent dialog is declined. Each of those used to
     * be its own two lines at its own call site, and the ones that forgot them
     * left the opaque hand-off surface - or the progress overlay - up over a
     * screen the user could neither read nor leave. There is one way out now,
     * and it always says something.
     *
     * [reason] null means "say the generic thing"; blank means say nothing,
     * which is what the explicit user actions want - they already know why.
     */
    /**
     * ⚠ **[path] has no default on purpose.** It used to default to `"unspecified"`, which meant
     * a new dead end that forgot to say why still produced a non-null `uncoverReason` and sailed
     * past [hasSilentUncover] unnoticed - the invariant was unenforceable at the one place it was
     * supposed to bite. A required parameter turns that into a compile error instead.
     */
    fun giveUpToSourceList(reason: String? = null, path: String) {
        manualPlaybackStarting = false
        manualCandidateFacts = null
        loadingToken?.let(PlaybackLoadingController::close)
        loadingToken = null
        qualitySheetDismissed = true
        manualSourceListRequested = true
        // ⚠ **Which of the eight ways in this was.** The maintainer could not name the
        // conditions under which the list appears in Streamlined or Instant, and that is the
        // finding: every path here was silent, so there was nothing to notice at the time.
        // Recorded per attempt under the same tag as the rest of the startup story, so a few
        // days of ordinary use turns "for whatever reason" into a ranked list of real causes -
        // which is something no amount of reading the code produces.
        uncoverPath = path
        // Every automatic dead end passes through here, so this is the one place that can tell the
        // process owner that party realization ended without playing. It is inert unless work for
        // this exact key is still in flight, so a member who merely walked back out of the player
        // keeps the realization they are already watching.
        partyRealizationKey?.let { PartySourceRealizer.abandoned(it, path) }
        // Arriving here means the app could not choose, so the user is about to
        // do it by hand - and `StreamsScreen` auto-filters to whichever addon
        // last served this show. That filter is a convenience when the list is
        // the first thing you see; it is an obstacle when the list is the
        // fallback, because the addon it selects is quite possibly the one that
        // just failed to produce anything usable.
        StreamsRepository.selectFilter(null)
        val message = reason ?: noAutomaticSourceMessage
        if (message.isNotBlank()) NuvioToastController.show(message)
    }

    /**
     * The host's party source choice, staged and handed back to the lobby.
     *
     * Every way the host can arrive at a source ends here - the Classic list, the Streamlined
     * quality step, Instant deciding alone - so all three stage the same thing in the same way.
     * It was written once for the list and then only the list could reach it, which is how
     * "Choose a source" came to mean Classic's release list no matter what the host had chosen
     * their playback mode to be.
     *
     * ⚠ **Source preparation is a credential-free choice, never a playback attempt.** The
     * original descriptor is staged before any debrid resolution or URL/header access, and this
     * transient source route then returns to the durable lobby. Nothing here opens a player.
     *
     * Answers false when the party moved underneath the choice or the stream describes nothing a
     * guest could match, so the caller can put the list back rather than fail silently.
     */
    fun stagePartyHostSource(
        selection: PartyStreamLaunchContext,
        descriptor: PartySourceDescriptorV2?,
        label: String?,
    ): Boolean {
        val currentParty = WatchPartyRepository.uiState.value.party
        val selectionIsCurrent = currentParty?.id == selection.partyId &&
            currentParty.sourceGeneration == selection.sourceGeneration
        if (descriptor == null || !selectionIsCurrent) {
            NuvioToastController.show(hostSourceUnavailableMessage)
            return false
        }
        WatchPartyRepository.stageHostSource(descriptor, label)
        StreamsRepository.cancelLoading()
        navController.popBackStack(route)
        return true
    }

    /**
     * Whether this route was opened for a party that is no longer running.
     *
     * ⚠ **A terminal party must never lead to a resolution.** Hardware Bug 5 (2026-09-15): a guest
     * sitting here "resolving source" when the host ended the party kept resolving, because nothing
     * on this route ever looked at the party again after it was opened. Read at the two hand-offs to
     * the player as well as by the effect below, so a frame in which both happen cannot open a
     * player for a dead party.
     */
    val partyLaunchId = launch.partyContext?.partyId
    fun partyLaunchEnded(): Boolean =
        partyLaunchId != null && !partyLaunchStillLive(partyLaunchId, WatchPartyRepository.uiState.value.party)

    if (partyLaunchId != null) {
        val partyUiForLaunch by WatchPartyRepository.uiState.collectAsStateWithLifecycle()
        val launchPartyLive = partyLaunchStillLive(partyLaunchId, partyUiForLaunch.party)
        LaunchedEffect(launchPartyLive) {
            if (launchPartyLive) return@LaunchedEffect
            streamLog.i { "party ended during source preparation party=${partyLaunchId.take(8)} - leaving" }
            // The realizer was already cleared with the party; this names the abandonment for the
            // trace and is inert if nothing was in flight.
            partyRealizationKey?.let { PartySourceRealizer.abandoned(it, "party_ended") }
            StreamsRepository.abandonAutoPlay()
            StreamsRepository.cancelLoading()
            loadingToken?.let(PlaybackLoadingController::close)
            loadingToken = null
            onBack()
        }
    }

    /**
     * Leaves for the details screen, and uncovers the list if that pop no-ops.
     *
     * The second half is the load-bearing one. `onBack` can silently do nothing -
     * `rememberGuardedPopBackStack` pops only while this route is current and
     * returns Unit, so the caller cannot tell - and with the route still up and
     * nothing uncovered, the opaque hand-off Box keeps painting over
     * `StreamsScreen`: a blank screen with no affordance. Backing out of the
     * quality sheet and backing out of the player both needed this guard and both
     * grew their own copy; this is the one copy.
     */
    fun leaveToDetails() {
        manualPlaybackStarting = false
        manualCandidateFacts = null
        loadingToken?.let(PlaybackLoadingController::close)
        loadingToken = null
        // ⚠ **The automatic play ends here, not at the pop.** "Escape takes you back and stops
        // whatever is running" is one action, and splitting it left the stopping half unwritten:
        // the pop tore this route down while the chain stayed armed in the repository, so the
        // auto-play effect relaunched the source on the way out, and the next visit to the same
        // title was handed the same chain again by `carriedAutoPlayChain`.
        //
        // The fetch goes with it. A user who has left is not waiting on a source list, and an
        // addon still answering into a route nobody is looking at is the "finding" half of what
        // Escape is supposed to terminate.
        StreamsRepository.abandonAutoPlay()
        StreamsRepository.cancelLoading()
        exitRequested = true
        onBack()
    }
    LaunchedEffect(exitRequested) {
        if (!exitRequested) return@LaunchedEffect
        withFrameNanos { }
        if (navController.currentRoute == route) {
            // The pop no-oped. Uncovering is a poor outcome, but an opaque
            // nothing is a worse one, and it leaves the user able to act.
            giveUpToSourceList(reason = "", path = "back_from_quality_sheet")
        }
    }

    // Streamlined covers the source list with the quality sheet until a tier is
    // picked; from that point until playback starts the progress overlay owns the
    // screen, so the list is never what the user is left looking at. Instant
    // reaches the same flag from the other side - it has no sheet, so the overlay
    // owns the screen from the start - and one flag serves both on purpose: two
    // that both mean "the automatic path is working" is how one ends up uncleared.
    var autoPlaybackStarting by rememberSaveable(route.launchId) { mutableStateOf(false) }
    // Instant has chosen. ⚠ **Latched for the life of the route, and never reset.**
    // It guards the effect that *seeds* the failure chain, so clearing it on a
    // retry re-seeds back to candidate 1 and the same failure loops forever
    // instead of advancing. Do not reset it alongside `playbackHandedOff`.
    var instantSelectionHandled by rememberSaveable(route.launchId) { mutableStateOf(false) }
    // The answer to "you're on mobile data", for this network, for this session.
    // Read once from the repository so a play that already asked does not ask
    // again, and held locally so answering it recomposes this route.
    var meteredChoice by remember(route.launchId) {
        mutableStateOf(NetworkQualityRepository.meteredChoiceForCurrentNetwork())
    }
    // 1-based, and only ever advanced by the auto-pick failure chain. The overlay
    // shows it so a silent retry does not read as a hang.
    var autoPickAttempt by rememberSaveable(route.launchId) { mutableStateOf(1) }
    // The source the chain last gave up on, rendered by the progress overlay.
    // Not `rememberSaveable`: it describes the wait currently on screen, and a
    // failure restored after the route left composition would name a source the
    // user is no longer waiting on.
    var autoPickFailure by remember(route.launchId) {
        mutableStateOf<PlaybackProgressFailure?>(null)
    }
    // The source handed to the player, kept so a player-requested retry can say
    // what it is retrying *from*. That path bumped `autoPickAttempt` silently -
    // the one failure route of three that reported nothing at all, and the one
    // covering the most visible failure there is: a source that opens, plays a
    // second and dies.
    //
    // ⚠ **`rememberSaveable`, and the label rather than the `StreamItem`.** This
    // was a plain `remember`, which is exactly what it must not be: a mode with a
    // failure chain keeps `StreamRoute` on the back stack on purpose, `NavDisplay`
    // composes only the top entry, and so the value was gone by the time the
    // player handed it back - null, the `let` below skipped, the overlay bumping
    // its counter in silence over the one failure this was added to report, and
    // `consumeAutoPickFailureReason` never called, leaving a stored reason to be
    // blamed on some later unrelated source. Every sibling flag that makes this
    // trip is already saveable (`instantSelectionHandled`, `autoPickAttempt`,
    // `playbackHandedOff`). A `String?` needs no `Saver`, and the label is all
    // `noteSourceFailure` ever needed - the identity lookup it does could not
    // survive process death anyway.
    var lastHandedOffLabel by rememberSaveable(route.launchId) {
        mutableStateOf<String?>(null)
    }
    var lastHandedOffFacts by remember(route.launchId) {
        mutableStateOf<SourceFacts?>(null)
    }
    /**
     * The user backed out of the player themselves, rather than a source failing.
     *
     * Read by the stall backstop, to keep it from reporting a deliberate exit as a dead end, and
     * by the auto-play effect, which must not answer a back press with another play. Saveable
     * because the exit happens on the far side of a route change.
     */
    var userAbandonedPlayback by rememberSaveable(route.launchId) { mutableStateOf(false) }
    // Set at *every* exit to playback, not just the reuse-last-link one.
    // Instant deliberately leaves StreamRoute on the back stack so the failure
    // chain survives, so without this, backing out of the player lands on an
    // opaque overlay with nothing to interact with.
    var playbackHandedOff by rememberSaveable(route.launchId) { mutableStateOf(false) }
    /** The requested title's year, once the meta answers. Null until then, and often for good. */
    var requestedYear by remember(route.launchId) { mutableStateOf<Int?>(null) }
    val playerSettings by remember {
        PlayerSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    /**
     * The title's original language, for the loading band's language inference
     * (`SourceLanguageInference`) and the player's "original" audio target.
     *
     * ⚠ **Read synchronously from what is already loaded, never fetched.** A fetch would land after
     * the band had drawn and change its language under the reader. `peek` answers from the details
     * screen's enriched meta (TMDB `original_language`) or the cache; when neither has it - a cold
     * Continue Watching launch - this is null for the whole play and the band says less, not more.
     *
     * Language only, never the production country: a country is not a language.
     */
    val requestedContentLanguage = remember(route.launchId) {
        val metaId = launch.parentMetaId ?: launch.videoId
        SourceLanguageInference.contentLanguageCode(
            MetaDetailsRepository.peek(launch.parentMetaType ?: launch.type, metaId)?.language,
        )
    }
    /**
     * This title's own language, for the `original` audio sentinel, once the meta answers.
     *
     * Null until then and often for good, which is the honest answer: `resolveRankableLanguages`
     * turns a null into "no language opinion" rather than falling back to the device locale, so a
     * user who asked for the original language is never quietly given English instead.
     *
     * ⚠ **Deliberately not seeded from [requestedContentLanguage], and not the same rule.** That one
     * refuses to read a production country as a language, because it is *displayed* and a wrong
     * language on the band is worse than a blank one. This one is only ever *ranked* with, where
     * `resolveContentLanguage`'s country fallback is a better guess than no guess. Two consumers,
     * two policies, on purpose.
     */
    var contentOriginalLanguage by remember(route.launchId) { mutableStateOf<String?>(null) }
    val shouldResolveEpisodeVideoId =
        launch.parentMetaId != null &&
            launch.seasonNumber != null &&
            launch.episodeNumber != null
    var effectiveVideoId by rememberSaveable(
        launch.videoId,
        launch.parentMetaId,
        launch.seasonNumber,
        launch.episodeNumber,
    ) {
        mutableStateOf(launch.videoId)
    }
    var hasResolvedVideoId by rememberSaveable(
        launch.videoId,
        launch.parentMetaId,
        launch.seasonNumber,
        launch.episodeNumber,
    ) {
        mutableStateOf(!shouldResolveEpisodeVideoId)
    }

    LaunchedEffect(
        launch.videoId,
        launch.parentMetaId,
        launch.parentMetaType,
        launch.type,
        launch.seasonNumber,
        launch.episodeNumber,
    ) {
        if (!shouldResolveEpisodeVideoId) {
            effectiveVideoId = launch.videoId
            hasResolvedVideoId = true
            // A movie needs no video-id resolution, so this branch used to return before meta was
            // ever fetched. That is still right for everyone except the one preference that cannot
            // be answered without meta: `original` audio names a language only the title knows.
            // Fetched here rather than always, because it buys nothing for the other settings.
            if (normalizeLanguageCode(playerSettings.preferredAudioLanguage) == AudioLanguageOption.ORIGINAL) {
                val metaId = launch.parentMetaId ?: launch.videoId
                val movieMeta = runCatching {
                    MetaDetailsRepository.fetch(launch.parentMetaType ?: launch.type, metaId)
                }.getOrNull()
                contentOriginalLanguage = resolveContentLanguage(
                    language = movieMeta?.language,
                    country = movieMeta?.country,
                )
            }
            return@LaunchedEffect
        }
        // Deliberately *not* reset to `launch.videoId` first. This effect
        // restarts every time the route re-enters composition - which is every
        // return from the player - and blanking an id that is already resolved
        // sent it resolved -> placeholder -> resolved on each return. Anything
        // keyed on it was discarded twice, and `StreamsScreen` issued two full
        // stream loads, the first against the parent id.
        if (hasResolvedVideoId) return@LaunchedEffect

        effectiveVideoId = launch.videoId
        hasResolvedVideoId = false
        val metaType = launch.parentMetaType ?: launch.type
        val metaId = launch.parentMetaId ?: return@LaunchedEffect
        val meta = runCatching {
            MetaDetailsRepository.fetch(metaType, metaId)
        }.getOrNull()
        // The title's own year, for the content-identity guard. Best-effort and often absent -
        // the guard treats a null as "not known", which always passes, so an addon that reports
        // no release info simply gets no year check rather than a wrong one.
        requestedYear = meta?.releaseInfo?.let(ContentIdentityGuard::parseYear)
        contentOriginalLanguage = resolveContentLanguage(
            language = meta?.language,
            country = meta?.country,
        )
        val resolvedVideoId = meta
        ?.videos
        ?.firstOrNull { video ->
            video.season == launch.seasonNumber &&
                video.episode == launch.episodeNumber
        }
        ?.id
        ?.takeIf { it.isNotBlank() }

        effectiveVideoId = resolvedVideoId ?: launch.videoId
        hasResolvedVideoId = true
    }

    // Streamlined and Instant own source selection. Passing them through the
    // legacy auto-play policy would run two pickers over the same candidates.
    val streamManualSelection = launch.manualSelection ||
        // A download-intent launch must never auto-play: the user pressed
        // Download, so every automatic playback path stays out of the way.
        launch.downloadIntent ||
        // Party playback has its own strict source matcher. Marking the repository request as
        // manual prevents Classic's legacy auto-play policy from seeding an ordinary ranked
        // source before that matcher has seen the complete catalogue.
        isPartyResolution ||
        // A Classic host choosing the party's source reads the release list, the same as a
        // Classic host choosing an ordinary play. `streamAutoPlayMode` is a Classic-only setting
        // and this keeps it out of the party's candidates, which is what the launch's hardcoded
        // `manualSelection = true` used to do - before it also overrode Streamlined and Instant.
        partySourceSelection != null ||
        // ⚠ **Back from the player is back to a list the user picks from.** Classic keeps this route
        // under a hand-picked play, and the player saves that release's binge group while it runs.
        // The pop re-fetched the list as an automatic request, which now found a remembered binge
        // group, raised "Finding source…" over the list, armed the same release - and then the
        // abandon rightly refused to play it, so the overlay stayed up until a second Escape.
        userAbandonedPlayback ||
        playerSettings.playbackMode != PlaybackMode.CLASSIC

    fun openP2pStream(
        stream: StreamItem,
        resolvedResumePositionMs: Long?,
        resolvedResumeProgressFraction: Float?,
        replaceStreamRoute: Boolean,
    ) {
        val infoHash = stream.p2pInfoHash ?: return
        if (partyLaunchEnded()) return
        val sentinelUrl = p2pSentinelUrl(infoHash, stream.p2pFileIdx)
        val hasFailureChain = replaceStreamRoute &&
            (isPartyResolution || playerSettings.playbackMode != PlaybackMode.CLASSIC)
        val playerLaunch = buildP2pPlayerLaunch(
            launch = launch,
            stream = stream,
            infoHash = infoHash,
            sentinelUrl = sentinelUrl,
            effectiveVideoId = effectiveVideoId,
            pauseDescription = pauseDescription,
            resolvedResumePositionMs = resolvedResumePositionMs,
            resolvedResumeProgressFraction = resolvedResumeProgressFraction,
            autoPickedWithFailureChain = hasFailureChain,
            autoPickAttempt = autoPickAttempt,
            contentLanguage = requestedContentLanguage,
        )

        val launchId = PlayerLaunchStore.put(playerLaunch)
        StreamsRepository.cancelLoading()
        lastHandedOffFacts = playerLaunch.sourceFacts
        if (hasFailureChain) {
            lastHandedOffLabel = stream.streamLabel
            autoPickFailure = null
        }
        playbackHandedOff = true
        navController.navigate(PlayerRoute(launchId = launchId, title = playerLaunch.title)) {
            if (replaceStreamRoute && !hasFailureChain) {
                popUpTo<StreamRoute> { inclusive = true }
            }
        }
        if (replaceStreamRoute && !hasFailureChain) StreamsRepository.consumeAutoPlay()
    }

    fun requestOrOpenP2pStream(
        stream: StreamItem,
        resolvedResumePositionMs: Long?,
        resolvedResumeProgressFraction: Float?,
        forceExternal: Boolean,
        forceInternal: Boolean,
        isAutoPlay: Boolean,
    ) {
        // Both of these advance the chain and *must* honour the answer. They
        // discarded it, so a refusal on the last candidate advanced to nothing
        // and returned - leaving whatever was covering the screen still up,
        // with no candidate left to take it down.
        if (stream.p2pInfoHash == null) {
            if (isAutoPlay && !StreamsRepository.skipAutoPlayStream(stream)) {
                giveUpToSourceList(path = "p2p_no_infohash_chain_spent")
            }
            return
        }
        if (!P2pSettingsRepository.isVisible) {
            if (isAutoPlay && !StreamsRepository.skipAutoPlayStream(stream)) {
                giveUpToSourceList(path = "p2p_hidden_chain_spent")
            }
            return
        }
        if (!p2pEnabled) {
            pendingP2pStreamOpen = PendingP2pStreamOpen(
                stream = stream,
                resumePositionMs = resolvedResumePositionMs,
                resumeProgressFraction = resolvedResumeProgressFraction,
                forceExternal = forceExternal,
                forceInternal = forceInternal,
                isAutoPlay = isAutoPlay,
            )
            return
        }
        openP2pStream(
            stream = stream,
            resolvedResumePositionMs = resolvedResumePositionMs,
            resolvedResumeProgressFraction = resolvedResumeProgressFraction,
            replaceStreamRoute = isAutoPlay,
        )
    }

    val streamsUiState by StreamsRepository.uiState.collectAsStateWithLifecycle()
    val expectedStreamsRequestToken = StreamsRepository.requestToken(
        type = launch.type,
        videoId = effectiveVideoId,
        season = launch.seasonNumber,
        episode = launch.episodeNumber,
        manualSelection = streamManualSelection,
    )
    val playbackCandidates = remember(
        streamsUiState.groups,
        streamsUiState.requestToken,
        expectedStreamsRequestToken,
    ) {
        if (streamsUiState.requestToken != expectedStreamsRequestToken) {
            emptyList()
        } else {
            streamsUiState.groups.flatMapIndexed { addonOrder, group ->
                group.streams.map { stream ->
                    PlaybackSourceCandidate(
                        stream = stream,
                        facts = SourceFactsExtractor.extract(stream),
                        addonOrder = addonOrder,
                    )
                }
            }
        }
    }
    /**
     * The facts for the candidate currently armed, for the loading screen's band.
     *
     * Read from `autoPlayStream` rather than from the winner the selector returned, because the
     * chain advances underneath it: after a dead candidate the armed stream is the *next* one,
     * and a band still describing the source that just failed is worse than a blank band - it
     * names the wrong release while the screen says it is starting playback.
     *
     * Null is an ordinary answer. Before anything is armed, and for a stream no candidate row
     * matches, the band simply has no chips.
     */
    val activeCandidateFacts = remember(streamsUiState.autoPlayStream, playbackCandidates, lastHandedOffFacts) {
        streamsUiState.autoPlayStream?.let { armed ->
            playbackCandidates.firstOrNull { it.stream === armed }?.facts
                ?: SourceFactsExtractor.extract(armed)
        } ?: lastHandedOffFacts
    }
    // ⚠ **Which of the ways into the list this was.** The maintainer could not name the
    // conditions under which the list appears in Streamlined or Instant, and that is the
    // finding: every path was silent, so there was nothing to notice at the time. Logged from
    // an effect rather than from `giveUpToSourceList` because the figures below it - the
    // attempt counter, the armed candidate, the addon's error state - are declared after that
    // function and a local cannot reach forward.
    LaunchedEffect(uncoverPath) {
        val path = uncoverPath ?: return@LaunchedEffect
        streamLog.i {
            PlaybackAttemptLog.attempt(
                mode = playerSettings.playbackMode.name.lowercase(),
                attempt = autoPickAttempt,
                maxAttempts = PLAYBACK_MAX_ATTEMPTS,
                candidate = lastHandedOffLabel,
                // ⚠ **Read from what was handed off, not from what is armed now.** By the time a
                // give-up is logged the chain has already moved on - or been consumed entirely -
                // so `autoPlayStream` is usually null and these printed `addon=unknown
                // cached=unknown` on exactly the lines that needed them most. `lastHandedOffFacts`
                // is the candidate this give-up is actually about.
                addonId = lastHandedOffFacts?.providerId
                    ?: streamsUiState.autoPlayStream?.addonId,
                addonErrored = streamsUiState.groups.any { !it.error.isNullOrBlank() },
                cached = lastHandedOffFacts?.isDebridReady
                    ?: streamsUiState.autoPlayStream?.let { armed ->
                        playbackCandidates.firstOrNull { it.stream === armed }?.facts?.isDebridReady
                    },
                outcome = "gave_up",
                uncoverReason = path,
            )
        }
    }

    val playbackSelectionContext = remember(
        requestedYear,
        contentOriginalLanguage,
        launch.runtimeMinutes,
        launch.seasonNumber,
        launch.episodeNumber,
        playerSettings.playbackAllowTorrentAutopick,
        playerSettings.preferredAudioLanguage,
        playerSettings.secondaryPreferredAudioLanguage,
        playerSettings.preferredSubtitleLanguage,
        playerSettings.secondaryPreferredSubtitleLanguage,
        playerSettings.playbackLanguageStrictness,
        playerSettings.playbackQualityCeilingMbps,
        playerSettings.playbackCodecPreference,
        playerSettings.playbackDynamicRangePolicy,
        // Was absent, so changing the audio preference left the context - and therefore the
        // whole quality panel built from it - showing the previous answer until something else
        // invalidated it.
        playerSettings.playbackAudioPreference,
        playerSettings.playbackPreferEmbeddedSubtitles,
    ) {
        playbackSelectionContextOf(
            settings = playerSettings,
            isEpisode = launch.seasonNumber != null && launch.episodeNumber != null,
            runtimeMinutes = launch.runtimeMinutes,
            contentOriginalLanguage = contentOriginalLanguage,
            // ⚠ **Automatic modes only.** Classic and every manual path leave this null, so the
            // guard is inert for them: a manual pick is the user reading the release name and
            // choosing anyway, and overriding that would be a refusal wearing a helper's name.
            identity = if (
                (isPartyResolution || playerSettings.playbackMode != PlaybackMode.CLASSIC) &&
                !launch.manualSelection &&
                !launch.downloadIntent
            ) {
                RequestedContent(
                    season = launch.seasonNumber,
                    episode = launch.episodeNumber,
                    year = requestedYear,
                )
            } else {
                null
            },
            // Codec, dynamic range, audio preference and the display ceiling are the factory's now;
            // passing them here again is how the three copies drifted in the first place.
            // Streamlined/Instant automatic picks only - null for Classic, manual and downloads.
            preferredEmbeddedSubtitleLanguage = automaticEmbeddedSubtitleLanguage(
                enabled = playerSettings.playbackPreferEmbeddedSubtitles,
                mode = playerSettings.playbackMode,
                manualSelection = launch.manualSelection,
                downloadIntent = launch.downloadIntent,
                primarySubtitleTarget = if (playerSettings.playbackPreferEmbeddedSubtitles) {
                    playerSettings.primarySubtitleTarget
                } else {
                    null
                },
            ),
        )
    }
    // The quality choices for *this* title, derived from what the addons actually
    // returned. A quality nobody released simply produces no row.
    val playbackQualityOptions = remember(playbackCandidates, playbackSelectionContext) {
        PlaybackQualityOptions.build(playbackCandidates, playbackSelectionContext)
    }
    val partyTieredSources = remember(
        partyResolutionContext,
        playbackCandidates,
        playbackQualityOptions,
        playbackSelectionContext,
    ) {
        val host = partyResolutionContext?.targetFingerprint ?: return@remember null
        tierPartyPlaybackSources(
            host = host,
            candidates = playbackCandidates,
            normalOrder = playbackQualityOptions.firstOrNull()?.candidates.orEmpty(),
            selection = playbackSelectionContext,
        )
    }
    // Resolved here because the band names are `stringResource`s and the effect
    // that announces a skipped sheet is not composable. Built from the same
    // function the sheet's own rows use, so the toast quotes the user's words
    // for the row they picked rather than a second description of it.
    // Keep the route decision across player hand-off and retry.
    var playbackRouteDecision by rememberSaveable(
        route.launchId,
        playerSettings.playbackMode,
        stateSaver = PlaybackRouteDecisionSaver,
    ) { mutableStateOf<PlaybackRouteDecision?>(null) }
    var routeDecisionHandled by rememberSaveable(
        route.launchId,
        playerSettings.playbackMode,
    ) { mutableStateOf(false) }
    LaunchedEffect(
        hasResolvedVideoId,
        playerSettings.playbackMode,
        launch.manualSelection,
        launch.downloadIntent,
        launch.partyContext,
    ) {
        if (!hasResolvedVideoId) return@LaunchedEffect
        if (routeDecisionHandled) return@LaunchedEffect
        routeDecisionHandled = true
        playbackRouteDecision = PlaybackModeRouter.decide(
            PlaybackRouteInputs(
                mode = playerSettings.playbackMode,
                manualSelection = launch.manualSelection || launch.downloadIntent,
                // Completed downloads are consumed before StreamRoute is created.
                hasCompletedLocalDownload = false,
                isPartyResolvePlayback = launch.partyContext?.purpose ==
                    PartyStreamLaunchPurpose.RESOLVE_PLAYBACK &&
                    launch.partyContext.targetFingerprint != null,
            ),
        )
    }

    var partyResolutionHandled by rememberSaveable(
        route.launchId,
        partyResolutionContext?.partyId,
        partyResolutionContext?.sourceGeneration,
        partyResolutionContext?.targetFingerprint?.releaseFingerprint,
    ) { mutableStateOf(false) }
    LaunchedEffect(
        isPartyResolution,
        partyResolutionHandled,
        partyTieredSources,
        streamsUiState.requestToken,
        streamsUiState.isAnyLoading,
        streamsUiState.emptyStateReason,
    ) {
        if (!isPartyResolution || partyResolutionHandled) return@LaunchedEffect
        // Reported, not owned: this route is torn down the moment playback starts, so the work it
        // is doing on the party's behalf has to be visible somewhere that outlives it.
        partyRealizationKey?.let(PartySourceRealizer::matching)
        val decision = decidePartyRealization(
            catalogueSettled = com.nuvio.app.features.playback.isStreamlinedSelectionReady(
                requestToken = streamsUiState.requestToken,
                expectedRequestToken = expectedStreamsRequestToken,
                isAnyLoading = streamsUiState.isAnyLoading,
                candidateCount = playbackCandidates.size,
                hasTerminalEmptyState = streamsUiState.emptyStateReason != null,
                hasStreams = streamsUiState.groups.any { it.streams.isNotEmpty() },
            ),
            tiered = partyTieredSources,
        )
        when (decision) {
            PartyRealizationDecision.Wait -> return@LaunchedEffect
            PartyRealizationDecision.FallbackRequired -> {
                partyResolutionHandled = true
                partyRealizationKey?.let(PartySourceRealizer::fallbackRequired)
                streamLog.w {
                    "party source unavailable: party=${partyResolutionContext?.partyId?.take(8)} " +
                        "sourceGeneration=${partyResolutionContext?.sourceGeneration} " +
                        "fallbackAvailable=${partyTieredSources?.fallback != null}"
                }
                // A fallback is only an offer. Never seed or open it: the user must explicitly
                // choose an alternate from the uncovered list, and the lobby remains the durable
                // owner.
                giveUpToSourceList(hostSourceUnavailableMessage, path = "party_source_unavailable")
            }
            is PartyRealizationDecision.Resolve -> {
                partyResolutionHandled = true
                streamLog.i {
                    "party source matched: party=${partyResolutionContext?.partyId?.take(8)} " +
                        "sourceGeneration=${partyResolutionContext?.sourceGeneration} " +
                        "tier=${partyTieredSources?.tier} candidates=${decision.candidates.size}"
                }
                qualitySheetDismissed = true
                autoPlaybackStarting = true
                partyRealizationKey?.let(PartySourceRealizer::resolving)
                StreamsRepository.seedAutoPlayCandidates(decision.candidates.map { it.stream })
            }
        }
    }

    /**
     * Records which source just failed and why, on the way to the next one.
     *
     * Streamlined steps past a dead candidate silently otherwise, which is
     * indistinguishable from the app hanging - and when the chain then runs
     * out, the only thing left on screen is whatever the last provider said,
     * out of context. Naming the source turns "unknown error" into something
     * the user can act on, or at least recognise.
     *
     * **Recorded, not toasted.** This used to raise one toast per dead
     * candidate over a progress overlay already counting attempts - two answers
     * to one question, stacking on a deep chain, and outliving the wait they
     * described: after a successful third attempt the last thing on screen was
     * a complaint about the second. The loading surface renders it
     * instead, so it lives and dies with the wait. The terminal case still
     * toasts, through `giveUpToSourceList`, because by then the overlay is gone.
     *
     * Declared above the retry effect below, which calls it: Kotlin resolves
     * local functions positionally, lambda or not.
     */
    // How a source is named in the progress overlay. Resolved while the
    // candidate list is still in hand, because the lookup is by identity and
    // nothing about it survives the route leaving composition.
    fun sourceFailureLabel(stream: StreamItem): String =
        PlaybackSourceSelector.describe(
            playbackCandidates.firstOrNull { it.stream === stream }?.facts,
        ).takeIf { it.isNotBlank() } ?: stream.streamLabel

    fun noteSourceFailureByLabel(label: String, reason: String?) {
        autoPickFailure = PlaybackProgressFailure(label = label, reason = reason)
    }

    /**
     * Names a dead candidate.
     *
     * ⚠ **There is deliberately no fallback to `AddonStreamGroup.error` here.** An earlier pass
     * added one, and it was dead by construction: the group only carries an `error` when its
     * stream list is empty, and a stream being reported dead necessarily came *from* that group,
     * so the group is non-empty and its error is always null. The addon's own words reach the
     * user through the group header in the list instead, which is where they can actually be
     * read - see `StreamsScreen.streamSection`.
     *
     * For the failures that happen *after* a stream exists - a resolve that returns nothing, a
     * link that will not open - the reason comes from the caller, which knows more than the
     * group ever did.
     */
    fun noteSourceFailure(stream: StreamItem, reason: String?) {
        noteSourceFailureByLabel(sourceFailureLabel(stream), reason?.takeIf { it.isNotBlank() })
    }

    // "Choose source manually", pressed inside the player and answered here.
    //
    // ⚠ **Ordered before the retry effect below on purpose.** Both wake on the return from the
    // player, and this one is the more specific answer: the user did not just leave, they asked
    // for the list. Letting the retry effect see it first would carry the gesture through
    // `leaveToDetails` to the details screen - which is right for a plain Back and wrong for a
    // button whose whole text is where it wants to go.
    //
    // The chain goes with it. A list the user is reading must not have a candidate starting
    // underneath it, which is the same rule `abandonAutoPlay` exists for.
    LaunchedEffect(navController.currentRoute) {
        if (navController.currentRoute != route) return@LaunchedEffect
        if (!StreamsRepository.consumeManualSourceRequest()) return@LaunchedEffect
        playbackHandedOff = false
        lastHandedOffFacts = null
        StreamsRepository.abandonAutoPlay()
        // Blank, because the user pressed the button: they already know why they are here.
        giveUpToSourceList(reason = "", path = "manual_escape_from_player")
    }

    // Coming back from the player with a candidate still armed. Two very
    // different things look identical here, and telling them apart is the whole
    // point of this effect.
    //
    // A **retry** - the source died and the chain advanced - should re-show the
    // progress overlay the hand-off had hidden and launch the next candidate.
    //
    // A **back press** produces exactly the same state, because nothing consumes
    // the chain until the first frame plays. Inferring a retry from state alone
    // therefore relaunched the source the user had just walked out of, over and
    // over: they could not escape a slow debrid mint at all, and only the in-app
    // back button worked, because that one pops this route on its way to details.
    //
    // So the player says when it is a retry, and silence means the user left.
    // Gated on this route being current because Instant deliberately leaves
    // `autoPlayStream` set while the player is open - without that check this
    // would fire the moment playback was handed off and uncover the overlay
    // underneath the player.
    LaunchedEffect(streamsUiState.autoPlayStream, navController.currentRoute) {
        if (navController.currentRoute != route) return@LaunchedEffect
        if (!playbackHandedOff) return@LaunchedEffect
        // ⚠ **`autoPlayStream` gates the *retry*, never the *user leaving*, and conflating the two
        // was the escape hatch's whole fault.**
        //
        // This guard used to sit above the branch, so a back press with no armed stream - a manual
        // pick, or an automatic chain whose stream had already been consumed - returned here and
        // set nothing. `userAbandonedPlayback` therefore stayed false, which is the one flag that
        // suppresses the dead-end backstop, so 1.5 s later that backstop uncovered the source list
        // and toasted "No safe sources found" at a user who had simply pressed Escape. Observed in
        // the log as `outcome=gave_up uncover=dead_end_backstop` with `attempt=1/3` - no failover
        // had happened at all - and it is also why Escape appeared to need two presses: the first
        // popped the player and the route did nothing with it.
        //
        // A retry still requires a stream to relaunch, so the check moves into that branch alone.
        if (!StreamsRepository.consumeFailoverRetry()) {
            // The user came back on their own. Retire the chain first, so
            // nothing relaunches behind them.
            StreamsRepository.consumeAutoPlay()
            if (
                playerSettings.playbackMode == PlaybackMode.CLASSIC ||
                launch.manualSelection ||
                launch.downloadIntent
            ) {
                // Classic and the manual paths came *from* the list, so the
                // list is where backing out belongs.
                playbackHandedOff = false
                // The hand-picked play that set this is over; left true, the route still believed
                // a play was starting, and the next pick reused a closed loading session.
                manualPlaybackStarting = false
                lastHandedOffFacts = null
                userAbandonedPlayback = true
                return@LaunchedEffect
            }
            // Streamlined did not, and must not end up there: this route is
            // the mechanism, not a destination the user asked for. Uncovering
            // the list here also re-fetched it, because `consumeAutoPlay`
            // clears the request key - so one Back landed on a "source
            // loading" screen and it took a second to actually leave. Carry
            // the gesture through to the details screen instead, which is what
            // backing out of the quality sheet already does - and through the
            // same exit, so the no-op guard exists once.
            userAbandonedPlayback = true
            leaveToDetails()
            return@LaunchedEffect
        }
        // A retry has nothing to relaunch without an armed stream. Only reachable when the player
        // asked for one, so silence here is the failover arriving before the stream does.
        if (streamsUiState.autoPlayStream == null) return@LaunchedEffect
        playbackHandedOff = false
        lastHandedOffFacts = null
        streamLog.i {
            "failover retrying from attempt $autoPickAttempt to ${autoPickAttempt + 1}: " +
                "previousCandidate=$lastHandedOffLabel"
        }
        autoPickAttempt += 1
        // The third failure route, and the only one that used to say nothing.
        // The source opened, played, and died - the most visible failure there
        // is - and the overlay came back showing a bumped counter with no
        // account of what had just happened. The player now leaves the reason on
        // the repository on its way out: the engine's own error message, or the
        // startup watchdog's verdict when it gave up on a source that never
        // played a frame. Still nullable, because a fatal path that has nothing
        // to say must not invent something.
        lastHandedOffLabel?.let {
            noteSourceFailureByLabel(
                label = it,
                reason = StreamsRepository.consumeAutoPickFailureReason(),
            )
        }
    }

    var autoPlayHandled by rememberSaveable(route.launchId) { mutableStateOf(false) }
    LaunchedEffect(
        streamsUiState.autoPlayStream,
        streamsUiState.requestToken,
        expectedStreamsRequestToken,
        routeDecisionHandled,
        playbackRouteDecision,
        playerSettings.playbackMode,
        launch.manualSelection,
        autoPlaybackStarting,
    ) {
        if (!routeDecisionHandled) return@LaunchedEffect
        if (launch.manualSelection) return@LaunchedEffect
        // ⚠ **The user leaving outranks an armed chain, and this line is the whole of "Escape
        // means stop".** On the pop back from the player this effect and the retry effect above
        // both wake on the same `autoPlayStream`, and whichever ran first decided what happened.
        // When this one won it relaunched the source the user had just escaped: measured at
        // 2.4 s after the back press, re-attaching at the position they left it
        // (`initialPositionMs=361499` against a `pos=360902` exit), which is why Escape had to
        // be out-pressed rather than pressed. Ordering the two effects is not a fix - the
        // abandon is a fact, and a fact outranks a race.
        if (userAbandonedPlayback) return@LaunchedEffect
        val isClassicAutoPlay = playerSettings.playbackMode == PlaybackMode.CLASSIC &&
            playbackRouteDecision is PlaybackRouteDecision.ShowSourceList
        // Streamlined runs the chain from the moment a tier is chosen, Instant
        // from the moment the connection answers. Before either,
        // `autoPlaybackStarting` is false and nothing has been seeded, so the
        // sheet is still the user's to answer and Instant is still deciding.
        //
        // One flag, asked once: "is there a next candidate to fall to?".
        // Answering that in two ways is how the chain ends up half-wired - which
        // is why this is not `mode == STREAMLINED || mode == INSTANT`.
        val hasFailureChain = autoPlaybackStarting &&
            (isPartyResolution || playerSettings.playbackMode != PlaybackMode.CLASSIC)
        if (!isClassicAutoPlay && !hasFailureChain) return@LaunchedEffect
        if (autoPlayHandled && !hasFailureChain) return@LaunchedEffect
        if (streamsUiState.requestToken != expectedStreamsRequestToken) return@LaunchedEffect
        val selectedStream = streamsUiState.autoPlayStream ?: return@LaunchedEffect
        val safePartySource = selectedStream.toPartySourceDescriptor(
            playbackCandidates.firstOrNull { it.stream === selectedStream }?.facts
                ?: SourceFactsExtractor.extract(selectedStream),
        )
        // ⚠ **A host choosing the party's source stops here, before the debrid resolve below.**
        // Streamlined's quality step and Instant's automatic pick both arrive as an armed
        // candidate on this chain, exactly as an ordinary play would, and an ordinary play would
        // now mint a link and open the player. The host is not playing anything yet - they are
        // answering "what will we watch" - so the descriptor is staged and the lobby takes over.
        // Classic never reaches this line for a party selection, because `streamManualSelection`
        // keeps the legacy auto-play policy off the party's candidates and the host picks from
        // the list.
        if (partySourceSelection != null) {
            autoPlayHandled = true
            if (!stagePartyHostSource(partySourceSelection, safePartySource, selectedStream.streamLabel)) {
                // The automatic pick describes nothing a guest could match. Hand the host the
                // list rather than leaving the overlay up over a choice that cannot be made.
                giveUpToSourceList(path = "party_source_not_describable")
            }
            return@LaunchedEffect
        }
        val stream = if (DirectDebridPlaybackResolver.shouldResolveToPlayableStream(selectedStream)) {
            streamLog.i {
                "debrid resolving: attempt=$autoPickAttempt candidate=${sourceFailureLabel(selectedStream)}"
            }
            when (
                val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(
                    stream = selectedStream,
                    season = launch.seasonNumber,
                    episode = launch.episodeNumber,
                )
            ) {
                is DirectDebridPlayableResult.Success -> {
                    streamLog.i {
                        "debrid resolve succeeded: attempt=$autoPickAttempt candidate=${sourceFailureLabel(selectedStream)}"
                    }
                    resolved.stream
                }
                else -> {
                    streamLog.w {
                        "debrid resolve failed: attempt=$autoPickAttempt candidate=${sourceFailureLabel(selectedStream)} " +
                            "reason=${resolved.toastMessage()}"
                    }
                    val hasNextCandidate = StreamsRepository.skipAutoPlayStream(selectedStream)
                    if (hasNextCandidate && hasFailureChain) {
                        autoPickAttempt += 1
                        // Silently stepping to the next source is how "not
                        // cached" turned into a spinner nobody could explain.
                        // Name the source and the reason on the way past.
                        noteSourceFailure(
                            stream = selectedStream,
                            reason = resolved.toastMessage(),
                        )
                    }
                    if (!hasNextCandidate) {
                        // The chain is spent and nothing else will move. Without
                        // this the progress overlay keeps covering the source
                        // list with "Starting playback" for a playback that is
                        // never going to start - a hang wearing a spinner.
                        giveUpToSourceList(resolved.toastMessage(), path = "debrid_resolve_failed")
                    }
                    if (!hasNextCandidate && resolved == DirectDebridPlayableResult.Stale) {
                        StreamsRepository.reload(
                            type = launch.type,
                            videoId = effectiveVideoId,
                            parentMetaId = launch.parentMetaId,
                            season = launch.seasonNumber,
                            episode = launch.episodeNumber,
                            manualSelection = streamManualSelection,
                        )
                    }
                    return@LaunchedEffect
                }
            }
        } else {
            selectedStream
        }
        val sourceUrl = stream.playableDirectUrl
        if (sourceUrl == null && stream.needsLocalDebridResolve && stream.p2pInfoHash != null) {
            autoPlayHandled = true
            requestOrOpenP2pStream(
                stream = stream,
                resolvedResumePositionMs = launch.resumePositionMs,
                resolvedResumeProgressFraction = launch.resumeProgressFraction,
                forceExternal = false,
                forceInternal = true,
                isAutoPlay = true,
            )
            return@LaunchedEffect
        }
        if (sourceUrl == null) {
            if (StreamsRepository.skipAutoPlayStream(selectedStream)) {
                if (hasFailureChain) {
                    autoPickAttempt += 1
                    noteSourceFailure(stream = selectedStream, reason = null)
                }
            } else if (hasFailureChain) {
                // Same reasoning as the resolve-failure arm: an exhausted chain
                // must uncover the list rather than leave the overlay up.
                giveUpToSourceList(path = "no_playable_url_chain_spent")
            }
            return@LaunchedEffect
        }
        autoPlayHandled = true
        val playerLaunch = PlayerLaunch(
            profileId = launch.profileId,
            title = launch.title,
            sourceUrl = sourceUrl,
            sourceHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request),
            sourceResponseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
            externalSubtitles = stream.externalSubtitles,
            streamType = stream.streamType,
            logo = launch.logo,
            poster = launch.poster,
            background = launch.background,
            seasonNumber = launch.seasonNumber,
            episodeNumber = launch.episodeNumber,
            episodeTitle = launch.episodeTitle,
            episodeThumbnail = launch.episodeThumbnail,
            streamTitle = stream.streamLabel,
            streamSubtitle = stream.streamSubtitle,
            bingeGroup = stream.behaviorHints.bingeGroup,
            pauseDescription = pauseDescription,
            providerName = stream.addonName,
            providerAddonId = stream.addonId,
            contentType = launch.type,
            videoId = effectiveVideoId,
            parentMetaId = launch.parentMetaId ?: effectiveVideoId,
            parentMetaType = launch.parentMetaType ?: launch.type,
            initialPositionMs = launch.resumePositionMs ?: 0L,
            initialProgressFraction = launch.resumeProgressFraction,
            contentLanguage = requestedContentLanguage,
            autoPickedWithFailureChain = hasFailureChain,
            // The band the player draws is the band the route was drawing a frame ago.
            sourceFacts = playbackCandidates.firstOrNull { it.stream === stream }?.facts
                ?: SourceFactsExtractor.extract(stream),
            playbackAttempt = autoPickAttempt,
            expectedRuntimeMinutes = launch.runtimeMinutes,
            partySourceDescriptor = safePartySource,
        )
        if (playerSettings.playbackMode == PlaybackMode.INSTANT) {
            val openedFacts = playbackCandidates
                .firstOrNull { it.stream === stream }?.facts
            // Instant otherwise gives no indication of what it decided, which is
            // most of why a defensible pick reads as a random one - reported as
            // "spinning a roulette wheel on what resolution I'm going to get".
            val detail = listOfNotNull(
                openedFacts?.resolution.qualityLabel.takeIf { it.isNotBlank() },
                openedFacts?.releaseQuality?.takeIf { it.isNotBlank() },
                (openedFacts?.providerName ?: openedFacts?.debridService)
                    ?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (detail.isNotBlank()) {
                NuvioToastController.show(
                    message = getString(Res.string.playback_instant_selected, detail),
                    actionLabel = getString(Res.string.playback_reused_last_link_change),
                    action = NuvioToastAction.ChangePlaybackSource,
                )
            }
        }
        // Remembered before the hand-off, because a retry comes back with the
        // chain already advanced and no way to name what it advanced *from*.
        // Resolved to a label here rather than carried as a `StreamItem`: this
        // is the last point at which the candidate list can name it.
        lastHandedOffLabel = sourceFailureLabel(stream)
        // The wait this described is over. Leaving it set would carry a
        // complaint about the previous candidate into the overlay of the one
        // that is now working.
        autoPickFailure = null

        if (playerSettings.externalPlayerEnabled) {
            lastHandedOffFacts = playerLaunch.sourceFacts
            playbackHandedOff = true
            val outcome = openExternalPlayback(playerLaunch)
            if (outcome != ExternalPlaybackOutcome.Opened) {
                lastHandedOffFacts = null
                playbackHandedOff = false
                val rejectedText = getString(Res.string.playback_external_player_rejected)
                when {
                    // ⚠ **A configuration problem is not a source problem.** Every candidate
                    // will fail the same way, so stepping the chain here spent the whole retry
                    // budget on it - three toasts of "external player not configured" and a
                    // failure finally blamed on three innocent sources. The user has already
                    // been told what is actually wrong by the toast this outcome produced.
                    outcome == ExternalPlaybackOutcome.PlayerUnavailable -> {
                        StreamsRepository.consumeAutoPlay()
                        giveUpToSourceList(reason = "", path = "external_player_unavailable")
                    }
                    hasFailureChain && StreamsRepository.skipAutoPlayStream(stream) -> {
                        autoPickAttempt += 1
                        noteSourceFailure(stream, rejectedText)
                    }
                    hasFailureChain -> giveUpToSourceList(
                        reason = rejectedText,
                        path = "external_player_chain_spent",
                    )
                    else -> StreamsRepository.consumeAutoPlay()
                }
                StreamsRepository.cancelLoading()
                return@LaunchedEffect
            }
            if (!hasFailureChain) StreamsRepository.consumeAutoPlay()
            StreamsRepository.cancelLoading()
            return@LaunchedEffect
        }
        if (!hasFailureChain) StreamsRepository.consumeAutoPlay()
        StreamsRepository.cancelLoading()
        if (partyLaunchEnded()) return@LaunchedEffect
        val launchId = PlayerLaunchStore.put(playerLaunch)
        lastHandedOffFacts = playerLaunch.sourceFacts
        playbackHandedOff = true
        loadingToken?.let(PlaybackLoadingController::handOff)
        streamLog.i {
            "handoff: attempt=$autoPickAttempt candidate=${playerLaunch.streamTitle} " +
                "urlType=${if (playerLaunch.torrentInfoHash != null) "p2p" else "http"} " +
                "facts=${playerLaunch.sourceFacts?.let { "${it.resolution.qualityLabel} ${it.releaseQuality.orEmpty()} ${it.debridService.orEmpty()}".trim() } ?: "unknown"}"
        }
        // A mode with a chain keeps StreamRoute on the back stack: that route
        // owns the auto-play effect, the attempt counter and the overlay, so
        // popping it is popping the thing that does the retrying.
        navController.navigate(PlayerRoute(launchId = launchId, title = playerLaunch.title)) {
            if (!hasFailureChain) popUpTo<StreamRoute> { inclusive = true }
        }
    }

    if (!hasResolvedVideoId) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            NuvioLoadingIndicator(color = MaterialTheme.nuvio.colors.accent)
        }
        return
    }

    fun openSelectedStream(
        stream: StreamItem,
        resolvedResumePositionMs: Long?,
        resolvedResumeProgressFraction: Float?,
        forceExternal: Boolean,
        forceInternal: Boolean,
        safePartySource: PartySourceDescriptorV2? = null,
    ) {
        val willOpenInternally = forceInternal || (!forceExternal && !playerSettings.externalPlayerEnabled && !stream.shouldOpenExternally)
        val streamFacts = playbackCandidates.firstOrNull { it.stream === stream }?.facts
            ?: SourceFactsExtractor.extract(stream)
        val retainedPartySource = safePartySource ?: stream.toPartySourceDescriptor(streamFacts)
        if (partySourceSelection != null) {
            stagePartyHostSource(partySourceSelection, retainedPartySource, stream.streamLabel)
            return
        }
        if (willOpenInternally) {
            manualCandidateFacts = streamFacts
            manualPlaybackStarting = true
            if (loadingToken == null) {
                loadingToken = PlaybackLoadingController.open(
                    step = if (DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream)) {
                        PlaybackProgressStep.ResolvingLink
                    } else {
                        PlaybackProgressStep.StartingPlayback
                    },
                    artwork = launch.background ?: launch.poster,
                    logo = launch.logo,
                    title = launch.title,
                    attempt = autoPickAttempt,
                    facts = streamFacts,
                )
            }
        }
        if (DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream)) {
            if (resolvingDebridStream) return
            streamRouteScope.launch {
                resolvingDebridStream = true
                val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(
                    stream = stream,
                    season = launch.seasonNumber,
                    episode = launch.episodeNumber,
                )
                resolvingDebridStream = false
                when (resolved) {
                    is DirectDebridPlayableResult.Success -> openSelectedStream(
                        stream = resolved.stream,
                        resolvedResumePositionMs = resolvedResumePositionMs,
                        resolvedResumeProgressFraction = resolvedResumeProgressFraction,
                        forceExternal = forceExternal,
                        forceInternal = forceInternal,
                        safePartySource = retainedPartySource,
                    )
                    else -> {
                        manualPlaybackStarting = false
                        manualCandidateFacts = null
                        loadingToken?.let(PlaybackLoadingController::close)
                        loadingToken = null
                        resolved.toastMessage()?.let { NuvioToastController.show(it) }
                        if (resolved == DirectDebridPlayableResult.Stale) {
                            StreamsRepository.reload(
                                type = launch.type,
                                videoId = effectiveVideoId,
                                parentMetaId = launch.parentMetaId,
                                season = launch.seasonNumber,
                                episode = launch.episodeNumber,
                                manualSelection = streamManualSelection,
                            )
                        }
                    }
                }
            }
            return
        }
        if (stream.needsLocalDebridResolve && stream.p2pInfoHash != null) {
            requestOrOpenP2pStream(
                stream = stream,
                resolvedResumePositionMs = resolvedResumePositionMs,
                resolvedResumeProgressFraction = resolvedResumeProgressFraction,
                forceExternal = forceExternal,
                forceInternal = forceInternal,
                isAutoPlay = false,
            )
            return
        }
        if (stream.shouldOpenExternally) {
            manualPlaybackStarting = false
            manualCandidateFacts = null
            loadingToken?.let(PlaybackLoadingController::close)
            loadingToken = null
            val opened = stream.externalOpenUrl?.let { url -> openExternalStreamUrl(url) } == true
            if (opened) {
                StreamsRepository.cancelLoading()
            }
            return
        }
        val sourceUrl = stream.playableDirectUrl ?: return
        val playerLaunch = PlayerLaunch(
            profileId = launch.profileId,
            title = launch.title,
            sourceUrl = sourceUrl,
            sourceHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request),
            sourceResponseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
            externalSubtitles = stream.externalSubtitles,
            streamType = stream.streamType,
            logo = launch.logo,
            poster = launch.poster,
            background = launch.background,
            seasonNumber = launch.seasonNumber,
            episodeNumber = launch.episodeNumber,
            episodeTitle = launch.episodeTitle,
            episodeThumbnail = launch.episodeThumbnail,
            streamTitle = stream.streamLabel,
            streamSubtitle = stream.streamSubtitle,
            bingeGroup = stream.behaviorHints.bingeGroup,
            pauseDescription = pauseDescription,
            providerName = stream.addonName,
            providerAddonId = stream.addonId,
            contentType = launch.type,
            videoId = effectiveVideoId,
            parentMetaId = launch.parentMetaId ?: effectiveVideoId,
            parentMetaType = launch.parentMetaType ?: launch.type,
            initialPositionMs = resolvedResumePositionMs ?: 0L,
            initialProgressFraction = resolvedResumeProgressFraction,
            contentLanguage = requestedContentLanguage,
            sourceFacts = playbackCandidates.firstOrNull { it.stream === stream }?.facts
                ?: SourceFactsExtractor.extract(stream),
            playbackAttempt = autoPickAttempt,
            expectedRuntimeMinutes = launch.runtimeMinutes,
            partySourceDescriptor = retainedPartySource,
        )

        if (!forceInternal && (forceExternal || playerSettings.externalPlayerEnabled)) {
            manualPlaybackStarting = false
            manualCandidateFacts = null
            loadingToken?.let(PlaybackLoadingController::close)
            loadingToken = null
            streamRouteScope.launch {
                lastHandedOffFacts = playerLaunch.sourceFacts
                playbackHandedOff = true
                if (openExternalPlayback(playerLaunch) != ExternalPlaybackOutcome.Opened) {
                    lastHandedOffFacts = null
                    playbackHandedOff = false
                }
                StreamsRepository.cancelLoading()
            }
            return
        }

        val launchId = PlayerLaunchStore.put(playerLaunch)
        StreamsRepository.cancelLoading()
        // The flag's own comment says "set at *every* exit to playback" and this
        // exit did not set it. That mattered for the Streamlined sticky-pin path,
        // which reaches the player through here and leaves StreamRoute on the
        // stack: coming back, nothing knew playback had ever been handed off, so
        // the opaque surface kept painting over a list nobody could see.
        lastHandedOffFacts = playerLaunch.sourceFacts
        playbackHandedOff = true
        loadingToken?.let(PlaybackLoadingController::handOff)
        navController.navigate(
            PlayerRoute(launchId = launchId, title = playerLaunch.title)
        )
    }

    /**
     * Arms the passive network measurement with what this source really costs.
     *
     * Confirmed only after a minute of unstarved playback, in
     * `observePlaybackForNetworkEstimate` - starting a stream proves nothing.
     */
    fun armNetworkObservation(stream: StreamItem) {
        val candidate = playbackCandidates.firstOrNull { it.stream === stream }
            ?: return
        val mbps = PlaybackQualityOptions.bitrateMbps(candidate, playbackSelectionContext)
            ?: return NetworkQualityRepository.cancelPlaybackObservation()
        NetworkQualityRepository.notePlaybackBitrate(
            mbps = mbps,
            providerId = candidate.facts.debridService ?: candidate.facts.providerId,
        )
    }

    /**
     * Starts the automatic path on a chosen quality row. Streamlined supplies the
     * user's current answer; Instant supplies a fresh connection-based answer.
     */
    fun startAutoSelectedPlayback(option: PlaybackQualityOption) {
        when (
            val result = PlaybackSourceSelector.select(
                option = option,
                context = playbackSelectionContext,
            )
        ) {
            is PlaybackSelectionResult.Play -> {
                qualitySheetDismissed = true
                autoPlaybackStarting = true
                armNetworkObservation(result.stream)
                // `select` has already ranked the whole row and handed back
                // everything behind the winner. Throwing those away is what made
                // one "not cached" answer the end of the road in Streamlined,
                // while Instant - seeding the very same chain - stepped past it.
                // Seeding rather than opening also puts the auto-play effect in
                // charge, so resolve failures, P2P and the
                // attempt counter all behave identically in both modes.
                //
                // Capped by `playbackChain`, because the overlay tells the user
                // "Attempt 2 of 3" and that has to be true. The whole row was
                // being seeded here, so a deep bucket ground through nine
                // candidates while the counter sat coerced at its maximum - a
                // progress figure that stops moving is a hang wearing a number.
                // `PlayerNextEpisodeAutoPlay` already took the same budget and
                // its comment claimed the two paths agreed; now they do.
                val canonicalChain = playbackChain(result.stream, result.fallbacks)
                streamLog.i {
                    canonicalChain.mapIndexed { index, stream ->
                        val candidate = playbackCandidates.firstOrNull { it.stream === stream }
                        val facts = candidate?.facts ?: SourceFactsExtractor.extract(stream)
                        val preferences = playbackSelectionContext.rankingPreferences
                        "#${index + 1}{resolution=${facts.resolution.qualityLabel.ifBlank { "unknown" }} " +
                            "range=${facts.dynamicRange.sorted()} language=${SourceRanking.languageScore(facts, preferences)} " +
                            "media=${SourceRanking.mediaScore(facts, preferences)} cached=${facts.isDebridReady} " +
                            "size=${facts.sizeBytes ?: -1L} release=${facts.releaseQuality ?: "unknown"} " +
                            "provider=${facts.debridService ?: facts.providerName ?: stream.addonName}}"
                    }.joinToString(prefix = "canonical failure chain ", separator = " -> ")
                }
                StreamsRepository.seedAutoPlayCandidates(canonicalChain)
            }
            is PlaybackSelectionResult.AskUncached -> {
                pendingUncachedStream = result.stream
            }
            is PlaybackSelectionResult.NeedsManual ->
                giveUpToSourceList(result.reason, path = "no_selectable_candidate")
        }
    }

    /**
     * Starts an uncached debrid source the user accepted, with a chain behind it.
     *
     * It used to call `openSelectedStream` directly, which made this the one
     * Streamlined start with no fallbacks at all - and it is the start most
     * likely to need them, because the provider has already said it does not
     * hold this file. Seeding puts it on the same auto-play path as every
     * other tier pick.
     */
    fun startUncachedStream(uncached: StreamItem) {
        qualitySheetDismissed = true
        autoPlaybackStarting = true
        armNetworkObservation(uncached)
        // A chain of one, deliberately. Everything else in this row failed the
        // same cache gate, so there is no better candidate to fall to - what
        // this buys is the *path*: the progress overlay while the mint runs,
        // the provider's reason named if it refuses, and the source list
        // uncovered afterwards instead of a toast under an opaque surface.
        StreamsRepository.seedAutoPlayCandidates(listOf(uncached))
    }

    fun selectStreamlinedOption(option: PlaybackQualityOption) {
        pendingStreamlinedOptionId = option.id
        streamlinedSelectionPending = true
    }

    // The connection figure on the sheet belongs to the host that would serve
    // the stream, not to the line in the abstract: on debrid the throughput is
    // the provider's. Scoped to what Best available would open, which is the
    // card the estimate is read against most often.
    val qualityProbeTarget = remember(playbackQualityOptions, playbackSelectionContext) {
        playbackQualityOptions.firstOrNull()?.let { option ->
            PlaybackSourceSelector.probeTarget(option, playbackSelectionContext)
        }
    }
    // The flow is a change signal only - it carries whichever provider asked
    // last. `peek` is the read, and it is pure, which is what keeps a value
    // derived during composition from writing back into the flow driving it.
    val networkSignal by NetworkQualityRepository.uiState.collectAsStateWithLifecycle()
    val sheetNetworkQuality = remember(networkSignal, qualityProbeTarget?.providerId) {
        NetworkQualityRepository.peek(qualityProbeTarget?.providerId)
    }

    // **Launched into the sheet's own scope, not the effect's.** `qualityProbeTarget`
    // is derived from `playbackQualityOptions`, which is rebuilt every time an addon
    // answers - so keying a `LaunchedEffect` body on it cancelled the transfer part
    // way through on every new source, and the probe was killed by the very fetch it
    // was meant to run beside. Re-triggering is harmless here: `probe` refuses to
    // start a second one while the first is in flight, and this scope is cancelled
    // when the sheet leaves composition, which is the cancellation actually wanted.
    val probeScope = rememberCoroutineScope()
    // **The sheet's "is a number still coming?" signal, and it is not
    // `isProbing`.** That flow only goes true once the transfer starts, which
    // waits on the option list, so between the sheet opening and the probe
    // launching the sheet had nothing to say and printed the stored figure -
    // then said "Checking", then replaced it. Three states for one question.
    //
    // Answered instead by `plan`, the same pure function the probe itself
    // obeys, so the header and the probe cannot disagree about whether a
    // measurement is happening.
    //
    // The nonce is bumped by the re-test tap, so a tap un-settles both answers in
    // the same frame and a late probe can say which ask it belongs to. There are
    // deliberately two answers: Instant may decide on the deadline, while the
    // quality sheet must keep withholding its figure until the probe really ends.
    var connectionRetestNonce by remember { mutableStateOf(0) }
    var connectionSettlement by remember { mutableStateOf(ConnectionProbeSettlement()) }
    val connectionSettlementState = connectionSettlement.stateFor(connectionRetestNonce)
    val connectionSettled = connectionSettlementState.isDecisionSettled
    val connectionFigureSettled = connectionSettlementState.isFigureSettled

    /**
     * Instant's metered question is on screen and unanswered.
     *
     * Hoisted here because **five** things need it and they have to agree.
     * Instant is the first thing in this route that waits on purpose, and every
     * mechanism below was written on the assumption that a wait here is a stall:
     * the probe would spend the user's data before they agreed to it, the
     * selection timeout would give up on someone still reading the dialog, the
     * stall backstop would give up 1.5 s in, the selection effect must not
     * choose without an answer, and the surface decides what is drawn behind the
     * dialog. Five copies of one condition is how four of them end up agreeing
     * and the fifth does not.
     *
     * Read from the remembered `peek`, never from
     * `NetworkQualityRepository.current()` - that one publishes to the flow it
     * is being derived from.
     */
    val awaitingMeteredAnswer =
        !isPartyResolution &&
            playbackRouteDecision is PlaybackRouteDecision.AutoPick &&
            sheetNetworkQuality.isMetered &&
            meteredChoice == null
    LaunchedEffect(
        playbackRouteDecision,
        qualityProbeTarget,
        qualitySheetDismissed,
        connectionRetestNonce,
        // Instant only, and it must re-run when the answer lands - see the
        // metered guard in the body.
        awaitingMeteredAnswer,
    ) {
        // Both automatic modes need this. Streamlined shows the figure and
        // withholds it while it moves; Instant never shows it and *decides* on
        // it, which is the stricter of the two requirements.
        val needsConnectionFigure = !isPartyResolution && (
            playbackRouteDecision is PlaybackRouteDecision.ShowQualitySheet ||
                playbackRouteDecision is PlaybackRouteDecision.AutoPick
            )
        if (!needsConnectionFigure) return@LaunchedEffect
        if (qualitySheetDismissed) return@LaunchedEffect
        // This effect re-runs often - `qualityProbeTarget` is rebuilt every
        // time an addon answers - and once this sheet has committed to a figure
        // it never goes back to "Checking": a later provider-scoped probe is a
        // refinement, and the sheet's own latch absorbs it. Only a re-test
        // re-opens the question.
        //
        // There is deliberately **no `isProbing` guard here.** `probe` waits for
        // an in-flight measurement rather than refusing, so re-entering is safe
        // and every caller settles when a real answer exists. Skipping the
        // launch instead would strand the sheet: nothing else would ever write
        // this ask's nonce, and it would sit on "Checking" for good.
        if (connectionSettled) return@LaunchedEffect
        // ⚠ **Instant asks before it spends.** A metered probe is capped at
        // `METERED_MAX_BYTES` - 16 MiB - and Instant's card promises it asks once
        // before using mobile data. Measuring first and asking afterwards would
        // spend that allowance to decide a question the user had not yet agreed
        // to be asked. Returning without settling is deliberate: the nonce stays
        // unwritten, so the selection effect keeps waiting and this re-runs when
        // the answer arrives.
        //
        // `awaitingMeteredAnswer` is route-gated rather than mode-gated because
        // Streamlined has no such dialog: its `meteredChoice` is null forever,
        // and waiting on it would strand its sheet on "Checking your connection…".
        if (awaitingMeteredAnswer) return@LaunchedEffect
        val platform = NetworkQualityRepository.peek(qualityProbeTarget?.providerId)
        val inputs = NetworkStrengthProbe.Inputs(
            isMetered = platform.isMetered,
            isOffline = platform.connectionType == NetworkConnectionType.OFFLINE,
            // Both keys, because only `plan` knows which one this probe would
            // end up writing to - a source with a direct URL refreshes its own
            // host, anything falling back to the CDN refreshes the line.
            sourceEstimateAgeMs = qualityProbeTarget?.providerId?.let {
                NetworkQualityRepository.estimateAgeMs(it)
            },
            lineEstimateAgeMs = NetworkQualityRepository.estimateAgeMs(null),
            sourceUrl = qualityProbeTarget?.url,
            sourceHeaders = qualityProbeTarget?.headers.orEmpty(),
            providerId = qualityProbeTarget?.providerId,
            // The **most expensive** row, not the first. The first is Best
            // available, whose `requiredMbps` is null by construction, so this
            // read null on every probe the app has ever run and the early exit
            // was unreachable code.
            requiredMbps = playbackQualityOptions.mapNotNull { it.requiredMbps }.maxOrNull(),
            force = connectionRetestNonce > 0,
        )
        val askedNonce = connectionRetestNonce
        if (NetworkStrengthProbe.plan(inputs) == null) {
            // Nothing is going to be measured - the stored estimate is still
            // fresh, or there is no connection. It *is* the answer, so show it
            // now rather than sitting on "Checking your connection…" waiting for
            // a probe that will never run.
            connectionSettlement = connectionSettlement.onProbeFinished(askedNonce)
            return@LaunchedEffect
        }
        probeScope.launch {
            // Settles on success and on failure alike. The sheet is withholding
            // a figure until this lands, so an early return that skipped it
            // would leave the surface stuck on "Checking".
            NetworkStrengthProbe.probe(inputs)
            connectionSettlement = connectionSettlement.onProbeFinished(askedNonce)
        }
        // ⚠ **The deadline has to be raced here, not awaited inside the probe.**
        // `probe` wraps its transfer in `withTimeoutOrNull`, but the Android and
        // desktop readers block in `InputStream.read`, and coroutine cancellation
        // cannot interrupt that - a host that answers its headers and then goes
        // silent holds the probe for the client's own 60 s read timeout. This
        // coroutine only ever suspends in `delay`, so it always fires. It settles
        // Instant's *decision*, but not the sheet's figure: publishing the stored
        // guess here is what made it change under the reader when the probe landed
        // later. The rows remain usable while the header honestly says Checking.
        probeScope.launch {
            delay(NetworkStrengthProbe.PROBE_DEADLINE_MS)
            connectionSettlement = connectionSettlement.onDeadline(askedNonce)
        }
    }

    // Nothing else bounds this wait. `isStreamlinedSelectionReady` closes every
    // *known* way the settle signal fails to arrive, but it is still a wait on
    // a condition owned by addons and plugins the app does not control: a
    // scraper that neither answers nor errors leaves `isAnyLoading` true for
    // good, and the sheet sits with every row disabled and only dismiss
    // working. The user tapped a quality and nothing happened, with nothing on
    // screen to say why. Cancelled automatically when the selection resolves,
    // because the effect is keyed on the flag it is waiting out.
    //
    // Instant is armed the moment its route decision exists, because it has no
    // tap to wait for - the whole mode is a wait the user did not ask for. Its
    // flag is deliberately **not** `streamlinedSelectionPending`: the effect
    // that flag drives falls back to `playbackQualityOptions.firstOrNull()`,
    // which would silently play Best available on a mode that is supposed to
    // pick from the connection.
    // ⚠ **Not armed while the metered question is unanswered.** This clock is for
    // an addon that never answers, not for a user reading a dialog - and someone
    // deciding whether to spend their data can easily take longer than twenty
    // seconds. Firing there would toast "sources timed out" at a question the app
    // itself had asked.
    val automaticSelectionPending = !isPartyResolution && !awaitingMeteredAnswer && (
        streamlinedSelectionPending ||
        (
            playbackRouteDecision is PlaybackRouteDecision.AutoPick &&
                !instantSelectionHandled &&
                !qualitySheetDismissed &&
                !manualSourceListRequested
            )
    )
    LaunchedEffect(automaticSelectionPending) {
        if (!automaticSelectionPending) return@LaunchedEffect
        delay(STREAMLINED_SELECTION_TIMEOUT_MS)
        streamlinedSelectionPending = false
        pendingStreamlinedOptionId = null
        giveUpToSourceList(getString(Res.string.playback_sources_timed_out), path = "selection_timeout")
    }

    LaunchedEffect(
        streamlinedSelectionPending,
        pendingStreamlinedOptionId,
        playbackQualityOptions,
        streamsUiState.requestToken,
        streamsUiState.isAnyLoading,
        streamsUiState.emptyStateReason,
    ) {
        if (!streamlinedSelectionPending) return@LaunchedEffect
        if (
            !com.nuvio.app.features.playback.isStreamlinedSelectionReady(
                requestToken = streamsUiState.requestToken,
                expectedRequestToken = expectedStreamsRequestToken,
                isAnyLoading = streamsUiState.isAnyLoading,
                candidateCount = playbackCandidates.size,
                hasTerminalEmptyState = streamsUiState.emptyStateReason != null,
                hasStreams = streamsUiState.groups.any { it.streams.isNotEmpty() },
            )
        ) return@LaunchedEffect

        // Every exit past this point clears the flag. It gates the sheet's
        // spinner, so a path that returns while it is still set leaves the
        // user looking at disabled rows with nothing left to complete them.
        streamlinedSelectionPending = false
        val optionId = pendingStreamlinedOptionId
        val option = playbackQualityOptions.firstOrNull { it.id == optionId }
        // The tapped row can genuinely vanish - a refetch may return a
        // different catalogue - so fall back to Best available rather than
        // stranding a user who has already chosen.
            ?: playbackQualityOptions.firstOrNull()
        if (option == null) {
            giveUpToSourceList(path = "streamlined_no_option")
            return@LaunchedEffect
        }
        startAutoSelectedPlayback(option)
    }

    /**
     * Instant: the same path, with the quality answered by the connection.
     *
     * **Instant is not a third selection mechanism.** It is the effect above
     * with `stickyAffordable` in place of `rememberedOption`, handing off through
     * the same `startAutoSelectedPlayback`. That is the whole mode, and it is
     * deliberate: two pickers scoring the same candidates and disagreeing is the
     * finicky behaviour `PLAYBACK_MODES_PLAN.md` names as the concentrated risk,
     * and a third ordering is what got Instant withdrawn the first time.
     *
     * ⚠ **It waits for the connection measurement to settle.** Instant *decides*
     * on that figure where the sheet merely prints it, so choosing early means
     * choosing from `defaultMbps`' unmeasured 50 Mbps Wi-Fi guess - which is
     * precisely "picks a tier from a line nobody measured and has no ceiling to
     * hold it", the sentence this mode was withdrawn under. The wait is bounded
     * by the deadline raced above and is usually invisible, because the probe
     * runs beside the fetch and the fetch is slower.
     *
     * ⚠ **`instantSelectionHandled` is latched and must never be reset.** It
     * guards the seed, so clearing it on a retry re-seeds the chain back to
     * candidate 1 and the failure loops instead of advancing.
     */
    LaunchedEffect(
        playbackRouteDecision,
        instantSelectionHandled,
        meteredChoice,
        connectionSettled,
        playbackQualityOptions,
        streamsUiState.requestToken,
        streamsUiState.isAnyLoading,
        streamsUiState.emptyStateReason,
    ) {
        if (playbackRouteDecision !is PlaybackRouteDecision.AutoPick) return@LaunchedEffect
        if (isPartyResolution) return@LaunchedEffect
        if (instantSelectionHandled) return@LaunchedEffect
        if (qualitySheetDismissed || manualSourceListRequested) return@LaunchedEffect
        if (
            !com.nuvio.app.features.playback.isStreamlinedSelectionReady(
                requestToken = streamsUiState.requestToken,
                expectedRequestToken = expectedStreamsRequestToken,
                isAnyLoading = streamsUiState.isAnyLoading,
                candidateCount = playbackCandidates.size,
                hasTerminalEmptyState = streamsUiState.emptyStateReason != null,
                hasStreams = streamsUiState.groups.any { it.streams.isNotEmpty() },
            )
        ) return@LaunchedEffect
        if (!connectionSettled) return@LaunchedEffect
        // The metered question is still on screen. Answering it is what makes
        // `maxHeight` knowable, so there is nothing to decide until it lands.
        // Unreachable in practice - the probe holds `connectionSettled` false
        // for the same reason - and kept because that ordering is the probe's to
        // change, not this effect's.
        if (awaitingMeteredAnswer) return@LaunchedEffect

        instantSelectionHandled = true
        // A resolution ceiling, applied to the derived rows exactly as it used to
        // be applied to the preset list. "High quality" is not "ignore the line" -
        // it only removes this cap, and the pick still comes from the estimate.
        val meteredCapHeight = playerSettings.playbackMeteredCapHeight
            .takeIf {
                sheetNetworkQuality.isMetered &&
                    meteredChoice == MeteredPlaybackChoice.CAPPED
            }
        // Biased towards the resolution this show already got in this sitting.
        // A tie-break, never a ceiling or a floor: it is dropped the moment the
        // estimate stops carrying it and never invents a row this episode has no
        // release for. It is what answers "Instant feels like spinning a roulette
        // wheel on what resolution I'm going to get" - two taps that look
        // identical must not land on different resolutions.
        val option = PlaybackQualityOptions.stickyAffordable(
            options = playbackQualityOptions,
            pinnedHeight = null,
            estimatedMbps = sheetNetworkQuality.estimatedMbps,
            maxHeight = meteredCapHeight,
            context = playbackSelectionContext,
        )
        if (option == null) {
            giveUpToSourceList(path = "instant_no_option")
            return@LaunchedEffect
        }
        startAutoSelectedPlayback(option)
    }

    // Instant and Streamlined must never leave the user reading the source list
    // while the app is still deciding. The overlay covers it - it cannot replace
    // it, because StreamsScreen owns the fetch this is reporting on.
    // Instant's metered question counts as a user answer for the same reason the
    // other two do - the stall backstop gives up on an overlay that is waiting
    // for nothing, and a question on screen is something. It does *not* uncover
    // the list the way the other two do, because Instant's own surface rule
    // outranks `awaitingUserAnswer`: the dialog is drawn over the progress
    // overlay rather than over the source list the mode exists to avoid, and
    // dismissing it answers Data saver rather than dropping the play.
    val awaitingUserAnswer = pendingUncachedStream != null ||
        pendingP2pStreamOpen != null ||
        awaitingMeteredAnswer
    val streamSurface = streamRouteSurface(
        StreamRouteSurfaceInputs(
            isPartyResolution = isPartyResolution,
            isClassic = playerSettings.playbackMode == PlaybackMode.CLASSIC,
            isManualLaunch = launch.manualSelection || launch.downloadIntent,
            manualSourceListRequested = manualSourceListRequested,
            hasNavigatedAway = playbackHandedOff,
            uncoverReason = uncoverPath,
            isQualitySheetRoute =
                playbackRouteDecision is PlaybackRouteDecision.ShowQualitySheet,
            qualitySheetDismissed = qualitySheetDismissed,
            isAutoPickRoute =
                playbackRouteDecision is PlaybackRouteDecision.AutoPick,
            isAutoPlaybackStarting = autoPlaybackStarting,
            awaitingUserAnswer = awaitingUserAnswer,
        ),
    )

    // The backstop, for the dead ends nobody has found yet.
    //
    // Three separate paths reached "overlay up, nothing left to run" in this
    // release alone, and each was fixed at its own call site. This catches the
    // shape rather than the instance: the progress overlay is showing, no
    // candidate is armed, nothing is resolving, and the fetch has settled - so
    // whatever the overlay claims to be waiting for is not coming.
    //
    // The grace period is what makes it safe. Every legitimate state here is
    // transient - a tier pick seeds its chain in the same frame it raises the
    // flag - so anything still true after it has genuinely stopped moving.
    LaunchedEffect(
        streamSurface,
        streamsUiState.autoPlayStream,
        streamsUiState.isAnyLoading,
        streamsUiState.requestToken,
        resolvingDebridStream,
        // Keyed, not just read: a dialog raised *during* the grace period has to
        // restart the effect, or the backstop it was meant to suppress has
        // already been scheduled and still fires underneath it.
        awaitingUserAnswer,
        // Same reason, for the probe: it settles mid-grace on most plays.
        playbackRouteDecision,
        connectionSettled,
    ) {
        // Both covered surfaces, not just the overlay. `HandOff` is supposed
        // to be a navigation in flight and nothing else, so resting on it once
        // the fetch has settled is the original blank screen returning by some
        // route nobody has found yet.
        if (
            streamSurface != StreamRouteSurface.ProgressOverlay &&
            streamSurface != StreamRouteSurface.HandOff
        ) return@LaunchedEffect
        if (streamsUiState.autoPlayStream != null) return@LaunchedEffect
        if (resolvingDebridStream) return@LaunchedEffect
        // A question on screen is not a dead end. Under a remembered band the
        // surface stays on ProgressOverlay while `AskUncached` waits (rule 3
        // outranks rule 5), so without this the backstop tears the dialog down
        // mid-question and toasts "no matching source" at someone who is being
        // asked to choose. The non-remembered path resolves to QualitySheet and
        // was already skipped by the surface check above.
        if (awaitingUserAnswer) return@LaunchedEffect
        // ⚠ **The user leaving is not a dead end.** Backing out of the player retires the chain
        // and, in Streamlined, pops this route - but `leaveToDetails` can no-op, and then this
        // route is left exactly in the shape the backstop is looking for. It fired six seconds
        // after a Back in the 2026-09-05 session and logged `uncover=dead_end_backstop` against a
        // playback the user had already abandoned, which is a false entry in the one log that
        // exists to explain why the source list appeared.
        if (userAbandonedPlayback) return@LaunchedEffect
        // ⚠ **Instant is waiting on the connection probe here, and that wait
        // routinely outlasts the 1.5 s grace.** Every other state this backstop
        // sees is transient by construction; this one is a deliberate pause with
        // a named owner and its own deadline (`PROBE_DEADLINE_MS`, raced above),
        // so it is not a dead end. Without this the backstop drops Instant onto
        // the Classic source list after a second and a half - on exactly the
        // unmeasured connections the wait exists to measure, which is the one
        // outcome the mode is for avoiding.
        if (
            playbackRouteDecision is PlaybackRouteDecision.AutoPick &&
            !connectionSettled
        ) return@LaunchedEffect
        if (
            streamsUiState.requestToken != expectedStreamsRequestToken ||
            streamsUiState.isAnyLoading
        ) return@LaunchedEffect
        delay(PLAYBACK_PROGRESS_STALL_GRACE_MS)
        giveUpToSourceList(path = "dead_end_backstop")
    }


    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawWithContent {
                drawContent()
                PlayerExitDiagnostics.recordT2("StreamDestination")
            },
    ) {
        StreamsScreen(
            type = launch.type,
            videoId = effectiveVideoId,
            parentMetaId = launch.parentMetaId ?: effectiveVideoId,
            parentMetaType = launch.parentMetaType ?: launch.type,
            title = launch.title,
            logo = launch.logo,
            poster = launch.poster,
            background = launch.background,
            seasonNumber = launch.seasonNumber,
            episodeNumber = launch.episodeNumber,
            episodeTitle = launch.episodeTitle,
            episodeThumbnail = launch.episodeThumbnail,
            resumePositionMs = launch.resumePositionMs,
            resumeProgressFraction = launch.resumeProgressFraction,
            manualSelection = streamManualSelection,
            startFromBeginning = launch.startFromBeginning,
            downloadOnSelect = launch.downloadIntent,
            showRepositoryAutoPlayOverlay = playerSettings.playbackMode == PlaybackMode.CLASSIC,
            onStreamSelected = { stream, resolvedResumePositionMs, resolvedResumeProgressFraction ->
                openSelectedStream(
                    stream = stream,
                    resolvedResumePositionMs = resolvedResumePositionMs,
                    resolvedResumeProgressFraction = resolvedResumeProgressFraction,
                    forceExternal = false,
                    forceInternal = false,
                )
            },
            onStreamActionOpen = { stream, openExternally, resolvedResumePositionMs, resolvedResumeProgressFraction ->
                openSelectedStream(
                    stream = stream,
                    resolvedResumePositionMs = resolvedResumePositionMs,
                    resolvedResumeProgressFraction = resolvedResumeProgressFraction,
                    forceExternal = openExternally,
                    forceInternal = !openExternally,
                )
            },
            onBack = onBack,
            modifier = Modifier.fillMaxSize(),
        )
        // StreamsScreen owns the fetch, but its list is an implementation
        // detail in Streamlined and Instant. Paint an opaque hand-off surface
        // from the first frame; the sheet and `PlaybackLoadingHost` render above
        // it, while every bail-out removes it - see `streamRouteSurface`.
        //
        // It consumes pointer input on purpose. Without that it painted over a
        // source list that was still fully tappable underneath, so the one
        // state where this surface should never be resting was also one where
        // an invisible row could be started by a stray tap.
        if (streamSurface != StreamRouteSurface.SourceList) {
            val handOffArtwork = launch.background ?: launch.poster
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // ⚠ **Opaque only when there is no artwork to cover with.** The backdrop below
                    // is full-bleed and opaque once loaded, so it hides the list on its own - but
                    // `AsyncImage` needs a frame to resolve even from the memory cache, and while
                    // this Box painted its own flat fill underneath, that frame *was* a grey one.
                    // Captured on screen at 14:21:55.136, one frame, immediately after a source was
                    // chosen and before the loading session opened. Transparent, the same list the
                    // user was already looking at stays visible for that frame instead, and the
                    // backdrop covers it the moment it arrives - continuous either way.
                    .then(
                        if (handOffArtwork.isNullOrBlank()) {
                            Modifier.background(MaterialTheme.nuvio.colors.background)
                        } else {
                            Modifier
                        },
                    )
                    .nuvioConsumePointerEvents(),
            ) {
                // ⚠ **The same picture as everything else on this path, and it must stay that
                // way.** This surface goes up the instant a source is chosen and comes down when
                // `PlaybackLoadingHost` opens its session - a gap of a frame or more. While it was
                // a flat fill, that gap was a grey flash between a source list showing the artwork
                // and a loading screen showing the artwork, measured on screen at the head of the
                // hand-over. Same artwork, same crop, same scrim, so the loading screen arriving
                // over it changes nothing visible. Same fallback as the session it precedes.
                PlaybackLoadingBackdrop(artwork = handOffArtwork)
            }
        }
        if (streamSurface == StreamRouteSurface.QualitySheet) {
            PlaybackQualitySheet(
                options = playbackQualityOptions,
                // Only "the figures are still moving" belongs here. Selection
                // pending is passed separately: folding it in would replace
                // the grid with a skeleton the instant the user tapped a card
                // - and on the uncached-debrid path, which leaves the sheet
                // composed under the consent dialog, they would watch it
                // happen.
                isLoading = streamsUiState.requestToken != expectedStreamsRequestToken ||
                    streamsUiState.isAnyLoading,
                isSelecting = streamlinedSelectionPending,
                selectionContext = playbackSelectionContext,
                // Always a labelled figure once it has settled - a connection
                // that could not be measured still gets its meters, which is
                // what an earlier "hide until measured" rule took away. What is
                // withheld is only the figure that is *about to be replaced*,
                // for the seconds `isMeasuringConnection` covers.
                estimatedMbps = sheetNetworkQuality.estimatedMbps,
                isConnectionMeasured = sheetNetworkQuality.isMeasured,
                isConnectionStale = sheetNetworkQuality.confidence ==
                    NetworkEstimateConfidence.CACHED,
                isMeasuringConnection = !connectionFigureSettled,
                onOptionSelected = ::selectStreamlinedOption,
                onRetestConnection = { connectionRetestNonce += 1 },
                onChooseManually = {
                    streamlinedSelectionPending = false
                    pendingStreamlinedOptionId = null
                    giveUpToSourceList(reason = "", path = "quality_sheet_choose_manually")
                },
                onAdjustPreferences = { showPlaybackPreferences = true },
                onDismiss = {
                    // Backing out of the sheet means "not now", so it returns to
                    // details rather than uncovering the Classic source list the
                    // user chose Streamlined to avoid - behind a bottom sheet a
                    // stray swipe would otherwise land there.
                    streamlinedSelectionPending = false
                    pendingStreamlinedOptionId = null
                    qualitySheetDismissed = true
                    leaveToDetails()
                },
            )
        }
        if (showPlaybackPreferences) {
            PlaybackPreferencesDialog(
                languageStrictness = playerSettings.playbackLanguageStrictness,
                dynamicRangePolicy = playerSettings.playbackDynamicRangePolicy,
                qualityCeilingMbps = playerSettings.playbackQualityCeilingMbps,
                // Straight through the real setters, so the grid behind this
                // rebuilds from the same state the next play will use. A
                // preview-only copy would be a second source of truth for a
                // decision the user is watching the result of.
                onLanguageStrictnessChange =
                    PlayerSettingsRepository::setPlaybackLanguageStrictness,
                onDynamicRangePolicyChange =
                    PlayerSettingsRepository::setPlaybackDynamicRangePolicy,
                onQualityCeilingChange =
                    PlayerSettingsRepository::setPlaybackQualityCeilingMbps,
                onDismiss = { showPlaybackPreferences = false },
            )
        }
        if (awaitingMeteredAnswer) {
            // Asked once per network per session, because Instant's promise is
            // that it does not ask - a question repeated every episode is the
            // mode failing at the one thing it is for.
            //
            // Dismissing counts as Data saver. The safe answer on someone's data
            // plan is the cheap one, and unlike every other dialog in this route
            // there is no "and now nothing happens" outcome to fall back to: the
            // play continues either way.
            AlertDialog(
                onDismissRequest = {
                    NetworkQualityRepository.rememberMeteredChoice(
                        MeteredPlaybackChoice.CAPPED,
                    )
                    meteredChoice = MeteredPlaybackChoice.CAPPED
                },
                title = { Text(stringResource(Res.string.playback_metered_title)) },
                text = {
                    Text(
                        stringResource(
                            Res.string.playback_metered_description,
                            "${playerSettings.playbackMeteredCapHeight}p",
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            NetworkQualityRepository.rememberMeteredChoice(
                                MeteredPlaybackChoice.CAPPED,
                            )
                            meteredChoice = MeteredPlaybackChoice.CAPPED
                        },
                    ) { Text(stringResource(Res.string.playback_metered_capped)) }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            NetworkQualityRepository.rememberMeteredChoice(
                                MeteredPlaybackChoice.FULL_QUALITY,
                            )
                            meteredChoice = MeteredPlaybackChoice.FULL_QUALITY
                        },
                    ) { Text(stringResource(Res.string.playback_metered_full)) }
                },
            )
        }
        pendingUncachedStream?.let { uncached ->
            AlertDialog(
                onDismissRequest = { pendingUncachedStream = null },
                title = { Text(stringResource(Res.string.playback_uncached_title)) },
                text = { Text(stringResource(Res.string.playback_uncached_description)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingUncachedStream = null
                            startUncachedStream(uncached)
                        },
                    ) { Text(stringResource(Res.string.playback_uncached_start)) }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            pendingUncachedStream = null
                            giveUpToSourceList(reason = "", path = "uncached_stream_choose_manually")
                        },
                    ) { Text(stringResource(Res.string.playback_quality_manual)) }
                },
            )
        }
        pendingP2pStreamOpen?.let { pending ->
            P2pConsentDialog(
                onEnableP2p = {
                    P2pSettingsRepository.setP2pEnabled(true)
                    pendingP2pStreamOpen = null
                    openP2pStream(
                        stream = pending.stream,
                        resolvedResumePositionMs = pending.resumePositionMs,
                        resolvedResumeProgressFraction = pending.resumeProgressFraction,
                        replaceStreamRoute = pending.isAutoPlay,
                    )
                },
                onDismiss = {
                    if (pending.isAutoPlay) {
                        StreamsRepository.skipAutoPlayStream(pending.stream)
                        // The chain is retired here, not advanced: declining
                        // P2P is a decision about every torrent candidate, not
                        // about this one. So there is nothing left to run, and
                        // without uncovering the list the progress overlay sat
                        // on "Starting playback" for a playback that had just
                        // been called off.
                        StreamsRepository.consumeAutoPlay()
                        giveUpToSourceList(path = "p2p_consent_declined")
                    }
                    pendingP2pStreamOpen = null
                },
            )
        }
        val showLoadingSurface = manualPlaybackStarting ||
            streamSurface == StreamRouteSurface.ProgressOverlay ||
            (streamSurface == StreamRouteSurface.HandOff && playbackHandedOff)
        // ⚠ **This route no longer draws the loading screen; it publishes to it.**
        // `PlaybackLoadingHost` renders it above `NavDisplay` - see the block comment there for
        // why. Everything below is the same state that used to be passed as arguments, sent to
        // the same screen through `PlaybackLoadingController` instead, so that the screen
        // survives the hand-off to the player and the pop back from a failover without being
        // destroyed and re-created in between.
        val loadingStep = if (playbackHandedOff) {
            PlaybackProgressStep.StartingPlayback
        } else if (manualPlaybackStarting) {
            if (resolvingDebridStream) PlaybackProgressStep.ResolvingLink else PlaybackProgressStep.StartingPlayback
        } else {
            PlaybackProgress.step(
                PlaybackProgressInputs(
                    isLoadingSources = streamsUiState.requestToken != expectedStreamsRequestToken ||
                        streamsUiState.isAnyLoading,
                    hasChosenSource = autoPlaybackStarting,
                    isResolvingLink = resolvingDebridStream,
                    attempt = autoPickAttempt,
                    // Instant only. The remembered-band path is also covered
                    // by this overlay and does not need an estimate - its
                    // band is exact - so it must not claim to be waiting for
                    // one.
                    isMeasuringConnection = !connectionSettled &&
                        playbackRouteDecision is PlaybackRouteDecision.AutoPick,
                ),
            )
        }
        val loadingFacts = if (manualPlaybackStarting) manualCandidateFacts ?: activeCandidateFacts else activeCandidateFacts
        val preferredAudioLanguageTarget = resolvePreferredAudioLanguageTargets(
            preferredAudioLanguage = playerSettings.preferredAudioLanguage,
            secondaryPreferredAudioLanguage = playerSettings.secondaryPreferredAudioLanguage,
            deviceLanguages = DeviceLanguagePreferences.preferredLanguageCodes(),
            contentOriginalLanguage = requestedContentLanguage,
        ).firstOrNull() ?: playerSettings.preferredAudioLanguage
        val loadingState = PlaybackLoadingState(
            step = loadingStep,
            attempt = autoPickAttempt,
            // The structured facts for whatever is actually armed, so the band names the release
            // the user is about to receive - and so the same figures survive the hand-off into
            // the player, which renders them from the same `SourceFacts` rather than from its own
            // re-parse of the display title.
            facts = loadingFacts,
            failure = autoPickFailure,
            contentLanguage = requestedContentLanguage,
            preferredAudioLanguage = preferredAudioLanguageTarget,
        )

        LaunchedEffect(showLoadingSurface) {
            if (showLoadingSurface) {
                if (loadingToken == null) {
                    loadingToken = PlaybackLoadingController.open(
                        step = loadingStep,
                        // Identical to what `PlayerDestination` hands the player, so the backdrop
                        // and the logo do not re-decode at the route change. Diverging these is
                        // the one way to make the hand-off visible again without changing
                        // anything else.
                        //
                        // ⚠ **`?: poster` is load-bearing, and its absence was the grey flash.**
                        // Every other surface on this path falls back to the poster when a title
                        // has no backdrop - `StreamsTabletLayout` uses `background ?: poster`, and
                        // the player's overlay uses `startingEpisode?.thumbnail ?: background ?:
                        // poster`. This one did not, so on a title with no `background` the
                        // loading screen alone had nothing to draw and fell through to
                        // `nuvio.colors.background`: a flat #0D0D0D screen for its whole life,
                        // between a source list showing the poster and a player overlay showing
                        // the poster. Captured on screen at 1560 ms, ending exactly when the JCEF
                        // overlay painted - which is why it read as "grey, then the loading
                        // screen", and why chasing the canvas and the native container never
                        // touched it. The comment above already claimed this matched the player.
                        artwork = launch.background ?: launch.poster,
                        logo = launch.logo,
                        title = launch.title,
                        attempt = autoPickAttempt,
                        facts = loadingFacts,
                        contentLanguage = requestedContentLanguage,
                        preferredAudioLanguage = preferredAudioLanguageTarget,
                    )
                }
            } else {
                loadingToken?.let(PlaybackLoadingController::close)
                loadingToken = null
                manualPlaybackStarting = false
                manualCandidateFacts = null
            }
        }

        // ⚠ **The "or the user leaves" arm of the session's lifetime, which was never written.**
        //
        // `PlaybackLoadingController.close` is reachable from exactly one place - the `else` above -
        // and an effect does not run its `else` when the composition is torn down. So popping this
        // route (`leaveToDetails`, the back button, Escape) cancelled that effect and left the
        // session **running**. `PlaybackLoadingHost` draws above `NavDisplay` and stops for nothing,
        // so the abandoned surface kept painting over the details screen: the details screen
        // appeared for one frame, the loading screen repainted over it, and there was no way out,
        // because nothing left alive could ever close it. That is the "press Escape, see the other
        // backdrop, then it flips back and you are stuck" report - and `PlaybackLoadingController`'s
        // own contract already claimed this case was handled ("...or when the user leaves").
        //
        // A handed-off session is deliberately exempt: from that point the player owns it and
        // `closeAfterHandOff` ends it, which is the entire reason the surface outlives this route.
        DisposableEffect(Unit) {
            onDispose {
                // ⚠ **A hand-off is the one exit that leaves the chain alone.** Every other way
                // this route can vanish - Escape, the back button, the window closing, a deep
                // link landing elsewhere - is the user leaving, and an automatic play that
                // outlives them is a play nobody asked for. `leaveToDetails` covers the exits
                // this route owns; this covers the ones it does not, which is why the abandon
                // is here as well as there rather than only at the call sites.
                if (PlaybackLoadingController.session?.handedOff == true) return@onDispose
                loadingToken?.let { token -> PlaybackLoadingController.close(token) }
                StreamsRepository.abandonAutoPlay()
                StreamsRepository.cancelLoading()
            }
        }

        // Revisions, including the attempt counter. A running session is never re-opened, so a
        // failover reaches the user as the stage line changing under a screen that has not moved.
        LaunchedEffect(loadingToken, loadingState) {
            loadingToken?.let { token -> PlaybackLoadingController.revise(token, loadingState) }
        }

        // The player now owns the playback. Nothing visible changes - that is the point - but the
        // session records it, so a player being torn down cannot close a session a *newer* play
        // has already opened. See `PlaybackLoadingController.closeAfterHandOff`.
        LaunchedEffect(loadingToken, playbackHandedOff) {
            if (playbackHandedOff) {
                loadingToken?.let(PlaybackLoadingController::handOff)
            }
        }

        // The surface's two buttons. Registered rather than passed because the screen is no
        // longer this route's child - see `PlaybackLoadingController.actions`.
        LaunchedEffect(loadingToken) {
            val token = loadingToken ?: return@LaunchedEffect
            PlaybackLoadingController.registerActions(
                token = token,
                actions = PlaybackLoadingActions(
                    onBack = {
                        if (manualPlaybackStarting) {
                            giveUpToSourceList(reason = "", path = "manual_escape")
                        } else {
                            leaveToDetails()
                        }
                    },
                    // The blank reason is the point: `giveUpToSourceList` toasts whatever it is
                    // given, and the user who just pressed this button already knows why they
                    // are looking at the list.
                    onChooseManually = {
                        giveUpToSourceList(reason = "", path = "manual_escape")
                    },
                ),
            )
        }

        if (resolvingDebridStream && !showLoadingSurface) {
            // Classic and every manual path keep the lighter scrim: the source
            // list behind it is what the user chose from and is worth keeping
            // visible.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.nuvio.colors.overlayScrim.copy(alpha = MaterialTheme.nuvio.opacity.overlayHeavy)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.nuvio.spacing.cardPadding),
                ) {
                    NuvioLoadingIndicator(color = MaterialTheme.nuvio.colors.playerControlsForeground)
                    Text(
                        text = stringResource(Res.string.streams_finding_source),
                        color = MaterialTheme.nuvio.colors.playerControlsForeground.copy(alpha = MaterialTheme.nuvio.opacity.overlayHeavy),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
