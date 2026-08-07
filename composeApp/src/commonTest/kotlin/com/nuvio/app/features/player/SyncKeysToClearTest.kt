package com.nuvio.app.features.player

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncKeysToClearTest {

    @Test
    fun aKeyTheRemoteHasNeverHeardOfSurvives() {
        // The exact shape of the 0.4.0-beta bug: a stored blob written before the playback
        // settings existed. Clearing every sync key first wiped them on every sync, which
        // reset the playback mode and re-showed the first-launch selector forever.
        val syncKeys = listOf("show_loading_overlay", "playback_mode", "playback_mode_selector_seen")
        val oldRemoteBlob = buildJsonObject { put("show_loading_overlay", JsonPrimitive("true")) }

        val cleared = syncKeysToClear(syncKeys, oldRemoteBlob)

        assertEquals(listOf("show_loading_overlay"), cleared)
        assertTrue("playback_mode" !in cleared)
        assertTrue("playback_mode_selector_seen" !in cleared)
    }

    @Test
    fun aKeyThePayloadCarriesIsStillCleared() {
        // The remote stays authoritative for anything it does know about, so replacing a
        // value still starts from a clean slate rather than merging.
        val syncKeys = listOf("playback_mode", "resize_mode")
        val payload = buildJsonObject {
            put("playback_mode", JsonPrimitive("INSTANT"))
            put("resize_mode", JsonPrimitive("Fit"))
        }

        assertEquals(syncKeys, syncKeysToClear(syncKeys, payload))
    }

    @Test
    fun anEmptyPayloadClearsNothing() {
        val syncKeys = listOf("playback_mode", "resize_mode")
        assertEquals(emptyList(), syncKeysToClear(syncKeys, buildJsonObject { }))
    }

    @Test
    fun payloadKeysThatAreNotSyncKeysAreIgnored() {
        val payload = buildJsonObject { put("something_else", JsonPrimitive("1")) }
        assertEquals(emptyList(), syncKeysToClear(listOf("playback_mode"), payload))
    }
}
