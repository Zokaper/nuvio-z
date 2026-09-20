package com.nuvio.app.features.player

import com.nuvio.app.features.social.WatchJoinPolicy
import com.nuvio.app.features.watchparty.PartyStatusAction
import com.nuvio.app.features.watchparty.PartyStatusLine
import com.nuvio.app.features.watchparty.PartyStatusTone

/**
 * What the native controls page receives for Watch Together.
 *
 * Render-ready strings and flags only: the page decides nothing about the party, it draws the state
 * it is handed. Replaces `PartyRoomViewState`, which could only describe a party that already existed
 * and so had no way to say "not in a party", "starting", "couldn't start" or "the party ended".
 *
 * Pure, and compiled by the pure suites alongside [projectWatchTogetherPanel].
 */
data class WatchTogetherBridgePerson(
    val name: String,
    val avatarUrl: String,
    val colorHex: String,
    val isHost: Boolean,
    val isSelf: Boolean,
    val status: String,
    val tone: String,
)

data class WatchTogetherBridgeInvite(
    val index: Int,
    val name: String,
    val avatarUrl: String,
    val invited: Boolean,
)

/** An outgoing join request, as the player mirrors it (§4 "In the player"). */
data class WatchTogetherOutgoingMirror(
    val name: String,
    val avatarUrl: String?,
    val colorHex: String,
    /** "pending" | "accepted" | "joining" */
    val phase: String,
    val expiresAtMs: Long,
)

data class WatchTogetherBridgeState(
    val open: Boolean = false,
    /** unshareable | idle | starting | startFailed | connecting | active | activeElsewhere | ended */
    val stateName: String = "idle",
    /** none | active | busy | incoming | outgoing */
    val badge: String = "none",
    val memberCount: Int = 0,
    val buttonLabel: String = "Watch Together",
    val title: String = "",
    val subline: String = "",
    val message: String = "",
    val offersOpenExisting: Boolean = false,
    val isHost: Boolean = false,
    /** live | reconnecting | delayed | offline */
    val connection: String = "live",
    val connectionLabel: String = "",
    val connectionTooltip: String = "",
    val people: List<WatchTogetherBridgePerson> = emptyList(),
    val incomingVisible: Boolean = false,
    val incomingName: String = "",
    val incomingAvatarUrl: String = "",
    val incomingColorHex: String = "#1E88E5",
    val incomingExpiresAtMs: Long = 0L,
    val guestsControl: Boolean = false,
    val pauseWhenBuffers: Boolean = true,
    val joinPolicyVisible: Boolean = false,
    /** 0 Direct, 1 Ask, 2 Off: the segment index. */
    val joinPolicy: Int = 1,
    val joinPolicyExplanation: String = "",
    val joinPolicySaving: Boolean = false,
    val joinPolicyError: String = "",
    val leaveHelper: String = "",
    val errorMessage: String = "",
    val syncDetails: String = "",
    val inviteTargets: List<WatchTogetherBridgeInvite> = emptyList(),
    val inviteCode: String = "",
    val endConfirm: Boolean = false,
    val outgoingVisible: Boolean = false,
    val outgoingPhase: String = "",
    val outgoingName: String = "",
    val outgoingAvatarUrl: String = "",
    val outgoingColorHex: String = "#1E88E5",
    val outgoingExpiresAtMs: Long = 0L,
)

fun WatchJoinPolicy.segmentIndex(): Int = when (this) {
    WatchJoinPolicy.direct -> 0
    WatchJoinPolicy.approval -> 1
    WatchJoinPolicy.disabled -> 2
}

fun joinPolicyForSegment(index: Int): WatchJoinPolicy? = when (index) {
    0 -> WatchJoinPolicy.direct
    1 -> WatchJoinPolicy.approval
    2 -> WatchJoinPolicy.disabled
    else -> null
}

fun PartyConnectionChip.wireName(): String = when (this) {
    PartyConnectionChip.Live -> "live"
    PartyConnectionChip.Reconnecting -> "reconnecting"
    PartyConnectionChip.Delayed -> "delayed"
    PartyConnectionChip.Offline -> "offline"
}

