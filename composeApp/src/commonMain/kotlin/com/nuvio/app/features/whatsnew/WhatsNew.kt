package com.nuvio.app.features.whatsnew

import com.nuvio.app.features.updater.AppReleaseNotes

enum class WhatsNewCategory {
    NewFeatures,
    Improvements,
    BugFixes,

    /** Debug builds only: what changed since the last debug build this device saw. */
    DebugBuild,
}

data class WhatsNewItem(
    val title: String,
    val description: String,
    /** The release an entry came from, shown only when one screen merges several releases. */
    val version: String? = null,
)

data class WhatsNewSection(
    val category: WhatsNewCategory,
    val items: List<WhatsNewItem>,
)

/**
 * Which release this build is, for the changelog (Phase 9): its release line, serial, version, and
 * debug build number when it is a debug build. Per platform because each repository generates its
 * own `AppVersionConfig` - desktop's `VERSION_NAME` is a stale mobile value, so desktop must read
 * `DESKTOP_VERSION_NAME` and its own `RELEASE_SERIAL`.
 */
data class WhatsNewReleaseIdentity(
    val family: String,
    val serial: Int,
    val versionName: String,
    val debugBuild: Int?,
    val platform: ChangelogPlatform,
)

internal expect object WhatsNewStorage {
    val isDesktop: Boolean

    /** The pre-Phase-9 key: only read, to recognise an upgrade (see `decideWhatsNew`). */
    fun loadLastSeenVersion(): String?
    fun saveLastSeenVersion(versionName: String)

    val releaseIdentity: WhatsNewReleaseIdentity

    /** Device-local, in its own key: what this device has been shown. Null when never written. */
    fun loadAck(): WhatsNewAck?
    fun saveAck(ack: WhatsNewAck)
}

/**
 * The screen's sections for a decision. Entries carry their version only when the screen merges
 * more than one release - "0.4.14-z1" on every line of a single release is noise.
 */
fun WhatsNewDecision.toSections(): List<WhatsNewSection> {
    val tagVersions = sections.flatMap { section -> section.entries.map { it.version } }.distinct().size > 1
    val released = sections.map { section ->
        WhatsNewSection(
            category = section.category.toWhatsNewCategory(),
            items = section.entries.map {
                WhatsNewItem(it.entry.title, it.entry.body.orEmpty(), it.version.takeIf { tagVersions })
            },
        )
    }
    val debug = debugNotes.takeIf { it.isNotEmpty() }?.let { notes ->
        WhatsNewSection(WhatsNewCategory.DebugBuild, notes.map { WhatsNewItem("#${it.build}", it.text) })
    }
    return listOfNotNull(debug) + released
}

private fun ChangelogCategory.toWhatsNewCategory(): WhatsNewCategory = when (this) {
    ChangelogCategory.FEATURE -> WhatsNewCategory.NewFeatures
    ChangelogCategory.IMPROVEMENT -> WhatsNewCategory.Improvements
    ChangelogCategory.FIX -> WhatsNewCategory.BugFixes
}

/**
 * One shipped release as a history entry for Settings -> What's new, in the markdown the history
 * list already renders. From the shipped changelog, so the history works offline.
 */
fun changelogHistoryNotes(
    release: ChangelogRelease,
    sections: List<WhatsNewCategorySection>,
    headings: Map<ChangelogCategory, String>,
): AppReleaseNotes = AppReleaseNotes(
    tag = release.version,
    title = release.version,
    notes = sections.joinToString("\n\n") { section ->
        "## ${headings[section.category].orEmpty()}\n" +
            section.entries.joinToString("\n") { "- ${it.entry.title}" + (it.entry.body?.let { body -> ": $body" } ?: "") }
    },
)
