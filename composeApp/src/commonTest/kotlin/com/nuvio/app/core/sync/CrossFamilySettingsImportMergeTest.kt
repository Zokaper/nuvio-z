package com.nuvio.app.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrossFamilySettingsImportMergeTest {
    private fun blobOf(version: Int, features: Map<String, kotlinx.serialization.json.JsonElement>) = buildJsonObject {
        put("version", version)
        put("features", JsonObject(features))
    }

    private val phone = blobOf(
        4,
        mapOf(
            "player_settings" to buildJsonObject {
                put("playback_mode", encodeSyncString("STREAMLINED"))
                put("preferred_audio_language", encodeSyncString("de"))
                put("playback_quality_ceiling_mbps", encodeSyncInt(8))
                put("use_legacy_player_layout", encodeSyncBoolean(true))
                put("setup_wizard_completed_revision", encodeSyncInt(10))
            },
            "theme_settings" to buildJsonObject {
                put("selected_theme", encodeSyncString("OCEAN"))
                put("nav_bar_style", encodeSyncString("classic"))
            },
            "poster_card_style_settings_payload" to JsonPrimitive(
                """{"widthDp":140,"catalogLandscapeModeEnabled":true,"hoverPreviewEnabled":false}""",
            ),
            "meta_screen_settings_payload" to JsonPrimitive(
                """{"background_mode":"CINEMATIC","poster_transition_enabled":true}""",
            ),
            "trakt_settings_payload" to JsonPrimitive("""{"watchProgressSource":"TRAKT"}"""),
            "social_features" to buildJsonObject { put("social_features_enabled", true) },
            "z_features" to buildJsonObject {
                put("home_presentation", buildJsonObject { put("hero_enabled", false) })
                put("episode_shuffle", buildJsonObject { put("available", true) })
            },
        ),
    )

    private val desktop = blobOf(
        5,
        mapOf(
            "player_settings" to buildJsonObject {
                put("playback_quality_ceiling_mbps", encodeSyncInt(0))
                put("decoder_priority", encodeSyncInt(2))
            },
            "theme_settings" to buildJsonObject {
                put("desktop_navigation_layout", encodeSyncString("Sidebar"))
            },
            "poster_card_style_settings_payload" to JsonPrimitive("""{"widthDp":126,"hoverPreviewEnabled":true}"""),
            "meta_screen_settings_payload" to JsonPrimitive("""{"poster_transition_enabled":false}"""),
            "trakt_settings_payload" to JsonPrimitive(""),
        ),
    )

    private val merged = mergeCrossFamilyBlob(local = desktop, other = phone)
    private val features = merged["features"]!!.jsonObject
    private fun payload(field: String) = Json.parseToJsonElement(features[field]!!.jsonPrimitive.content).jsonObject

    @Test
    fun thePlatformNeutralPlayerKeysComeAndTheDeviceOnesStay() {
        val player = features["player_settings"]!!.jsonObject
        assertEquals("STREAMLINED", player.decodeSyncString("playback_mode"))
        assertEquals("de", player.decodeSyncString("preferred_audio_language"))
        assertEquals(0, player.decodeSyncInt("playback_quality_ceiling_mbps"))
        assertEquals(2, player.decodeSyncInt("decoder_priority"))
        assertNull(player["use_legacy_player_layout"])
        // The revision is written by the import itself, capped - never copied as a key.
        assertNull(player["setup_wizard_completed_revision"])
    }

    @Test
    fun themeComesButNavigationStays() {
        val theme = features["theme_settings"]!!.jsonObject
        assertEquals("OCEAN", theme.decodeSyncString("selected_theme"))
        assertNull(theme["nav_bar_style"])
        assertEquals("Sidebar", theme.decodeSyncString("desktop_navigation_layout"))
    }

    @Test
    fun stringPayloadsAreMergedKeyByKey() {
        val poster = payload("poster_card_style_settings_payload")
        assertEquals(140, poster["widthDp"]!!.jsonPrimitive.content.toInt())
        assertEquals("true", poster["catalogLandscapeModeEnabled"]!!.jsonPrimitive.content)
        assertEquals("true", poster["hoverPreviewEnabled"]!!.jsonPrimitive.content)

        val meta = payload("meta_screen_settings_payload")
        assertEquals("CINEMATIC", meta["background_mode"]!!.jsonPrimitive.content)
        assertEquals("false", meta["poster_transition_enabled"]!!.jsonPrimitive.content)
    }

    @Test
    fun skippedFeaturesKeepThisFamilysValue() {
        assertEquals("", features["trakt_settings_payload"]!!.jsonPrimitive.content)
    }

    @Test
    fun wholeFeaturesArriveEvenWhenThisFamilyHadNone() {
        assertEquals(
            "true",
            features["social_features"]!!.jsonObject["social_features_enabled"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun onlyTheSharedZFeaturesCome() {
        val z = features["z_features"]!!.jsonObject
        assertEquals(setOf("home_presentation"), z.keys)
    }

    @Test
    fun theBlobKeepsThisFamilysVersion() {
        assertEquals("5", merged["version"]!!.jsonPrimitive.content)
    }

    @Test
    fun aMalformedStringPayloadLeavesThisFamilysValue() {
        val broken = blobOf(4, mapOf("poster_card_style_settings_payload" to JsonPrimitive("not json")))
        val result = mergeCrossFamilyBlob(local = desktop, other = broken)["features"]!!.jsonObject
        assertEquals(
            """{"widthDp":126,"hoverPreviewEnabled":true}""",
            result["poster_card_style_settings_payload"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun ownFamilyPullFinishesBeforeTheArrivalImportStarts() = runBlocking {
        val events = mutableListOf<String>()

        val result = pullThenImportCrossFamilySettings(
            profileId = 6,
            pullAll = {
                events += "pull:start:$it"
                events += "pull:end:$it"
            },
            importSettings = {
                events += "import:$it"
                CrossFamilyImportOutcome.Imported
            },
        )

        assertEquals(CrossFamilyImportOutcome.Imported, result)
        assertEquals(listOf("pull:start:6", "pull:end:6", "import:6"), events)
    }

    @Test
    fun importerFailureBecomesRetryableWithoutFailingTheProfileSwitch() = runBlocking {
        val result = pullThenImportCrossFamilySettings(
            profileId = 4,
            pullAll = {},
            importSettings = { error("server unavailable") },
        )

        assertEquals(CrossFamilyImportOutcome.TransportFailure, result)
    }

    @Test
    fun arrivingDesktopBlobCanAppearAfterTheFirstLookup() = runBlocking {
        var attempts = 0
        val pauses = mutableListOf<Long>()
        val blob = JsonObject(emptyMap())
        val result = awaitArrivingFamilyBlob(
            fetch = { Result.success(if (++attempts == 1) null else blob) },
            pause = { pauses += it },
        )
        assertEquals(ArrivingFamilyLookup.Found(blob), result)
        assertEquals(2, attempts)
        assertEquals(listOf(250L), pauses)
    }

    @Test
    fun lookupFailureIsDistinctFromConfirmedAbsence() = runBlocking {
        val failed = awaitArrivingFamilyBlob(fetch = { Result.failure(IllegalStateException("offline")) })
        assertTrue(failed is ArrivingFamilyLookup.Failed)
        var attempts = 0
        val absent = awaitArrivingFamilyBlob(
            fetch = { attempts++; Result.success(null) },
            pause = {},
        )
        assertEquals(ArrivingFamilyLookup.Absent, absent)
        assertEquals(5, attempts)
    }
}