/**
 * Flattens [panel] for the page. The header badge follows §3's list: a spinner while starting or
 * connecting, a pulsing amber dot for a request waiting on the host, a clock for the viewer's own
 * outgoing request, and the member count on an accent dot while a party is active.
 */
fun watchTogetherBridgeState(
    panel: WatchTogetherPanelState,
    open: Boolean,
    inviteTargets: List<WatchTogetherBridgeInvite> = emptyList(),
    inviteCode: String = "",
    endConfirm: Boolean = false,
    outgoing: WatchTogetherOutgoingMirror? = null,
): WatchTogetherBridgeState {
    val incoming = when (panel) {
        is WatchTogetherPanelState.Idle -> panel.incomingRequest
        is WatchTogetherPanelState.Active -> panel.incomingRequest
        else -> null
    }
    val base = WatchTogetherBridgeState(
        open = open,
        incomingVisible = incoming != null,
        incomingName = incoming?.name.orEmpty(),
        incomingAvatarUrl = incoming?.avatarUrl.orEmpty(),
        incomingColorHex = incoming?.avatarColorHex ?: "#1E88E5",
        incomingExpiresAtMs = incoming?.expiresAtMs ?: 0L,
        outgoingVisible = outgoing != null,
        outgoingPhase = outgoing?.phase.orEmpty(),
        outgoingName = outgoing?.name.orEmpty(),
        outgoingAvatarUrl = outgoing?.avatarUrl.orEmpty(),
        outgoingColorHex = outgoing?.colorHex ?: "#1E88E5",
        outgoingExpiresAtMs = outgoing?.expiresAtMs ?: 0L,
    )
    val state = when (panel) {
        WatchTogetherPanelState.Unshareable -> base.copy(
            stateName = "unshareable",
            message = "This source can't be shared. Local files and downloads play only on this device.",
        )
        is WatchTogetherPanelState.Idle -> base.copy(
            stateName = "idle",
            title = panel.title,
            message = "Start a party and friends can watch this with you, in step.",
            joinPolicyVisible = true,
            joinPolicy = panel.joinPolicy.selected.segmentIndex(),
            joinPolicyExplanation = panel.joinPolicy.explanation,
            joinPolicySaving = panel.joinPolicy.saving,
            joinPolicyError = panel.joinPolicy.errorMessage.orEmpty(),
        )
        WatchTogetherPanelState.Starting -> base.copy(stateName = "starting", message = "Starting party…")
        is WatchTogetherPanelState.StartFailed -> base.copy(
            stateName = "startFailed",
            message = panel.message,
            offersOpenExisting = panel.offersOpenExisting,
        )
        WatchTogetherPanelState.Connecting -> base.copy(stateName = "connecting", message = "Connecting to your party…")
        is WatchTogetherPanelState.Active -> {
            val host = panel.role == WatchTogetherRole.Host
            val policy = panel.settings?.joinPolicy
            base.copy(
                stateName = "active",
                title = panel.title,
                subline = panel.subline,
                isHost = host,
                connection = panel.connection.wireName(),
                connectionLabel = panel.connection.label,
                connectionTooltip = if (panel.connection == PartyConnectionChip.Delayed) {
                    "Live sync is down; staying in step every few seconds"
                } else {
                    ""
                },
                people = panel.people.map {
                    WatchTogetherBridgePerson(
                        name = it.name,
                        avatarUrl = it.avatarUrl.orEmpty(),
                        colorHex = it.avatarColorHex,
                        isHost = it.isHost,
                        isSelf = it.isSelf,
                        status = it.statusLabel,
                        tone = it.tone.wireName,
                    )
                },
                memberCount = panel.people.size,
                guestsControl = panel.settings?.guestsControlPlayback ?: false,
                pauseWhenBuffers = panel.settings?.pauseWhenSomeoneBuffers ?: true,
                joinPolicyVisible = policy != null,
                joinPolicy = policy?.selected?.segmentIndex() ?: 1,
                joinPolicyExplanation = policy?.explanation.orEmpty(),
                joinPolicySaving = policy?.saving ?: false,
                joinPolicyError = policy?.errorMessage.orEmpty(),
                leaveHelper = panel.leaveHelper.orEmpty(),
                errorMessage = panel.errorMessage.orEmpty(),
                syncDetails = panel.syncDetails.orEmpty(),
                inviteTargets = if (host) inviteTargets else emptyList(),
                inviteCode = if (host) inviteCode else "",
                endConfirm = endConfirm && host,
            )
        }
        is WatchTogetherPanelState.ActiveElsewhere -> base.copy(
            stateName = "activeElsewhere",
            title = panel.partyTitle,
            message = "You're in a party watching ${panel.partyTitle}",
        )
        is WatchTogetherPanelState.Ended -> base.copy(stateName = "ended", message = panel.headline)
    }
    val badge = when {
        panel == WatchTogetherPanelState.Starting || panel == WatchTogetherPanelState.Connecting -> "busy"
        incoming != null -> "incoming"
        outgoing != null -> "outgoing"
        panel is WatchTogetherPanelState.Active -> "active"
        else -> "none"
    }
    val label = when (badge) {
        "busy" -> if (panel == WatchTogetherPanelState.Starting) "Watch Together, starting a party" else "Watch Together, connecting"
        "incoming" -> "Watch Together, ${state.incomingName} wants to join"
        "outgoing" -> "Watch Together, asking ${state.outgoingName} to join"
        "active" -> "Watch Together, ${state.memberCount} ${if (state.memberCount == 1) "person" else "people"} in your party"
        else -> "Watch Together"
    }
    return state.copy(badge = badge, buttonLabel = label)
}

