package com.nuvio.app.features.social

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.watchparty.currentEpochMs
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.date_month_april
import nuvio.composeapp.generated.resources.date_month_august
import nuvio.composeapp.generated.resources.date_month_december
import nuvio.composeapp.generated.resources.date_month_february
import nuvio.composeapp.generated.resources.date_month_january
import nuvio.composeapp.generated.resources.date_month_july
import nuvio.composeapp.generated.resources.date_month_june
import nuvio.composeapp.generated.resources.date_month_march
import nuvio.composeapp.generated.resources.date_month_may
import nuvio.composeapp.generated.resources.date_month_november
import nuvio.composeapp.generated.resources.date_month_october
import nuvio.composeapp.generated.resources.date_month_september
import nuvio.composeapp.generated.resources.date_month_short_apr
import nuvio.composeapp.generated.resources.date_month_short_aug
import nuvio.composeapp.generated.resources.date_month_short_dec
import nuvio.composeapp.generated.resources.date_month_short_feb
import nuvio.composeapp.generated.resources.date_month_short_jan
import nuvio.composeapp.generated.resources.date_month_short_jul
import nuvio.composeapp.generated.resources.date_month_short_jun
import nuvio.composeapp.generated.resources.date_month_short_mar
import nuvio.composeapp.generated.resources.date_month_short_may
import nuvio.composeapp.generated.resources.date_month_short_nov
import nuvio.composeapp.generated.resources.date_month_short_oct
import nuvio.composeapp.generated.resources.date_month_short_sep
import nuvio.composeapp.generated.resources.social_ask_to_join
import nuvio.composeapp.generated.resources.social_audience_everyone
import nuvio.composeapp.generated.resources.social_audience_except_names
import nuvio.composeapp.generated.resources.social_audience_names_more
import nuvio.composeapp.generated.resources.social_audience_names_two
import nuvio.composeapp.generated.resources.social_audience_nobody
import nuvio.composeapp.generated.resources.social_audience_only_names
import nuvio.composeapp.generated.resources.social_back
import nuvio.composeapp.generated.resources.social_cancel
import nuvio.composeapp.generated.resources.social_cancel_request
import nuvio.composeapp.generated.resources.social_cancelling
import nuvio.composeapp.generated.resources.social_details
import nuvio.composeapp.generated.resources.social_join_now
import nuvio.composeapp.generated.resources.social_joining
import nuvio.composeapp.generated.resources.social_nothing_shared
import nuvio.composeapp.generated.resources.social_profile_between
import nuvio.composeapp.generated.resources.social_profile_earlier
import nuvio.composeapp.generated.resources.social_profile_history
import nuvio.composeapp.generated.resources.social_profile_history_failed
import nuvio.composeapp.generated.resources.social_profile_month_titles
import nuvio.composeapp.generated.resources.social_profile_notify
import nuvio.composeapp.generated.resources.social_profile_now
import nuvio.composeapp.generated.resources.social_profile_sees_now
import nuvio.composeapp.generated.resources.social_profile_sees_recent
import nuvio.composeapp.generated.resources.social_profile_see_all
import nuvio.composeapp.generated.resources.social_profile_show_earlier
import nuvio.composeapp.generated.resources.social_profile_show_less
import nuvio.composeapp.generated.resources.social_profile_stat_parties
import nuvio.composeapp.generated.resources.social_profile_stat_together
import nuvio.composeapp.generated.resources.social_profile_with_you
import nuvio.composeapp.generated.resources.social_profile_you_two
import nuvio.composeapp.generated.resources.social_remove
import nuvio.composeapp.generated.resources.social_remove_friend
import nuvio.composeapp.generated.resources.social_remove_friend_body
import nuvio.composeapp.generated.resources.social_remove_title
import nuvio.composeapp.generated.resources.social_watched_together
import nuvio.composeapp.generated.resources.social_try_again
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A friend's profile as a page (2026-10-24), replacing the bottom sheet the maintainer found ugly.
 *
 * Picked from `SocialFriendProfileHarness`: candidate E's chrome (a banner, avatar and name; the Now
 * card; a "You two" card with together stats, Watched together and the per-friend switches) with
 * history as H2a, one shelf of captioned stills per month. Wide windows put "You two" in a column
 * beside the history; phones put it behind a History / You two switch (M3), never above the history.
 *
 * The banner is the friend's own picture when they set one ([SocialProfileSummary.bannerUrl]),
 * otherwise a strip of what they watched lately.
 */
