package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.social.SocialAvatar
import com.nuvio.app.features.social.SocialFeaturePreferencesRepository
import com.nuvio.app.features.social.SocialIdentityBody
import com.nuvio.app.features.social.SocialPillButton
import com.nuvio.app.features.social.SocialRepository
import com.nuvio.app.features.social.WatchJoinPolicy
import com.nuvio.app.features.social.holdsLiveParty
import com.nuvio.app.features.social.shutdownSocialLayer
import com.nuvio.app.features.watchparty.WatchPartySessionCoordinator
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_social_enabled
import nuvio.composeapp.generated.resources.settings_social_enabled_description
import nuvio.composeapp.generated.resources.settings_social_handle
import nuvio.composeapp.generated.resources.settings_social_join_policy
import nuvio.composeapp.generated.resources.settings_social_leave_party_body
import nuvio.composeapp.generated.resources.settings_social_leave_party_cancel
import nuvio.composeapp.generated.resources.settings_social_leave_party_confirm
import nuvio.composeapp.generated.resources.settings_social_leave_party_title
import nuvio.composeapp.generated.resources.settings_social_share_recent
import nuvio.composeapp.generated.resources.settings_social_share_recent_description
import nuvio.composeapp.generated.resources.settings_social_share_watching
import nuvio.composeapp.generated.resources.settings_social_share_watching_description
import nuvio.composeapp.generated.resources.settings_social_title
import org.jetbrains.compose.resources.stringResource

/**
 * Sections only one platform has, drawn between Privacy and Home. Desktop registers Pop-ups and
 * In the background (the tray) at startup; the phones register Notifications (`PhoneSocialAlerts`).
 *
 * ⚠ **A registration, not an `expect`.** This file is shared, byte-identical, with `NuvioZDesktop`, whose
 * Android and iOS targets do not build; an `expect` would need an actual in every target of both repos
 * for a section that exists on one platform.
 */
object SocialSettingsExtensions {
    var platformSections: (@Composable (isTablet: Boolean) -> Unit)? = null
}

/**
 * Settings for the social layer, and the only way back into it once it is off.
 *
 * ## Two doors, one page (Social V2, 2026-10-16)
 *
 * The same [SocialSettingsSections] is drawn by Settings -> Social and by the gear in the Social tab.
 * The Social tab is where people look; Settings is the door that survives the tab being hidden.
 *
 * ⚠ **The master switch is the one social surface that stays visible when social is off.** That
 * is the deliberate exception to "the feature disappears cleanly"; without it the preference
 * would be a one-way door. The Social tab's copy replaces it with "Turn off Social" at the bottom,
 * since that door only exists while social is on.
 */
internal fun LazyListScope.socialSettingsContent(isTablet: Boolean) {
    item {
        SocialSettingsSections(isTablet = isTablet)
    }
}

