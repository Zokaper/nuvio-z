package com.nuvio.app.features.social

/** Ordinary relationship refusals are UI states, never backend exception copy. */
fun socialFriendRequestMessage(message: String?): String {
    val code = message.orEmpty().lowercase()
    return when {
        "already_friends" in code -> "You're already friends."
        "request_incoming" in code -> "They've already sent you a request. Check your inbox."
        "request_pending" in code || "friend_requests_one_pending_pair" in code ->
            "A friend request is already pending."
        "cannot_friend_self" in code || "friend_request_not_self" in code ->
            "You can't send a friend request to yourself."
        "rate_limited" in code -> "Too many requests. Try again later."
        else -> "Could not send that friend request. Please try again."
    }
}
