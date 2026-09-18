package com.nuvio.app.features.player

import com.nuvio.app.features.social.WatchJoinPolicy
import com.nuvio.app.features.watchparty.PartyHealthState
import com.nuvio.app.features.watchparty.PartyMemberPresentation
import com.nuvio.app.features.watchparty.PartyPromotionFailure
import com.nuvio.app.features.watchparty.PartyReadyTone
import com.nuvio.app.features.watchparty.PartyRealtimeHealth
import com.nuvio.app.features.watchparty.PartySourceMatch
import com.nuvio.app.features.watchparty.PartySyncCapability
import com.nuvio.app.features.watchparty.WatchPartyControlMode
import com.nuvio.app.features.watchparty.WatchPartyParticipant
import com.nuvio.app.features.watchparty.WatchPartyRealtimeVerificationGraceMs
import com.nuvio.app.features.watchparty.WatchPartyState
import com.nuvio.app.features.watchparty.WatchPartyStatus
import com.nuvio.app.features.watchparty.displayName
import com.nuvio.app.features.watchparty.matchesPlayback

/**
 * What the in-player Watch Together panel is showing, as one sealed state.
 *
 * The panel used to exist only while a party did (`available = activeParty != null`), so pressing the
 * header button with no party *created* one - there was no "not in a party" state to open onto. It now
 * opens onto whichever of these is true and moves between them in place: Idle → Starting → Active
 * without closing, StartFailed inline instead of a toast, Ended instead of a centred modal.
 *
 * Pure: every input is a value the caller already holds, so the state machine is tested without a
 * player, a bridge or a party.
 */
sealed interface WatchTogetherPanelState {
    /** Social is on but this playback has no descriptor a friend could match. */
    data object Unshareable : WatchTogetherPanelState

    data class Idle(
        val title: String,
        val joinPolicy: JoinPolicyControl,
        val incomingRequest: IncomingJoinRequestRow?,
    ) : WatchTogetherPanelState

    data object Starting : WatchTogetherPanelState

    data class StartFailed(val reason: PartyPromotionFailure, val message: String) : WatchTogetherPanelState {
        /** "You're already in another party" carries an Open it next to Try again. */
        val offersOpenExisting: Boolean get() = reason == PartyPromotionFailure.AlreadyInAnotherParty
    }

    data object Connecting : WatchTogetherPanelState

    data class Active(
        val role: WatchTogetherRole,
        val title: String,
        val subline: String,
        val connection: PartyConnectionChip,
        val people: List<WatchTogetherPersonRow>,
        val incomingRequest: IncomingJoinRequestRow?,
        /** Null for guests: the settings section is the host's. */
        val settings: WatchTogetherHostSettings?,
        /** "Seraph becomes host", under a host's Leave. Null when nobody would, or for a guest. */
        val leaveHelper: String?,
        val errorMessage: String?,
        val syncDetails: String?,
    ) : WatchTogetherPanelState

    data class ActiveElsewhere(val partyTitle: String) : WatchTogetherPanelState

    data class Ended(val headline: String) : WatchTogetherPanelState
}

enum class WatchTogetherRole { Host, Guest }

enum class PartyConnectionChip(val label: String) {
    Live("Live"),
    Reconnecting("Reconnecting…"),
    Delayed("Delayed"),
    Offline("Offline"),
}

data class JoinPolicyControl(
    val selected: WatchJoinPolicy,
    /** A change sent and not yet confirmed; the control shows [selected] optimistically. */
    val saving: Boolean = false,
    val errorMessage: String? = null,
) {
    val explanation: String
        get() = when (selected) {
            WatchJoinPolicy.direct -> "Friends can jump straight in."
            WatchJoinPolicy.approval -> "You'll approve each friend."
            WatchJoinPolicy.disabled -> "Friends can see what you're watching but can't join."
        }
}

data class IncomingJoinRequestRow(
    val requestId: String,
    val profileId: String,
    val name: String,
    val avatarUrl: String?,
    val avatarColorHex: String,
    val expiresAtMs: Long?,
)