@Composable
internal fun SocialSettingsSections(isTablet: Boolean, inSocialTab: Boolean = false) {
    val scope = rememberCoroutineScope()
    val preferences by remember {
        SocialFeaturePreferencesRepository.ensureLoaded()
        SocialFeaturePreferencesRepository.uiState
    }.collectAsStateWithLifecycle()
    val socialState by remember { SocialRepository.uiState }.collectAsStateWithLifecycle()
    val partySession by remember { WatchPartySessionCoordinator.state }.collectAsStateWithLifecycle()
    val profileState by remember { ProfileRepository.state }.collectAsStateWithLifecycle()

    // ⚠ **The other place the migration probe has to run.** A user who skipped the wizard - or
    // completed it before this preference existed - can arrive here with the switch showing a
    // value derived from a local cache that a second install or a cleared data root has left
    // empty. Without this they would see "off" for an account that has a handle and friends, and
    // the only way to find out otherwise would be to flip a switch they have been told is already
    // correct. The repository runs it at most once per profile and never persists the result.
    val socialProfileId = profileState.activeProfile?.id?.takeIf(String::isNotBlank)
    LaunchedEffect(socialProfileId) {
        SocialFeaturePreferencesRepository.refreshIdentityProbe(socialProfileId)
    }

    var confirmLeaveParty by remember { mutableStateOf(false) }
    var confirmTurnOff by remember { mutableStateOf(false) }
    var showHandleDialog by remember { mutableStateOf(false) }
    var handleDraft by rememberSaveable { mutableStateOf("") }
    var handleBusy by remember { mutableStateOf(false) }
    var handleMessage by remember { mutableStateOf<String?>(null) }

    /**
     * Turning social off, in the right order.
     *
     * ⚠ **Teardown first, flag second.** `shutdownSocialLayer` departs any party through the
     * coordinator - which is what tells the server and, for a host, transfers the party - and
     * that has to happen while the social surfaces still exist. Writing the preference first
     * would pull them out from under an in-flight departure.
     */
    fun disableSocial() {
        scope.launch {
            shutdownSocialLayer()
            SocialFeaturePreferencesRepository.setEnabled(false)
        }
    }

    fun requestDisable() {
        // Leaving a party is not a silent side effect of a settings toggle.
        if (partySession.phase.holdsLiveParty) confirmLeaveParty = true else disableSocial()
    }

    val me = socialState.me
    val sectionGap = if (isTablet) 22.dp else 18.dp
    Column(verticalArrangement = Arrangement.spacedBy(sectionGap)) {
        if (!inSocialTab) {
            SettingsSection(title = stringResource(Res.string.settings_social_title), isTablet = isTablet) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_social_enabled),
                        description = stringResource(Res.string.settings_social_enabled_description),
                        checked = preferences.enabled,
                        isTablet = isTablet,
                        onCheckedChange = { enabled ->
                            if (enabled) SocialFeaturePreferencesRepository.setEnabled(true) else requestDisable()
                        },
                    )
                }
            }
        }

        // Everything below is about *how* the layer behaves, so it is meaningless while it is off. The Social
        // tab's copy only exists while it is on.
        if (preferences.enabled || inSocialTab) {
            if (me != null) {
                SettingsSection(title = "You", isTablet = isTablet) {
                    SettingsGroup(isTablet = isTablet) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            SocialAvatar(me.displayName, me.avatarUrl, me.avatarColorHex, 44.dp)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    me.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val friends = socialState.friends.size
                                Text(
                                    "@${me.handle} · $friends ${if (friends == 1) "friend" else "friends"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            SocialPillButton(
                                "Change handle",
                                onClick = {
                                    handleDraft = me.handle
                                    handleMessage = null
                                    showHandleDialog = true
                                },
                            )
                        }
                    }
                }
            }

            SettingsSection(title = "Privacy", isTablet = isTablet) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_social_share_watching),
                        description = stringResource(Res.string.settings_social_share_watching_description),
                        checked = me?.shareWatchingNow ?: true,
                        enabled = me != null,
                        isTablet = isTablet,
                        onCheckedChange = { share ->
                            scope.launch {
                                SocialRepository.setPrivacy(
                                    shareWatchingNow = share,
                                    shareRecentlyWatched = me?.shareRecentlyWatched ?: true,
                                )
                            }
                        },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_social_share_recent),
                        description = stringResource(Res.string.settings_social_share_recent_description),
                        checked = me?.shareRecentlyWatched ?: true,
                        enabled = me != null,
                        isTablet = isTablet,
                        onCheckedChange = { share ->
                            scope.launch {
                                SocialRepository.setPrivacy(
                                    shareWatchingNow = me?.shareWatchingNow ?: true,
                                    shareRecentlyWatched = share,
                                )
                            }
                        },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    // Three values that all fit on one line: chips rather than a row that cycles on tap.
                    SettingsChoiceRow(
                        title = stringResource(Res.string.settings_social_join_policy),
                        options = listOf(
                            WatchJoinPolicy.direct to "Any friend",
                            WatchJoinPolicy.approval to "Ask first",
                            WatchJoinPolicy.disabled to "No one",
                        ),
                        selected = me?.defaultJoinPolicy ?: WatchJoinPolicy.approval,
                        enabled = me != null,
                        isTablet = isTablet,
                        onSelect = { policy -> scope.launch { SocialRepository.setDefaultJoinPolicy(policy) } },
                    )
                    // Per-friend hiding is set on each friend's profile; this only says who, so the
                    // global switches above are not mistaken for the whole story.
                    val hiddenFrom = socialState.friends.filter { friend ->
                        socialState.friendPrefs.any { it.friendProfileId == friend.profileId && it.hideActivity }
                    }
                    if (socialState.socialV2Backend && hiddenFrom.isNotEmpty()) {
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsNavigationRow(
                            title = "Hidden from",
                            description = hiddenFrom.joinToString { it.displayName } + " - change it on their profile",
                            isTablet = isTablet,
                            enabled = false,
                            onClick = {},
                        )
                    }
                }
            }

            SocialSettingsExtensions.platformSections?.invoke(isTablet)

            SettingsSection(title = "Home", isTablet = isTablet) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsSwitchRow(
                        title = "Show friends on Home",
                        description = "What friends are watching now and recently, in one shelf",
                        checked = preferences.showFriendsOnHome,
                        isTablet = isTablet,
                        onCheckedChange = SocialFeaturePreferencesRepository::setShowFriendsOnHome,
                    )
                }
            }

            if (inSocialTab) {
                SettingsGroup(isTablet = isTablet) {
                    Column(
                        Modifier.fillMaxWidth()
                            .clickable(role = Role.Button) { confirmTurnOff = true }
                            .padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 14.dp),
                    ) {
                        Text(
                            "Turn off Social",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "Hides the Social tab and stops sharing. Turn it back on from Settings.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (confirmTurnOff) {
        AlertDialog(
            onDismissRequest = { confirmTurnOff = false },
            title = { Text("Turn off Social?") },
            text = {
                Text(
                    "The Social tab disappears and you stop sharing what you watch. Your handle and friends are kept. " +
                        "Turn it back on any time from Settings -> Social.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmTurnOff = false
                        requestDisable()
                    },
                ) { Text("Turn off") }
            },
            dismissButton = {
                TextButton(onClick = { confirmTurnOff = false }) {
                    Text(stringResource(Res.string.settings_social_leave_party_cancel))
                }
            },
        )
    }

    if (confirmLeaveParty) {
        AlertDialog(
            onDismissRequest = { confirmLeaveParty = false },
            title = { Text(stringResource(Res.string.settings_social_leave_party_title)) },
            text = { Text(stringResource(Res.string.settings_social_leave_party_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLeaveParty = false
                        disableSocial()
                    },
                ) {
                    Text(stringResource(Res.string.settings_social_leave_party_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeaveParty = false }) {
                    Text(stringResource(Res.string.settings_social_leave_party_cancel))
                }
            },
        )
    }

    if (showHandleDialog) {
        AlertDialog(
            onDismissRequest = { showHandleDialog = false },
            title = { Text(stringResource(Res.string.settings_social_handle)) },
            text = {
                // ⚠ The same composable the setup wizard and the Social tab use. Three callers,
                // one implementation - see `SocialIdentityBody`.
                SocialIdentityBody(
                    handle = handleDraft,
                    onHandleChange = {
                        handleDraft = it
                        handleMessage = null
                    },
                    message = handleMessage,
                    busy = handleBusy,
                    onSave = {
                        scope.launch {
                            handleBusy = true
                            SocialRepository.setupHandle(handleDraft)
                                .onSuccess { showHandleDialog = false }
                                .onFailure { error ->
                                    handleMessage = error.message ?: "Could not save that handle"
                                }
                            handleBusy = false
                        }
                    },
                    showHeading = false,
                    modifier = Modifier,
                )
            },
            confirmButton = {
                TextButton(onClick = { showHandleDialog = false }) {
                    Text(stringResource(Res.string.settings_social_leave_party_cancel))
                }
            },
        )
    }
}

/** A row whose two or three values all fit: a segmented chip strip under the title. */
@Composable
internal fun <T> SettingsChoiceRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    isTablet: Boolean,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = if (isTablet) 16.dp else 14.dp)
            .alpha(if (enabled) 1f else 0.5f),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        Row(
            Modifier.clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .padding(3.dp),
        ) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.14f) else Color.Transparent)
                        .clickable(enabled = enabled && !isSelected, role = Role.RadioButton) { onSelect(value) }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        softWrap = false,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}
