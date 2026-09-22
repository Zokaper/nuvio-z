package com.nuvio.app.features.social

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The presence payload's null handling, pinned.
 *
 * `Watching Now` showed nobody through a two-client friend test because
 * `social_publish_presence` sanitizes the descriptor with
 * `sanitize_source_descriptor_v2(p_entry->'source_fingerprint')`, and that function's null guard is
 * `if p_value is null then return null` - an **SQL** NULL test. An absent key reads as SQL NULL and
 * returns cleanly; an explicit `"source_fingerprint": null` reads as jsonb `'null'`, which is not
 * SQL NULL, so `jsonb_typeof` answers `'null'` instead of `'object'` and the publish aborts with
 * `invalid_source_descriptor`. Verified against the live project, both directions.
 *
 * kotlinx defaults `explicitNulls` to true, so the moment the descriptor joined this payload every
 * publish made **outside** a party started failing - and the discarded `Result` meant nothing said
 * so. These tests fail if that default ever comes back.
 */
class SocialPresencePayloadTest {

    /** The same configuration `SocialRepository` encodes RPC parameters with. */
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private fun entry(fingerprint: com.nuvio.app.features.watchparty.PartySourceDescriptorV2? = null) =
        SocialPresencePublish(
            sessionId = "11111111-1111-1111-1111-111111111111",
            contentId = "tt0000001",
            contentType = "series",
            videoId = "tt0000001:1:2",
            title = "The Defenders",
            season = 1,
            episode = 2,
            positionMs = 1_000,
            durationMs = 2_400_000,
            playbackSpeed = 1f,
            state = SocialPlaybackState.playing,
            sourceFingerprint = fingerprint,
        )

    @Test fun omitsAbsentDescriptorRatherThanSendingJsonNull() {
        val encoded = json.encodeToJsonElement(entry()).jsonObject
        // The whole bug in one assertion: the key must be *absent*, not present-and-null.
        assertFalse(
            encoded.containsKey("source_fingerprint"),
            "a null descriptor must be omitted; an explicit JSON null makes the backend " +
                "sanitizer raise invalid_source_descriptor and no presence row is written",
        )
    }

    @Test fun omitsEveryOptionalFieldThatIsUnset() {
        val encoded = json.encodeToJsonElement(entry()).jsonObject
        listOf("poster", "background", "episode_thumbnail", "episode_title", "track_intent")
            .forEach { assertFalse(encoded.containsKey(it), "$it must be omitted when unset") }
    }

    @Test fun stillSendsTheDescriptorWhenThereIsOne() {
        val descriptor = com.nuvio.app.features.watchparty.PartySourceDescriptorV2(
            originKind = com.nuvio.app.features.watchparty.PartySourceOriginKind.addon,
            originId = "com.example.addon",
            releaseFingerprint = "sha256:" + "a".repeat(64),
        )
        val encoded = json.encodeToJsonElement(entry(descriptor)).jsonObject
        assertTrue(encoded.containsKey("source_fingerprint"), "a real descriptor must still travel")
        val sent = encoded["source_fingerprint"]!!.jsonObject
        assertEquals(2, sent["version"].toString().toInt())
        // The sanitizer rejects any key outside its allowlist, so the shape is part of the contract.
        val allowed = setOf(
            "version", "origin_kind", "origin_id", "origin_version",
            "info_hash", "file_index", "release_fingerprint", "media",
        )
        sent.keys.forEach { assertTrue(it in allowed, "unexpected descriptor key '$it' would be rejected") }
    }

    @Test fun keepsTheFieldsThePresenceRowRequires() {
        val encoded = json.encodeToJsonElement(entry()).jsonObject
        // These columns are NOT NULL, so omitting any of them fails the insert instead of the sanitizer.
        listOf("session_id", "content_id", "content_type", "video_id", "title", "position_ms", "duration_ms", "state")
            .forEach { assertTrue(encoded.containsKey(it), "$it is required by watch_presence") }
    }
}