data class WatchTogetherPersonRow(
    val profileId: String,
    val name: String,
    val avatarUrl: String?,
    val avatarColorHex: String,
    val isHost: Boolean,
    val isSelf: Boolean,
    val statusLabel: String,
    val tone: PartyReadyTone,
)

data class WatchTogetherHostSettings(
    val guestsControlPlayback: Boolean,
    val pauseWhenSomeoneBuffers: Boolean,
    val joinPolicy: JoinPolicyControl,
)

/** Where starting a party from this playback is. */
sealed interface PartyPromotionProgress {
    data object Idle : PartyPromotionProgress
    data object Starting : PartyPromotionProgress
    data class Failed(val reason: PartyPromotionFailure) : PartyPromotionProgress
}

data class WatchTogetherPanelInputs(
    val shareable: Boolean,
    val playbackContentId: String,
    val playbackVideoId: String?,
    val playbackTitle: String,
    val viewerProfileId: String?,
    /** The party this profile holds, if any, matching or not. */
    val party: WatchPartyState?,
    val health: PartyHealthState = PartyHealthState(),
    /** When realtime entered its current [PartyHealthState.realtime] value. */
    val realtimeSinceMs: Long? = null,
    val nowMs: Long = 0L,
    val members: Map<String, PartyMemberPresentation> = emptyMap(),
    val promotion: PartyPromotionProgress = PartyPromotionProgress.Idle,
    /** Adopting a discovered party or restoring one, before its snapshot is in hand. */
    val connecting: Boolean = false,
    /** `guestPostEndChoice`, with the name of whoever ended it when known. */
    val postEndChoice: Boolean = false,
    val endedByName: String? = null,
    val joinPolicy: JoinPolicyControl = JoinPolicyControl(WatchJoinPolicy.approval),
    val incomingRequest: IncomingJoinRequestRow? = null,
    val waitForEveryone: Boolean = true,
    val errorMessage: String? = null,
    val sourceMatch: PartySourceMatch? = null,
    val releaseName: String? = null,
)

fun projectWatchTogetherPanel(inputs: WatchTogetherPanelInputs): WatchTogetherPanelState = with(inputs) {
    if (postEndChoice) {
        val who = endedByName?.takeIf(String::isNotBlank)
        return WatchTogetherPanelState.Ended(if (who != null) "$who ended the party" else "The party ended")
    }
    val live = party?.takeIf { it.status != WatchPartyStatus.ended }
    if (live != null) {
        if (!live.matchesPlayback(playbackContentId, playbackVideoId)) {
            return WatchTogetherPanelState.ActiveElsewhere(live.content.title)
        }
        return active(live)
    }
    if (connecting) return WatchTogetherPanelState.Connecting
    if (promotion is PartyPromotionProgress.Starting) return WatchTogetherPanelState.Starting
    if (!shareable) return WatchTogetherPanelState.Unshareable
    if (promotion is PartyPromotionProgress.Failed) {
        return WatchTogetherPanelState.StartFailed(promotion.reason, promotionFailureMessage(promotion.reason))
    }
    WatchTogetherPanelState.Idle(playbackTitle, joinPolicy, incomingRequest)
}

fun promotionFailureMessage(reason: PartyPromotionFailure): String = when (reason) {
    PartyPromotionFailure.PresenceStale -> "Getting ready to share, try again in a moment"
    PartyPromotionFailure.AlreadyInAnotherParty -> "You're already in another party"
    PartyPromotionFailure.NoPresenceSession -> "This source can't be shared"
    PartyPromotionFailure.Refused -> "Couldn't start the party"
}

