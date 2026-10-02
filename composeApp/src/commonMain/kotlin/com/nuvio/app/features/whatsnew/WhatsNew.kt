package com.nuvio.app.features.whatsnew

import com.nuvio.app.core.format.formatReleaseDateForDisplay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    /** An action card: the entry offers to do something now ("Try Advanced Setup"). */
    val action: ChangelogAction? = null,
    /** Set for "on other devices" entries only: the platforms the entry is about. */
    val platforms: List<ChangelogPlatform> = emptyList(),
)

data class WhatsNewSection(
    val category: WhatsNewCategory,
    val items: List<WhatsNewItem>,
)

/** One version an event shipped in. [isViewer] marks this device's own platform. */
data class WhatsNewVersion(val platform: ChangelogPlatform, val version: String, val isViewer: Boolean)

/**
 * One release event as the screen draws it: the date and optional summary are its identity, the
 * versions are secondary, and only the platforms it really shipped on are listed.
 */
data class WhatsNewEventUi(
    val seq: Int,
    /** Formatted for display; null for an event not released yet (a debug build's preview). */
    val date: String?,
    val summary: String?,
    val versions: List<WhatsNewVersion>,
    val sections: List<WhatsNewSection>,
    /** "Also in this update, on other devices" - kept, set apart, never hidden. */
    val otherDevices: List<WhatsNewItem>,
)

/**
 * This build, for the changelog: its platform and family, its release serial and version, and its
 * debug build number when it is a debug build. Per platform because each repository generates its
 * own `AppVersionConfig` - desktop's `VERSION_NAME` is a stale mobile value, so desktop reads
 * `DESKTOP_VERSION_NAME` and its own `RELEASE_SERIAL`.
 */
data class WhatsNewReleaseIdentity(
    val family: String,
    val serial: Int,
    val versionName: String,
    val debugBuild: Int?,
    val platform: ChangelogPlatform,
) {
    val viewer: ChangelogViewer get() = ChangelogViewer(platform, serial, debugBuild)
}

internal expect object WhatsNewStorage {
    val releaseIdentity: WhatsNewReleaseIdentity

    /** Everything stored, including the keys earlier builds wrote (read only, never rewritten). */
    fun load(): StoredWhatsNew

    /** Device-local, in its own keys: what this device has acknowledged and viewed. */
    fun save(state: WhatsNewState)
}

/**
 * Settings -> What's New's "New" badge. Process-wide because on iOS the Settings row is drawn by a
 * different `AppGate` from the one that owns the runtime and decides the badge (`bypassAppGate`).
 */
object WhatsNewBadge {
    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    internal fun update(value: Boolean) {
        _pending.value = value
    }
}

private fun ChangelogCategory.toWhatsNewCategory(): WhatsNewCategory = when (this) {
    ChangelogCategory.FEATURE -> WhatsNewCategory.NewFeatures
    ChangelogCategory.IMPROVEMENT -> WhatsNewCategory.Improvements
    ChangelogCategory.FIX -> WhatsNewCategory.BugFixes
}

private fun ChangelogEntry.toItem(withPlatforms: Boolean) = WhatsNewItem(
    title = title,
    description = body.orEmpty(),
    action = action,
    platforms = if (withPlatforms) ChangelogPlatformOrder.filter { it in platforms } else emptyList(),
)

/** [events] for [platform], newest first, in the screen's shape. */
fun whatsNewEventsUi(events: List<ChangelogEvent>, platform: ChangelogPlatform): List<WhatsNewEventUi> =
    events.sortedByDescending { it.seq }.map { event ->
        val view = event.viewFor(platform)
        WhatsNewEventUi(
            seq = event.seq,
            date = view.date?.let(::formatReleaseDateForDisplay),
            summary = event.summary,
            versions = view.versions.map { (p, ship) -> WhatsNewVersion(p, ship.version, p == platform) },
            sections = view.own.map { (category, entries) ->
                WhatsNewSection(category.toWhatsNewCategory(), entries.map { it.toItem(withPlatforms = false) })
            },
            otherDevices = view.otherDevices.map { it.toItem(withPlatforms = true) },
        )
    }

/** The "This debug build" section, or null when there is nothing to say. */
fun whatsNewDebugSection(notes: List<ChangelogDebugNote>): WhatsNewSection? =
    notes.takeIf { it.isNotEmpty() }?.let { list ->
        WhatsNewSection(WhatsNewCategory.DebugBuild, list.map { WhatsNewItem("#${it.build}", it.text) })
    }

/**
 * Whether a GitHub release of this app is already covered by the shipped changelog - the history
 * list's "Previous versions" shows only what the changelog does not.
 */
fun isCoveredByChangelog(tag: String, events: List<ChangelogEvent>, platform: ChangelogPlatform): Boolean {
    val version = tag.trim().trimStart('v', 'V').substringBefore('+')
    return events.any { it.ships[platform]?.version == version }
}
