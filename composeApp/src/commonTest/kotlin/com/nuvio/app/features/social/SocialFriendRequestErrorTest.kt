package com.nuvio.app.features.social

import kotlin.test.Test
import kotlin.test.assertEquals

class SocialFriendRequestErrorTest {
    @Test fun ordinaryRelationshipErrorsNeverExposeBackendText() {
        assertEquals("You're already friends.", socialFriendRequestMessage("PostgrestRestException: already_friends"))
        assertEquals("A friend request is already pending.", socialFriendRequestMessage("friend_requests_one_pending_pair"))
        assertEquals("They've already sent you a request. Check your inbox.", socialFriendRequestMessage("request_incoming"))
        assertEquals("You can't send a friend request to yourself.", socialFriendRequestMessage("cannot_friend_self"))
        assertEquals("Too many requests. Try again later.", socialFriendRequestMessage("rate_limited"))
        assertEquals("Could not send that friend request. Please try again.", socialFriendRequestMessage("SQLSTATE 99999 private text"))
    }
}