/** Whoever the status pill is about, as the page draws their avatar. */
data class PartyStatusBridgePerson(
    val name: String,
    val avatarUrl: String,
    val colorHex: String,
)

/**
 * The status pill (§5), replacing `partyBannerVisible` / `partyBannerText`.
 *
 * Commands and labels are chosen here, not on the page, so the pill's buttons can only ever send
 * something `handlePlayerControlsEvent` answers.
 */
data class PartyStatusBridgeState(
    val visible: Boolean = false,
    val kind: String = "",
    val text: String = "",
    /** The second line, when the row has one. Empty for every row that does not. */
    val detail: String = "",
    /** neutral | waiting | warning | error */
    val tone: String = "neutral",
    val action: String = "",
    val actionLabel: String = "",
    val secondaryAction: String = "",
    val secondaryActionLabel: String = "",
    val people: List<PartyStatusBridgePerson> = emptyList(),
)

fun partyStatusBridgeState(line: PartyStatusLine?, suppressed: Boolean = false): PartyStatusBridgeState {
    if (line == null || suppressed) return PartyStatusBridgeState()
    return PartyStatusBridgeState(
        visible = true,
        kind = line.kind.name,
        text = line.text,
        detail = line.detail.orEmpty(),
        tone = when (line.tone) {
            PartyStatusTone.Neutral -> "neutral"
            PartyStatusTone.Waiting -> "waiting"
            PartyStatusTone.Warning -> "warning"
            PartyStatusTone.Error -> "error"
        },
        action = line.action?.command().orEmpty(),
        actionLabel = line.action?.label().orEmpty(),
        secondaryAction = line.secondaryAction?.command().orEmpty(),
        secondaryActionLabel = line.secondaryAction?.label().orEmpty(),
        people = line.people.take(2).map { PartyStatusBridgePerson(it.name, it.avatarUrl.orEmpty(), it.avatarColorHex) },
    )
}

fun PartyStatusAction.command(): String = when (this) {
    PartyStatusAction.ChooseSource -> "wtChooseSource"
    PartyStatusAction.StartAnyway -> "wtStartAnyway"
    PartyStatusAction.DontWait -> "wtDontWait"
    PartyStatusAction.LetIn -> "wtAcceptRequest"
    PartyStatusAction.Decline -> "wtDeclineRequest"
    PartyStatusAction.CancelOutgoing -> "wtCancelOutgoing"
}

private fun PartyStatusAction.label(): String = when (this) {
    PartyStatusAction.ChooseSource -> "Choose source"
    PartyStatusAction.StartAnyway -> "Start anyway"
    PartyStatusAction.DontWait -> "Don't wait"
    PartyStatusAction.LetIn -> "Let in"
    PartyStatusAction.Decline -> "Decline"
    PartyStatusAction.CancelOutgoing -> "Cancel"
}
