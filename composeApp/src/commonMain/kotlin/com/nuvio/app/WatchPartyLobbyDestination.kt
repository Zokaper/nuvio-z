package com.nuvio.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.touchlab.kermit.Logger
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.streams.PartyStreamLaunchContext
import com.nuvio.app.features.streams.PartyStreamLaunchPurpose
import com.nuvio.app.features.streams.StreamLaunch
import com.nuvio.app.features.streams.StreamLaunchStore
import com.nuvio.app.features.player.PlayerLaunchStore
import com.nuvio.app.features.watchparty.LobbyBackAction
import com.nuvio.app.features.watchparty.LobbyCloseReason
import com.nuvio.app.features.watchparty.WatchPartyLobbyScreen
import com.nuvio.app.features.watchparty.decideLobbyBack
import com.nuvio.app.features.watchparty.hydratePartyLaunchArtwork
import com.nuvio.app.features.watchparty.lobbyCloseReason
import com.nuvio.app.features.watchparty.partySourceKey
import com.nuvio.app.features.watchparty.PartySourceRealizer
import com.nuvio.app.features.watchparty.WatchPartyRepository
import com.nuvio.app.features.watchparty.WatchPartySessionCoordinator
import com.nuvio.app.features.watchparty.WatchPartyState
import com.nuvio.app.features.watchparty.WatchPartyStatus
import com.nuvio.app.navigation.AppRoute
import com.nuvio.app.navigation.DetailRoute
import com.nuvio.app.navigation.NuvioNavigator
import com.nuvio.app.navigation.PlayerRoute
import com.nuvio.app.navigation.StreamRoute
import com.nuvio.app.navigation.WatchPartyLobbyRoute
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val watchPartyLobbyDestinationLog = Logger.withTag("WatchPartyLobbyDestination")

/**
 * Navigation owner for the durable lobby.
 *
 * The route carries only an opaque membership target. Party state and source descriptors stay in
 * their process-scoped repositories, and a playback preparation route receives only the safe
 * descriptor plus party generations through [StreamLaunchStore].
 *
 * ⚠ **This route owns the lobby exit invariant** - see `WatchPartyLobbyExit.kt`. It is on the back
 * stack exactly as long as its party is: a system back asks to leave like the header arrow does,
 * and the route closes itself when the party it was showing is gone.
 */
