package com.nuvio.app.features.whatsnew

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import nuvio.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * Reads the two shipped changelog files, offline:
 *
 * - `files/changelog.json` - the **global** changelog of release events, byte-identical in both
 *   repositories (`scripts/check-changelog.py compare`);
 * - `files/changelog-debug.json` - this repository's debug-build notes, which belong to one release
 *   family and so are not shared.
 *
 * Parsing walks the JSON tree and is lenient per item: an event without a seq or a known platform, an
 * entry with an unknown category, no known platform or no title, is dropped on its own, and an
 * unknown action drops only the action. A newer file can never blank the screen of an older build,
 * and one bad line cannot take the rest with it. Unknown keys (`qa`, `_readme`) are ignored.
 */
object ChangelogCatalog {
    data class Loaded(val events: List<ChangelogEvent>, val debugNotes: List<ChangelogDebugNote>)

    @OptIn(ExperimentalResourceApi::class)
    suspend fun load(family: String): Loaded {
        val events = runCatching { parse(Res.readBytes("files/changelog.json").decodeToString()) }
            .getOrDefault(emptyList())
        val debug = runCatching { parseDebug(Res.readBytes("files/changelog-debug.json").decodeToString(), family) }
            .getOrDefault(emptyList())
        return Loaded(events, debug)
    }

    /** The events of a `changelog.json`, in file order (newest first). Empty for unreadable text. */
    fun parse(text: String): List<ChangelogEvent> {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return emptyList()
        return (root["events"] as? JsonArray)?.mapNotNull(::parseEvent).orEmpty()
    }

    /** The notes of a `changelog-debug.json` when it belongs to [family]. */
    fun parseDebug(text: String, family: String): List<ChangelogDebugNote> {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return emptyList()
        if (root.string("family") != family) return emptyList()
        return (root["notes"] as? JsonArray)?.mapNotNull { element ->
            val note = element as? JsonObject ?: return@mapNotNull null
            val build = note.int("build") ?: return@mapNotNull null
            val text = note.string("text")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ChangelogDebugNote(build, text)
        }.orEmpty()
    }

    private fun parseEvent(element: JsonElement): ChangelogEvent? {
        val event = element as? JsonObject ?: return null
        val seq = event.int("seq")?.takeIf { it > 0 } ?: return null
        val ships = (event["ships"] as? JsonObject)?.entries?.mapNotNull { (name, value) ->
            val platform = platformOf(name) ?: return@mapNotNull null
            parseShip(value)?.let { platform to it }
        }?.toMap().orEmpty()
        if (ships.isEmpty()) return null
        return ChangelogEvent(
            seq = seq,
            ships = ships,
            entries = (event["entries"] as? JsonArray)?.mapNotNull(::parseEntry).orEmpty(),
            summary = event.string("summary")?.takeIf { it.isNotBlank() },
        )
    }

    private fun parseShip(element: JsonElement): ChangelogShip? {
        val ship = element as? JsonObject ?: return null
        val version = ship.string("version")?.takeIf { it.isNotBlank() } ?: return null
        val serial = ship.int("serial") ?: return null
        val date = ship.string("date")?.takeIf { it.isNotBlank() && it != UNRELEASED }
        return ChangelogShip(version, serial, date)
    }

    private fun parseEntry(element: JsonElement): ChangelogEntry? {
        val entry = element as? JsonObject ?: return null
        val category = when (entry.string("category")) {
            "feature" -> ChangelogCategory.FEATURE
            "improvement" -> ChangelogCategory.IMPROVEMENT
            "fix" -> ChangelogCategory.FIX
            else -> return null
        }
        val platforms = (entry["platforms"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.let(::platformOf) }
            ?.toSet().orEmpty()
        if (platforms.isEmpty()) return null
        val title = entry.string("title")?.takeIf { it.isNotBlank() } ?: return null
        return ChangelogEntry(
            category = category,
            platforms = platforms,
            title = title,
            body = entry.string("body")?.takeIf { it.isNotBlank() },
            action = ChangelogAction.fromKey(entry.string("action")),
        )
    }

    private fun platformOf(name: String): ChangelogPlatform? =
        ChangelogPlatform.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private const val UNRELEASED = "unreleased"
}
