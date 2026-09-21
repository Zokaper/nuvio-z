package com.nuvio.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioTypeScale
import com.nuvio.app.core.ui.nuvioWindowClass
import com.nuvio.app.features.profiles.parseHexColor

/** The pill's buttons, in drawing order; each is the command the bridge chose for it. */
internal fun mobilePartyStatusActions(status: PartyStatusBridgeState): List<MobilePartyAction> = buildList {
    if (status.action.isNotBlank()) {
        add(MobilePartyAction(status.actionLabel, status.action, emphasis = MobilePartyActionEmphasis.Primary))
    }
    if (status.secondaryAction.isNotBlank()) {
        add(MobilePartyAction(status.secondaryActionLabel, status.secondaryAction))
    }
}

/**
 * The in-player social card's buttons. `socialNotificationActions` carries lower-cased
 * `SocialNotificationAction` names; each maps onto the event desktop's page sends for it, and the
 * card can always be dismissed.
 */
internal fun mobileSocialNotificationActions(actions: List<String>): List<MobilePartyAction> = buildList {
    if ("join" in actions) add(MobilePartyAction("Join", "socialNotificationJoin", emphasis = MobilePartyActionEmphasis.Primary))
    if ("accept" in actions) add(MobilePartyAction("Accept", "socialNotificationAccept", emphasis = MobilePartyActionEmphasis.Primary))
    if ("decline" in actions) add(MobilePartyAction("Decline", "socialNotificationDecline"))
    add(MobilePartyAction("Dismiss", "socialNotificationDismiss"))
}

/**
 * The status pill's shape for one frame: which density it is drawn at, where it sits, and which of
 * its optional lines it carries.
 *
 * Pure, so the rules below are asserted by a test rather than by looking at a phone.
 */
internal data class MobilePartyStatusLayout(
    /** The chrome has faded: smaller, dimmer, one line. */
    val compact: Boolean,
    /** Sitting in the gesture pill's 40dp slot rather than under the title row. */
    val raised: Boolean,
    val showDetail: Boolean,
    val showActions: Boolean,
)

internal fun mobilePartyStatusLayout(
    status: PartyStatusBridgeState,
    controlsVisible: Boolean,
    locked: Boolean,
    gestureFeedbackShowing: Boolean,
    shortSurface: Boolean,
): MobilePartyStatusLayout {
    // A locked player draws no chrome whatever `controlsVisible` says, so it gets the compact pill.
    val compact = !controlsVisible || locked
    return MobilePartyStatusLayout(
        compact = compact,
        // The 40dp slot is the gesture pill's, and it is only free while no gesture is running.
        // Gesture feedback is the higher-priority transient and clears itself in 900ms.
        raised = compact && !gestureFeedbackShowing,
        // On a landscape phone the second line is what would reach down towards the scrub bar.
        showDetail = !compact && !shortSurface && status.detail.isNotBlank(),
        showActions = !compact && !locked && mobilePartyStatusActions(status).isNotEmpty(),
    )
}

/** What the pill's leading chip draws when nobody is named in the status. */
internal enum class PartyStatusGlyph { Sync, Waiting, Offline, Paused, JoinRequest, Problem, Party }

/** [PartyStatusBridgeState.kind] carries a `PartyStatusKind` name; unknown names fall back to [PartyStatusGlyph.Party]. */
internal fun partyStatusGlyph(kind: String): PartyStatusGlyph = when (kind) {
    "SourceActivity", "MatchingSource", "CatchingUp", "SwitchingEpisode" -> PartyStatusGlyph.Sync
    "HostChoosingSource", "WaitingForSources", "WaitingForBuffering", "WaitingForAway",
    "EveryoneWaitingOnYou", "HostBuffering", "WaitingForHostStart" -> PartyStatusGlyph.Waiting
    "Reconnecting", "Offline" -> PartyStatusGlyph.Offline
    "PausedBy" -> PartyStatusGlyph.Paused
    "IncomingJoinRequest", "OutgoingJoinRequest" -> PartyStatusGlyph.JoinRequest
    "SourceNotFound", "VersionTooShort" -> PartyStatusGlyph.Problem
    else -> PartyStatusGlyph.Party
}

/**
 * The party's status pill and the in-player social card, stacked at the top centre so neither can
 * sit on the other. Desktop draws both on its native controls page from the same
 * [PlayerControlsState] fields; this is their phone rendering.
 *
 * The pill stays up when the chrome fades — a player held still because it is waiting on somebody
 * has to say so — but its buttons, like every other control, are withheld while the player is
 * locked. With the chrome gone it compacts and rises into the gesture pill's slot, so the message
 * sits where the eye already expects HUD instead of floating over an empty frame.
 */
@Composable
internal fun BoxScope.MobilePartyOverlays(
    partyStatus: PartyStatusBridgeState,
    state: PlayerControlsState,
    locked: Boolean,
    controlsVisible: Boolean,
    gestureFeedbackShowing: Boolean,
    horizontalSafePadding: Dp,
    onEvent: (String, Double) -> Boolean,
) {
    // Only for the window's height; it draws nothing and takes no input of its own.
    BoxWithConstraints(Modifier.matchParentSize()) {
        val layout = mobilePartyStatusLayout(
            status = partyStatus,
            controlsVisible = controlsVisible,
            locked = locked,
            gestureFeedbackShowing = gestureFeedbackShowing,
            shortSurface = nuvioWindowClass().isShortSurface,
        )
        val top by animateDpAsState(
            targetValue = if (layout.raised) 40.dp else 96.dp,
            animationSpec = tween(NuvioTokens.Motion.normalMillis, easing = NuvioTokens.Motion.standard),
            label = "partyStatusTop",
        )
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Top))
                .padding(horizontal = horizontalSafePadding)
                .padding(top = top)
                .widthIn(max = 520.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (partyStatus.visible) {
                StatusPill(partyStatus, layout, onEvent = onEvent)
            }
            AnimatedVisibility(
                visible = state.socialNotificationVisible && !locked,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                SocialNotificationCard(state, onEvent)
            }
        }
    }
}

