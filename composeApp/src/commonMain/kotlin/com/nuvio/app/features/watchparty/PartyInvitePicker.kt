package com.nuvio.app.features.watchparty

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.PartyInviteButton
import com.nuvio.app.features.player.nextPartyInviteCooldownEndMs
import com.nuvio.app.features.social.SentPartyInvite
import com.nuvio.app.features.social.SocialAvatar
import com.nuvio.app.features.social.SocialPillButton
import com.nuvio.app.features.social.SocialPickerFrame
import com.nuvio.app.features.social.WatchingNowItem
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.social_cancelling
import nuvio.composeapp.generated.resources.social_friend_watching
import nuvio.composeapp.generated.resources.social_party_invite_cancel
import nuvio.composeapp.generated.resources.social_party_invite_declined
import nuvio.composeapp.generated.resources.social_party_invite_friends
import nuvio.composeapp.generated.resources.social_party_invite_pick
import nuvio.composeapp.generated.resources.social_party_invite_send
import nuvio.composeapp.generated.resources.social_party_invite_title
import nuvio.composeapp.generated.resources.social_search_friends
import nuvio.composeapp.generated.resources.social_sending
import nuvio.composeapp.generated.resources.social_sent
import nuvio.composeapp.generated.resources.social_watching_now_label
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Watch Together invite picker (2026-10-19, candidate C of `PartyInviteListHarness`). The lobby drew every friend as
 * a chip in a wrapping cloud and the phone player listed every friend inline, which ran away with twenty friends.
 * The existing Invite buttons now open this: search, friends watching right now first, check circles, and one
 * button for everyone picked. The desktop player's HTML panel draws the same picker in `controls.js`.
 */
data class PartyInviteRow(
    /** What the caller invites by: a profile id in the lobby, the bridge index in the player. */
    val key: String,
    val name: String,
    val avatarUrl: String?,
    val colorHex: String?,
    /** What they are watching right now, from Watching Now; null when they are not. */
    val watching: String?,
    val button: PartyInviteButton,
)

/** Pure: friends watching right now first, then by name. */
fun partyInviteOrder(rows: List<PartyInviteRow>): List<PartyInviteRow> =
    rows.sortedWith(compareBy<PartyInviteRow>({ it.watching == null }, { it.name.lowercase() }))

/** Pure: the search box, on the name. */
fun partyInviteMatches(row: PartyInviteRow, query: String): Boolean =
    query.isBlank() || row.name.contains(query.trim(), ignoreCase = true)

/** Pure: what [profileId] is watching right now, as the picker's second line says it ("The Bear · S3 E4"). */
fun partyInviteWatching(profileId: String, watchingNow: List<WatchingNowItem>): String? =
    watchingNow.firstOrNull { it.profile.profileId == profileId }?.let { item ->
        val episode = if (item.season != null && item.episode != null) " · S${item.season} E${item.episode}" else ""
        item.title + episode
    }

/** Ticks only while a decline cooldown is running, so a Declined turns back into a check circle when it ends. */
@Composable
fun rememberPartyInviteNowMs(sent: List<SentPartyInvite>?, sentAtMs: Long): Long {
    val now by produceState(currentEpochMs(), sent, sentAtMs) {
        while (true) {
            value = currentEpochMs()
            val next = nextPartyInviteCooldownEndMs(sent, sentAtMs, value) ?: break
            delay((next - value).coerceAtLeast(0L) + 50L)
        }
    }
    return now
}

/**
 * [slots] is how many more people the party can take; picking stops there. [onInvite] gets the picked keys in
 * list order and the picker closes; each invite then reads Sending, Sent (with a ✕ that calls [onCancel]) or
 * Declined the next time it opens.
 */
