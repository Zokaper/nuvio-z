package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.social.SocialAvatar

/**
 * One button the phone panel offers, and exactly what it sends.
 *
 * Every [event] is a `handlePlayerControlsEvent` command — the same vocabulary desktop's controls
 * page speaks — so the panel can only ever ask for something the runtime already answers. Kept
 * pure so the state → buttons decision is tested without Compose.
 */
internal data class MobilePartyAction(
    val label: String,
    val event: String,
    val value: Double = 0.0,
    val emphasis: MobilePartyActionEmphasis = MobilePartyActionEmphasis.Secondary,
)

internal enum class MobilePartyActionEmphasis { Primary, Secondary, Danger }

/** The state-level actions: what to do about the party as a whole, in the order they are drawn. */
internal fun mobilePartyStateActions(state: WatchTogetherBridgeState): List<MobilePartyAction> =
    when (state.stateName) {
        "idle" -> listOf(MobilePartyAction("Start a party", "wtStartParty", emphasis = MobilePartyActionEmphasis.Primary))
        "startFailed" -> buildList {
            add(MobilePartyAction("Try again", "wtRetry", emphasis = MobilePartyActionEmphasis.Primary))
            if (state.offersOpenExisting) add(MobilePartyAction("Open existing party", "wtOpenExisting"))
        }
        "activeElsewhere" -> listOf(
            MobilePartyAction("Open party", "wtOpenExisting", emphasis = MobilePartyActionEmphasis.Primary),
            MobilePartyAction("Leave party", "wtLeaveElsewhere", emphasis = MobilePartyActionEmphasis.Danger),
        )
        "ended" -> listOf(
            MobilePartyAction("Keep watching", "partyEndContinue", emphasis = MobilePartyActionEmphasis.Primary),
            MobilePartyAction("Exit player", "partyEndExit"),
        )
        "active" -> when {
            state.isHost && state.endConfirm -> listOf(
                MobilePartyAction("End for everyone", "partyEnd", emphasis = MobilePartyActionEmphasis.Danger),
                MobilePartyAction("Cancel", "wtEndConfirm", 0.0),
            )
            state.isHost -> listOf(MobilePartyAction("End party", "wtEndConfirm", 1.0, MobilePartyActionEmphasis.Danger))
            else -> listOf(MobilePartyAction("Leave party", "partyLeave", emphasis = MobilePartyActionEmphasis.Danger))
        }
        // unshareable, starting, connecting: nothing to press; the message says why.
        else -> emptyList()
    }

/** The viewer's own request to join someone else: waiting, or answered and ready to go. */
internal fun mobileOutgoingRequestActions(state: WatchTogetherBridgeState): List<MobilePartyAction> {
    if (!state.outgoingVisible) return emptyList()
    return when (state.outgoingPhase) {
        "accepted", "joining" -> listOf(
            MobilePartyAction("Join now", "wtJoinAccepted", emphasis = MobilePartyActionEmphasis.Primary),
            MobilePartyAction("Not now", "wtDismissAccepted"),
        )
        else -> listOf(MobilePartyAction("Cancel request", "wtCancelOutgoing"))
    }
}

internal fun mobileOutgoingRequestTitle(state: WatchTogetherBridgeState): String = when (state.outgoingPhase) {
    "accepted", "joining" -> "${state.outgoingName} let you in"
    else -> "Asking ${state.outgoingName} to join"
}

/**
 * Whether the header offers the Watch Together button. Offered wherever desktop offers it, and
 * additionally whenever the projection has something to say (a party, a request either way, a
 * start in flight) so a party can never be running with no way back into its room.
 */
internal fun mobileWatchTogetherButtonVisible(showWatchTogether: Boolean, state: WatchTogetherBridgeState): Boolean =
    showWatchTogether || state.badge != "none"