private fun WatchTogetherPanelInputs.active(party: WatchPartyState): WatchTogetherPanelState.Active {
    val isHost = party.hostProfileId == viewerProfileId
    val presentCount = party.members.count { it.connected }
    val hostName = party.members.firstOrNull { it.profileId == party.hostProfileId }
        ?.displayName(viewerProfileId = null)
        .orEmpty()
        .ifBlank { "The host" }
    val subline = when {
        isHost -> {
            val count = presentCount.coerceAtLeast(1)
            "You're hosting · $count ${if (count == 1) "person" else "people"}"
        }
        party.controlMode == WatchPartyControlMode.collaborative -> "Anyone can pause & seek"
        else -> "$hostName controls playback"
    }
    val people = party.members
        .sortedWith(compareByDescending<WatchPartyParticipant> { it.profileId == party.hostProfileId }.thenBy { it.joinedAt })
        .map { member ->
            val presentation = members[member.profileId]
            WatchTogetherPersonRow(
                profileId = member.profileId,
                name = member.displayName(viewerProfileId),
                avatarUrl = member.profile?.avatarUrl,
                avatarColorHex = member.profile?.avatarColorHex ?: "#1E88E5",
                isHost = member.profileId == party.hostProfileId,
                isSelf = member.profileId == viewerProfileId,
                statusLabel = presentation?.label.orEmpty(),
                tone = presentation?.tone ?: PartyReadyTone.Working,
            )
        }
    return WatchTogetherPanelState.Active(
        role = if (isHost) WatchTogetherRole.Host else WatchTogetherRole.Guest,
        title = party.content.title,
        subline = subline,
        connection = partyConnectionChip(health, realtimeSinceMs, nowMs),
        people = people,
        incomingRequest = incomingRequest.takeIf { isHost },
        settings = if (isHost) {
            WatchTogetherHostSettings(
                guestsControlPlayback = party.controlMode == WatchPartyControlMode.collaborative,
                pauseWhenSomeoneBuffers = waitForEveryone,
                joinPolicy = joinPolicy,
            )
        } else {
            null
        },
        leaveHelper = if (isHost) {
            partyLeaveSuccessor(party)?.let { "${it.displayName(viewerProfileId = null)} becomes host" }
        } else {
            null
        },
        errorMessage = errorMessage?.takeIf(String::isNotBlank),
        syncDetails = listOfNotNull(
            when (sourceMatch) {
                PartySourceMatch.exact -> "Same file as host"
                PartySourceMatch.alternate -> "Similar version"
                null -> null
            }.takeIf { !isHost },
            releaseName?.takeIf(String::isNotBlank),
        ).joinToString(" · ").ifBlank { null },
    )
}

/**
 * Who would host if the host left: `party_live_successor` - the earliest-joined live member other
 * than the host, ties broken by profile id. Mirrored only to *describe* it; the server decides.
 */
fun partyLeaveSuccessor(party: WatchPartyState): WatchPartyParticipant? =
    party.members
        .filter { it.profileId != party.hostProfileId && it.connected }
        .minWithOrNull(compareBy({ it.joinedAt }, { it.profileId }))

/**
 * One connection control in place of the two sync lines that used to say the same thing twice.
 *
 * A Connecting / Degraded socket reads as Live for its first [PartyConnectionReconnectVisibleMs], and
 * `SubscribedUnverified` for its verification grace: both are ordinary and both are usually over
 * before anyone could read the word.
 */
fun partyConnectionChip(health: PartyHealthState, realtimeSinceMs: Long?, nowMs: Long): PartyConnectionChip {
    val capability = health.capability()
    if (capability == PartySyncCapability.OfflineLocalPlayback) return PartyConnectionChip.Offline
    if (capability == PartySyncCapability.FullSync) return PartyConnectionChip.Live
    val inState = realtimeSinceMs?.let { nowMs - it } ?: Long.MAX_VALUE
    return when (health.realtime) {
        PartyRealtimeHealth.Connecting,
        PartyRealtimeHealth.Degraded,
        -> if (inState > PartyConnectionReconnectVisibleMs) PartyConnectionChip.Reconnecting else PartyConnectionChip.Live
        PartyRealtimeHealth.SubscribedUnverified ->
            if (inState <= WatchPartyRealtimeVerificationGraceMs) PartyConnectionChip.Live else PartyConnectionChip.Delayed
        PartyRealtimeHealth.Detached -> PartyConnectionChip.Delayed
        PartyRealtimeHealth.Live -> PartyConnectionChip.Live
    }
}

const val PartyConnectionReconnectVisibleMs = 3_000L