@Composable
internal fun SocialFriendProfilePage(
    friend: SocialProfileSummary,
    friends: List<SocialProfileSummary>,
    audiences: SocialAudiences?,
    watching: WatchingNowItem?,
    affordance: WatchingNowJoinAffordance,
    wide: Boolean,
    topInset: Dp,
    utcOffsetMs: Long,
    onBack: () -> Unit,
    onJoin: (WatchingNowItem) -> Unit,
    onCancelRequest: () -> Unit,
    onWatchingDetails: (WatchingNowItem) -> Unit,
    onOpenContent: (contentType: String, contentId: String, title: String) -> Unit,
    onSetAudienceMember: (SocialAudienceSetting, friendProfileId: String, allowed: Boolean) -> Unit,
    onRemove: () -> Unit,
    loadStats: suspend (friendProfileId: String) -> SocialTogetherStats?,
    loadHistory: suspend (friendProfileId: String, before: SocialActivityCursor?) -> Result<FriendHistoryPage>,
    /** For the render harness: a page to start from instead of loading one. */
    initialHistory: FriendHistoryPage? = null,
    initialStats: SocialTogetherStats? = null,
    initialPhoneTab: Int = 0,
) {
    PlatformBackHandler(enabled = true, onBack = onBack)
    val scope = rememberCoroutineScope()
    val runs = remember(friend.profileId) { mutableStateListOf<RecentActivityRun>().apply { initialHistory?.let { addAll(it.runs) } } }
    var next by remember(friend.profileId) { mutableStateOf(initialHistory?.next) }
    var loaded by remember(friend.profileId) { mutableStateOf(initialHistory != null) }
    var loadingMore by remember(friend.profileId) { mutableStateOf(false) }
    var failed by remember(friend.profileId) { mutableStateOf(false) }
    var stats by remember(friend.profileId) { mutableStateOf(initialStats) }
    var phoneTab by remember(friend.profileId) { mutableStateOf(initialPhoneTab) }
    var confirmRemove by remember { mutableStateOf(false) }

    LaunchedEffect(friend.profileId) {
        if (initialStats == null) stats = loadStats(friend.profileId)
    }
    LaunchedEffect(friend.profileId) {
        if (initialHistory != null) return@LaunchedEffect
        loadHistory(friend.profileId, null)
            .onSuccess { page -> runs.clear(); runs.addAll(page.runs); next = page.next; failed = false }
            .onFailure { failed = true }
        loaded = true
    }
    val loadEarlier: () -> Unit = {
        val cursor = next
        if ((cursor != null || failed) && !loadingMore) {
            loadingMore = true
            scope.launch {
                loadHistory(friend.profileId, cursor)
                    .onSuccess { page ->
                        if (cursor == null) runs.clear()
                        runs.addAll(page.runs.filter { new -> runs.none { it.runId == new.runId } })
                        next = page.next
                        failed = false
                    }
                    .onFailure { failed = true }
                loadingMore = false
                loaded = true
            }
        }
    }
    val months = remember(runs.toList(), utcOffsetMs) { friendHistoryMonths(runs, utcOffsetMs) }
    val togetherIds = remember(stats) { stats?.titles?.mapNotNull { it.contentId }?.toSet().orEmpty() }

    val history: @Composable () -> Unit = {
        ProfileHistory(
            months = months,
            wide = wide,
            loaded = loaded,
            failed = failed,
            canLoadEarlier = next != null,
            loadingMore = loadingMore,
            togetherIds = togetherIds,
            onLoadEarlier = loadEarlier,
            onOpenContent = onOpenContent,
        )
    }
    val youTwo: @Composable () -> Unit = {
        YouTwoCard(
            friend = friend,
            friends = friends,
            audiences = audiences,
            stats = stats,
            onOpenContent = onOpenContent,
            onSetAudienceMember = onSetAudienceMember,
            onRemove = { confirmRemove = true },
        )
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ProfileBanner(friend, months, watching, wide, topInset)
            if (wide) {
                Row(
                    Modifier.padding(horizontal = 48.dp).padding(top = 24.dp, bottom = nuvioSafeBottomPadding(extra = 24.dp)),
                    horizontalArrangement = Arrangement.spacedBy(40.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        watching?.let { NowCard(it, affordance, wide = true, onJoin, onCancelRequest, onWatchingDetails) }
                        history()
                    }
                    Column(Modifier.width(380.dp)) { youTwo() }
                }
            } else {
                Column(
                    Modifier.padding(horizontal = 16.dp).padding(top = 16.dp, bottom = nuvioSafeBottomPadding(extra = 24.dp)),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    watching?.let { NowCard(it, affordance, wide = false, onJoin, onCancelRequest, onWatchingDetails) }
                    ProfileTabs(phoneTab) { phoneTab = it }
                    if (phoneTab == 0) history() else youTwo()
                }
            }
        }
        Box(
            Modifier.padding(top = topInset + 12.dp, start = if (wide) 24.dp else 12.dp).size(40.dp).clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f)).clickable(role = Role.Button, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(Res.string.social_back), Modifier.size(22.dp), tint = Color.White)
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(Res.string.social_remove_title, friend.displayName)) },
            text = { Text(stringResource(Res.string.social_remove_friend_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    onRemove()
                }) { Text(stringResource(Res.string.social_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(Res.string.social_cancel)) } },
        )
    }
}

