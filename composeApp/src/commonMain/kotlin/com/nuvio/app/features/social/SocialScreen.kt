package com.nuvio.app.features.social

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.social_accept
import nuvio.composeapp.generated.resources.social_add_friend
import nuvio.composeapp.generated.resources.social_decline
import nuvio.composeapp.generated.resources.social_disabled
import nuvio.composeapp.generated.resources.social_friends
import nuvio.composeapp.generated.resources.social_handle
import nuvio.composeapp.generated.resources.social_handle_help
import nuvio.composeapp.generated.resources.social_inbox
import nuvio.composeapp.generated.resources.social_no_activity
import nuvio.composeapp.generated.resources.social_offline_cache
import nuvio.composeapp.generated.resources.social_recently_watched
import nuvio.composeapp.generated.resources.social_remove_friend
import nuvio.composeapp.generated.resources.social_save_handle
import nuvio.composeapp.generated.resources.social_search_handle
import nuvio.composeapp.generated.resources.social_share_recent
import nuvio.composeapp.generated.resources.social_share_watching
import nuvio.composeapp.generated.resources.social_title
import nuvio.composeapp.generated.resources.social_watching_now
import org.jetbrains.compose.resources.stringResource

@Composable
fun SocialScreen(
    modifier: Modifier = Modifier,
    scrollToTopRequests: Flow<Unit> = emptyFlow(),
    onOpenContent: (contentType: String, contentId: String, title: String) -> Unit = { _, _, _ -> },
    onJoinParty: (inviteCode: String) -> Unit = {},
    onJoinInvitedParty: (partyId: String) -> Unit = {},
) {
    val state by SocialRepository.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var handle by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SocialProfileSummary>>(emptyList()) }
    var partyCode by rememberSaveable { mutableStateOf("") }
    var shareWatching by rememberSaveable(state.me?.profileId) { mutableStateOf(true) }
    var shareRecent by rememberSaveable(state.me?.profileId) { mutableStateOf(true) }

    LaunchedEffect(state.me?.profileId, state.me?.shareWatchingNow, state.me?.shareRecentlyWatched) {
        shareWatching = state.me?.shareWatchingNow ?: true
        shareRecent = state.me?.shareRecentlyWatched ?: true
    }

    LaunchedEffect(scrollToTopRequests) {
        scrollToTopRequests.collect { listState.animateScrollToItem(0) }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.social_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { scope.launch { SocialRepository.refresh() } }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                }
            }
        }

        if (!state.capabilities.socialEnabled) {
            item { SocialMessageCard(stringResource(Res.string.social_disabled)) }
        } else if (state.needsHandleSetup) {
            item {
                SocialPanel {
                    Text(stringResource(Res.string.social_handle), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(Res.string.social_handle_help), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = handle,
                        onValueChange = { handle = normalizeSocialHandle(it).take(24) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(Res.string.social_handle)) },
                        isError = handle.isNotEmpty() && !isValidSocialHandle(handle),
                    )
                    Button(
                        onClick = { scope.launch { SocialRepository.setupHandle(handle) } },
                        enabled = isValidSocialHandle(handle),
                    ) { Text(stringResource(Res.string.social_save_handle)) }
                }
            }
        } else {
            if (state.isOfflineCache) item { SocialMessageCard(stringResource(Res.string.social_offline_cache)) }
            state.errorMessage?.let { error -> item { SocialMessageCard(error) } }

            if (state.capabilities.watchPartyEnabled) {
                item {
                    SocialPanel {
                        Text("Watch Together", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = partyCode,
                                onValueChange = { partyCode = it.trim().uppercase().take(32) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                label = { Text("Invite code") },
                            )
                            Button(onClick = { onJoinParty(partyCode) }, enabled = partyCode.isNotBlank()) { Text("Join") }
                        }
                    }
                }
            }

            if (state.requests.isNotEmpty()) {
                item { SocialSectionTitle(stringResource(Res.string.social_inbox), state.requests.size) }
                items(state.requests, key = { "request:${it.id}" }) { request ->
                    SocialPersonRow(request.sender, subtitle = "@${request.sender.handle}") {
                        IconButton(onClick = { scope.launch { SocialRepository.respondFriendRequest(request.id, true) } }) {
                            Icon(Icons.Rounded.Check, stringResource(Res.string.social_accept))
                        }
                        IconButton(onClick = { scope.launch { SocialRepository.respondFriendRequest(request.id, false) } }) {
                            Icon(Icons.Rounded.Close, stringResource(Res.string.social_decline))
                        }
                    }
                }
            }
            if (state.partyInvites.isNotEmpty()) {
                item { SocialSectionTitle("Watch Together invites", state.partyInvites.size) }
                items(state.partyInvites, key = { "party-invite:${it.id}" }) { invite ->
                    SocialPersonRow(invite.sender, subtitle = invite.content.title) {
                        Button(onClick = { onJoinInvitedParty(invite.partyId) }) { Text("Join") }
                    }
                }
            }

            if (state.watchingNow.isNotEmpty()) {
                item { SocialSectionTitle(stringResource(Res.string.social_watching_now)) }
                items(state.watchingNow, key = { "watching:${it.profile.profileId}:${it.videoId}" }) { item ->
                    SocialPanel(
                        modifier = Modifier.clickable { onOpenContent(item.contentType, item.contentId, item.title) },
                    ) {
                        Text("${item.profile.displayName} · ${if (item.state == SocialPlaybackState.playing) "Playing" else "Paused"}", style = MaterialTheme.typography.labelLarge)
                        Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        item.episode?.let { Text("S${item.season ?: 1} E$it${item.episodeTitle?.let { title -> " · $title" }.orEmpty()}") }
                        LinearProgressIndicator(progress = { item.progressFraction }, modifier = Modifier.fillMaxWidth())
                        Text("${item.roundedProgressPercent}%", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            item { SocialSectionTitle(stringResource(Res.string.social_recently_watched)) }
            if (state.activity.isEmpty() && !state.isLoading) item { SocialMessageCard(stringResource(Res.string.social_no_activity)) }
            items(state.activity, key = { "activity:${it.runId}" }) { run ->
                SocialPanel(modifier = Modifier.clickable { onOpenContent(run.contentType, run.contentId, run.title) }) {
                    Text(run.profile.displayName, style = MaterialTheme.typography.labelLarge)
                    Text(run.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    run.episode?.let { Text("S${run.season ?: 1} E$it${if (run.eventCount > 1) " · ${run.eventCount} episodes" else ""}") }
                }
            }
            if (state.nextCursor != null) item {
                Button(onClick = { scope.launch { SocialRepository.refresh(append = true) } }, enabled = !state.isLoadingMore) {
                    Text("Load more")
                }
            }

            item { SocialSectionTitle(stringResource(Res.string.social_friends)) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = normalizeSocialHandle(it).take(24) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text(stringResource(Res.string.social_search_handle)) },
                    )
                    IconButton(onClick = { scope.launch { searchResults = SocialRepository.searchProfiles(search).getOrDefault(emptyList()) } }) {
                        Icon(Icons.Rounded.PersonAdd, stringResource(Res.string.social_add_friend))
                    }
                }
            }
            items(searchResults, key = { "search:${it.profileId}" }) { profile ->
                SocialPersonRow(profile, subtitle = "@${profile.handle}") {
                    IconButton(onClick = { scope.launch { SocialRepository.sendFriendRequest(profile.profileId) } }) {
                        Icon(Icons.Rounded.PersonAdd, stringResource(Res.string.social_add_friend))
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = state.selectedFriendId == null, onClick = { SocialRepository.selectFriend(null) }, label = { Text("All") })
                    state.friends.forEach { friend ->
                        FilterChip(selected = state.selectedFriendId == friend.profileId, onClick = { SocialRepository.selectFriend(friend.profileId) }, label = { Text(friend.displayName) })
                    }
                }
            }
            items(state.friends, key = { "friend:${it.profileId}" }) { friend ->
                SocialPersonRow(friend, subtitle = "@${friend.handle}") {
                    IconButton(onClick = { scope.launch { SocialRepository.removeFriend(friend.profileId) } }) {
                        Icon(Icons.Rounded.DeleteOutline, stringResource(Res.string.social_remove_friend))
                    }
                }
            }
            item {
                SocialPanel {
                    PrivacyToggle(stringResource(Res.string.social_share_watching), shareWatching) { shareWatching = it; scope.launch { SocialRepository.setPrivacy(shareWatching, shareRecent) } }
                    PrivacyToggle(stringResource(Res.string.social_share_recent), shareRecent) { shareRecent = it; scope.launch { SocialRepository.setPrivacy(shareWatching, shareRecent) } }
                }
            }
        }
    }
}

@Composable private fun SocialSectionTitle(title: String, count: Int? = null) {
    Text(if (count == null) title else "$title · $count", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable private fun SocialPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, tonalElevation = 2.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable private fun SocialMessageCard(message: String) = SocialPanel { Text(message) }

@Composable private fun SocialPersonRow(profile: SocialProfileSummary, subtitle: String, actions: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(profile.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            actions()
        }
    }
}

@Composable private fun PrivacyToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
