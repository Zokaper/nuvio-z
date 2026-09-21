package com.nuvio.app.features.watchparty

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.touchlab.kermit.Logger
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.core.ui.nuvioWindowClass
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.components.DetailCastSection
import com.nuvio.app.features.details.components.DetailRatingsRow
import com.nuvio.app.features.details.components.desktopSeasonCountLabel
import com.nuvio.app.features.details.components.desktopYearLabel
import com.nuvio.app.features.details.formatRuntimeForDisplay
import com.nuvio.app.features.profiles.parseHexColor
import com.nuvio.app.features.social.SocialProfileSummary
import com.nuvio.app.features.social.SocialRepository
import com.nuvio.app.features.streams.PartyStreamLaunchPurpose
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watching.application.WatchingState
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.watch_party_addon_differences
import nuvio.composeapp.generated.resources.watch_party_choose_source
import nuvio.composeapp.generated.resources.watch_party_resolve_source
import nuvio.composeapp.generated.resources.watch_party_source_explanation
import nuvio.composeapp.generated.resources.watch_party_title
import org.jetbrains.compose.resources.stringResource

private val lobbyLog = Logger.withTag("WatchPartyLobby")

/**
 * The lobby's amber.
 *
 * The scheme has no warning role, and `error` is the wrong thing to say about somebody who is
 * merely still looking for a source - it reads as a failure that nobody needs to act on.
 */
private val PartyWorkingColor = Color(0xFFE0A458)

/**
 * The lobby's green, fixed rather than themed.
 *
 * Readiness is semantic, and `colorScheme.primary` follows the user's theme picker: under Crimson
 * a red "ready" sits beside a pink `colorScheme.error` "no source found" and the pair says nothing.
 * The accent still carries emphasis - the stage rail, the invite tile - where no state is meant.
 */
private val PartyReadyColor = Color(0xFF6FD08C)

/**
 * Where the title stops sharing the window with the party.
 *
 * Below this the rail would squeeze the participant tiles into a single column, so the title folds
 * back into [PartyHero] and the lobby is the one scrolling column it used to be everywhere.
 */
private val PartyTwoPaneMinWidth = 1180.dp

/** Narrow enough that a full party - eight tiles - stays on one row beside the title rail. */
private val PartyTileWidth = 156.dp

/**
 * The details screen blurs an unwatched still by 18.dp over a card roughly twice this wide.
 * Scaled down with the still so the effect is the same one, not a lighter version of it that
 * leaves a recognisable frame behind.
 */
private val EpisodeStillBlur = 12.dp

@Composable
fun WatchPartyLobbyScreen(
    onBack: () -> Unit,
    /**
     * Whether the leave / end question is on screen. Hoisted so the route can raise it for a system
     * back (Escape) - see `dispatchNavigationBack` and `WatchPartyLobbyExit.kt`.
     */
    showDepartureDialog: Boolean,
    onShowDepartureDialogChange: (Boolean) -> Unit,
    onOpenContent: (contentType: String, contentId: String, title: String) -> Unit = { _, _, _ -> },
    onChooseSource: (WatchPartyState, PartyStreamLaunchPurpose) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val state by WatchPartyRepository.uiState.collectAsStateWithLifecycle()
    val syncState by WatchPartySync.state.collectAsStateWithLifecycle()
    val socialState by SocialRepository.uiState.collectAsStateWithLifecycle()
    val joinHandoff by PartyJoinHandoff.current.collectAsStateWithLifecycle()
    val addonsState by AddonRepository.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val addonSignature = remember(addonsState.addons) { watchPartyAddonSignature(addonsState.addons) }

    LaunchedEffect(state.party?.id, addonSignature) {
        if (state.party != null) WatchPartyRepository.publishAddonSignature(addonSignature)
    }

    /**
     * The one way out of the lobby, and it is the same one for everybody.
     *
     * The host used to walk itself to the player from the button handler while guests were pushed
     * here by the snapshot, so the two halves of "we all start together" were two different pieces
     * of code that had to be kept saying the same thing - and they stopped. Publishing a source is
     * now the *only* thing that starts a party, so this effect is the only thing that has to be
     * right: the host publishes on Start, the snapshot reaches everyone, and everyone leaves for
     * the player off the same signal.
     *
     * `PartySourceRealizer.claimAutomaticLaunch` is process-owned rather than held here: this
     * composition is destroyed when the player goes on top of it, so a latch held locally would be
     * gone by the time somebody backed out - and this effect would throw them straight back in.
     */
    LaunchedEffect(state.party?.partySourceKey()) {
        val party = state.party ?: return@LaunchedEffect
        // An ended party launches nobody. The route closes itself on it; this frame must not race it.
        if (party.status == WatchPartyStatus.ended) return@LaunchedEffect
        val key = party.partySourceKey() ?: return@LaunchedEffect
        if (
            party.effectiveStage() !in setOf(
                WatchPartyStage.resolving_sources,
                WatchPartyStage.ready_to_launch,
                WatchPartyStage.playing,
            )
        ) return@LaunchedEffect
        if (!PartySourceRealizer.claimAutomaticLaunch(key)) return@LaunchedEffect
        lobbyLog.i { "launching party=${party.id.shortId()} generation=${party.sourceGeneration}" }
        onChooseSource(party, PartyStreamLaunchPurpose.RESOLVE_PLAYBACK)
    }

    val party = state.party
    val isHost = party != null && party.hostProfileId == state.activeProfileId
    val presentation = PartyPresentationProjector.project(
        party = party,
        selfProfileId = state.activeProfileId,
        health = state.health,
        realtime = syncState,
        partyNowMs = WatchPartySync.partyNowMs(),
    )
    val requestDeparture = { onShowDepartureDialogChange(true) }

    WatchPartyLobbyFrame(poster = party?.content?.poster, modifier = modifier) {
        if (party == null) {
            PartyLobbyOpening(
                onBack = onBack,
                errorMessage = state.errorMessage,
                isWorking = state.isWorking,
                handoff = joinHandoff,
            )
            return@WatchPartyLobbyFrame
        }

        val hostSignature = party.members
            .firstOrNull { it.profileId == party.hostProfileId }?.addonSignature.orEmpty()
        val addonMismatches = party.members.filter { member ->
            member.connected && member.profileId != party.hostProfileId &&
                comparePartyAddonSignatures(hostSignature, member.addonSignature).differs
        }
        val addonNotice = if (addonMismatches.isEmpty()) {
            null
        } else {
            stringResource(Res.string.watch_party_addon_differences) + " — " +
                "${addonMismatches.size} ${if (addonMismatches.size == 1) "person has" else "people have"} " +
                "a different set of stream addons. You can continue; an alternate source may be needed."
        }

        // "I'm ready" used to sit in the action bar and mark a member ready from the lobby,
        // where nobody has resolved anything yet - it reported a source that did not exist
        // and was the one thing that could defeat the host's own readiness gate. Readiness
        // is now reported by the player, once a stream is actually open.
        //
        // The host's half of the lobby is two buttons because it is two decisions. Picking
        // a release and committing everyone to it in the same press is what made the source
        // list a trapdoor: there was no moment between the two in which the host could look
        // at who had actually turned up.
        val chosenSource = state.stagedHostSource ?: party.sourceFingerprint
        val onChoose: () -> Unit = {
            scope.launch {
                // Announces "the host is picking" and bumps the generation, so a source
                // chosen now cannot be published against the previous round's number.
                WatchPartyRepository.beginSourceSelection(addonSignature).onSuccess {
                    WatchPartyRepository.uiState.value.party?.let {
                        onChooseSource(it, PartyStreamLaunchPurpose.SELECT_SOURCE)
                    }
                }
            }
        }
        val onStart: () -> Unit = {
            when (
                partyPlaybackEntryAction(
                    authoritativeSourcePublished = party.sourceFingerprint != null,
                    stagedHostSourceAvailable = state.stagedHostSource != null,
                    reusableLocalLaunchAvailable = false,
                    viewerIsHost = isHost,
                )
            ) {
                PartyPlaybackEntryAction.PublishStagedHostSource -> {
                    state.stagedHostSource?.let { fingerprint ->
                        scope.launch {
                            // Choosing the source already began this generation. Publish it
                            // exactly once against that generation; beginning again here was
                            // the lifecycle reset that sent every guest back to waiting.
                            WatchPartyRepository.selectSource(
                                fingerprint = fingerprint,
                                expectedSourceGeneration = party.sourceGeneration,
                            )
                        }
                    }
                }
                PartyPlaybackEntryAction.ReuseLocalLaunch,
                PartyPlaybackEntryAction.ResolveAuthoritativeSource ->
                    onChooseSource(party, PartyStreamLaunchPurpose.RESOLVE_PLAYBACK)
                PartyPlaybackEntryAction.AwaitHostSource -> Unit
            }
        }
        val invitableFriends = socialState.friends.filterNot { friend ->
            party.members.any { it.profileId == friend.profileId }
        }

        PartyLobbyContent(
            model = PartyLobbyModel(
                party = party,
                viewerProfileId = state.activeProfileId,
                isHost = isHost,
                presentation = presentation,
                sync = syncState,
                inviteCode = state.inviteCode,
                errorMessage = state.errorMessage,
                joinHandoff = joinHandoff?.takeIf { it.partyId == party.id },
                addonNotice = addonNotice,
                hostSourceStaged = state.stagedHostSource != null,
                hasSource = chosenSource != null,
                sourceLabel = state.stagedHostSourceLabel,
                waitForEveryone = state.waitForEveryone,
                pauseForAwayUsers = state.pauseForAwayUsers,
                invitableFriends = invitableFriends,
            ),
            actions = PartyLobbyActions(
                onRequestDeparture = requestDeparture,
                onChoose = onChoose,
                onStart = onStart,
                onLeave = requestDeparture,
                onInvite = { profileId -> scope.launch { WatchPartyRepository.invite(profileId) } },
                onControlMode = { mode -> scope.launch { WatchPartyRepository.setControlMode(mode) } },
                onWaitForEveryone = { WatchPartyRepository.setWaitForEveryone(it) },
                onPauseForAwayUsers = { WatchPartyRepository.setPauseForAwayUsers(it) },
            ),
        )
    }

    if (party != null && party.status != WatchPartyStatus.ended && showDepartureDialog) {
        fun depart(mode: PartyDepartureMode) {
            onShowDepartureDialogChange(false)
            scope.launch {
                // ⚠ **Only on success.** This used to leave the lobby whatever the RPC said, and
                // `depart` deliberately keeps local state when the server refuses so it can be
                // retried - so a failed departure removed the one screen that could retry it and
                // left the party running with nothing on screen. The same orphan Escape produced.
                WatchPartyRepository.depart(mode)
                    .onSuccess { onBack() }
                    .onFailure { NuvioToastController.show("Couldn't leave the party. Check your connection and try again.") }
            }
        }
        AlertDialog(
            onDismissRequest = { onShowDepartureDialogChange(false) },
            title = { Text(if (isHost) "Leave Watch Together?" else "Leave party?") },
            text = {
                Text(
                    if (isHost) "Transfer hosting to the next person, or end the party for everyone."
                    else "You will leave this Watch Together party.",
                )
            },
            confirmButton = {
                TextButton(onClick = { depart(PartyDepartureMode.LEAVE_AND_TRANSFER) }) {
                    Text(if (isHost) "Leave and transfer" else "Leave party")
                }
            },
            dismissButton = {
                Row {
                    if (isHost) {
                        TextButton(onClick = { depart(PartyDepartureMode.END_PARTY) }) { Text("End party") }
                    }
                    TextButton(onClick = { onShowDepartureDialogChange(false) }) { Text("Cancel") }
                }
            },
        )
    }
}

