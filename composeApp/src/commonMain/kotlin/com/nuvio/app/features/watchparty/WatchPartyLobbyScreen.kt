package com.nuvio.app.features.watchparty

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.navigation.WatchPartyLobbyRoute
import kotlinx.coroutines.launch

@Composable
fun WatchPartyLobbyScreen(route: WatchPartyLobbyRoute, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by WatchPartyRepository.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var inviteCode by remember(route) { mutableStateOf<String?>(null) }

    LaunchedEffect(route) {
        if (state.party != null) return@LaunchedEffect
        if (route.partyId != null) {
            WatchPartyRepository.join(partyId = route.partyId)
        } else if (!route.inviteCode.isNullOrBlank()) {
            WatchPartyRepository.join(inviteCode = route.inviteCode)
        } else if (route.contentId != null && route.contentType != null && route.videoId != null) {
            WatchPartyRepository.create(
                PartyContent(
                    contentId = route.contentId,
                    contentType = route.contentType,
                    videoId = route.videoId,
                    title = route.title.orEmpty(),
                    poster = route.poster,
                    season = route.season,
                    episode = route.episode,
                    episodeTitle = route.episodeTitle,
                ),
                sourceFingerprint = route.sourceReleaseFingerprint?.let {
                    SourceFingerprint(
                        addonId = route.sourceAddonId,
                        infoHash = route.sourceInfoHash,
                        fileIndex = route.sourceFileIndex,
                        releaseFingerprint = it,
                    )
                },
            ).onSuccess { inviteCode = it }
        }
    }

    val party = state.party
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) }
                Text("Watch Together", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
        state.errorMessage?.let { item { PartyPanel { Text(it, color = MaterialTheme.colorScheme.error) } } }
        if (party == null) {
            item { PartyPanel { Text(if (state.isWorking) "Creating session…" else "Unable to open this session.") } }
        } else {
            item {
                PartyPanel {
                    Text(party.content.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    party.content.episode?.let { Text("S${party.content.season ?: 1} E$it") }
                    Text("${state.connection.name.replaceFirstChar(Char::uppercase)} · ${party.members.size}/$WatchPartyMaxParticipants")
                    inviteCode?.let { Text("Invite code: $it", fontWeight = FontWeight.Bold) }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = party.controlMode == WatchPartyControlMode.host_only,
                        onClick = { scope.launch { WatchPartyRepository.setControlMode(WatchPartyControlMode.host_only) } },
                        label = { Text("Host controls") },
                    )
                    FilterChip(
                        selected = party.controlMode == WatchPartyControlMode.collaborative,
                        onClick = { scope.launch { WatchPartyRepository.setControlMode(WatchPartyControlMode.collaborative) } },
                        label = { Text("Collaborative") },
                    )
                }
            }
            item { Text("Participants", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            items(party.members, key = WatchPartyParticipant::profileId) { member ->
                PartyPanel {
                    Text(if (member.profileId == state.activeProfileId) "You" else member.profileId.take(8), fontWeight = FontWeight.SemiBold)
                    Text("${member.role} · ${member.readyState.name}${member.readyError?.let { " · $it" }.orEmpty()}")
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { scope.launch { WatchPartyRepository.updateReady(SourceResolutionState.ready) } }) { Text("I'm ready") }
                    if (party.hostProfileId == state.activeProfileId) {
                        Button(onClick = { scope.launch { WatchPartyRepository.play(party.positionMs) } }) { Text("Start") }
                    }
                }
            }
            item {
                if (party.hostProfileId == state.activeProfileId) {
                    OutlinedButton(onClick = { scope.launch { WatchPartyRepository.end(); onBack() } }) { Text("End session") }
                } else {
                    OutlinedButton(onClick = { scope.launch { WatchPartyRepository.leave(); onBack() } }) { Text("Leave") }
                }
            }
        }
    }
}

@Composable private fun PartyPanel(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, tonalElevation = 2.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp), content = content)
    }
}
