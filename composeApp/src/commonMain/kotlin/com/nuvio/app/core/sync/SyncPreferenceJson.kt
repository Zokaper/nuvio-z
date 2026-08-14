package com.nuvio.app.core.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val TYPE_KEY = "type"
private const val VALUE_KEY = "value"
private const val TYPE_STRING = "string"
private const val TYPE_BOOLEAN = "boolean"
private const val TYPE_INT = "int"
private const val TYPE_FLOAT = "float"
private const val TYPE_STRING_SET = "string_set"

internal fun encodeSyncString(value: String): JsonObject = buildJsonObject {
    put(TYPE_KEY, TYPE_STRING)
    put(VALUE_KEY, value)
}

internal fun encodeSyncBoolean(value: Boolean): JsonObject = buildJsonObject {
    put(TYPE_KEY, TYPE_BOOLEAN)
    put(VALUE_KEY, value)
}

internal fun encodeSyncInt(value: Int): JsonObject = buildJsonObject {
    put(TYPE_KEY, TYPE_INT)
    put(VALUE_KEY, value)
}

internal fun encodeSyncFloat(value: Float): JsonObject = buildJsonObject {
    put(TYPE_KEY, TYPE_FLOAT)
    put(VALUE_KEY, value)
}

internal fun encodeSyncStringSet(values: Set<String>): JsonObject = buildJsonObject {
    put(TYPE_KEY, TYPE_STRING_SET)
    put(VALUE_KEY, JsonArray(values.sorted().map(::JsonPrimitive)))
}

internal fun JsonObject.decodeSyncString(key: String): String? =
    get(key)
        ?.jsonObject
        ?.takeIf { it[TYPE_KEY]?.jsonPrimitive?.contentOrNull == TYPE_STRING }
        ?.get(VALUE_KEY)
        ?.jsonPrimitive
        ?.contentOrNull

internal fun JsonObject.decodeSyncBoolean(key: String): Boolean? =
    get(key)
        ?.jsonObject
        ?.takeIf { it[TYPE_KEY]?.jsonPrimitive?.contentOrNull == TYPE_BOOLEAN }
        ?.get(VALUE_KEY)
        ?.jsonPrimitive
        ?.booleanOrNull

internal fun JsonObject.decodeSyncInt(key: String): Int? =
    get(key)
        ?.jsonObject
        ?.takeIf { it[TYPE_KEY]?.jsonPrimitive?.contentOrNull == TYPE_INT }
        ?.get(VALUE_KEY)
        ?.jsonPrimitive
        ?.intOrNull

internal fun JsonObject.decodeSyncFloat(key: String): Float? =
    get(key)
        ?.jsonObject
        ?.takeIf { it[TYPE_KEY]?.jsonPrimitive?.contentOrNull == TYPE_FLOAT }
        ?.get(VALUE_KEY)
        ?.jsonPrimitive
        ?.floatOrNull

internal fun JsonObject.decodeSyncStringSet(key: String): Set<String>? =
    get(key)
        ?.jsonObject
        ?.takeIf { it[TYPE_KEY]?.jsonPrimitive?.contentOrNull == TYPE_STRING_SET }
        ?.get(VALUE_KEY)
        ?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
        ?.filter(String::isNotBlank)
        ?.toSet()

/**
 * Which stored sync keys a `replaceFromSyncPayload` may clear before applying [payload].
 *
 * **Only the keys the payload actually carries.** The remote blob is authoritative for
 * settings it knows about, never for settings it has never heard of: clearing every sync key
 * first destroys anything added since that blob was last written. That is not hypothetical -
 * it reset the playback mode and re-showed the first-launch selector on every sync for any
 * signed-in user whose stored blob predated `0.4.0-beta`, because none of the `playback_*`
 * keys existed when it was written.
 *
 * Every settings store shares this so the rule cannot drift between stores or platforms.
 */
internal fun syncKeysToClear(syncKeys: List<String>, payload: JsonObject): List<String> =
    syncKeys.filter(payload::containsKey)

/**
 * The value to store for a sync key that may only ever increase.
 *
 * ⚠ **A `replaceFromSyncPayload` bypasses every guard the repository puts on a setter**, because
 * it writes through the store directly. `setup_wizard_completed_revision` is monotonic -
 * `PlayerSettingsRepository.markSetupWizardCompleted` refuses to lower it - and the sync path
 * lowered it anyway: the remote blob was written by an older build, so every startup pull cleared
 * the key and wrote the older number back, `onProfileChanged()` republished it, and the
 * first-launch wizard reappeared. On every launch, permanently, because a wizard that gates the
 * app never gets far enough to push the corrected value.
 *
 * The rule is the same one [syncKeysToClear] encodes, one step further on: **the remote is
 * authoritative for what it knows, never for what it has not caught up with.** Taking the larger
 * of the two costs nothing when the remote is ahead - a profile really can arrive from a newer
 * install - and is the only thing that stops it dragging a device backwards.
 *
 * Lives here rather than in each `PlayerSettingsStorage` actual so the rule cannot drift between
 * platforms; there are three actuals in this repository and four in `NuvioZDesktop`.
 *
 * Answers null only when neither side has a value, so the caller writes nothing at all.
 */
internal fun mergeMonotonicSyncInt(local: Int?, remote: Int?): Int? = when {
    local == null -> remote
    remote == null -> local
    else -> maxOf(local, remote)
}