/**
 * The lobby's backdrop and its constraint stack, shared by the screen and by the render harness.
 *
 * ⚠ **The insets are consumed here, once, above every layout branch.**
 *
 * This route is pushed onto the navigator's own back stack rather than hosted in the tab shell, and
 * the root `Scaffold` consumes nothing by design (`MainTabsDestination`,
 * `contentWindowInsets = WindowInsets(0)`), so nothing upstream was ever going to supply these: the
 * back arrow sat at a flat 20dp from the physical top of the display, under the status bar and
 * under any cutout.
 *
 * Applying them on the `BoxWithConstraints` rather than inside each branch is what makes it
 * impossible for the branches to disagree about the inset or to apply it twice - and it means
 * `maxWidth`/`maxHeight` inside [content] are the space this screen actually owns, which is what the
 * window class must be read from.
 *
 * [PartyLobbyBackdrop] stays *outside* it: the artwork is meant to run full-bleed behind the status
 * bar. Only the content is inset.
 */
@Composable
internal fun WatchPartyLobbyFrame(
    poster: String?,
    modifier: Modifier = Modifier,
    content: @Composable BoxWithConstraintsScope.() -> Unit,
) {
    // Hosted outside a Surface, so LocalContentColor falls back to black. Without this the whole
    // lobby - the invite code included - is black on a dark background.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Box(modifier.fillMaxSize()) {
            // The party is about one specific title, and this screen used to show a 92x132 poster on
            // the settings-screen background. The art is most of what makes it a lobby, not a form.
            PartyLobbyBackdrop(poster)
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                        ),
                    ),
                content = content,
            )
        }
    }
}

/**
 * Everything the lobby draws, already gathered.
 *
 * [WatchPartyLobbyScreen] reads the repositories and the sync singletons and folds them into this;
 * [PartyLobbyContent] only lays it out. The split exists so a render harness can compose the real
 * layout - every branch, under the real constraint stack - without a party, a socket or Supabase.
 * Nothing in here decides anything about the party.
 */
internal data class PartyLobbyModel(
    val party: WatchPartyState,
    val viewerProfileId: String?,
    val isHost: Boolean,
    val presentation: PartyPresentationState,
    val sync: WatchPartySyncState,
    val inviteCode: String?,
    val errorMessage: String?,
    /** Already filtered to this party. */
    val joinHandoff: PartyJoinHandoffInfo?,
    val addonNotice: String?,
    val hostSourceStaged: Boolean,
    val hasSource: Boolean,
    val sourceLabel: String?,
    val waitForEveryone: Boolean,
    val pauseForAwayUsers: Boolean,
    val invitableFriends: List<SocialProfileSummary>,
)

/** The lobby's callbacks, exactly as the screen defines them. Layout only rearranges who calls them. */
internal class PartyLobbyActions(
    /** The header's back arrow, which asks the leave / end question rather than leaving. */
    val onRequestDeparture: () -> Unit,
    val onChoose: () -> Unit,
    val onStart: () -> Unit,
    val onLeave: () -> Unit,
    val onInvite: (String) -> Unit,
    val onControlMode: (WatchPartyControlMode) -> Unit,
    val onWaitForEveryone: (Boolean) -> Unit,
    val onPauseForAwayUsers: (Boolean) -> Unit,
)

/**
 * The lobby, laid out for the window it was given.
 *
 * Four compositions, chosen from [nuvioWindowClass] rather than from a bare width:
 *
 * | Window | Composition |
 * | --- | --- |
 * | wide and not short (desktop) | two panes: the party, and the title rail |
 * | short and not phone-narrow (a landscape phone) | two regions over a pinned action strip |
 * | phone-narrow (a portrait phone) | one dense scrolling column over a pinned action bar |
 * | anything else (a tablet) | the original single column |
 *
 * ⚠ **The desktop and tablet branches are the code they were before the phone branches existed.**
 * Both phone branches sit below them and are reached only by windows those two never took - a
 * 1280dp desktop window cannot land in either - so the desktop rendering is unchanged by
 * construction rather than by care.
 *
 * The one boundary that moved is 900dp: the old `wide` flag put an 891dp landscape phone and a
 * 1000dp landscape tablet on opposite sides of a 9dp coin-flip despite their wildly different
 * heights. Short windows are now caught before it is read.
 */