/**
 * The gesture pill's anatomy — a 28dp leading chip, then white text on `Black @ 0.75` — so the two
 * HUD surfaces read as one family. Severity lives in the chip and its dot, never in the sentence:
 * an amber sentence reads as a warning banner, not as player chrome.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatusPill(
    status: PartyStatusBridgeState,
    layout: MobilePartyStatusLayout,
    onEvent: (String, Double) -> Boolean,
) {
    val tokens = MaterialTheme.nuvio
    val toneColor = when (status.tone) {
        "waiting", "warning" -> tokens.colors.warning
        "error" -> tokens.colors.danger
        else -> null
    }
    val motion = tween<Float>(NuvioTokens.Motion.normalMillis, easing = NuvioTokens.Motion.standard)
    val scale by animateFloatAsState(if (layout.compact) 0.88f else 1f, motion, label = "partyStatusScale")
    val backgroundAlpha by animateFloatAsState(if (layout.compact) 0.62f else 0.75f, motion, label = "partyStatusAlpha")
    val actions = if (layout.showActions) mobilePartyStatusActions(status) else emptyList()
    Surface(
        color = Color.Black.copy(alpha = backgroundAlpha),
        contentColor = Color.White,
        shape = RoundedCornerShape(if (actions.isEmpty()) 24.dp else 20.dp),
        modifier = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(0.5f, 0f)
        },
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .animateContentSize(tween(NuvioTokens.Motion.normalMillis, easing = NuvioTokens.Motion.standard)),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusChip(status, toneColor)
                Column(Modifier.weight(1f, fill = false)) {
                    Text(
                        text = status.text,
                        color = Color.White,
                        style = MaterialTheme.nuvioTypeScale.bodyLg.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = if (layout.compact) 1 else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // The second line, dimmed: what happened is the headline, what is being done
                    // about it reads as the subordinate clause it is.
                    if (layout.showDetail) {
                        Text(
                            text = status.detail,
                            color = Color.White.copy(alpha = 0.72f),
                            style = MaterialTheme.nuvioTypeScale.bodyMd,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (actions.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    actions.forEach { OverlayButton(it, onEvent) }
                }
            }
        }
    }
}

/**
 * The person the status is about when it names one — the bridge has always carried them — and
 * otherwise a glyph for what is happening. A 6dp dot on the chip's corner carries the tone.
 */
@Composable
private fun StatusChip(status: PartyStatusBridgeState, toneColor: Color?) {
    val person = status.people.firstOrNull()
    Box(Modifier.size(28.dp)) {
        if (person != null) {
            Box(
                Modifier.fillMaxSize().clip(CircleShape).background(parseHexColor(person.colorHex)),
                contentAlignment = Alignment.Center,
            ) {
                // Under the image, so a URL that fails to load still leaves the initial.
                Text(
                    person.name.trim().take(1).uppercase(),
                    style = MaterialTheme.nuvioTypeScale.bodyMd.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
                if (person.avatarUrl.isNotBlank()) {
                    NuvioAsyncImage(
                        model = person.avatarUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(toneColor?.copy(alpha = 0.22f) ?: Color.White.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = when (partyStatusGlyph(status.kind)) {
                        PartyStatusGlyph.Sync -> Icons.Rounded.Sync
                        PartyStatusGlyph.Waiting -> Icons.Rounded.HourglassTop
                        PartyStatusGlyph.Offline -> Icons.Rounded.WifiOff
                        PartyStatusGlyph.Paused -> Icons.Rounded.Pause
                        PartyStatusGlyph.JoinRequest -> Icons.Rounded.PersonAdd
                        PartyStatusGlyph.Problem -> Icons.Rounded.ErrorOutline
                        PartyStatusGlyph.Party -> Icons.Rounded.Groups
                    },
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (toneColor != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color.Black)
                    .padding(1.dp)
                    .clip(CircleShape)
                    .background(toneColor),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SocialNotificationCard(state: PlayerControlsState, onEvent: (String, Double) -> Boolean) {
    val tokens = MaterialTheme.nuvio
    Surface(
        color = tokens.colors.surfaceElevated.copy(alpha = 0.96f),
        contentColor = tokens.colors.textPrimary,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row {
                Text(
                    buildString {
                        append(state.socialNotificationActor)
                        if (state.socialNotificationMessage.isNotBlank()) append(' ').append(state.socialNotificationMessage)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                mobileSocialNotificationActions(state.socialNotificationActions).forEach { OverlayButton(it, onEvent) }
            }
        }
    }
}

@Composable
private fun OverlayButton(action: MobilePartyAction, onEvent: (String, Double) -> Boolean) {
    val tokens = MaterialTheme.nuvio
    val primary = action.emphasis == MobilePartyActionEmphasis.Primary
    Text(
        text = action.label,
        color = if (primary) tokens.colors.onAccent else Color.White,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (primary) tokens.colors.accent else Color.White.copy(alpha = 0.16f))
            .clickable(role = Role.Button) { onEvent(action.event, action.value) }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
