package com.nuvio.app.features.social

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Inbox's Unread / Read split (2026-10-19). Hardware: an invite that arrived while the Inbox was open
 * showed "Just now" under "Earlier", because the old rule only counted what was unread when the page opened.
 */
class SocialInboxGroupingTest {

    @Test
    fun `a message that arrives after the page opened is unread`() {
        // At open: "old" was read, "waiting" was unread. "arrived" came in afterwards.
        val (unread, read) = splitInboxUnreadRead(listOf("arrived", "waiting", "old"), readAtOpen = setOf("old")) { it }
        assertEquals(listOf("arrived", "waiting"), unread)
        assertEquals(listOf("old"), read)
    }

    @Test
    fun `marking the page read does not move anything`() {
        // Same entries after the read marks land: the split still uses what was read at open.
        val (unread, read) = splitInboxUnreadRead(listOf("waiting", "old"), readAtOpen = setOf("old")) { it }
        assertEquals(listOf("waiting"), unread)
        assertEquals(listOf("old"), read)
    }
}