@Composable
internal fun BoxWithConstraintsScope.PartyLobbyContent(
    model: PartyLobbyModel,
    actions: PartyLobbyActions,
    titleRail: @Composable (PartyContent, Modifier) -> Unit = { content, railModifier ->
        PartyTitleRail(content, railModifier)
    },
) {
    val windowClass = nuvioWindowClass()
    when {
        windowClass.isTwoPaneSurface -> PartyLobbyTwoPane(model, actions, maxWidth, titleRail)
        windowClass.isShortWide -> PartyLobbyShortLandscape(model, actions, maxWidth)
        windowClass.isCompactWidth -> PartyLobbyPhonePortrait(model, actions)
        else -> PartyLobbySingleColumn(model, actions, wide = maxWidth >= 900.dp)
    }
}

@Composable
private fun PartyLobbyTwoPane(
    model: PartyLobbyModel,
    actions: PartyLobbyActions,
    maxWidth: Dp,
    titleRail: @Composable (PartyContent, Modifier) -> Unit,
) {
    val party = model.party
    // A centred `widthIn(max = 1040.dp)` column read as a document with a dead margin down either
    // side of a desktop window, and still ran off the bottom. Given the width, the party takes the
    // left and the title takes a rail on the right: the two panes meet, so none of the window is
    // spent on margins, and nothing scrolls.
    //
    // A share of the window rather than a fixed rail: the left pane takes whatever is left, so the
    // two panes always meet.
    val railWidth = (maxWidth * 0.30f).coerceIn(400.dp, 560.dp)
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxHeight()
                .padding(start = 40.dp, end = 24.dp, top = 20.dp)
                .padding(bottom = nuvioSafeBottomPadding(extra = 8.dp)),
        ) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PartyLobbyHeader(actions.onRequestDeparture)
                model.joinHandoff?.let { PartyJoinHero(it) }
                model.errorMessage?.let { message ->
                    PartyNotice(message, MaterialTheme.colorScheme.error)
                }
                PartyStatusBand(
                    party = party,
                    inviteCode = model.inviteCode,
                    connection = model.presentation.connection,
                    sync = model.sync,
                    hostSourceStaged = model.hostSourceStaged,
                )
                PartyStageRail(party.effectiveStage())
                model.addonNotice?.let { PartyNotice(it, PartyWorkingColor) }
                PartyParticipants(
                    party = party,
                    viewerProfileId = model.viewerProfileId,
                    presentation = model.presentation,
                    invitableFriends = model.invitableFriends,
                    onInvite = actions.onInvite,
                )
                if (model.isHost) {
                    PartyHostSettings(
                        controlMode = party.controlMode,
                        onControlMode = actions.onControlMode,
                        waitForEveryone = model.waitForEveryone,
                        onWaitForEveryone = actions.onWaitForEveryone,
                        pauseForAwayUsers = model.pauseForAwayUsers,
                        onPauseForAwayUsers = actions.onPauseForAwayUsers,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            PartyActionBar(
                isHost = model.isHost,
                hasSource = model.hasSource,
                sourceLabel = model.sourceLabel,
                onChoose = actions.onChoose,
                onStart = actions.onStart,
                onLeave = actions.onLeave,
            )
        }
        titleRail(
            party.content,
            Modifier
                .width(railWidth)
                .fillMaxHeight()
                .padding(end = 40.dp, top = 20.dp)
                .padding(bottom = nuvioSafeBottomPadding(extra = 8.dp)),
        )
    }
}

/** The tablet composition: one scrolling column, as every window used to get. */
@Composable
private fun PartyLobbySingleColumn(
    model: PartyLobbyModel,
    actions: PartyLobbyActions,
    wide: Boolean,
) {
    val party = model.party
    LazyColumn(
        modifier = Modifier.fillMaxHeight().widthIn(max = 1040.dp),
        // 88dp was a literal that cleared nothing in particular. There is no bottom nav on this
        // route, so the overlay term is zero - but the system navigation bar is not, and this
        // screen never asked for it.
        contentPadding = PaddingValues(
            start = 24.dp,
            end = 24.dp,
            top = 20.dp,
            bottom = nuvioSafeBottomPadding(extra = 24.dp),
        ),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { PartyLobbyHeader(actions.onRequestDeparture) }
        model.joinHandoff?.let { info -> item { PartyJoinHero(info) } }

        model.errorMessage?.let { message ->
            item { PartyNotice(message, MaterialTheme.colorScheme.error) }
        }

        item {
            PartyHero(
                party = party,
                inviteCode = model.inviteCode,
                connection = model.presentation.connection,
                sync = model.sync,
                wide = wide,
                hostSourceStaged = model.hostSourceStaged,
            )
        }

        item { PartyStageRail(party.effectiveStage()) }

        model.addonNotice?.let { notice ->
            item { PartyNotice(notice, PartyWorkingColor) }
        }

        item {
            PartyParticipants(
                party = party,
                viewerProfileId = model.viewerProfileId,
                presentation = model.presentation,
                invitableFriends = model.invitableFriends,
                onInvite = actions.onInvite,
            )
        }

        if (model.isHost) {
            item {
                PartyHostSettings(
                    controlMode = party.controlMode,
                    onControlMode = actions.onControlMode,
                    waitForEveryone = model.waitForEveryone,
                    onWaitForEveryone = actions.onWaitForEveryone,
                    pauseForAwayUsers = model.pauseForAwayUsers,
                    onPauseForAwayUsers = actions.onPauseForAwayUsers,
                )
            }
        }

        item {
            PartyActionBar(
                isHost = model.isHost,
                hasSource = model.hasSource,
                sourceLabel = model.sourceLabel,
                onChoose = actions.onChoose,
                onStart = actions.onStart,
                onLeave = actions.onLeave,
            )
        }
    }
}

/**
 * A portrait phone: one dense column that scrolls, over an action bar that does not.
 *
 * The tablet column at 411dp was ~1,100dp of desktop-scaled content: a 156dp poster, a full-width
 * invite block inside the hero, a four-label stage rail with ~90dp per label, 156dp participant
 * tiles two to a row, and Leave as the last item of the list - so on a phone you scrolled to leave.
 * Every piece is still here and still does the same thing; it is arranged for the width.
 *
 * ⚠ **Fitting one screen is a target, not an invariant.** The ordinary party - a short title, two
 * or three people, no notice - fits a 914dp screen. A long title, a large font scale, an expanded
 * notice or a full roster scrolls, and nothing is shrunk, clipped or hidden to avoid it.
 */
@Composable
private fun PartyLobbyPhonePortrait(model: PartyLobbyModel, actions: PartyLobbyActions) {
    val party = model.party
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { PartyLobbyHeader(actions.onRequestDeparture, compact = true) }
            model.joinHandoff?.let { info -> item { PartyJoinHero(info) } }
            model.errorMessage?.let { message ->
                item { PartyCompactNotice(message, MaterialTheme.colorScheme.error, collapsible = false) }
            }
            item {
                PartyCompactHero(
                    party = party,
                    connection = model.presentation.connection,
                    sync = model.sync,
                    hostSourceStaged = model.hostSourceStaged,
                )
            }
            model.addonNotice?.let { notice ->
                item { PartyCompactNotice(notice, PartyWorkingColor, collapsible = true) }
            }
            model.inviteCode?.let { code -> item { PartyInviteCard(code) } }
            item {
                PartyParticipantRows(
                    party = party,
                    viewerProfileId = model.viewerProfileId,
                    presentation = model.presentation,
                    invitableFriends = model.invitableFriends,
                    onInvite = actions.onInvite,
                )
            }
            if (model.isHost) {
                item {
                    PartyHostSettingsCompact(
                        controlMode = party.controlMode,
                        onControlMode = actions.onControlMode,
                        waitForEveryone = model.waitForEveryone,
                        onWaitForEveryone = actions.onWaitForEveryone,
                        pauseForAwayUsers = model.pauseForAwayUsers,
                        onPauseForAwayUsers = actions.onPauseForAwayUsers,
                    )
                }
            }
        }
        PartyPinnedActionBar(model, actions, landscape = false)
    }
}

/**
 * A landscape phone: what you read on the left, what changes on the right, the actions under both.
 *
 * At 891 x 411dp the tablet column spent the whole viewport on the header and a hero whose invite
 * code had stretched to 891dp, and Leave was a scroll past the entire lobby. Here each region
 * scrolls on its own, the invite code is capped by its pane, and the action strip spans both and
 * never moves.
 */
