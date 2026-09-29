package com.nuvio.app.features.watchparty

/**
 * How a refused party RPC reads to the person who caused it.
 *
 * The backend raises short machine labels (`invalid_source_media`, `stale_party_generation`) and the
 * Supabase client wraps them in a message that also carries the SQLSTATE, the request URL and the
 * RPC name. None of that is for a user, and the lobby printed all of it.
 */
enum class PartyRpcFailure(val userMessage: String) {
    /** The backend refused what the client sent; retrying the same thing cannot succeed. */
    SourceRejected("That source can't be shared with the party. Pick a different one."),
    PartyMoved("The party changed while you were choosing. Try again."),
    NotHost("Only the host can do that."),
    Other("Watch Together couldn't do that. Try again."),
}

private val SourceRejectionLabels = listOf(
    "invalid_source_", "unsafe_source_", "invalid_info_hash", "invalid_file_index",
    "invalid_release_fingerprint", "unsupported_party_contract",
)

fun classifyPartyRpcFailure(rawMessage: String?): PartyRpcFailure {
    val message = rawMessage.orEmpty()
    return when {
        SourceRejectionLabels.any { it in message } -> PartyRpcFailure.SourceRejected
        "stale_party_generation" in message -> PartyRpcFailure.PartyMoved
        "host_required" in message -> PartyRpcFailure.NotHost
        else -> PartyRpcFailure.Other
    }
}
