package com.nuvio.app.features.social

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsSection
import com.nuvio.app.features.settings.SocialSettingsExtensions
import com.nuvio.app.features.settings.SettingsSwitchRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.social_alert_friend_requests
import nuvio.composeapp.generated.resources.social_alert_parties
import nuvio.composeapp.generated.resources.social_alert_recs
import nuvio.composeapp.generated.resources.social_alert_recs_desc
import nuvio.composeapp.generated.resources.social_alert_watching
import nuvio.composeapp.generated.resources.social_alert_watching_desc
import nuvio.composeapp.generated.resources.social_notifications
import nuvio.composeapp.generated.resources.social_notifications_switch
import nuvio.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource

/**
 * Which Social alerts this phone shows (2026-10-18): the phone's counterpart of the desktop's Pop-ups
 * switches, and the same three kinds. One set gates every phone surface - the in-app banners
 * ([SocialBanners]), the in-player card and, on Android, system notifications - so a kind turned off is off
 * everywhere on this device.
 *
 * **Per device, not per profile, and not synced**, like the desktop's: what may interrupt you is a fact
 * about this phone. Mobile only (`NuvioZDesktop` has its own, in the tray).
 */
@Serializable
internal data class PhoneSocialAlertsState(
    val enabled: Boolean = true,
    val friendRequests: Boolean = true,
    /** Party invites and requests to join your playback: the two "someone is waiting on you" kinds. */
    val parties: Boolean = true,
    /** Still gated per friend by "Notify me when they start watching" on their profile. */
    val friendWatching: Boolean = true,
    /** A friend recommends a title, or starts one you recommended. */
    val recommendations: Boolean = true,
) {
    fun allows(kind: SocialPopupKind): Boolean = enabled && when (kind) {
        SocialPopupKind.FriendRequest -> friendRequests
        SocialPopupKind.PartyInvite, SocialPopupKind.JoinRequest -> parties
        SocialPopupKind.FriendWatching -> friendWatching
        SocialPopupKind.Recommendation -> recommendations
    }
}

internal expect object PhoneSocialAlertsStorage {
    fun load(): String?
    fun save(payload: String)
}

internal object PhoneSocialAlerts {
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(load())
    val state: StateFlow<PhoneSocialAlertsState> = _state.asStateFlow()
    val current: PhoneSocialAlertsState get() = _state.value

    /** Registers the Social settings section. Called once at startup, on both phones. */
    fun install() {
        SocialSettingsExtensions.platformSections = { isTablet -> PhoneSocialAlertsSection(isTablet) }
    }

    fun update(transform: (PhoneSocialAlertsState) -> PhoneSocialAlertsState) {
        val next = transform(_state.value)
        if (next == _state.value) return
        _state.value = next
        PhoneSocialAlertsStorage.save(json.encodeToString(next))
    }

    private fun load(): PhoneSocialAlertsState =
        PhoneSocialAlertsStorage.load()
            ?.let { runCatching { json.decodeFromString<PhoneSocialAlertsState>(it) }.getOrNull() }
            ?: PhoneSocialAlertsState()
}

@Composable
private fun PhoneSocialAlertsSection(isTablet: Boolean) {
    val alerts by PhoneSocialAlerts.state.collectAsState()
    SettingsSection(title = stringResource(Res.string.social_notifications), isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = stringResource(Res.string.social_notifications_switch),
                description = phoneSocialAlertsDescription,
                checked = alerts.enabled,
                isTablet = isTablet,
                onCheckedChange = { on -> PhoneSocialAlerts.update { it.copy(enabled = on) } },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.social_alert_friend_requests),
                checked = alerts.friendRequests,
                enabled = alerts.enabled,
                isTablet = isTablet,
                onCheckedChange = { on -> PhoneSocialAlerts.update { it.copy(friendRequests = on) } },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.social_alert_parties),
                checked = alerts.parties,
                enabled = alerts.enabled,
                isTablet = isTablet,
                onCheckedChange = { on -> PhoneSocialAlerts.update { it.copy(parties = on) } },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.social_alert_watching),
                description = stringResource(Res.string.social_alert_watching_desc),
                checked = alerts.friendWatching,
                enabled = alerts.enabled,
                isTablet = isTablet,
                onCheckedChange = { on -> PhoneSocialAlerts.update { it.copy(friendWatching = on) } },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.social_alert_recs),
                description = stringResource(Res.string.social_alert_recs_desc),
                checked = alerts.recommendations,
                enabled = alerts.enabled,
                isTablet = isTablet,
                onCheckedChange = { on -> PhoneSocialAlerts.update { it.copy(recommendations = on) } },
            )
        }
    }
}

/** What the master switch covers here: Android also has system notifications, iOS only the in-app ones. */
internal expect val phoneSocialAlertsDescription: String