// --- header ----------------------------------------------------------------------------------

@Composable
private fun ProfileBanner(
    friend: SocialProfileSummary,
    months: List<FriendHistoryMonth>,
    watching: WatchingNowItem?,
    wide: Boolean,
    topInset: Dp,
) {
    val bg = MaterialTheme.colorScheme.background
    val live = watching?.state == SocialPlaybackState.playing
    // Clipped: the artwork's placeholder glow draws past its own bounds and showed as a haze under the name.
    Box(Modifier.fillMaxWidth().height((if (wide) 250.dp else 230.dp) + topInset).clipToBounds()) {
        val banner = friend.bannerUrl?.takeIf(String::isNotBlank)
        if (banner != null) {
            SocialArtwork(listOf(banner), friend.handle, Modifier.matchParentSize())
        } else {
            // A mix of what they watched lately: newest first, one tile per title.
            val recent = remember(months) { months.flatMap { it.entries }.distinctBy { it.group.contentId }.take(if (wide) 5 else 3) }
            if (recent.isEmpty()) {
                SocialArtwork(emptyList(), friend.handle, Modifier.matchParentSize())
            } else {
                Row(Modifier.matchParentSize()) {
                    recent.forEach { entry ->
                        SocialArtwork(entry.group.artwork(), entry.group.title, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
        // Light over the picture, then the backdrop dissolves into the page under the name. Phones keep
        // less shade at the top: there is no tab bar to read over it there.
        val topShade = if (wide) 0.4f else 0.2f
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to bg.copy(alpha = topShade),
                    0.45f to bg.copy(alpha = topShade * 0.5f),
                    0.8f to bg.copy(alpha = 0.75f),
                    1f to bg,
                ),
            ),
        )
        Row(
            Modifier.align(Alignment.BottomStart).padding(horizontal = if (wide) 48.dp else 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val size = if (wide) 96.dp else 76.dp
            Box(Modifier.size(size), contentAlignment = Alignment.Center) {
                if (live) Box(Modifier.fillMaxSize().border(3.dp, SocialLiveColor, CircleShape))
                SocialAvatar(friend.displayName, friend.avatarUrl, friend.avatarColorHex, size * 0.88f)
            }
            Column(Modifier.weight(1f, fill = false)) {
                Text(
                    friend.displayName.ifBlank { friend.handle },
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("@${friend.handle}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun NowCard(
    item: WatchingNowItem,
    affordance: WatchingNowJoinAffordance,
    wide: Boolean,
    onJoin: (WatchingNowItem) -> Unit,
    onCancelRequest: () -> Unit,
    onDetails: (WatchingNowItem) -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints {
            // The history column is much narrower than the window beside "You two". Keep the title
            // readable there by putting the actions below it until this card has room for all three.
            val inlineActions = wide && maxWidth >= 760.dp
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.width(if (wide) 220.dp else 120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button) { onDetails(item) },
                ) {
                    SocialArtwork(
                        SocialArtRef(item.title, item.contentType, item.contentId, item.poster, listOf(item.background, item.episodeThumbnail),
                            item.videoId, item.season, item.episode).artwork(),
                        item.title,
                        Modifier.matchParentSize(),
                    )
                    SocialProgressEdge(item.progressFraction, Modifier.align(Alignment.BottomStart))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(SocialLiveColor))
                        Text(
                            stringResource(Res.string.social_profile_now).uppercase(),
                            color = SocialLiveColor,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                        )
                    }
                    Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    item.socialMeta().takeIf(String::isNotBlank)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!inlineActions) {
                        Spacer(Modifier.height(6.dp))
                        NowActions(item, affordance, wide = false, onJoin, onCancelRequest, onDetails)
                    }
                }
                if (inlineActions) NowActions(item, affordance, wide = true, onJoin, onCancelRequest, onDetails)
            }
        }
    }
}

@Composable
private fun NowActions(
    item: WatchingNowItem,
    affordance: WatchingNowJoinAffordance,
    wide: Boolean,
    onJoin: (WatchingNowItem) -> Unit,
    onCancelRequest: () -> Unit,
    onDetails: (WatchingNowItem) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (wide) SocialPillButton(stringResource(Res.string.social_details), { onDetails(item) }, compact = false)
        when (affordance) {
            WatchingNowJoinAffordance.Join ->
                SocialPillButton(stringResource(Res.string.social_join_now), { onJoin(item) }, primary = true, compact = !wide)
            WatchingNowJoinAffordance.AskToJoin ->
                SocialPillButton(stringResource(Res.string.social_ask_to_join), { onJoin(item) }, primary = true, compact = !wide)
            is WatchingNowJoinAffordance.Requested -> SocialPillButton(
                if (affordance.cancelling) stringResource(Res.string.social_cancelling) else stringResource(Res.string.social_cancel_request),
                onCancelRequest,
                compact = !wide,
                enabled = !affordance.cancelling,
            )
            WatchingNowJoinAffordance.Joining ->
                SocialPillButton(stringResource(Res.string.social_joining), {}, compact = !wide, enabled = false)
            else -> Unit
        }
    }
}

@Composable
private fun ProfileTabs(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)).padding(4.dp),
    ) {
        listOf(Res.string.social_profile_history, Res.string.social_profile_you_two).forEachIndexed { index, label ->
            val on = index == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(999.dp))
                    .background(if (on) Color.White else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(index) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(label),
                    color = if (on) Color.Black else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

// --- history ---------------------------------------------------------------------------------

private val MonthNames: List<StringResource> = listOf(
    Res.string.date_month_january, Res.string.date_month_february, Res.string.date_month_march,
    Res.string.date_month_april, Res.string.date_month_may, Res.string.date_month_june,
    Res.string.date_month_july, Res.string.date_month_august, Res.string.date_month_september,
    Res.string.date_month_october, Res.string.date_month_november, Res.string.date_month_december,
)

private val MonthShortNames: List<StringResource> = listOf(
    Res.string.date_month_short_jan, Res.string.date_month_short_feb, Res.string.date_month_short_mar,
    Res.string.date_month_short_apr, Res.string.date_month_short_may, Res.string.date_month_short_jun,
    Res.string.date_month_short_jul, Res.string.date_month_short_aug, Res.string.date_month_short_sep,
    Res.string.date_month_short_oct, Res.string.date_month_short_nov, Res.string.date_month_short_dec,
)

@Composable
private fun ProfileHistory(
    months: List<FriendHistoryMonth>,
    wide: Boolean,
    loaded: Boolean,
    failed: Boolean,
    canLoadEarlier: Boolean,
    loadingMore: Boolean,
    togetherIds: Set<String>,
    onLoadEarlier: () -> Unit,
    onOpenContent: (contentType: String, contentId: String, title: String) -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val currentYear = remember { civilDate(currentEpochMs()).first }
    val expanded = remember { mutableStateListOf<Pair<Int, Int>>() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            !loaded -> Unit
            months.isEmpty() && failed -> Text(stringResource(Res.string.social_profile_history_failed), color = muted, style = MaterialTheme.typography.bodyMedium)
            months.isEmpty() -> Text(stringResource(Res.string.social_nothing_shared), color = muted, style = MaterialTheme.typography.bodyMedium)
        }
        months.forEach { month ->
            val key = month.year to month.month
            val open = key in expanded
            val label = if (month.month in 1..12) {
                val name = stringResource(MonthNames[month.month - 1])
                if (month.year == currentYear) name else "$name ${month.year}"
            } else {
                stringResource(Res.string.social_profile_earlier)
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    pluralStringResource(Res.plurals.social_profile_month_titles, month.entries.size, month.entries.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                    modifier = Modifier.padding(bottom = 2.dp).weight(1f),
                )
                if (month.entries.size > 2) {
                    Text(
                        stringResource(if (open) Res.string.social_profile_show_less else Res.string.social_profile_see_all),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button) { if (open) expanded.remove(key) else expanded.add(key) }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            val tileWidth = if (wide) 290.dp else 230.dp
            if (open) {
                val columns = if (wide) 3 else 2
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    month.entries.chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { entry -> HistoryTile(entry, month.month, entry.group.contentId in togetherIds, Modifier.weight(1f), onOpenContent) }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    month.entries.forEach { entry -> HistoryTile(entry, month.month, entry.group.contentId in togetherIds, Modifier.width(tileWidth), onOpenContent) }
                }
            }
        }
        if (canLoadEarlier || failed) {
            Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                SocialPillButton(stringResource(if (failed) Res.string.social_try_again else Res.string.social_profile_show_earlier), onLoadEarlier, compact = false, enabled = !loadingMore)
            }
        }
    }
}

/** H2a: the still with its title, date and what on it. */
@Composable
private fun HistoryTile(
    entry: FriendHistoryEntry,
    /** The shelf's month (1-12, 0 for unreadable times): the latest run is in it by construction. */
    month: Int,
    withYou: Boolean,
    modifier: Modifier,
    onOpenContent: (contentType: String, contentId: String, title: String) -> Unit,
) {
    val group = entry.group
    val title = group.displayTitle().ifBlank { group.title }
    val date = if (entry.day != null && month in 1..12) "${entry.day} ${stringResource(MonthShortNames[month - 1])}" else null
    val meta = listOfNotNull(date, group.contextLabel().takeIf(String::isNotBlank)).joinToString(" · ")
    Box(
        modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button) { onOpenContent(group.contentType, group.contentId, title) },
    ) {
        SocialArtwork(group.artwork(), group.title, Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
        if (withYou) {
            SocialGlassChip(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Text(stringResource(Res.string.social_profile_with_you), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (meta.isNotBlank()) {
                Text(meta, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// --- you two ---------------------------------------------------------------------------------

@Composable
private fun YouTwoCard(
    friend: SocialProfileSummary,
    friends: List<SocialProfileSummary>,
    audiences: SocialAudiences?,
    stats: SocialTogetherStats?,
    onOpenContent: (contentType: String, contentId: String, title: String) -> Unit,
    onSetAudienceMember: (SocialAudienceSetting, friendProfileId: String, allowed: Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(Res.string.social_profile_you_two), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            stats?.takeIf { it.parties > 0 }?.let { together ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigStat(socialTogetherDuration(together.seconds), stringResource(Res.string.social_profile_stat_together), Modifier.weight(1f))
                    BigStat(
                        together.parties.toString(),
                        pluralStringResource(Res.plurals.social_profile_stat_parties, together.parties),
                        Modifier.weight(1f),
                    )
                }
            }
            stats?.titles?.takeIf { it.isNotEmpty() }?.let { titles ->
                Text(
                    stringResource(Res.string.social_watched_together).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = muted,
                    fontWeight = FontWeight.Bold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    titles.take(3).forEach { item ->
                        Box(
                            Modifier.weight(1f).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).then(
                                if (item.contentId != null && item.contentType != null) {
                                    Modifier.clickable(role = Role.Button) { onOpenContent(item.contentType, item.contentId, item.title) }
                                } else {
                                    Modifier
                                },
                            ),
                        ) {
                            SocialArtwork(SocialArtRef(item.title, item.contentType, item.contentId, item.poster).artwork(), item.title, Modifier.matchParentSize())
                        }
                    }
                    repeat(3 - titles.take(3).size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (audiences != null) {
                HairLine()
                Text(stringResource(Res.string.social_profile_between), style = MaterialTheme.typography.labelLarge, color = muted, fontWeight = FontWeight.SemiBold)
                val name = friend.displayName.ifBlank { friend.handle }
                listOf(
                    Triple(SocialAudienceSetting.WatchingNow, Icons.Rounded.Visibility, stringResource(Res.string.social_profile_sees_now)),
                    Triple(SocialAudienceSetting.Recent, Icons.Rounded.VideoLibrary, stringResource(Res.string.social_profile_sees_recent)),
                    Triple(SocialAudienceSetting.Notify, Icons.Rounded.Notifications, stringResource(Res.string.social_profile_notify, name)),
                ).forEach { (setting, icon, title) ->
                    val audience = audiences[setting]
                    ControlRow(icon, title, socialAudienceSummary(audience, friends), audience.allows(friend.profileId)) { allowed ->
                        onSetAudienceMember(setting, friend.profileId, allowed)
                    }
                }
            }
            HairLine()
            Text(
                stringResource(Res.string.social_remove_friend),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onRemove).padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ControlRow(icon: ImageVector, title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Switch) { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = muted)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun BigStat(value: String, label: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)).padding(14.dp)) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun HairLine() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)))
}

/** "All my friends", "All my friends, except ben and Mika", "No one, except faisal, Seraph +2", "No one". */
@Composable
internal fun socialAudienceSummary(audience: SocialAudience, friends: List<SocialProfileSummary>): String {
    val names = audience.members.mapNotNull { id -> friends.firstOrNull { it.profileId == id } }
        .map { it.displayName.ifBlank { it.handle } }
    val joined = when (names.size) {
        0 -> ""
        1 -> names[0]
        2 -> stringResource(Res.string.social_audience_names_two, names[0], names[1])
        else -> stringResource(Res.string.social_audience_names_more, names[0], names[1], names.size - 2)
    }
    return when {
        audience.mode == SocialAudienceMode.Everyone || (audience.mode == SocialAudienceMode.Except && names.isEmpty()) ->
            stringResource(Res.string.social_audience_everyone)
        audience.mode == SocialAudienceMode.Nobody || (audience.mode == SocialAudienceMode.Only && names.isEmpty()) ->
            stringResource(Res.string.social_audience_nobody)
        audience.mode == SocialAudienceMode.Except -> stringResource(Res.string.social_audience_except_names, joined)
        else -> stringResource(Res.string.social_audience_only_names, joined)
    }
}