/**
 * Phone rendering of the shared Watch Together projection ([WatchTogetherBridgeState]).
 *
 * The mobile player is landscape-locked, so this is the same right-hand rail as Sources and
 * Episodes rather than desktop's floating card or a centred dialog: the video stays visible beside
 * it, and system Back closes it through [PlayerSidePanel]. It decides nothing about the party;
 * every button is a [MobilePartyAction] sent through [onEvent].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MobileWatchTogetherPanel(
    state: WatchTogetherBridgeState,
    onDismiss: () -> Unit,
    onEvent: (String, Double) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    PlayerSidePanel(
        visible = state.open,
        onDismiss = onDismiss,
        width = 420.dp,
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp)) {
            PlayerPanelHeader(title = "Watch Together") {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Close Watch Together",
                        tint = tokens.colors.textSecondary,
                    )
                }
            }
            if (state.title.isNotBlank()) {
                Text(
                    state.title,
                    color = tokens.colors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 12.dp, bottom = 16.dp, end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PanelBody(state, onEvent)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.PanelBody(state: WatchTogetherBridgeState, onEvent: (String, Double) -> Boolean) {
    val tokens = MaterialTheme.nuvio

    if (state.stateName == "active" && state.connectionLabel.isNotBlank()) {
        ConnectionChip(state)
    }
    if (state.subline.isNotBlank()) {
        Text(state.subline, color = tokens.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
    }
    if (state.stateName == "starting" || state.stateName == "connecting") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NuvioLoadingIndicator(color = tokens.colors.accent, modifier = Modifier.size(20.dp))
            Text(state.message, color = tokens.colors.textPrimary, style = MaterialTheme.typography.bodyLarge)
        }
    } else if (state.message.isNotBlank()) {
        Text(state.message, color = tokens.colors.textPrimary, style = MaterialTheme.typography.bodyLarge)
    }
    if (state.errorMessage.isNotBlank()) {
        NoticeCard(
            title = state.errorMessage,
            accent = tokens.colors.danger,
            actions = listOf(MobilePartyAction("Dismiss", "wtDismissError")),
            onEvent = onEvent,
        )
    }

    if (state.incomingVisible) {
        NoticeCard(
            title = "${state.incomingName} wants to join",
            accent = tokens.colors.warning,
            avatar = Triple(state.incomingName, state.incomingAvatarUrl, state.incomingColorHex),
            actions = listOf(
                MobilePartyAction("Let in", "wtAcceptRequest", emphasis = MobilePartyActionEmphasis.Primary),
                MobilePartyAction("Decline", "wtDeclineRequest"),
            ),
            onEvent = onEvent,
        )
    }
    if (state.outgoingVisible) {
        NoticeCard(
            title = mobileOutgoingRequestTitle(state),
            accent = tokens.colors.accent,
            avatar = Triple(state.outgoingName, state.outgoingAvatarUrl, state.outgoingColorHex),
            actions = mobileOutgoingRequestActions(state),
            onEvent = onEvent,
        )
    }

    if (state.stateName == "active") {
        ActiveParty(state, onEvent)
    }

    if (state.joinPolicyVisible) {
        SectionLabel("Who can join")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Direct", "Ask", "Off").forEachIndexed { index, label ->
                AddonFilterChip(
                    label = label,
                    isSelected = state.joinPolicy == index,
                    isLoading = state.joinPolicySaving && state.joinPolicy == index,
                    onClick = {
                        if (!state.joinPolicySaving && state.joinPolicy != index) {
                            onEvent("wtSetJoinPolicy", index.toDouble())
                        }
                    },
                )
            }
        }
        if (state.joinPolicyExplanation.isNotBlank()) {
            Text(state.joinPolicyExplanation, color = tokens.colors.textMuted, style = MaterialTheme.typography.bodySmall)
        }
        if (state.joinPolicyError.isNotBlank()) {
            Text(state.joinPolicyError, color = tokens.colors.danger, style = MaterialTheme.typography.bodySmall)
        }
    }

    if (state.stateName == "active" && state.syncDetails.isNotBlank()) {
        Text(state.syncDetails, color = tokens.colors.textMuted, style = MaterialTheme.typography.bodySmall)
    }
    if (state.stateName == "active" && state.leaveHelper.isNotBlank()) {
        Text(state.leaveHelper, color = tokens.colors.textMuted, style = MaterialTheme.typography.bodySmall)
    }
    if (state.stateName == "active" && state.isHost && state.endConfirm) {
        Text(
            "End the party for everyone? Guests keep watching on their own.",
            color = tokens.colors.danger,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    ActionColumn(mobilePartyStateActions(state), onEvent)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveParty(state: WatchTogetherBridgeState, onEvent: (String, Double) -> Boolean) {
    val tokens = MaterialTheme.nuvio
    if (state.people.isNotEmpty()) {
        SectionLabel(if (state.people.size == 1) "1 person" else "${state.people.size} people")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.people.forEach { person -> PersonRow(person) }
        }
    }

    if (state.isHost) {
        SectionLabel("Party settings")
        PartyToggle("Guests can pause and seek", state.guestsControl) {
            onEvent("wtSetGuestControl", if (it) 1.0 else 0.0)
        }
        PartyToggle("Pause when someone buffers", state.pauseWhenBuffers) {
            onEvent("wtSetWaitForEveryone", if (it) 1.0 else 0.0)
        }
        // Its own row, right under the one it is most likely to be confused with, so the
        // difference between "their stream stopped" and "they are not here" is readable on the
        // screen rather than only in the code.
        PartyToggle("Pause for away users", state.pauseForAway) {
            onEvent("wtSetPauseForAway", if (it) 1.0 else 0.0)
        }

        if (state.inviteTargets.isNotEmpty()) {
            SectionLabel("Invite friends")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.inviteTargets.forEach { target ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SocialAvatar(target.name, target.avatarUrl, null, 32.dp)
                        Text(
                            target.name,
                            color = tokens.colors.textPrimary,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        PlayerDialogButton(
                            label = if (target.invited) "Invited" else "Invite",
                            enabled = !target.invited,
                            onClick = { onEvent("wtInviteFriend", target.index.toDouble()) },
                        )
                    }
                }
            }
        }
        if (state.inviteCode.isNotBlank()) {
            InviteCode(state.inviteCode, onCopied = { onEvent("wtCopyInviteCode", 0.0) })
        }
    }
}

@Composable
private fun PersonRow(person: WatchTogetherBridgePerson) {
    val tokens = MaterialTheme.nuvio
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SocialAvatar(person.name, person.avatarUrl, person.colorHex, 36.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    person.name,
                    color = tokens.colors.textPrimary,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (person.isSelf) Badge("You", tokens.colors.textSecondary)
                if (person.isHost) Badge("Host", tokens.colors.accent)
            }
            if (person.status.isNotBlank()) {
                Text(
                    person.status,
                    color = toneColor(person.tone),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun InviteCode(code: String, onCopied: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.colors.surfaceCard)
            .clickable(role = Role.Button, onClickLabel = "Copy invite code") {
                clipboard.setText(AnnotatedString(code))
                onCopied()
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Invite code", color = tokens.colors.textMuted, style = MaterialTheme.typography.labelMedium)
            Text(code, color = tokens.colors.textPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Icon(Icons.Rounded.ContentCopy, contentDescription = null, tint = tokens.colors.textSecondary)
    }
}

@Composable
private fun ConnectionChip(state: WatchTogetherBridgeState) {
    val color = connectionColor(state.connection)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(state.connectionLabel, color = color, style = MaterialTheme.typography.labelLarge)
    }
    if (state.connectionTooltip.isNotBlank()) {
        Text(state.connectionTooltip, color = MaterialTheme.nuvio.colors.textMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoticeCard(
    title: String,
    accent: Color,
    actions: List<MobilePartyAction>,
    onEvent: (String, Double) -> Boolean,
    avatar: Triple<String, String, String>? = null,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(tokens.colors.surfaceCard)
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            avatar?.let { (name, url, color) -> SocialAvatar(name, url, color, 32.dp) }
            Text(
                title,
                color = tokens.colors.textPrimary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEach { action -> ActionButton(action, onEvent) }
        }
    }
}

@Composable
private fun ActionColumn(actions: List<MobilePartyAction>, onEvent: (String, Double) -> Boolean) {
    if (actions.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.forEach { action -> ActionButton(action, onEvent, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun ActionButton(
    action: MobilePartyAction,
    onEvent: (String, Double) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val (background, foreground) = when (action.emphasis) {
        MobilePartyActionEmphasis.Primary -> tokens.colors.accent to tokens.colors.onAccent
        MobilePartyActionEmphasis.Danger -> tokens.colors.danger.copy(alpha = 0.14f) to tokens.colors.danger
        MobilePartyActionEmphasis.Secondary -> tokens.colors.surfaceElevated to tokens.colors.textPrimary
    }
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(1.dp, tokens.colors.borderDefault.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .clickable(role = Role.Button) { onEvent(action.event, action.value) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            action.label,
            color = foreground,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PartyToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = tokens.colors.textPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = tokens.colors.onAccent,
                checkedTrackColor = tokens.colors.accent,
            ),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = MaterialTheme.nuvio.colors.textMuted,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .alpha(0.95f),
        maxLines = 1,
    )
}

@Composable
private fun connectionColor(connection: String): Color {
    val colors = MaterialTheme.nuvio.colors
    return when (connection) {
        "live" -> colors.success
        "reconnecting", "delayed" -> colors.warning
        else -> colors.danger
    }
}

@Composable
private fun toneColor(tone: String): Color {
    val colors = MaterialTheme.nuvio.colors
    return when (tone) {
        "ready" -> colors.success
        "working", "buffering", "reconnecting" -> colors.warning
        "failed", "offline" -> colors.danger
        else -> colors.textSecondary
    }
}