@Composable
private fun PartyLobbyShortLandscape(
    model: PartyLobbyModel,
    actions: PartyLobbyActions,
    maxWidth: Dp,
) {
    val party = model.party
    val leftWidth = (maxWidth * 0.42f).coerceIn(300.dp, 440.dp)
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                Modifier.width(leftWidth).fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PartyLobbyHeader(actions.onRequestDeparture, compact = true)
                model.joinHandoff?.let { PartyJoinHero(it) }
                model.errorMessage?.let { message ->
                    PartyCompactNotice(message, MaterialTheme.colorScheme.error, collapsible = false)
                }
                PartyCompactHero(
                    party = party,
                    connection = model.presentation.connection,
                    sync = model.sync,
                    hostSourceStaged = model.hostSourceStaged,
                    posterWidth = 56.dp,
                )
                model.inviteCode?.let { PartyInviteCard(it) }
                model.addonNotice?.let { PartyCompactNotice(it, PartyWorkingColor, collapsible = true) }
            }
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 8.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PartyParticipantRows(
                    party = party,
                    viewerProfileId = model.viewerProfileId,
                    presentation = model.presentation,
                    invitableFriends = model.invitableFriends,
                    onInvite = actions.onInvite,
                )
                if (model.isHost) {
                    PartyHostSettingsCompact(
                        controlMode = party.controlMode,
                        onControlMode = actions.onControlMode,
                        waitForEveryone = model.waitForEveryone,
                        onWaitForEveryone = actions.onWaitForEveryone,
                        pauseForAwayUsers = model.pauseForAwayUsers,
                        onPauseForAwayUsers = actions.onPauseForAwayUsers,
                    )
                }
            }
        }
        PartyPinnedActionBar(model, actions, landscape = true)
    }
}

/**
 * The poster, the title and the party's state in one card, at phone scale.
 *
 * The desktop hero's 104 x 156 poster competed with the backdrop that is already that poster, and
 * cost ~70dp a phone does not have. The stage rail lives in here too, as a footer: it is the same
 * question the headline answers - where the party has got to - so it belongs beside it.
 */
