package com.nuvio.app.features.social

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.PlatformBackHandler
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.social_audience_done
import nuvio.composeapp.generated.resources.social_audience_edit
import nuvio.composeapp.generated.resources.social_audience_everyone
import nuvio.composeapp.generated.resources.social_audience_everyone_desc
import nuvio.composeapp.generated.resources.social_audience_names_more
import nuvio.composeapp.generated.resources.social_audience_names_two
import nuvio.composeapp.generated.resources.social_audience_except
import nuvio.composeapp.generated.resources.social_audience_except_desc
import nuvio.composeapp.generated.resources.social_audience_no_friends
import nuvio.composeapp.generated.resources.social_audience_nobody
import nuvio.composeapp.generated.resources.social_audience_nobody_desc
import nuvio.composeapp.generated.resources.social_audience_notify_desc
import nuvio.composeapp.generated.resources.social_audience_notify_title
import nuvio.composeapp.generated.resources.social_audience_only
import nuvio.composeapp.generated.resources.social_audience_only_desc
import nuvio.composeapp.generated.resources.social_audience_pick_except
import nuvio.composeapp.generated.resources.social_audience_pick_only
import nuvio.composeapp.generated.resources.social_audience_recent_desc
import nuvio.composeapp.generated.resources.social_audience_recent_title
import nuvio.composeapp.generated.resources.social_audience_watching_desc
import nuvio.composeapp.generated.resources.social_audience_watching_title
import nuvio.composeapp.generated.resources.social_back
import nuvio.composeapp.generated.resources.social_banner_desc
import nuvio.composeapp.generated.resources.social_banner_invalid
import nuvio.composeapp.generated.resources.social_banner_placeholder
import nuvio.composeapp.generated.resources.social_banner_save
import nuvio.composeapp.generated.resources.social_banner_title
import nuvio.composeapp.generated.resources.social_cancel
import nuvio.composeapp.generated.resources.social_search_friends
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/*
 * Audience privacy (2026-10-24, maintainer's pick P2): Social settings shows one row per setting with
 * who it reaches; the row opens a page of four option cards; "except" and "only" show who is listed
 * with Edit, which opens a friend picker. Never expand-in-place (P1, rejected).
 */

internal val SocialAudienceSetting.titleRes: StringResource
    get() = when (this) {
        SocialAudienceSetting.WatchingNow -> Res.string.social_audience_watching_title
        SocialAudienceSetting.Recent -> Res.string.social_audience_recent_title
        SocialAudienceSetting.Notify -> Res.string.social_audience_notify_title
    }

private val SocialAudienceSetting.descriptionRes: StringResource
    get() = when (this) {
        SocialAudienceSetting.WatchingNow -> Res.string.social_audience_watching_desc
        SocialAudienceSetting.Recent -> Res.string.social_audience_recent_desc
        SocialAudienceSetting.Notify -> Res.string.social_audience_notify_desc
    }

private val AudienceOptions = listOf(
    Triple(SocialAudienceMode.Everyone, Res.string.social_audience_everyone, Res.string.social_audience_everyone_desc),
    Triple(SocialAudienceMode.Except, Res.string.social_audience_except, Res.string.social_audience_except_desc),
    Triple(SocialAudienceMode.Only, Res.string.social_audience_only, Res.string.social_audience_only_desc),
    Triple(SocialAudienceMode.Nobody, Res.string.social_audience_nobody, Res.string.social_audience_nobody_desc),
)

/**
 * One setting's page. Picking "except" or "only" with an empty remembered list opens the picker
 * straight away, and the mode is saved with the list on Done - so the page never sits on "No one,
 * except..." with no one in it.
 */
@Composable
internal fun SocialAudiencePage(
    setting: SocialAudienceSetting,
    audience: SocialAudience,
    friends: List<SocialProfileSummary>,
    wide: Boolean,
    onBack: () -> Unit,
    onSet: (mode: SocialAudienceMode, members: List<String>?) -> Unit,
) {
    PlatformBackHandler(enabled = true, onBack = onBack)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var picking by remember { mutableStateOf<SocialAudienceMode?>(null) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(Res.string.social_back)) }
        }
        Text(stringResource(setting.titleRes), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            stringResource(setting.descriptionRes),
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
            modifier = Modifier.padding(top = 4.dp, bottom = 18.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AudienceOptions.forEach { (mode, label, description) ->
                val selected = audience.mode == mode
                val shape = RoundedCornerShape(18.dp)
                Surface(
                    shape = shape,
                    color = if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                        .then(if (selected) Modifier.border(1.5.dp, Color.White.copy(alpha = 0.8f), shape) else Modifier)
                        .clip(shape)
                        .clickable(role = Role.RadioButton) {
                            val listed = when (mode) {
                                SocialAudienceMode.Except -> audience.except
                                SocialAudienceMode.Only -> audience.only
                                else -> null
                            }
                            when {
                                listed == null -> onSet(mode, null)
                                listed.none { id -> friends.any { it.profileId == id } } -> picking = mode
                                else -> onSet(mode, null)
                            }
                        },
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            AudienceRadio(selected)
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(label), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(stringResource(description), style = MaterialTheme.typography.bodySmall, color = muted)
                            }
                        }
                        if (selected && (mode == SocialAudienceMode.Except || mode == SocialAudienceMode.Only)) {
                            val listed = audience.members.mapNotNull { id -> friends.firstOrNull { it.profileId == id } }
                            Row(
                                Modifier.padding(start = 38.dp, top = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                AudienceFaces(listed.take(4), 30.dp)
                                Text(
                                    socialAudienceNames(listed),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                SocialPillButton(stringResource(Res.string.social_audience_edit), { picking = mode })
                            }
                        }
                    }
                }
            }
        }
    }
    picking?.let { mode ->
        SocialAudiencePicker(
            setting = setting,
            mode = mode,
            initial = if (mode == SocialAudienceMode.Except) audience.except else audience.only,
            friends = friends,
            wide = wide,
            onDismiss = { picking = null },
            onDone = { members ->
                picking = null
                onSet(mode, members)
            },
        )
    }
}

