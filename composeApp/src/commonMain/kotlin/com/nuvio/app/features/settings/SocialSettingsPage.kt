package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.social.SocialFeaturePreferencesRepository
import com.nuvio.app.features.social.SocialIdentityBody
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
import nuvio.composeapp.generated.resources.settings_social_handle_unset
import nuvio.composeapp.generated.resources.settings_social_join_policy
import nuvio.composeapp.generated.resources.settings_social_join_policy_approval
import nuvio.composeapp.generated.resources.settings_social_join_policy_direct
import nuvio.composeapp.generated.resources.settings_social_join_policy_disabled
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
 * Settings for the social layer, and the only way back into it once it is off.
 *
 * ## Why this page has to exist
 *
 * Before Phase 5 there were **no** social rows anywhere in Settings: the handle, the two privacy
 * toggles and the join policy all lived inside `SocialScreen`. That was survivable while the
 * Social tab was always present. It is not survivable now - the moment the tab can be hidden,
 * every one of those controls becomes unreachable along with it, and so does any way of turning
 * the feature back on.
 *
 * ⚠ **The master switch is the one social surface that stays visible when social is off.** That
 * is the deliberate exception to "the feature disappears cleanly"; without it the preference
 * would be a one-way door.
 */
internal fun LazyListScope.socialSettingsContent(isTablet: Boolean) {
    item {
        SocialSettingsSections(isTablet = isTablet)
    }
}

@Composable
private fun SocialSettingsSections(isTablet: Boolean) {
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

    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        SettingsSection(title = stringResource(Res.string.settings_social_title), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_social_enabled),
                    description = stringResource(Res.string.settings_social_enabled_description),
                    checked = preferences.enabled,
                    isTablet = isTablet,
                    onCheckedChange = { enabled ->
                        when {
                            enabled -> SocialFeaturePreferencesRepository.setEnabled(true)
                            // Leaving a party is not a silent side effect of a settings toggle.
                            partySession.phase.holdsLiveParty -> confirmLeaveParty = true
                            else -> disableSocial()
                        }
                    },
                )
            }
        }

        // Everything below is about *how* the layer behaves, so it is meaningless while it is off.
        // These rows are the reason the page exists: they have no other home, and hiding the tab
        // would otherwise take them with it.
        if (preferences.enabled) {
            SettingsSection(title = stringResource(Res.string.settings_social_title), isTablet = isTablet) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_social_handle),
                        description = socialState.me?.handle?.let { "@$it" }
                            ?: stringResource(Res.string.settings_social_handle_unset),
                        isTablet = isTablet,
                        onClick = {
                            handleDraft = socialState.me?.handle.orEmpty()
                            handleMessage = null
                            showHandleDialog = true
                        },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_social_share_watching),
                        description = stringResource(Res.string.settings_social_share_watching_description),
                        checked = socialState.me?.shareWatchingNow ?: true,
                        enabled = socialState.me != null,
                        isTablet = isTablet,
                        onCheckedChange = { share ->
                            scope.launch {
                                SocialRepository.setPrivacy(
                                    shareWatchingNow = share,
                                    shareRecentlyWatched = socialState.me?.shareRecentlyWatched ?: true,
                                )
                            }
                        },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_social_share_recent),
                        description = stringResource(Res.string.settings_social_share_recent_description),
                        checked = socialState.me?.shareRecentlyWatched ?: true,
                        enabled = socialState.me != null,
                        isTablet = isTablet,
                        onCheckedChange = { share ->
                            scope.launch {
                                SocialRepository.setPrivacy(
                                    shareWatchingNow = socialState.me?.shareWatchingNow ?: true,
                                    shareRecentlyWatched = share,
                                )
                            }
                        },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    // Cycles rather than opening a picker: three values, all of which fit in the
                    // row's own subtitle. The same reasoning `PlaybackPreferencesDialog` uses.
                    val policy = socialState.me?.defaultJoinPolicy ?: WatchJoinPolicy.approval
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_social_join_policy),
                        description = joinPolicyLabel(policy),
                        enabled = socialState.me != null,
                        isTablet = isTablet,
                        onClick = {
                            val next = WatchJoinPolicy.entries[
                                (policy.ordinal + 1) % WatchJoinPolicy.entries.size,
                            ]
                            scope.launch { SocialRepository.setDefaultJoinPolicy(next) }
                        },
                    )
                }
            }
        }
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

@Composable
private fun joinPolicyLabel(policy: WatchJoinPolicy): String = when (policy) {
    WatchJoinPolicy.direct -> stringResource(Res.string.settings_social_join_policy_direct)
    WatchJoinPolicy.approval -> stringResource(Res.string.settings_social_join_policy_approval)
    WatchJoinPolicy.disabled -> stringResource(Res.string.settings_social_join_policy_disabled)
}
