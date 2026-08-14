package com.nuvio.app.core.sync

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    // The five stores below carried the same wipe-then-apply pattern as PlayerSettingsStorage.
    // None of them was losing data yet only because none had gained a key since its blobs were
    // written - `STATUS.md` recorded that "the next key added to any of them will hit exactly
    // this". Each case is the old-blob shape for that store: a payload holding the keys the
    // store shipped with, and one key added later that must survive the pull.

    @Test
    fun mdbListKeepsAKeyAddedAfterTheBlobWasWritten() {
        val syncKeys = listOf(
            "mdblist_enabled",
            "mdblist_api_key",
            "mdblist_use_imdb",
            "mdblist_use_mal",
        )
        val oldBlob = buildJsonObject {
            put("mdblist_enabled", JsonPrimitive("true"))
            put("mdblist_api_key", JsonPrimitive("k"))
            put("mdblist_use_imdb", JsonPrimitive("true"))
        }

        assertTrue("mdblist_use_mal" !in syncKeysToClear(syncKeys, oldBlob))
    }

    @Test
    fun streamBadgesKeepAKeyAddedAfterTheBlobWasWritten() {
        val syncKeys = listOf(
            "stream_badge_rules",
            "show_file_size_badges",
            "stream_badge_placement",
        )
        val oldBlob = buildJsonObject { put("stream_badge_rules", JsonPrimitive("[]")) }

        assertEquals(listOf("stream_badge_rules"), syncKeysToClear(syncKeys, oldBlob))
    }

    @Test
    fun tmdbKeepsAKeyAddedAfterTheBlobWasWritten() {
        val syncKeys = listOf("tmdb_enabled", "tmdb_api_key", "tmdb_use_collections")
        val oldBlob = buildJsonObject {
            put("tmdb_enabled", JsonPrimitive("true"))
            put("tmdb_api_key", JsonPrimitive("k"))
        }

        assertTrue("tmdb_use_collections" !in syncKeysToClear(syncKeys, oldBlob))
    }

    @Test
    fun traktCommentsClearNothingWhenThePayloadOmitsTheOnlyKey() {
        // This store has a single key, so the old pattern was an unconditional remove: a pull
        // whose payload had no trakt_comments entry silently switched comments back off.
        val syncKeys = listOf("comments_enabled")

        assertEquals(emptyList(), syncKeysToClear(syncKeys, buildJsonObject { }))
    }

    @Test
    fun debridKeepsAProviderKeyAddedAfterTheBlobWasWritten() {
        // Debrid's key list is built at runtime from DebridProviders.all(), so a provider added
        // in a later release is exactly the "key the remote has never heard of" case - and here
        // it would have deleted a stored API key.
        val syncKeys = listOf("debrid_enabled", "debrid_real_debrid_api_key", "debrid_torbox_api_key")
        val oldBlob = buildJsonObject {
            put("debrid_enabled", JsonPrimitive("true"))
            put("debrid_real_debrid_api_key", JsonPrimitive("k"))
        }

        assertTrue("debrid_torbox_api_key" !in syncKeysToClear(syncKeys, oldBlob))
    }

    @Test
    fun themeKeepsAKeyAddedAfterTheBlobWasWritten() {
        val syncKeys = listOf("selected_theme", "amoled_enabled", "nav_bar_style")
        val oldBlob = buildJsonObject { put("selected_theme", JsonPrimitive("nuvio")) }

        assertEquals(listOf("selected_theme"), syncKeysToClear(syncKeys, oldBlob))
    }

    @Test
    fun twoDevicePullReplacesExplicitValuesButPreservesUnknownLocalSettings() {
        val syncKeys = listOf("playback_mode", "show_loading_overlay", "future_setting")
        val deviceB = mutableMapOf(
            "playback_mode" to "CLASSIC",
            "show_loading_overlay" to "true",
            "future_setting" to "keep-me",
        )
        val deviceAPayload = buildJsonObject {
            put("playback_mode", JsonPrimitive("STREAMLINED"))
            put("show_loading_overlay", JsonPrimitive("false"))
        }

        syncKeysToClear(syncKeys, deviceAPayload).forEach(deviceB::remove)
        deviceAPayload.forEach { (key, value) -> deviceB[key] = value.toString().trim('"') }

        assertEquals("STREAMLINED", deviceB["playback_mode"])
        assertEquals("false", deviceB["show_loading_overlay"])
        assertEquals("keep-me", deviceB["future_setting"])
    }

    // --- mergeMonotonicSyncInt -------------------------------------------------------------
    //
    // The same rule one step further on. `syncKeysToClear` stops the remote destroying a key it
    // has never heard of; this stops it dragging a key it *has* heard of backwards. The wizard
    // revision is the only monotonic sync key today, and it is where this was found.

    @Test
    fun aStaleRemoteRevisionCannotLowerTheLocalOne() {
        // The whole defect: a remote blob pushed by an older build carries revision 3, the local
        // profile has finished revision 4, and every startup pull wrote 3 back - so the wizard
        // that gates the app reappeared on every single launch.
        assertEquals(4, mergeMonotonicSyncInt(local = 4, remote = 3))
    }

    @Test
    fun aNewerRemoteRevisionStillWins() {
        // A profile really can arrive from a newer install. Refusing it would re-ask a user a
        // superset of what they have already answered.
        assertEquals(5, mergeMonotonicSyncInt(local = 4, remote = 5))
    }

    @Test
    fun equalRevisionsAreLeftAlone() {
        assertEquals(4, mergeMonotonicSyncInt(local = 4, remote = 4))
    }

    @Test
    fun aRemoteThatHasNeverHeardOfTheKeyKeepsTheLocalValue() {
        // Reachable together with syncKeysToClear: the payload omits the key, so nothing is
        // cleared and nothing is decoded. The local value has to survive that untouched.
        assertEquals(4, mergeMonotonicSyncInt(local = 4, remote = null))
    }

    @Test
    fun aFreshDeviceTakesTheRemoteValue() {
        assertEquals(3, mergeMonotonicSyncInt(local = null, remote = 3))
    }

    @Test
    fun neitherSideHavingAValueWritesNothingAtAll() {
        // Null rather than 0, so the caller skips the write entirely. Writing 0 would turn "never
        // asked" into "answered revision 0", which reads the same today and would not if the
        // rule ever gained a floor.
        assertNull(mergeMonotonicSyncInt(local = null, remote = null))
    }
}
