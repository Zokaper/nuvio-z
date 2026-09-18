package com.nuvio.app.features.watchparty

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Who a guest is being taken to, carried from an accepted join request to the lobby and the player.
 *
 * An accepted guest used to land in a lobby that then started playback by itself with no word about
 * whose party it was, which read as being kidnapped by the app. Every step of that hand-off names the
 * person and the title now: the lobby's hero says "Joining Seraph", and the loading screen the lobby
 * launches says "Joining Seraph's party". Process-scoped, because the lobby and the player are
 * different routes and neither outlives the other.
 */
data class PartyJoinHandoffInfo(
    val partyId: String,
    val hostName: String,
    val title: String,
    val artwork: String?,
)

object PartyJoinHandoff {
    private val _current = MutableStateFlow<PartyJoinHandoffInfo?>(null)
    val current: StateFlow<PartyJoinHandoffInfo?> = _current.asStateFlow()

    fun begin(info: PartyJoinHandoffInfo) {
        _current.value = info
    }

    /** The hand-off for [partyId], if that is the party it was for. */
    fun forParty(partyId: String?): PartyJoinHandoffInfo? = _current.value?.takeIf { partyId != null && it.partyId == partyId }

    /** Once the player has reached its first frame, or the party is gone, the story is over. */
    fun finish(partyId: String? = null) {
        if (partyId == null || _current.value?.partyId == partyId) _current.value = null
    }
}

/** "Seraph's party" / "James' party". */
fun partyPossessive(name: String): String = if (name.endsWith("s")) "$name'" else "$name's"
