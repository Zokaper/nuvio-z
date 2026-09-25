package com.nuvio.app.features.whatsnew

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * Reads `composeResources/files/changelog.json` - the one changelog, shipped in the app so What's
 * New and its history work offline. Parsing is lenient on purpose: an entry with an unknown
 * category or platform is dropped rather than failing the file, so a newer changelog can never
 * blank the screen of an older build.
 */
object ChangelogCatalog {
    private val json = Json { ignoreUnknownKeys = true }

    @OptIn(ExperimentalResourceApi::class)
    suspend fun load(): List<ChangelogRelease> = runCatching {
        parse(Res.readBytes("files/changelog.json").decodeToString())
    }.getOrDefault(emptyList())

    fun parse(text: String): List<ChangelogRelease> =
        json.decodeFromString(ChangelogFile.serializer(), text).releases.map { release ->
            ChangelogRelease(
                family = release.family,
                version = release.version,
                serial = release.serial,
                date = release.date,
                entries = release.entries.mapNotNull { entry ->
                    val category = when (entry.category) {
                        "feature" -> ChangelogCategory.FEATURE
                        "improvement" -> ChangelogCategory.IMPROVEMENT
                        "fix" -> ChangelogCategory.FIX
                        else -> return@mapNotNull null
                    }
                    val platforms = entry.platforms.mapNotNull { name ->
                        ChangelogPlatform.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    }.toSet()
                    ChangelogEntry(category, platforms, entry.title, entry.body)
                },
                debug = release.debug.map { ChangelogDebugNote(it.build, it.text) },
            )
        }

    @Serializable
    private data class ChangelogFile(val releases: List<ReleaseJson> = emptyList())

    @Serializable
    private data class ReleaseJson(
        val family: String,
        val version: String,
        val serial: Int,
        val date: String = "",
        val entries: List<EntryJson> = emptyList(),
        val debug: List<DebugJson> = emptyList(),
    )

    @Serializable
    private data class EntryJson(
        val category: String,
        val platforms: List<String> = emptyList(),
        val title: String,
        val body: String? = null,
    )

    @Serializable
    private data class DebugJson(val build: Int, val text: String)
}
