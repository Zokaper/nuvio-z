package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PartyRpcFailureTest {
    @Test fun aRejectedSourceIsClassifiedAndNeverEchoesBackendText() {
        val raw = "invalid_source_media\nCode: 22023\nURL: https://pz.supabase.co/rest/v1/rpc/party_select_source_v2"
        val failure = classifyPartyRpcFailure(raw)
        assertEquals(PartyRpcFailure.SourceRejected, failure)
        for (leaked in listOf("invalid_source_media", "22023", "supabase", "rpc", "party_select_source_v2")) {
            assertFalse(leaked in failure.userMessage.lowercase(), "leaked $leaked")
        }
    }

    @Test fun otherBackendLabelsAreClassified() {
        assertEquals(PartyRpcFailure.SourceRejected, classifyPartyRpcFailure("unsafe_source_media"))
        assertEquals(PartyRpcFailure.PartyMoved, classifyPartyRpcFailure("stale_party_generation"))
        assertEquals(PartyRpcFailure.NotHost, classifyPartyRpcFailure("host_required"))
        assertEquals(PartyRpcFailure.Other, classifyPartyRpcFailure("connection reset"))
        assertEquals(PartyRpcFailure.Other, classifyPartyRpcFailure(null))
    }
}
