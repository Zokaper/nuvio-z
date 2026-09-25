package com.nuvio.app.features.whatsnew

// No imports, and none may be added: this file runs in the pure suites
// (`scripts/run-pure-suites.sh`). What a user is shown after an update is decided here and only
// here; the screen and the gate render the answer.

/**
 * The structured changelog (Phase 9 stage 9, plan section 4.10): one file,
 * `composeResources/files/changelog.json`, shared by both repositories, read by the app offline and
 * by the release tooling. Release identity is the monotonic **serial**, never the version string -
 * a Nuvio Z version can go backwards by name (`ReleaseSerial.xcconfig`).
 */
enum class ChangelogCategory { FEATURE, IMPROVEMENT, FIX }

enum class ChangelogPlatform { ANDROID, IOS, DESKTOP }

data class ChangelogEntry(
    val category: ChangelogCategory,
    val platforms: Set<ChangelogPlatform>,
    val title: String,
    val body: String? = null,
)

/** A line for the "This debug build" section. Debug builds never reach stable users. */
data class ChangelogDebugNote(val build: Int, val text: String)

data class ChangelogRelease(
    /** `mobile` or `desktop`: two release lines with their own serials. */
    val family: String,
    val version: String,
    val serial: Int,
    val date: String,
    val entries: List<ChangelogEntry>,
    /** Notes for debug builds cut on the way to this release. */
    val debug: List<ChangelogDebugNote> = emptyList(),
)

/** What this device has already been shown, device-local. */
data class WhatsNewAck(val serial: Int, val debugBuild: Int)

/** One entry with the version it arrived in, so merged sections can say which is which. */
data class TaggedChangelogEntry(val version: String, val entry: ChangelogEntry)

data class WhatsNewCategorySection(val category: ChangelogCategory, val entries: List<TaggedChangelogEntry>)

data class WhatsNewDecision(
    /** Empty when nothing is to be shown. */
    val sections: List<WhatsNewCategorySection>,
    /** The "This debug build" section; always empty on a stable build. */
    val debugNotes: List<ChangelogDebugNote>,
    /** What to store once the user continues - or at once, when nothing is shown. */
    val ackToWrite: WhatsNewAck,
) {
    val shouldShow: Boolean get() = sections.isNotEmpty() || debugNotes.isNotEmpty()
}

/** Features, then improvements, then fixes - the order the screen lists them in. */
val ChangelogCategoryOrder: List<ChangelogCategory> =
    listOf(ChangelogCategory.FEATURE, ChangelogCategory.IMPROVEMENT, ChangelogCategory.FIX)

/**
 * What the full-screen What's New shows on this launch.
 *
 * - **Fresh install** (no ack, no legacy key): nothing; the ack is set to the current build, so a
 *   first launch never opens on release notes.
 * - **Upgrade from the old `last_seen_version` key** (no ack): only the current release - unless
 *   that key already names this version, in which case nothing.
 * - Otherwise every release of this [family] with `ack.serial < serial <= currentSerial`, entries
 *   filtered to [platform] and merged by category, newest release first, each tagged with its
 *   version.
 * - A debug build ([currentDebugBuild] non-null) adds the debug notes of this family with
 *   `ack.debugBuild < build <= currentDebugBuild`.
 *
 * Continuing acknowledges; the caller writes [WhatsNewDecision.ackToWrite].
 */
fun decideWhatsNew(
    releases: List<ChangelogRelease>,
    family: String,
    platform: ChangelogPlatform,
    currentSerial: Int,
    currentVersion: String,
    currentDebugBuild: Int?,
    ack: WhatsNewAck?,
    legacyLastSeenVersion: String?,
): WhatsNewDecision {
    val debugNow = currentDebugBuild ?: 0
    if (ack == null && legacyLastSeenVersion == null) {
        return WhatsNewDecision(emptyList(), emptyList(), WhatsNewAck(currentSerial, debugNow))
    }
    // The old key only ever stood for "the version before this one": show this release alone (or
    // nothing, if the key already names it), and of the debug lines only this build's own - debug
    // builds share one version name, so a tester arriving from the previous debug build would
    // otherwise be shown nothing at all.
    val legacyDebug = (debugNow - 1).coerceAtLeast(0)
    val effective = ack ?: if (legacyLastSeenVersion == currentVersion) {
        WhatsNewAck(currentSerial, legacyDebug)
    } else {
        WhatsNewAck(currentSerial - 1, legacyDebug)
    }
    val familyReleases = releases.filter { it.family == family }
    val shown = familyReleases
        .filter { it.serial > effective.serial && it.serial <= currentSerial }
        .sortedByDescending { it.serial }
    val sections = mergeByCategory(shown, platform)
    val debugNotes = if (currentDebugBuild == null) {
        emptyList()
    } else {
        familyReleases.flatMap { it.debug }
            .filter { it.build > effective.debugBuild && it.build <= currentDebugBuild }
            .sortedByDescending { it.build }
    }
    return WhatsNewDecision(
        sections = sections,
        debugNotes = debugNotes,
        ackToWrite = WhatsNewAck(
            serial = maxOf(currentSerial, effective.serial),
            debugBuild = maxOf(debugNow, effective.debugBuild),
        ),
    )
}

/** Settings -> What's new: every shipped release of this family, newest first, for [platform]. */
fun changelogHistory(
    releases: List<ChangelogRelease>,
    family: String,
    platform: ChangelogPlatform,
    currentSerial: Int,
): List<Pair<ChangelogRelease, List<WhatsNewCategorySection>>> =
    releases
        .filter { it.family == family && it.serial <= currentSerial }
        .sortedByDescending { it.serial }
        .map { release -> release to mergeByCategory(listOf(release), platform) }
        .filter { (_, sections) -> sections.isNotEmpty() }

/** Whether a release line has notes for [serial] - the release guard's question. */
fun changelogHasRelease(releases: List<ChangelogRelease>, family: String, serial: Int): Boolean =
    releases.any { it.family == family && it.serial == serial && it.entries.isNotEmpty() }

private fun mergeByCategory(
    releasesNewestFirst: List<ChangelogRelease>,
    platform: ChangelogPlatform,
): List<WhatsNewCategorySection> = ChangelogCategoryOrder.mapNotNull { category ->
    val entries = releasesNewestFirst.flatMap { release ->
        release.entries
            .filter { it.category == category && platform in it.platforms }
            .map { TaggedChangelogEntry(release.version, it) }
    }
    entries.takeIf { it.isNotEmpty() }?.let { WhatsNewCategorySection(category, it) }
}