@Composable
private fun socialAudienceNames(listed: List<SocialProfileSummary>): String {
    val names = listed.map { it.displayName.ifBlank { it.handle } }
    return when (names.size) {
        0 -> ""
        1 -> names[0]
        2 -> stringResource(Res.string.social_audience_names_two, names[0], names[1])
        else -> stringResource(Res.string.social_audience_names_more, names[0], names[1], names.size - 2)
    }
}

@Composable
internal fun SocialAudiencePicker(
    setting: SocialAudienceSetting,
    mode: SocialAudienceMode,
    initial: List<String>,
    friends: List<SocialProfileSummary>,
    wide: Boolean,
    onDismiss: () -> Unit,
    onDone: (List<String>) -> Unit,
) {
    var picked by remember(mode) { mutableStateOf(initial.filter { id -> friends.any { it.profileId == id } }.toSet()) }
    var query by remember { mutableStateOf("") }
    val shown = remember(friends, query) {
        val q = query.trim().lowercase()
        friends.filter { q.isEmpty() || it.handle.lowercase().contains(q) || it.displayName.lowercase().contains(q) }
            .sortedBy { it.displayName.ifBlank { it.handle }.lowercase() }
    }
    SocialPickerFrame(
        wide = wide,
        onDismiss = onDismiss,
        header = {
            Text(stringResource(setting.titleRes), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                stringResource(if (mode == SocialAudienceMode.Except) Res.string.social_audience_pick_except else Res.string.social_audience_pick_only),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (friends.size > 6) {
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
        list = {
            if (friends.isEmpty()) {
                Text(stringResource(Res.string.social_audience_no_friends), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            shown.forEach { friend ->
                val on = friend.profileId in picked
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Checkbox) { picked = if (on) picked - friend.profileId else picked + friend.profileId }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SocialAvatar(friend.displayName, friend.avatarUrl, friend.avatarColorHex, 40.dp)
                    Column(Modifier.weight(1f)) {
                        Text(friend.displayName.ifBlank { friend.handle }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("@${friend.handle}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    AudienceCheck(on)
                }
            }
        },
        footer = {
            SocialPillButton(
                pluralStringResource(Res.plurals.social_audience_done, picked.size, picked.size),
                { onDone(friends.filter { it.profileId in picked }.map { it.profileId }) },
                Modifier.fillMaxWidth(),
                primary = true,
                compact = false,
            )
        },
    )
}

@Composable
private fun AudienceRadio(selected: Boolean) {
    Box(
        Modifier.size(24.dp).border(2.dp, if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(12.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
private fun AudienceCheck(on: Boolean) {
    Box(
        Modifier.size(24.dp).clip(RoundedCornerShape(7.dp))
            .background(if (on) Color.White else Color.Transparent)
            .border(2.dp, if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center,
    ) { if (on) Text("✓", color = Color.Black, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium) }
}

@Composable
private fun AudienceFaces(people: List<SocialProfileSummary>, size: Dp) {
    Box(Modifier.width(size + size * 0.7f * (people.size - 1).coerceAtLeast(0))) {
        people.forEachIndexed { index, person ->
            Box(Modifier.padding(start = size * 0.7f * index)) {
                SocialAvatar(person.displayName, person.avatarUrl, person.avatarColorHex, size)
            }
        }
    }
}

/** An https link, like the avatar link in profile settings but https only (the server checks the same). */
internal fun isValidSocialBannerUrl(url: String): Boolean {
    val trimmed = url.trim()
    return trimmed.length <= 2048 && trimmed.startsWith("https://", ignoreCase = true) &&
        trimmed.length > "https://".length && trimmed.none(Char::isWhitespace)
}

/** The banner link, set like a custom avatar: paste a link, or leave it empty for recent titles. */
@Composable
internal fun SocialBannerDialog(current: String?, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var draft by remember { mutableStateOf(current.orEmpty()) }
    val invalid = draft.isNotBlank() && !isValidSocialBannerUrl(draft)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.social_banner_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(Res.string.social_banner_desc), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    isError = invalid,
                    placeholder = { Text(stringResource(Res.string.social_banner_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (invalid) {
                    Text(stringResource(Res.string.social_banner_invalid), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (!invalid && draft.isNotBlank()) {
                    Box(Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(12.dp))) {
                        SocialArtwork(listOf(draft.trim()), draft, Modifier.fillMaxWidth().height(120.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft.trim().ifBlank { null }) }, enabled = !invalid) {
                Text(stringResource(Res.string.social_banner_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.social_cancel)) } },
    )
}
