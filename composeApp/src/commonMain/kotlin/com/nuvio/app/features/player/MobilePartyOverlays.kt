package com.nuvio.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio

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
 * The party's status pill and the in-player social card, stacked at the top centre so neither can
 * sit on the other. Desktop draws both on its native controls page from the same
 * [PlayerControlsState] fields; this is their phone rendering.
 *
 * The pill stays up when the chrome fades — a player held still because it is waiting on somebody
 * has to say so — but its buttons, like every other control, are withheld while the player is
 * locked.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BoxScope.MobilePartyOverlays(
    partyStatus: PartyStatusBridgeState,
    state: PlayerControlsState,
    locked: Boolean,
    horizontalSafePadding: Dp,
    onEvent: (String, Double) -> Boolean,
) {
    Column(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Top))
            .padding(horizontal = horizontalSafePadding)
            .padding(top = 96.dp)
            .widthIn(max = 520.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (partyStatus.visible) {
            StatusPill(partyStatus, actionsEnabled = !locked, onEvent = onEvent)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusPill(status: PartyStatusBridgeState, actionsEnabled: Boolean, onEvent: (String, Double) -> Boolean) {
    val tokens = MaterialTheme.nuvio
    val toneColor = when (status.tone) {
        "waiting" -> tokens.colors.warning
        "warning" -> tokens.colors.warning
        "error" -> tokens.colors.danger
        else -> Color.White
    }
    val actions = if (actionsEnabled) mobilePartyStatusActions(status) else emptyList()
    Surface(
        color = Color.Black.copy(alpha = 0.72f),
        contentColor = Color.White,
        shape = RoundedCornerShape(if (actions.isEmpty()) 50 else 20),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = status.text,
                color = if (status.tone == "neutral") Color.White else toneColor,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (actions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions.forEach { OverlayButton(it, onEvent) }
                }
            }
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
