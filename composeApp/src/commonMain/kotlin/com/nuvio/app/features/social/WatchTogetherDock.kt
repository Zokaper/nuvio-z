package com.nuvio.app.features.social

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.watchparty.currentEpochMs
import com.nuvio.app.features.watchparty.partyPossessive
import kotlinx.coroutines.delay

/**
 * What an outgoing Join / Ask to join is doing, as a persistent pill in the app shell.
 *
 * Replaces "Join request sent" - a toast that vanished while an invisible poll ran for two minutes.
 * The pill stays until the request resolves, so a person can leave Home, open a title and come back
 * and still see that Seraph has not answered yet, or cancel.
 *
 * Reads only [OutgoingJoinRequestStore.state]; it keeps no copy, so an identity boundary removes it
 * in the same frame it clears the store.
 */
@Composable
fun WatchTogetherDock(
    state: OutgoingJoinRequestState,
    windowWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val bound = state as? OutgoingJoinRequestState.Bound
    // The exit animation still draws after the store has cleared; it draws what was last shown.
    var shown by remember { mutableStateOf<OutgoingJoinRequestState.Bound?>(null) }
    if (bound != null) shown = bound
    AnimatedVisibility(
        visible = bound != null,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
    ) {
        shown?.let { DockContent(it, narrow = windowWidth < 720.dp) }
    }
}

@Composable
private fun DockContent(state: OutgoingJoinRequestState.Bound, narrow: Boolean) {
    var nowMs by remember { mutableLongStateOf(currentEpochMs()) }
    LaunchedEffect(state::class) {
        while (true) {
            nowMs = currentEpochMs()
            delay(250)
        }
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val presentation = dockPresentation(state, nowMs)
    val accent = state is OutgoingJoinRequestState.Accepted
    Surface(
        modifier = Modifier.hoverable(interaction).then(
            if (narrow && !hovered) Modifier else Modifier.widthIn(max = 340.dp),
        ),
        shape = RoundedCornerShape(28.dp),
        color = if (accent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (accent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        shadowElevation = 8.dp,
    ) {
        Row(
            Modifier.padding(start = 6.dp, end = if (narrow && !hovered) 6.dp else 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                presentation.ring?.let { fraction ->
                    CircularProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.size(44.dp),
                        strokeWidth = 2.5.dp,
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    )
                }
                if (presentation.spinning) {
                    CircularProgressIndicator(Modifier.size(44.dp), strokeWidth = 2.5.dp)
                }
                SocialAvatar(state.target.displayName, state.target.avatarUrl, state.target.avatarColorHex, 34.dp)
            }
            if (!narrow || hovered) {
                Column(Modifier.weight(1f, fill = false).widthIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        presentation.headline,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val muted = if (accent) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                    presentation.subline?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = muted)
                    }
                    presentation.detail?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = muted)
                    }
                }
                presentation.actions.forEach { action ->
                    TextButton(
                        onClick = action.onClick,
                        enabled = action.enabled,
                        contentPadding = PaddingValues(horizontal = 10.dp),
                    ) { Text(action.label, maxLines = 1, softWrap = false) }
                }
                presentation.onDismiss?.let { dismiss ->
                    IconButton(onClick = dismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "Dismiss", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

private data class DockAction(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

private data class DockPresentation(
    val headline: String,
    val subline: String?,
    /** Remaining fraction for a countdown ring, or null. */
    val ring: Float?,
    val spinning: Boolean,
    val actions: List<DockAction>,
    /** A close icon rather than a second text button, so the headline keeps its width. */
    val onDismiss: (() -> Unit)? = null,
    val detail: String? = null,
)

private fun dockPresentation(state: OutgoingJoinRequestState.Bound, nowMs: Long): DockPresentation {
    val name = state.target.displayName
    val what = listOfNotNull(
        state.content.title,
        if (state.content.season != null && state.content.episode != null) "S${state.content.season}E${state.content.episode}" else null,
    ).joinToString(" ")
    val store = OutgoingJoinRequestStore
    return when (state) {
        is OutgoingJoinRequestState.Sending -> DockPresentation(
            headline = "Asking $name…",
            subline = what,
            ring = null,
            spinning = true,
            actions = emptyList(),
        )
        is OutgoingJoinRequestState.Pending -> {
            val remainingMs = (state.expiresAtMs - nowMs).coerceAtLeast(0L)
            DockPresentation(
                headline = "Asking $name to join",
                subline = "$what · ${clock(remainingMs)}",
                ring = (remainingMs.toFloat() / OutgoingJoinDefaultLifetimeMs).coerceIn(0f, 1f),
                spinning = false,
                actions = listOf(DockAction("Cancel", onClick = { store.cancel() })),
                detail = "You'll join automatically when $name lets you in",
            )
        }
        is OutgoingJoinRequestState.Cancelling -> DockPresentation(
            headline = "Cancelling…",
            subline = what,
            ring = null,
            spinning = true,
            actions = listOf(DockAction("Cancel", enabled = false, onClick = {})),
        )
        is OutgoingJoinRequestState.Accepted -> {
            val deadline = state.countdownDeadlineMs
            if (deadline != null) {
                val seconds = ((deadline - nowMs + 999) / 1000).coerceAtLeast(0)
                DockPresentation(
                    headline = "$name let you in · Joining in $seconds…",
                    subline = what,
                    ring = ((deadline - nowMs).toFloat() / OutgoingJoinAcceptCountdownMs).coerceIn(0f, 1f),
                    spinning = false,
                    actions = listOf(DockAction("Not now", onClick = { store.notNow() })),
                )
            } else {
                DockPresentation(
                    headline = "$name let you in",
                    subline = what,
                    ring = null,
                    spinning = false,
                    actions = listOf(
                        DockAction("Join", onClick = { store.joinNow() }),
                        DockAction("Not now", onClick = { store.notNow() }),
                    ),
                )
            }
        }
        is OutgoingJoinRequestState.Joining -> DockPresentation(
            headline = "Joining ${partyPossessive(name)} party…",
            subline = what,
            ring = null,
            spinning = true,
            actions = emptyList(),
        )
        is OutgoingJoinRequestState.Outcome -> when (state.kind) {
            OutgoingJoinOutcome.Cancelled -> DockPresentation("Request cancelled", what, null, false, emptyList())
            OutgoingJoinOutcome.Declined -> DockPresentation(
                "$name can't have company right now", what, null, false,
                listOf(DockAction("Ask again", onClick = { store.askAgain() })),
                onDismiss = { store.dismiss() },
            )
            OutgoingJoinOutcome.Expired -> DockPresentation(
                "No answer from $name", what, null, false,
                listOf(DockAction("Ask again", onClick = { store.askAgain() })),
                onDismiss = { store.dismiss() },
            )
            OutgoingJoinOutcome.TargetStopped -> DockPresentation(
                "$name stopped watching", what, null, false, emptyList(),
                onDismiss = { store.dismiss() },
            )
            OutgoingJoinOutcome.Failed -> DockPresentation(
                state.message ?: "Couldn't join", what, null, false,
                listOf(DockAction("Try again", onClick = { store.askAgain() })),
                onDismiss = { store.dismiss() },
            )
        }
    }
}

private fun clock(ms: Long): String {
    val total = ms / 1000
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}