@Composable
fun PartyInvitePicker(
    rows: List<PartyInviteRow>,
    slots: Int,
    wide: Boolean,
    onDismiss: () -> Unit,
    onInvite: (List<String>) -> Unit,
    onCancel: (String) -> Unit,
) {
    var picked by remember { mutableStateOf<List<String>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    val ordered = remember(rows) { partyInviteOrder(rows) }
    // A friend whose invite went out from elsewhere (or who joined) while this was open is no longer pickable.
    val pickable = ordered.filter { it.button == PartyInviteButton.Invite }.map { it.key }.toSet()
    val chosen = picked.filter { it in pickable }
    val shown = ordered.filter { partyInviteMatches(it, query) }
    val toggle: (PartyInviteRow) -> Unit = { row ->
        picked = when {
            row.key in chosen -> chosen - row.key
            chosen.size < slots -> chosen + row.key
            else -> chosen
        }
    }
    SocialPickerFrame(
        wide = wide,
        onDismiss = onDismiss,
        header = {
            Text(stringResource(Res.string.social_party_invite_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (rows.size > 8) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(Res.string.social_search_friends)) },
                    shape = RoundedCornerShape(12.dp),
                )
            }
        },
        // The frame scrolls this alone and caps it, so the Invite button stays in reach however many friends there are.
        list = {
            val live = shown.filter { it.watching != null }
            val rest = shown.filter { it.watching == null }
            if (live.isNotEmpty()) {
                PartyInviteSection(stringResource(Res.string.social_watching_now_label))
                live.forEach { row -> PartyInvitePickRow(row, row.key in chosen, canPick = row.key in chosen || chosen.size < slots, onToggle = { toggle(row) }, onCancel = { onCancel(row.key) }) }
                if (rest.isNotEmpty()) PartyInviteSection(stringResource(Res.string.social_party_invite_friends))
            }
            rest.forEach { row -> PartyInvitePickRow(row, row.key in chosen, canPick = row.key in chosen || chosen.size < slots, onToggle = { toggle(row) }, onCancel = { onCancel(row.key) }) }
        },
        footer = {
            SocialPillButton(
                if (chosen.isEmpty()) stringResource(Res.string.social_party_invite_pick)
                else pluralStringResource(Res.plurals.social_party_invite_send, chosen.size, chosen.size),
                onClick = {
                    onInvite(chosen)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                primary = true,
                compact = false,
                enabled = chosen.isNotEmpty(),
            )
        },
    )
}

@Composable
private fun PartyInviteSection(label: String) {
    Text(
        label,
        Modifier.padding(top = 6.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PartyInvitePickRow(row: PartyInviteRow, picked: Boolean, canPick: Boolean, onToggle: () -> Unit, onCancel: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val open = row.button == PartyInviteButton.Invite
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(enabled = open && canPick, role = Role.Checkbox, onClick = onToggle)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SocialAvatar(row.name, row.avatarUrl, row.colorHex, 40.dp)
        Column(Modifier.weight(1f)) {
            Text(
                row.name,
                fontWeight = FontWeight.SemiBold,
                color = if (open) MaterialTheme.colorScheme.onSurface else muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            row.watching?.let {
                Text(
                    stringResource(Res.string.social_friend_watching, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = PartyReadyColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when (row.button) {
            PartyInviteButton.Invite -> PartyInviteCheck(picked, dimmed = !canPick)
            PartyInviteButton.Sending -> PartyInviteStateLabel(stringResource(Res.string.social_sending))
            PartyInviteButton.Cancelling -> PartyInviteStateLabel(stringResource(Res.string.social_cancelling))
            PartyInviteButton.Declined -> PartyInviteStateLabel(stringResource(Res.string.social_party_invite_declined))
            PartyInviteButton.Sent -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PartyInviteStateLabel(stringResource(Res.string.social_sent))
                // Withdraw it while it waits.
                val cancel = stringResource(Res.string.social_party_invite_cancel, row.name)
                Box(
                    Modifier.size(28.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        .clickable(role = Role.Button, onClickLabel = cancel, onClick = onCancel),
                    contentAlignment = Alignment.Center,
                ) { Text("✕", style = MaterialTheme.typography.labelMedium, color = muted) }
            }
        }
    }
}

@Composable
private fun PartyInviteStateLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PartyInviteCheck(on: Boolean, dimmed: Boolean) {
    Box(
        Modifier.size(24.dp).clip(CircleShape)
            .background(if (on) Color.White else Color.Transparent)
            .then(if (on) Modifier else Modifier.border(2.dp, Color.White.copy(alpha = if (dimmed) 0.15f else 0.4f), CircleShape)),
        contentAlignment = Alignment.Center,
    ) { if (on) Text("✓", color = Color.Black, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium) }
}