@Composable
private fun PartyCompactHero(
    party: WatchPartyState,
    connection: PartyConnectionState,
    sync: WatchPartySyncState,
    hostSourceStaged: Boolean,
    posterWidth: Dp = 64.dp,
) {
    PartyPanel(padding = 14.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            party.content.poster?.let { poster ->
                NuvioAsyncImage(
                    model = poster,
                    contentDescription = null,
                    modifier = Modifier.width(posterWidth).aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(NuvioTokens.Radius.compactCard))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    party.content.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                party.content.episode?.let { episode ->
                    Text(
                        "S${party.content.season ?: 1} E$episode" +
                            party.content.episodeTitle?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    party.stageHeadline(hostSourceStaged),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                PartySyncLine(connection, sync, party.members.size)
            }
        }
        Box(
            Modifier.fillMaxWidth().padding(vertical = 2.dp).height(1.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        )
        PartyStageDots(party.effectiveStage())
    }
}

/**
 * [PartyStageRail] at phone width: four dots and connectors, and only the current step named.
 *
 * Four `weight(1f)` labels at ~90dp each were unreadable, and three of them are always either done
 * or not yet meaningful. Same input, same colours, one ~20dp row.
 */
@Composable
private fun PartyStageDots(stage: WatchPartyStage) {
    val reached = stage.railIndex()
    Row(verticalAlignment = Alignment.CenterVertically) {
        WatchPartyStageRail.forEachIndexed { index, _ ->
            val done = index <= reached
            val color by animateColorAsState(
                // `outline` - the desktop rail's undone colour - vanishes on a phone card; the steps
                // still to come have to be countable at a glance or the dots say nothing.
                if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                label = "party-stage-dot-$index",
            )
            if (index > 0) {
                Box(Modifier.width(14.dp).height(2.dp).clip(CircleShape).background(color))
            }
            Box(
                Modifier.size(if (index == reached) 10.dp else 7.dp).clip(CircleShape).background(color),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            WatchPartyStageRail[reached.coerceIn(0, WatchPartyStageRail.lastIndex)].railLabel(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Text(
            "  ·  step ${reached + 1} of ${WatchPartyStageRail.size}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The invite code as its own compact card, rather than as a full-width footer of the hero.
 *
 * Tap-to-copy and the 1.6s "Copied" state are [PartyInviteCode]'s, unchanged.
 */
@Composable
private fun PartyInviteCard(code: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_600)
            copied = false
        }
    }
    val accent = MaterialTheme.colorScheme.primary
    // On a 320dp split screen the code and a labelled Copy pill do not fit together, and it is the
    // pill's label that goes, never a character of the code: an ellipsized invite code is a wrong one.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val narrow = maxWidth < 340.dp
        Surface(
            modifier = Modifier.fillMaxWidth().clickable {
                clipboard.setText(AnnotatedString(code))
                copied = true
            },
            shape = RoundedCornerShape(NuvioTokens.Radius.xl),
            color = accent.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
        ) {
            Row(
                Modifier.padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "INVITE CODE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        code,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = if (narrow) 1.sp else 2.sp,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                Row(
                    Modifier.clip(RoundedCornerShape(NuvioTokens.Radius.chip))
                        .background(accent.copy(alpha = if (copied) 0.22f else 0.14f))
                        .padding(horizontal = if (narrow) 10.dp else 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                        contentDescription = if (copied) "Copied" else "Copy invite code",
                        modifier = Modifier.size(16.dp),
                        tint = if (copied) accent else MaterialTheme.colorScheme.onSurface,
                    )
                    if (!narrow) {
                        Text(
                            if (copied) "Copied" else "Copy",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (copied) accent else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * [PartyNotice] at phone density: an icon, one line, and a tap to read the rest.
 *
 * [collapsible] is for advice (the addon differences), which is worth a line until somebody asks.
 * An error is never folded - it is the one thing on the screen that must be read in full.
 */
@Composable
private fun PartyCompactNotice(message: String, accent: Color, collapsible: Boolean) {
    var expanded by remember(message) { mutableStateOf(false) }
    val folded = collapsible && !expanded
    Surface(
        modifier = Modifier.fillMaxWidth()
            .then(if (collapsible) Modifier.clickable { expanded = !expanded } else Modifier),
        shape = RoundedCornerShape(NuvioTokens.Radius.lg),
        // Grounded on the card surface: a bare 12% tint over the poster backdrop left red text on
        // pale grey, which is the one notice that must not be hard to read.
        color = accent.copy(alpha = 0.14f).compositeOver(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = if (folded) Alignment.CenterVertically else Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                if (collapsible) Icons.Rounded.WarningAmber else Icons.Rounded.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = accent,
            )
            Text(
                message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = accent,
                maxLines = if (folded) 1 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
            if (collapsible) {
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Show less" else "Show more",
                    modifier = Modifier.size(18.dp),
                    tint = accent,
                )
            }
        }
    }
}

/**
 * The roster as rows, for a phone.
 *
 * A 156dp tile with a 46dp avatar spent ~120dp of height per two people and centred a long name
 * under the avatar where it had the least room. A row gives the name the flexible middle, keeps the
 * status pill at its natural width on the right, and holds eight people without a grid. The pill,
 * the avatar, the working ring and the dimming are [PartyParticipantTile]'s, rearranged.
 */
@Composable
private fun PartyParticipantRows(
    party: WatchPartyState,
    viewerProfileId: String?,
    presentation: PartyPresentationState,
    invitableFriends: List<SocialProfileSummary>,
    onInvite: (String) -> Unit,
) {
    var inviting by remember { mutableStateOf(false) }
    val canInvite = invitableFriends.isNotEmpty() && party.members.size < WatchPartyMaxParticipants
    PartyPanel(padding = 14.dp, spacing = 4.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("People", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(
                "${party.members.size}/$WatchPartyMaxParticipants",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${party.readyCount()} of ${party.members.count { it.connected }} ready",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        party.members.forEach { member ->
            PartyParticipantRow(
                member = member,
                isHost = member.profileId == party.hostProfileId,
                viewerProfileId = viewerProfileId,
                status = presentation.members.getValue(member.profileId),
            )
        }
        if (canInvite) {
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(NuvioTokens.Radius.lg))
                    .clickable { inviting = !inviting }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(32.dp).clip(CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.PersonAdd,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    if (inviting) "Close" else "Invite a friend",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    if (inviting) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (inviting && canInvite) {
            PartyInviteFriendChips(invitableFriends, onInvite)
        }
    }
}

@Composable
private fun PartyParticipantRow(
    member: WatchPartyParticipant,
    isHost: Boolean,
    viewerProfileId: String?,
    status: PartyMemberPresentation,
) {
    val tone = status.tone
    val dimmed = tone == PartyReadyTone.Offline || tone == PartyReadyTone.Away
    val detail = when {
        member.readyError != null -> member.readyError
        !dimmed && member.sourceMatch == PartySourceMatch.alternate -> "different source"
        else -> null
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).alpha(if (dimmed) 0.55f else 1f).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
            if (tone == PartyReadyTone.Working || tone == PartyReadyTone.Buffering || tone == PartyReadyTone.Reconnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(38.dp),
                    color = PartyWorkingColor,
                    strokeWidth = 2.dp,
                    trackColor = Color.Transparent,
                )
            }
            PartyAvatar(
                name = member.displayName(viewerProfileId),
                avatarUrl = member.profile?.avatarUrl,
                colorHex = member.profile?.avatarColorHex,
                size = 32.dp,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    member.displayName(viewerProfileId),
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isHost) PartyHostChip()
            }
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (member.readyError != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.widthIn(max = 150.dp)) { PartyStatusPill(tone, status.label) }
    }
}

@Composable
private fun PartyHostChip() {
    Row(
        Modifier.clip(RoundedCornerShape(NuvioTokens.Radius.chip))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            Icons.Rounded.Star,
            contentDescription = null,
            modifier = Modifier.size(11.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            "Host",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}

/**
 * [PartyHostSettings] at phone width: each switch beside its own explaining line.
 *
 * Same three controls, same callbacks. The desktop form puts the switch on the chips' line and its
 * sentence underneath both, which on a phone left the switch a line away from what it explains.
 */
@Composable
private fun PartyHostSettingsCompact(
    controlMode: WatchPartyControlMode,
    onControlMode: (WatchPartyControlMode) -> Unit,
    waitForEveryone: Boolean,
    onWaitForEveryone: (Boolean) -> Unit,
    pauseForAwayUsers: Boolean,
    onPauseForAwayUsers: (Boolean) -> Unit,
) {
    PartyPanel(padding = 14.dp, spacing = 10.dp) {
        Text(
            "HOST SETTINGS",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(
                selected = controlMode == WatchPartyControlMode.host_only,
                onClick = { onControlMode(WatchPartyControlMode.host_only) },
                label = { Text("Host controls") },
            )
            FilterChip(
                selected = controlMode == WatchPartyControlMode.collaborative,
                onClick = { onControlMode(WatchPartyControlMode.collaborative) },
                label = { Text("Collaborative") },
            )
        }
        PartySwitchRow(
            label = "Wait for everyone",
            explanation = if (waitForEveryone) {
                "Playback pauses for anyone whose stream stalls, and starts again together."
            } else {
                "Playback carries on when someone's stream stalls; they catch up on their own."
            },
            checked = waitForEveryone,
            onCheckedChange = onWaitForEveryone,
        )
        PartySwitchRow(
            label = "Pause for away users",
            explanation = if (pauseForAwayUsers) {
                "Playback waits while someone has the app in the background, and starts again together."
            } else {
                "Playback carries on when someone steps away; they catch up when they come back."
            },
            checked = pauseForAwayUsers,
            onCheckedChange = onPauseForAwayUsers,
        )
    }
}

@Composable
private fun PartySwitchRow(
    label: String,
    explanation: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * [PartyActionBar]'s buttons, pinned under a phone layout instead of scrolled to.
 *
 * Same three callbacks and the same host sentence. "Change source" moves beside that sentence as a
 * text button - it is a correction to the source the sentence names, not a third peer of Start and
 * Leave - which is what lets the two buttons that matter sit on one line at 360dp.
 */
@Composable
private fun PartyPinnedActionBar(model: PartyLobbyModel, actions: PartyLobbyActions, landscape: Boolean) {
    val caption = when {
        !model.isHost -> "The host starts playback for everyone."
        !model.hasSource -> stringResource(Res.string.watch_party_source_explanation)
        model.sourceLabel != null -> "${model.sourceLabel} - nobody leaves the lobby until you press Start."
        else -> "Source ready - nobody leaves the lobby until you press Start."
    }
    val changeSource: @Composable () -> Unit = {
        if (model.isHost && model.hasSource) {
            TextButton(onClick = actions.onChoose, contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text(stringResource(Res.string.watch_party_resolve_source), maxLines = 1, softWrap = false)
            }
        }
    }
    val leave: @Composable () -> Unit = {
        OutlinedButton(onClick = actions.onLeave) {
            Text(if (model.isHost) "End session" else "Leave", maxLines = 1, softWrap = false)
        }
    }
    val primary: @Composable (Modifier) -> Unit = { buttonModifier ->
        if (model.isHost) {
            Button(
                onClick = if (model.hasSource) actions.onStart else actions.onChoose,
                modifier = buttonModifier,
            ) {
                Text(
                    if (model.hasSource) "Start watching" else stringResource(Res.string.watch_party_choose_source),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    val hairline = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
    val captionText: @Composable (Modifier) -> Unit = { textModifier ->
        Text(
            caption,
            modifier = textModifier,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f))
            .drawBehind {
                drawLine(
                    color = hairline,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(start = 16.dp, end = 16.dp, top = 10.dp)
            .padding(bottom = nuvioSafeBottomPadding(extra = 10.dp)),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (landscape) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                captionText(Modifier.weight(1f))
                changeSource()
                leave()
                primary(Modifier)
            }
        } else if (model.isHost) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                captionText(Modifier.weight(1f))
                changeSource()
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                leave()
                primary(Modifier.weight(1f))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                captionText(Modifier.weight(1f))
                leave()
            }
        }
    }
}

@Composable
private fun PartyInviteFriendChips(invitableFriends: List<SocialProfileSummary>, onInvite: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        invitableFriends.forEach { friend ->
            Surface(
                modifier = Modifier.clickable { onInvite(friend.profileId) },
                shape = RoundedCornerShape(NuvioTokens.Radius.chip),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    Modifier.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PartyAvatar(
                        name = friend.displayName,
                        avatarUrl = friend.avatarUrl,
                        colorHex = friend.avatarColorHex,
                        size = 26.dp,
                    )
                    Text(friend.displayName, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun PartyLobbyHeader(onBack: () -> Unit, compact: Boolean = false) {
    Row(
        // A phone spends its 48dp touch target on the arrow and nothing else: the title starts where
        // the arrow's own padding ends, flush with the cards below rather than 24dp inside them.
        modifier = if (compact) Modifier.offset(x = (-12).dp) else Modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        Spacer(Modifier.width(if (compact) 0.dp else 4.dp))
        Text(
            stringResource(Res.string.watch_party_title),
            style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Before there is a party to lay out - the same in either pane arrangement. */
@Composable
private fun PartyLobbyOpening(
    onBack: () -> Unit,
    errorMessage: String?,
    isWorking: Boolean,
    handoff: PartyJoinHandoffInfo? = null,
) {
    Column(
        Modifier.fillMaxSize().widthIn(max = 640.dp)
            .padding(start = 40.dp, end = 40.dp, top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        PartyLobbyHeader(onBack)
        handoff?.let { PartyJoinHero(it) }
        errorMessage?.let { PartyNotice(it, MaterialTheme.colorScheme.error) }
        PartyPanel {
            if (isWorking) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Opening the session…")
                }
            } else {
                Text("Unable to open this session.")
            }
        }
    }
}

/**
 * What the party is doing, and the code that gets people into it.
 *
 * [PartyHero] carried the poster and the title as well, because the lobby was one column and there
 * was nowhere else for them; in the two-pane layout the title has its own rail, and what is left
 * here is the party's own state.
 */
@Composable
private fun PartyStatusBand(
    party: WatchPartyState,
    hostSourceStaged: Boolean,
    inviteCode: String?,
    connection: PartyConnectionState,
    sync: WatchPartySyncState,
) {
    PartyPanel {
        Row(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    party.stageHeadline(hostSourceStaged),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                PartySyncLine(connection, sync, party.members.size)
            }
            if (inviteCode != null) PartyInviteCode(inviteCode)
        }
    }
}

/**
 * The title, as the details screen would put it, in the width of a rail.
 *
 * The party only carries a title, a poster and an episode number - enough to say what is being
 * watched, not enough to make the case for it while people wait. This fetches the real metadata,
 * and reads top to bottom in the same order as the desktop hero: art, meta, ratings, genres,
 * synopsis. [MetaDetailsRepository.fetch] is deliberately the entry point rather than `load`: it
 * caches and returns, without publishing into the details screen's own state.
 */
@Composable
private fun PartyTitleRail(content: PartyContent, modifier: Modifier = Modifier) {
    // The rail is a details screen in miniature, so it answers to the details screen's own
    // spoiler setting rather than to a rule of its own. Blurring the still but leaving the
    // synopsis underneath it would be no protection at all - the overview is the spoiler.
    val metaScreenSettings by remember {
        MetaScreenSettingsRepository.ensureLoaded()
        MetaScreenSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val watchedState by remember {
        WatchedRepository.ensureLoaded()
        WatchedRepository.uiState
    }.collectAsStateWithLifecycle()
    val watchProgressState by remember {
        WatchProgressRepository.ensureLoaded()
        WatchProgressRepository.uiState
    }.collectAsStateWithLifecycle()
    var meta by remember(content.contentType, content.contentId) { mutableStateOf<MetaDetails?>(null) }
    LaunchedEffect(content.contentType, content.contentId) {
        meta = MetaDetailsRepository.fetch(type = content.contentType, id = content.contentId)
    }
    val loaded = meta
    val episode = remember(loaded, content.videoId, content.season, content.episode) {
        val videos = loaded?.videos.orEmpty()
        videos.firstOrNull { it.id == content.videoId }
            ?: videos.firstOrNull { it.season == content.season && it.episode == content.episode }
    }
    // Exactly `EpisodeListCard`'s rule, through the same helper: explicitly marked watched, or
    // playback past the completion threshold. An episode we cannot resolve counts as unwatched,
    // which is the safe direction - it hides rather than reveals.
    val blurEpisode = remember(
        metaScreenSettings.blurUnwatchedEpisodes,
        watchedState.watchedKeys,
        watchProgressState.entries,
        episode,
        content.contentId,
    ) {
        metaScreenSettings.blurUnwatchedEpisodes && (
            episode == null ||
                !WatchingState.isEpisodeSeen(
                    watchedKeys = watchedState.watchedKeys,
                    progressByVideoId = watchProgressState.byVideoIdForContent(content.contentId),
                    metaType = content.contentType,
                    metaId = content.contentId,
                    episode = episode,
                )
            )
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(NuvioTokens.Radius.card),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            PartyTitleRailArt(content = content, meta = loaded)
            Column(
                Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (loaded == null) {
                    // Only what the party knows for certain is on screen until the fetch lands -
                    // the poster and the title - so nothing already shown changes underneath
                    // somebody reading it. These bars just hold the shape it will take.
                    PartyRailPlaceholder()
                } else {
                    PartyTitleMetaRow(loaded)
                    if (loaded.externalRatings.isNotEmpty()) {
                        DetailRatingsRow(ratings = loaded.externalRatings)
                    }
                    if (loaded.genres.isNotEmpty()) {
                        Text(
                            loaded.genres.take(4).joinToString(" • "),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    loaded.description?.takeIf { it.isNotBlank() }?.let { synopsis ->
                        Text(
                            synopsis,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (content.episode != null) {
                    PartyTitleEpisode(content = content, video = episode, blurred = blurEpisode)
                }
                // The rail is as tall as the window and a synopsis does not fill it; the cast is
                // what the details screen puts next, and it reads as a title's page rather than a
                // panel that ran out of things to say.
                loaded?.cast?.take(12)?.takeIf { it.isNotEmpty() }?.let { cast ->
                    DetailCastSection(cast = cast)
                }
            }
        }
    }
}

@Composable
private fun PartyTitleRailArt(content: PartyContent, meta: MetaDetails?) {
    val panel = MaterialTheme.colorScheme.surface
    var logoFailed by remember(meta?.logo) { mutableStateOf(false) }
    val image = meta?.background?.takeIf { it.isNotBlank() }
        ?: meta?.poster?.takeIf { it.isNotBlank() }
        ?: content.poster
    val logo = meta?.logo?.takeIf { it.isNotBlank() && !logoFailed }
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
        if (!image.isNullOrBlank()) {
            NuvioAsyncImage(
                model = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.42f to panel.copy(alpha = 0.30f),
                    0.78f to panel.copy(alpha = 0.80f),
                    1f to panel.copy(alpha = 0.96f),
                ),
            ),
        )
        Box(Modifier.align(Alignment.BottomStart).padding(start = 18.dp, end = 18.dp, bottom = 14.dp)) {
            if (logo != null) {
                NuvioAsyncImage(
                    model = logo,
                    contentDescription = content.title,
                    modifier = Modifier.widthIn(max = 220.dp).height(58.dp),
                    alignment = Alignment.BottomStart,
                    contentScale = ContentScale.Fit,
                    onError = { logoFailed = true },
                )
            } else {
                Text(
                    meta?.name?.takeIf { it.isNotBlank() } ?: content.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PartyTitleMetaRow(meta: MetaDetails) {
    val labels = buildList {
        desktopYearLabel(meta)?.let(::add)
        desktopSeasonCountLabel(meta)?.let(::add)
        formatRuntimeForDisplay(meta.runtime)?.let(::add)
    }
    val ageRating = meta.ageRating?.takeIf { it.isNotBlank() }
    if (labels.isEmpty() && ageRating == null) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        labels.forEach { label ->
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
        ageRating?.let { rating ->
            Box(
                Modifier.border(
                    NuvioTokens.Border.thin,
                    MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                    RoundedCornerShape(NuvioTokens.Radius.sm),
                ).padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    rating,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The episode under the show, rather than beside the title as a single line.
 *
 * A party is on one episode, and "S2 E4" says nothing about it; the still and the synopsis are what
 * somebody sitting in the lobby is actually waiting to see.
 */
@Composable
private fun PartyTitleEpisode(content: PartyContent, video: MetaVideo?, blurred: Boolean) {
    val episodeNumber = content.episode ?: return
    val season = content.season ?: video?.season ?: 1
    val title = video?.title?.takeIf { it.isNotBlank() }
        ?: content.episodeTitle?.takeIf { it.isNotBlank() }
    val still = video?.thumbnail?.takeIf { it.isNotBlank() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.fillMaxWidth().height(1.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        )
        Text(
            "NOW WATCHING",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            if (still != null) {
                NuvioAsyncImage(
                    model = still,
                    contentDescription = null,
                    modifier = Modifier.width(132.dp).aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(NuvioTokens.Radius.lg))
                        .then(if (blurred) Modifier.blur(EpisodeStillBlur) else Modifier),
                    contentScale = ContentScale.Crop,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "S$season · E$episodeNumber",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                title?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        // Dropped rather than blurred. Four lines of blurred `bodySmall` stays legible enough to
        // spoil at this size and reads as a rendering fault; the still above already says the
        // episode is being withheld, and the title and S/E line still name it.
        if (!blurred) {
            video?.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                Text(
                    overview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PartyRailPlaceholder() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f)
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        listOf(0.42f, 0.92f, 1f, 0.72f).forEach { fraction ->
            Box(
                Modifier.fillMaxWidth(fraction).height(11.dp)
                    .clip(RoundedCornerShape(NuvioTokens.Radius.xs)).background(color),
            )
        }
    }
}

/**
 * The title's own art, blurred behind the lobby.
 *
 * `Modifier.blur` is a no-op below API 31 and the tint is all that survives there - see the note on
 * `isBackdropBlurSupported` - so the scrim is heavy enough to carry the screen without it. Only the
 * poster is on [PartyContent], so this is a 2:3 image cropped to fill; at this radius it reads as
 * colour rather than as a stretched poster.
 */
@Composable
private fun PartyLobbyBackdrop(poster: String?) {
    Box(Modifier.fillMaxSize()) {
        if (!poster.isNullOrBlank()) {
            NuvioAsyncImage(
                model = poster,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().blur(72.dp).alpha(0.5f),
                contentScale = ContentScale.Crop,
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.background.copy(alpha = 0.42f),
                    0.5f to MaterialTheme.colorScheme.background.copy(alpha = 0.74f),
                    1f to MaterialTheme.colorScheme.background.copy(alpha = 0.94f),
                ),
            ),
        )
    }
}

@Composable
private fun PartyHero(
    party: WatchPartyState,
    inviteCode: String?,
    connection: PartyConnectionState,
    sync: WatchPartySyncState,
    wide: Boolean,
    hostSourceStaged: Boolean,
) {
    PartyPanel {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Top) {
            party.content.poster?.let { poster ->
                NuvioAsyncImage(
                    model = poster,
                    contentDescription = null,
                    modifier = Modifier.size(width = 104.dp, height = 156.dp)
                        .clip(RoundedCornerShape(NuvioTokens.Radius.poster)),
                    contentScale = ContentScale.Crop,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    party.content.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                party.content.episode?.let { episode ->
                    Text(
                        "S${party.content.season ?: 1} E$episode" +
                            party.content.episodeTitle?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    party.stageHeadline(hostSourceStaged),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                PartySyncLine(connection, sync, party.members.size)
            }
            if (wide && inviteCode != null) PartyInviteCode(inviteCode)
        }
        if (!wide && inviteCode != null) {
            Spacer(Modifier.height(4.dp))
            PartyInviteCode(inviteCode, fillWidth = true)
        }
    }
}

/**
 * The invite code, as the thing a host actually came here for.
 *
 * It used to be the last line of a paragraph of body text at `titleMedium`, with no way to take it
 * short of dragging a selection across it.
 */
@Composable
private fun PartyInviteCode(code: String, fillWidth: Boolean = false) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_600)
            copied = false
        }
    }
    Surface(
        modifier = Modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .clickable {
                clipboard.setText(AnnotatedString(code))
                copied = true
            },
        shape = RoundedCornerShape(NuvioTokens.Radius.xl),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
    ) {
        Column(
            Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "INVITE CODE",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SelectionContainer {
                    Text(
                        code,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp,
                    )
                }
                Icon(
                    if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                    contentDescription = if (copied) "Copied" else "Copy invite code",
                    modifier = Modifier.size(18.dp),
                    tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (copied) "Copied" else "Click to copy",
                style = MaterialTheme.typography.labelSmall,
                color = if (copied) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun PartySyncLine(connection: PartyConnectionState, sync: WatchPartySyncState, memberCount: Int) {
    val connected = connection == PartyConnectionState.connected
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.size(8.dp).clip(CircleShape).background(
                if (connected) PartyReadyColor else PartyWorkingColor,
            ),
        )
        Text(
            "${partySyncLabel(connection, sync)} · $memberCount/$WatchPartyMaxParticipants",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Where the party is, as four steps rather than one word.
 *
 * The stage was rendered as `resolving_sources` with its underscores swapped out, which named the
 * state without saying whether it was near the start or the end of getting everyone watching.
 */
@Composable
private fun PartyStageRail(stage: WatchPartyStage) {
    val reached = stage.railIndex()
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        WatchPartyStageRail.forEachIndexed { index, step ->
            val done = index <= reached
            val color by animateColorAsState(
                if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                label = "party-stage-$index",
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(color))
                Text(
                    step.railLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (index == reached) FontWeight.Bold else FontWeight.Normal,
                    color = if (done) {
                        MaterialTheme.colorScheme.onBackground
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PartyParticipants(
    party: WatchPartyState,
    viewerProfileId: String?,
    presentation: PartyPresentationState,
    invitableFriends: List<SocialProfileSummary>,
    onInvite: (String) -> Unit,
) {
    // WatchPartyRepository.invite existed with no caller, so the receiving side rendered invites
    // that nothing could ever send. Only friends can be invited, which party_invite_friend enforces
    // regardless of what is listed here.
    var inviting by remember { mutableStateOf(false) }
    PartyPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Participants", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                "${party.readyCount()} of ${party.members.count { it.connected }} ready",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            party.members.forEach { member ->
                PartyParticipantTile(
                    member = member,
                    isHost = member.profileId == party.hostProfileId,
                    viewerProfileId = viewerProfileId,
                    status = presentation.members.getValue(member.profileId),
                )
            }
            if (invitableFriends.isNotEmpty() && party.members.size < WatchPartyMaxParticipants) {
                PartyInviteTile(expanded = inviting, onClick = { inviting = !inviting })
            }
        }
        if (inviting && invitableFriends.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            PartyInviteFriendChips(invitableFriends, onInvite)
        }
    }
}

/**
 * One participant, as a tile whose readiness can be read across the room.
 *
 * The old row put the answer - "source ready" - in the middle of a run-on `bodySmall` string
 * between the role and the source match, in the smallest type on the screen, with the only colour
 * anywhere on it spent on a 9dp connection dot at the far right.
 */
@Composable
private fun PartyParticipantTile(
    member: WatchPartyParticipant,
    isHost: Boolean,
    viewerProfileId: String?,
    status: PartyMemberPresentation,
) {
    val tone = status.tone
    // Away is dimmed like Offline and for the same reading reason - this person is not with us
    // right now - but nothing else treats them the same: they are still a member, still counted in
    // the party size, and still somebody the party may be waiting for.
    val dimmed = tone == PartyReadyTone.Offline || tone == PartyReadyTone.Away
    Surface(
        modifier = Modifier.width(PartyTileWidth).alpha(if (dimmed) 0.55f else 1f),
        shape = RoundedCornerShape(NuvioTokens.Radius.xl),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                // The ring is the ambient signal: a tile still working turns and the finished ones
                // sit still, so "who are we waiting for" is answerable without reading a word.
                if (tone == PartyReadyTone.Working || tone == PartyReadyTone.Buffering || tone == PartyReadyTone.Reconnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(56.dp),
                        color = PartyWorkingColor,
                        strokeWidth = 2.5.dp,
                        trackColor = Color.Transparent,
                    )
                }
                PartyAvatar(
                    name = member.displayName(viewerProfileId),
                    avatarUrl = member.profile?.avatarUrl,
                    colorHex = member.profile?.avatarColorHex,
                    size = 46.dp,
                )
                if (isHost) {
                    Box(
                        Modifier.align(Alignment.BottomEnd)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Star,
                            contentDescription = "Host",
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
            Text(
                member.displayName(viewerProfileId),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            PartyStatusPill(tone, status.label)
            if (!dimmed && member.sourceMatch == PartySourceMatch.alternate) {
                Text(
                    "different source",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            member.readyError?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PartyInviteTile(expanded: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.width(PartyTileWidth).clickable(onClick = onClick),
        shape = RoundedCornerShape(NuvioTokens.Radius.xl),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.9f)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.size(46.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.PersonAdd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (expanded) "Close" else "Invite",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PartyStatusPill(tone: PartyReadyTone, label: String) {
    val color = when (tone) {
        PartyReadyTone.Ready -> PartyReadyColor
        PartyReadyTone.Working, PartyReadyTone.Buffering, PartyReadyTone.Reconnecting -> PartyWorkingColor
        PartyReadyTone.Paused -> MaterialTheme.colorScheme.primary
        PartyReadyTone.Failed -> MaterialTheme.colorScheme.error
        // Away reads like Offline and is not Offline: the member is still here and still counted.
        // Muted rather than coloured, because the one thing it must not do is look like a problem
        // somebody has to act on.
        PartyReadyTone.Away,
        PartyReadyTone.Offline,
        -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(NuvioTokens.Radius.chip),
        color = color.copy(
            alpha = if (tone == PartyReadyTone.Offline || tone == PartyReadyTone.Away) 0.10f else 0.18f,
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            when (tone) {
                PartyReadyTone.Ready -> Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = color,
                )
                PartyReadyTone.Failed -> Icon(
                    Icons.Rounded.PriorityHigh,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = color,
                )
                else -> Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            }
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.3.sp,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PartyAvatar(name: String, avatarUrl: String?, colorHex: String?, size: Dp) {
    val background = colorHex?.let(::parseHexColor) ?: MaterialTheme.colorScheme.primaryContainer
    Box(
        Modifier.size(size).clip(CircleShape).background(background),
        contentAlignment = Alignment.Center,
    ) {
        if (!avatarUrl.isNullOrBlank()) {
            // Carried on the model since the feature shipped and drawn by nothing, so every avatar
            // in the party and on the social tab was a monogram.
            NuvioAsyncImage(
                model = avatarUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                name.trim().take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun PartyHostSettings(
    controlMode: WatchPartyControlMode,
    onControlMode: (WatchPartyControlMode) -> Unit,
    waitForEveryone: Boolean,
    onWaitForEveryone: (Boolean) -> Unit,
    pauseForAwayUsers: Boolean,
    onPauseForAwayUsers: (Boolean) -> Unit,
) {
    // Two stacked rows for four controls cost the lobby a panel's worth of height it needs to fit
    // one screen; the switch reads perfectly well beside the chips, with its one explaining line
    // underneath them both.
    PartyPanel {
        LeadingBesideTrailingRow(
            leading = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = controlMode == WatchPartyControlMode.host_only,
                        onClick = { onControlMode(WatchPartyControlMode.host_only) },
                        label = { Text("Host controls") },
                    )
                    FilterChip(
                        selected = controlMode == WatchPartyControlMode.collaborative,
                        onClick = { onControlMode(WatchPartyControlMode.collaborative) },
                        label = { Text("Collaborative") },
                    )
                }
            },
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Wait for everyone", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                    Spacer(Modifier.weight(1f))
                    Switch(checked = waitForEveryone, onCheckedChange = onWaitForEveryone)
                }
            },
        )
        Text(
            if (waitForEveryone) {
                "Playback pauses for anyone whose stream stalls, and starts again together."
            } else {
                "Playback carries on when someone's stream stalls; they catch up on their own."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Its own row and its own sentence. A stalled stream and a person who has put their phone
        // down are different problems, and a host has no reason to answer both the same way.
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Pause for away users", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Switch(checked = pauseForAwayUsers, onCheckedChange = onPauseForAwayUsers)
        }
        Text(
            if (pauseForAwayUsers) {
                "Playback waits while someone has the app in the background, and starts again together."
            } else {
                "Playback carries on when someone steps away; they catch up when they come back."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The host's two decisions, in the order they are made.
 *
 * Before a source exists there is one button and it opens the list. Once one is chosen the emphasis
 * moves to Start and picking again demotes itself to the outlined button beside it: the party is
 * one press from beginning, and nothing else in the bar should compete with that.
 */
@Composable
private fun PartyActionBar(
    isHost: Boolean,
    hasSource: Boolean,
    sourceLabel: String?,
    onChoose: () -> Unit,
    onStart: () -> Unit,
    onLeave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LeadingBesideTrailingRow(
            gap = 12.dp,
            leading = {
                // A `FlowRow`, so two primary buttons on a very narrow phone wrap instead of one of
                // them being squeezed.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isHost) {
                        if (hasSource) {
                            Button(onClick = onStart) { Text("Start watching") }
                            OutlinedButton(onClick = onChoose) {
                                Text(stringResource(Res.string.watch_party_resolve_source))
                            }
                        } else {
                            Button(onClick = onChoose) {
                                Text(stringResource(Res.string.watch_party_choose_source))
                            }
                        }
                    }
                }
            },
            trailing = {
                Box(contentAlignment = Alignment.CenterEnd) {
                    OutlinedButton(onClick = onLeave) { Text(if (isHost) "End session" else "Leave") }
                }
            },
        )
        if (isHost) {
            Text(
                when {
                    !hasSource -> stringResource(Res.string.watch_party_source_explanation)
                    sourceLabel != null -> "$sourceLabel - nobody leaves the lobby until you press Start."
                    else -> "Source ready - nobody leaves the lobby until you press Start."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PartyNotice(message: String, accent: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(NuvioTokens.Radius.lg),
        color = accent.copy(alpha = 0.12f),
    ) {
        Text(
            message,
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall,
            color = accent,
        )
    }
}

/**
 * "Joining Seraph", with the title's art, at the top of the lobby an accepted join request opened.
 *
 * An accepted guest used to arrive in a lobby that then started playback by itself, with nothing
 * saying whose party it was. This names the person and the title for the whole of the hand-off; the
 * loading screen the lobby launches carries the same sentence.
 */
@Composable
private fun PartyJoinHero(info: PartyJoinHandoffInfo) {
    PartyPanel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (!info.artwork.isNullOrBlank()) {
                NuvioAsyncImage(
                    model = info.artwork,
                    contentDescription = null,
                    modifier = Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Joining ${info.hostName}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    info.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * [leading] at the start and [trailing] at the end of one line when both fit at their natural
 * widths, and [trailing] on a line of its own, given the full width, when they do not.
 *
 * WARN **Decided by measurement, not by a breakpoint.** The lobby's rows were written for the
 * desktop window: a `Row` with a weighted spacer between two groups. On a 411dp phone the groups
 * need more than the panel has, so the row pushed the "Wait for everyone" switch past the card's
 * edge and folded its label into "Wait fo / everyon", and squeezed "End session" into a column one
 * letter wide. A width threshold would be wrong again under font scaling or a translation; this
 * asks the actual pieces. Where they fit, the result is the old row exactly.
 *
 * [trailing] is measured at its natural width on a shared line and at the full width on its own,
 * so it decides how it sits there - a label-and-switch row spreads out, a button aligns itself.
 */
@Composable
private fun LeadingBesideTrailingRow(
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    gap: Dp = 8.dp,
) {
    Layout(
        contents = listOf(leading, trailing),
        modifier = Modifier.fillMaxWidth(),
    ) { (leadingMeasurables, trailingMeasurables), constraints ->
        val gapPx = gap.roundToPx()
        val leadingPlaceable = leadingMeasurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val trailingMeasurable = trailingMeasurables.single()
        val trailingNatural = trailingMeasurable.maxIntrinsicWidth(Constraints.Infinity)
        val oneLine = leadingPlaceable.width + gapPx + trailingNatural <= constraints.maxWidth
        val trailingWidth = if (oneLine) trailingNatural else constraints.maxWidth
        val trailingPlaceable = trailingMeasurable.measure(Constraints.fixedWidth(trailingWidth))
        if (oneLine) {
            val height = maxOf(leadingPlaceable.height, trailingPlaceable.height)
            layout(constraints.maxWidth, height) {
                leadingPlaceable.place(0, (height - leadingPlaceable.height) / 2)
                trailingPlaceable.place(constraints.maxWidth - trailingPlaceable.width, (height - trailingPlaceable.height) / 2)
            }
        } else {
            layout(constraints.maxWidth, leadingPlaceable.height + gapPx + trailingPlaceable.height) {
                leadingPlaceable.place(0, 0)
                trailingPlaceable.place(0, leadingPlaceable.height + gapPx)
            }
        }
    }
}

@Composable private fun PartyPanel(
    padding: Dp = 18.dp,
    spacing: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(NuvioTokens.Radius.card),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
    ) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
    }
}

/**
 * What the party's synchronisation is actually doing.
 *
 * The connection line above says whether the channel is up, which is not the same question: a
 * channel can be up while the shared clock is still being measured, and during those first few
 * seconds the party is following the database anchor rather than the host's timeline. Saying so is
 * the difference between "it is warming up" and "it is broken".
 */
private fun partySyncLabel(connection: PartyConnectionState, sync: WatchPartySyncState): String = when {
    connection != PartyConnectionState.connected -> "Following the party every few seconds"
    !sync.clockLocked -> "Measuring the shared clock…"
    sync.bestRttMs < 0 -> "Live sync"
    else -> "Live sync · ${sync.bestRttMs} ms"
}