@Composable
internal fun WatchPartyLobbyDestination(
    route: WatchPartyLobbyRoute,
    navController: NuvioNavigator,
    playbackProfileId: Int,
    onSystemBackHandlerChanged: (AppRoute, (() -> Unit)?) -> Unit,
) {
    val state by WatchPartyRepository.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showDepartureDialog by rememberSaveable(route) { mutableStateOf(false) }
    // The party this lobby has seen live. Saved, because the lobby leaves composition whenever the
    // source list or the player is on top of it, and has to still know what it was the room of when
    // it comes back - including to find that the party ended while it was covered.
    var boundPartyId by rememberSaveable(route) { mutableStateOf<String?>(null) }
    var closed by rememberSaveable(route) { mutableStateOf(false) }

    /** Answers whether the lobby is now gone. */
    fun closeLobby(reason: String): Boolean {
        if (closed) return true
        showDepartureDialog = false
        // Only while the lobby is actually on top. A lobby still composed under a push transition
        // is not, and latching `closed` there would stop it closing when it comes back - which it
        // does, by recomposing and running these effects again.
        closed = navController.popBackStack(route)
        watchPartyLobbyDestinationLog.i {
            "close target=${route.partyId?.take(8) ?: "invite"} reason=$reason popped=$closed"
        }
        return closed
    }

    // A system back (Escape on desktop) comes here instead of popping the route. Registered for as
    // long as the lobby is composed; `dispatchNavigationBack` fails closed in the gap before it is.
    val latestState by rememberUpdatedState(state)
    DisposableEffect(route) {
        onSystemBackHandlerChanged(route) {
            when (decideLobbyBack(route.partyId ?: boundPartyId, latestState.party)) {
                LobbyBackAction.RequestDeparture -> {
                    watchPartyLobbyDestinationLog.i { "system back -> departure question party=${latestState.party?.id?.take(8)}" }
                    showDepartureDialog = true
                }
                LobbyBackAction.Close -> closeLobby("system-back-no-live-party")
            }
        }
        onDispose { onSystemBackHandlerChanged(route, null) }
    }

    LaunchedEffect(route.partyId, route.inviteCode) {
        val held = WatchPartyRepository.uiState.value.party
        val heldMatches = held != null &&
            held.status != WatchPartyStatus.ended &&
            (route.partyId == null || held.id == route.partyId)

        watchPartyLobbyDestinationLog.i {
            "open target=${route.partyId?.take(8) ?: "invite"} held=${held?.id?.take(8)} matches=$heldMatches"
        }
        when {
            heldMatches -> WatchPartyRepository.refresh()
            // The party this route names already ended - the lobby was covered when it happened, or
            // the route is being restored. There is nothing to join; re-joining an ended party only
            // produces an error on a screen that should not exist.
            held != null && held.status == WatchPartyStatus.ended && held.id == route.partyId ->
                closeLobby("opened-on-ended-party")
            !route.partyId.isNullOrBlank() -> joinForThisLobby { WatchPartyRepository.join(partyId = route.partyId) }
            !route.inviteCode.isNullOrBlank() -> joinForThisLobby { WatchPartyRepository.join(inviteCode = route.inviteCode) }
            else -> closeLobby("no-target")
        }
    }

    LaunchedEffect(state.party?.id) {
        state.party?.takeIf { it.status != WatchPartyStatus.ended }?.id?.let(WatchPartySessionCoordinator::enterLobby)
    }

    // Bind to the party once it is live for this route, then close when it is gone.
    LaunchedEffect(state.party?.id, state.party?.status) {
        val held = state.party
        if (boundPartyId == null && held != null && held.status != WatchPartyStatus.ended &&
            (route.partyId == null || held.id == route.partyId)
        ) {
            boundPartyId = held.id
        }
        when (lobbyCloseReason(boundPartyId, held)) {
            null -> Unit
            LobbyCloseReason.Departed -> closeLobby("departed")
            LobbyCloseReason.PartyEnded -> {
                if (closeLobby("party-ended")) NuvioToastController.show("The Watch Together party has ended.")
            }
        }
    }

    suspend fun prepareSource(party: WatchPartyState, purpose: PartyStreamLaunchPurpose) {
        val target = party.sourceFingerprint.takeIf {
            purpose == PartyStreamLaunchPurpose.RESOLVE_PLAYBACK
        }
        if (purpose == PartyStreamLaunchPurpose.RESOLVE_PLAYBACK && target == null) return

        val key = target?.let { party.partySourceKey() }
        if (key != null) {
            PartySourceRealizer.reusable(key)?.let { retained ->
                val playerLaunch = retained.copy(
                    initialPositionMs = WatchPartyRepository.authoritativePositionMs(party),
                    initialProgressFraction = null,
                    // The retained StreamRoute was deliberately removed on lobby exit. This is a
                    // direct attachment, not a fresh failure chain owned by a source route.
                    autoPickedWithFailureChain = false,
                )
                val playerLaunchId = PlayerLaunchStore.put(playerLaunch)
                navController.navigate(PlayerRoute(playerLaunchId, playerLaunch.title))
                return
            }
        }

        val content = party.content
        // The party wire carries identity, not presentation - no logo, no background, no episode
        // thumbnail - so a party launch reached the loading screen with nothing to draw and fell
        // back to plain title text while ordinary playback showed the centred logo. Hydrated from
        // the same metadata cache ordinary playback reads, on this client, rather than widening
        // the backend contract to ship artwork URLs through it.
        val artwork = hydratePartyLaunchArtwork(content)
        // The hydration above can take seconds. A party that ended, or a lobby that closed, in that
        // window must not be followed by a source route that starts resolving for it.
        val live = WatchPartyRepository.uiState.value.party
        if (closed || live == null || live.id != party.id || live.status == WatchPartyStatus.ended) return
        val launchId = StreamLaunchStore.put(
            StreamLaunch(
                profileId = playbackProfileId,
                type = content.contentType,
                videoId = content.videoId,
                parentMetaId = content.contentId,
                parentMetaType = content.contentType,
                title = content.title,
                logo = artwork.logo,
                poster = artwork.poster,
                background = artwork.background,
                episodeThumbnail = artwork.episodeThumbnail,
                seasonNumber = content.season,
                episodeNumber = content.episode,
                episodeTitle = content.episodeTitle,
                resumePositionMs = WatchPartyRepository.authoritativePositionMs(party),
                // ⚠ **Not `purpose == SELECT_SOURCE`.** That read as "the host asked for the
                // source list", and `manualSelection` is the first thing `PlaybackModeRouter`
                // tests, so it overrode the host's playback mode outright: a Streamlined or
                // Instant host pressed "Choose a source" and got Classic's release list. The
                // host's mode is the product authority for *which question to ask*, and the
                // router already answers that correctly once nothing short-circuits it.
                // `StreamDestination` is what keeps the *answer* party-shaped, staging the
                // descriptor instead of opening a player whichever way the source was chosen.
                manualSelection = false,
                partyContext = PartyStreamLaunchContext(
                    partyId = party.id,
                    isHost = party.hostProfileId == state.activeProfileId,
                    contentGeneration = party.contentGeneration,
                    sourceGeneration = party.sourceGeneration,
                    targetFingerprint = target,
                    purpose = purpose,
                ),
            ),
        )
        navController.navigate(StreamRoute(launchId = launchId, title = content.title))
    }

    WatchPartyLobbyScreen(
        onBack = { closeLobby("departure-accepted") },
        showDepartureDialog = showDepartureDialog,
        onShowDepartureDialogChange = { showDepartureDialog = it },
        onOpenContent = { contentType, contentId, title ->
            navController.navigate(DetailRoute(type = contentType, id = contentId, title = title))
        },
        // Preparing a source now reads metadata before it builds the launch, so it suspends. The
        // screen's callback is not suspending and should not become one - this is a navigation
        // that happens to need a cache read first, not an operation the lobby waits on.
        onChooseSource = { party, purpose -> scope.launch { prepareSource(party, purpose) } },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * Joins on behalf of a lobby that may be dismissed while the join is on the wire.
 *
 * Closing a lobby that has not seen its party yet is allowed - there is nothing to leave - and it
 * cancels this effect. But a join whose RPC had already been accepted installs the membership
 * anyway, and that membership would then have no lobby: the orphan this route exists to prevent,
 * reached through the one door the back handler deliberately leaves open. Seen after the fact, it
 * is left again straight away.
 */
private suspend fun kotlinx.coroutines.CoroutineScope.joinForThisLobby(join: suspend () -> Result<Unit>) {
    val joined = join()
    if (!isActive && joined.isSuccess) {
        watchPartyLobbyDestinationLog.w { "join completed after the lobby closed - leaving the orphaned membership" }
        WatchPartySessionCoordinator.leave()
    }
}
