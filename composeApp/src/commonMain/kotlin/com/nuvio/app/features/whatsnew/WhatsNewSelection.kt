package com.nuvio.app.features.whatsnew

// No imports, and none may be added: this file runs in the pure suites
// (`scripts/run-pure-suites.sh`). What a user is shown, when, and what counts as seen is decided
// here and only here; the screen, the Settings badge and the gate render the answer.

/**
 * The global changelog: one Nuvio Z history across Desktop, Android and iOS, shipped in the app as
 * `composeResources/files/changelog.json` and byte-identical in both repositories.
 *
 * A changelog entry belongs to a **release event**, not to an app version. An event has a global
 * monotonic [ChangelogEvent.seq] and lists, per platform, the version and release serial it shipped
 * in ([ChangelogEvent.ships]). Desktop and mobile keep their own version names and serials; the event
 * is what ties them together. Nothing here orders by a version string, a date or one family's serial.
 */
enum class ChangelogCategory { FEATURE, IMPROVEMENT, FIX }

enum class ChangelogPlatform { ANDROID, IOS, DESKTOP }

/** Display order for an event's version line and entry tags. */
val ChangelogPlatformOrder: List<ChangelogPlatform> =
    listOf(ChangelogPlatform.DESKTOP, ChangelogPlatform.ANDROID, ChangelogPlatform.IOS)

/**
 * Release family: Android and iOS ship together as `mobile` (one version, one serial - see
 * `Docs/RELEASES.md`); desktop is its own. Families own release serials and debug-build notes.
 */
val ChangelogPlatform.family: String
    get() = if (this == ChangelogPlatform.DESKTOP) "desktop" else "mobile"

/**
 * Something an entry can offer to do right there - "Try Advanced Setup". Optional in the file
 * (`"action": "advanced_setup"`); an unknown key drops the action, never the entry, so an older build
 * shows a newer card as an ordinary line.
 */
enum class ChangelogAction(val key: String) {
    ADVANCED_SETUP("advanced_setup"),
    ;

    companion object {
        fun fromKey(key: String?): ChangelogAction? = entries.firstOrNull { it.key == key }
    }
}

data class ChangelogEntry(
    val category: ChangelogCategory,
    val platforms: Set<ChangelogPlatform>,
    val title: String,
    val body: String? = null,
    val action: ChangelogAction? = null,
)

/**
 * Where one event shipped on one platform. [date] is an ISO `YYYY-MM-DD`, or null while it has not
 * been released there (`"unreleased"` in the file).
 */
data class ChangelogShip(val version: String, val serial: Int, val date: String?)

data class ChangelogEvent(
    val seq: Int,
    val ships: Map<ChangelogPlatform, ChangelogShip>,
    val entries: List<ChangelogEntry>,
    /** Optional one line. Deliberately not a "release name": the date is the identity. */
    val summary: String? = null,
)

/** A line for the "This debug build" section. Per family, from `changelog-debug.json`. */
data class ChangelogDebugNote(val build: Int, val text: String)

/** The running build, as the changelog sees it. [debugBuild] is null on a stable build. */
data class ChangelogViewer(
    val platform: ChangelogPlatform,
    val serial: Int,
    val debugBuild: Int? = null,
)

/**
 * **The eligibility rule.** Whether [this] event may be shown to [viewer] at all - in What's New, in
 * its history and in the Settings badge. A bundled changelog routinely carries events this platform
 * has not shipped yet; this is what keeps them hidden.
 *
 * - The event ships on the viewer's platform: eligible once that platform's release serial has
 *   reached it (`ships[platform].serial <= viewer.serial`). The other platforms' dates are
 *   irrelevant - an event mobile has already released stays hidden on desktop until desktop's own
 *   serial gets there. A **debug** build additionally previews an event its platform has not
 *   released yet (its ship has no date), because a debug build is how the next release is tested.
 * - The event does not ship on the viewer's platform (an iOS-only fix, read on desktop): eligible once
 *   it has been released somewhere (any ship has a date). An undated other-platform event is a plan,
 *   and nobody sees plans - debug builds included.
 */
fun ChangelogEvent.isEligibleFor(viewer: ChangelogViewer): Boolean {
    val own = ships[viewer.platform]
    return if (own != null) {
        own.serial <= viewer.serial || (viewer.debugBuild != null && own.date == null)
    } else {
        ships.values.any { it.date != null }
    }
}

/** Eligible events, newest first. */
fun eligibleEvents(events: List<ChangelogEvent>, viewer: ChangelogViewer): List<ChangelogEvent> =
    events.filter { it.isEligibleFor(viewer) }.sortedByDescending { it.seq }

/**
 * A set of event seqs, kept as a floor plus the seen seqs above it. Not a bare "last seen seq":
 * platforms release one event at different times, so an event can become eligible on this platform
 * *after* a higher-seq event did (mobile ships event 5, an iOS fix 6 is released, desktop reaches 5
 * later). A high-water mark would have marked 5 seen without anyone having seen it.
 */
data class SeenEvents(val floor: Int, val above: Set<Int> = emptySet()) {
    operator fun contains(seq: Int): Boolean = seq <= floor || seq in above

    /** Adds [seqs], then raises the floor over every [known] seq that is now contained. */
    fun plus(seqs: Collection<Int>, known: Collection<Int>): SeenEvents {
        val seen = above + seqs.filter { it > floor }
        var newFloor = floor
        for (seq in known.filter { it > floor }.distinct().sorted()) {
            if (seq in seen) newFloor = seq else break
        }
        return SeenEvents(newFloor, seen.filter { it > newFloor }.toSet())
    }

    companion object {
        val NONE = SeenEvents(0)

        /** The stored form: `"5,7"`. Anything that is not a positive integer is ignored. */
        fun decodeAbove(text: String?): Set<Int> =
            text.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull()?.takeIf { seq -> seq > 0 } }.toSet()

        fun encodeAbove(seqs: Set<Int>): String = seqs.sorted().joinToString(",")
    }
}

/**
 * What this device has done with the changelog. Device-local.
 *
 * - [acknowledged]: events the post-update screen must not show again.
 * - [viewed]: events the user has actually had in front of them - the Settings "New" badge stays
 *   while an eligible event is not in it. The two differ only after a fresh install, which
 *   acknowledges everything silently (no screen over onboarding) and views nothing.
 * - [debugBuild]: the newest debug-build note shown.
 */
data class WhatsNewState(
    val acknowledged: SeenEvents,
    val viewed: SeenEvents,
    val debugBuild: Int,
)

/**
 * Everything storage holds, read raw. The two `legacy` fields are what earlier builds wrote and are
 * only ever read: [legacySerial] (`ack_serial`, the per-family serial acknowledgement - written only
 * by debug builds, because no stable build carried that system) and [legacyLastSeenVersion]
 * (`last_seen_version`, every stable build before this one, including desktop `0.1.23-alpha-z6` and
 * the Android pre-alpha). Desktop's legacy value is not even desktop's version - it stored a stale
 * mobile name - so only its presence is ever trusted.
 */
data class StoredWhatsNew(
    val acknowledged: SeenEvents? = null,
    val viewed: SeenEvents? = null,
    val debugBuild: Int? = null,
    val legacySerial: Int? = null,
    val legacyLastSeenVersion: String? = null,
)

enum class WhatsNewOrigin { CURRENT, MIGRATED_SERIAL, MIGRATED_LEGACY_VERSION, FRESH_INSTALL }

/**
 * The floor below the first event on [platform] whose serial is at least [minSerial]: every event
 * before it counts as seen. When no event ships on [platform] at or above [minSerial], everything
 * in the file counts as seen - a migration never dumps history on anyone.
 */
internal fun migrationFloor(events: List<ChangelogEvent>, platform: ChangelogPlatform, minSerial: Int): Int {
    val first = events.filter { event -> event.ships[platform]?.let { it.serial >= minSerial } == true }
        .minOfOrNull { it.seq }
    return if (first != null) first - 1 else events.maxOfOrNull { it.seq } ?: 0
}

/**
 * **The seen-state and migration rule.** Turns stored state into a [WhatsNewState]:
 *
 * 1. `ack_seq` present: used as it is. A missing viewed set means "viewed what was acknowledged".
 * 2. Else `ack_serial` (debug builds only): that serial was the *upcoming* release whose draft notes
 *    the tester had seen, so events on this platform **below** it are seen and events at or above it
 *    are not - `migrationFloor(minSerial = ack_serial)`. Negative values are treated as absent.
 * 3. Else a non-blank `last_seen_version`: a stable install from before global events existed on
 *    this platform. It has seen none of this platform's events, and nothing older matters:
 *    `migrationFloor(minSerial = any)` - everything before the platform's first event is seen. This
 *    is the same answer whether the user arrives from desktop `z6` or several releases later.
 * 4. Else a fresh install: every eligible event is acknowledged (no screen over onboarding) and none
 *    is viewed (the Settings badge invites a look). Debug notes up to this build count as shown.
 */
fun resolveWhatsNewState(
    events: List<ChangelogEvent>,
    viewer: ChangelogViewer,
    stored: StoredWhatsNew,
): Pair<WhatsNewState, WhatsNewOrigin> {
    val debugNow = viewer.debugBuild ?: 0
    stored.acknowledged?.let { ack ->
        return WhatsNewState(ack, stored.viewed ?: ack, stored.debugBuild ?: 0) to WhatsNewOrigin.CURRENT
    }
    val legacySerial = stored.legacySerial?.takeIf { it >= 0 }
    if (legacySerial != null) {
        val floor = SeenEvents(migrationFloor(events, viewer.platform, legacySerial))
        return WhatsNewState(floor, floor, stored.debugBuild ?: 0) to WhatsNewOrigin.MIGRATED_SERIAL
    }
    if (!stored.legacyLastSeenVersion.isNullOrBlank()) {
        val floor = SeenEvents(migrationFloor(events, viewer.platform, Int.MIN_VALUE))
        // Debug builds share one version name, so a tester arriving from the previous debug build
        // is shown this build's own line rather than nothing.
        val debug = (debugNow - 1).coerceAtLeast(0)
        return WhatsNewState(floor, floor, debug) to WhatsNewOrigin.MIGRATED_LEGACY_VERSION
    }
    val known = events.map { it.seq }
    val acknowledged = SeenEvents.NONE.plus(eligibleEvents(events, viewer).map { it.seq }, known)
    return WhatsNewState(acknowledged, SeenEvents.NONE, debugNow) to WhatsNewOrigin.FRESH_INSTALL
}

data class WhatsNewDecision(
    /** Events for the post-update screen, newest first. Empty when nothing new is eligible. */
    val events: List<ChangelogEvent>,
    /** The "This debug build" lines, newest first; always empty on a stable build. */
    val debugNotes: List<ChangelogDebugNote>,
    /** Save at once: the migrated or fresh-install state, so a migration happens exactly once. */
    val state: WhatsNewState,
    /** Save when the screen is dismissed (Done, or an action card). */
    val stateAfterContinue: WhatsNewState,
    /** Whether Settings -> What's New shows its "New" badge right now. */
    val badge: Boolean,
    val origin: WhatsNewOrigin,
) {
    val shouldShow: Boolean get() = events.isNotEmpty() || debugNotes.isNotEmpty()
}

/**
 * What this launch does with What's New. The post-update screen shows every eligible event not yet
 * acknowledged, newest first, unfiltered by family or platform - entries for other platforms are part
 * of the changelog and the screen only sets them apart. Continuing acknowledges and views every
 * eligible event. A debug build adds its family's debug notes newer than the last one shown.
 */
fun decideWhatsNew(
    events: List<ChangelogEvent>,
    debugNotes: List<ChangelogDebugNote>,
    viewer: ChangelogViewer,
    stored: StoredWhatsNew,
): WhatsNewDecision {
    val (state, origin) = resolveWhatsNewState(events, viewer, stored)
    val eligible = eligibleEvents(events, viewer)
    val unseen = eligible.filter { it.seq !in state.acknowledged }
    val notes = viewer.debugBuild?.let { current ->
        debugNotes.filter { it.build > state.debugBuild && it.build <= current }.sortedByDescending { it.build }
    }.orEmpty()
    val known = events.map { it.seq }
    val all = eligible.map { it.seq }
    val after = WhatsNewState(
        acknowledged = state.acknowledged.plus(all, known),
        viewed = state.viewed.plus(all, known),
        debugBuild = maxOf(state.debugBuild, viewer.debugBuild ?: 0),
    )
    return WhatsNewDecision(
        events = unseen,
        debugNotes = notes,
        state = state,
        stateAfterContinue = after,
        badge = eligible.any { it.seq !in state.viewed },
        origin = origin,
    )
}

/**
 * Settings -> What's New was opened: everything eligible is now viewed and acknowledged, so the badge
 * clears and the same events never come back as a post-update screen. Debug notes are untouched -
 * that screen does not show them.
 */
fun markWhatsNewViewed(state: WhatsNewState, events: List<ChangelogEvent>, viewer: ChangelogViewer): WhatsNewState {
    val known = events.map { it.seq }
    val all = eligibleEvents(events, viewer).map { it.seq }
    return state.copy(acknowledged = state.acknowledged.plus(all, known), viewed = state.viewed.plus(all, known))
}

/** Whether the badge shows for [state]. */
fun whatsNewBadge(state: WhatsNewState, events: List<ChangelogEvent>, viewer: ChangelogViewer): Boolean =
    eligibleEvents(events, viewer).any { it.seq !in state.viewed }

/** Features, then improvements, then fixes - the order the screen lists them in. */
val ChangelogCategoryOrder: List<ChangelogCategory> =
    listOf(ChangelogCategory.FEATURE, ChangelogCategory.IMPROVEMENT, ChangelogCategory.FIX)

/** One event, split for one platform. */
data class ChangelogEventView(
    /** Entries that include the viewer's platform, by category in [ChangelogCategoryOrder]. */
    val own: List<Pair<ChangelogCategory, List<ChangelogEntry>>>,
    /** Entries that do not: "Also in this update, on other devices". Category order, never hidden. */
    val otherDevices: List<ChangelogEntry>,
    /** The event's versions, in [ChangelogPlatformOrder]; only the platforms it actually shipped on. */
    val versions: List<Pair<ChangelogPlatform, ChangelogShip>>,
    /** The date to show: the viewer's own release date, else the first release elsewhere; null if none. */
    val date: String?,
)

fun ChangelogEvent.viewFor(platform: ChangelogPlatform): ChangelogEventView {
    val own = ChangelogCategoryOrder.mapNotNull { category ->
        entries.filter { it.category == category && platform in it.platforms }
            .takeIf { it.isNotEmpty() }?.let { category to it }
    }
    val others = ChangelogCategoryOrder.flatMap { category ->
        entries.filter { it.category == category && platform !in it.platforms }
    }
    val versions = ChangelogPlatformOrder.mapNotNull { p -> ships[p]?.let { p to it } }
    val date = ships[platform]?.date ?: ships.values.mapNotNull { it.date }.minOrNull()
    return ChangelogEventView(own, others, versions, date)
}

/** The event a release ships: `ships[platform].serial == serial`. The release guard's question. */
fun changelogEventFor(events: List<ChangelogEvent>, platform: ChangelogPlatform, serial: Int): ChangelogEvent? =
    events.firstOrNull { it.ships[platform]?.serial == serial }

private fun isIsoDate(text: String): Boolean =
    text.length == 10 && text[4] == '-' && text[7] == '-' &&
        text.filterIndexed { i, _ -> i != 4 && i != 7 }.all { it in '0'..'9' }

/**
 * Structural problems in a parsed changelog; empty when it is sound. The same rules
 * `scripts/check-changelog.py validate` applies to the raw file, run against the shipped file by
 * `ChangelogFileTest` / `DesktopChangelogTest` on every push.
 *
 * - seqs are positive and unique, and the file lists events newest (highest seq) first;
 * - every event ships somewhere and has entries; every entry names a platform the event ships on;
 * - per platform, serials and versions strictly follow seq, and once a ship is unreleased no later
 *   event on that platform is released;
 * - Android and iOS, when both present, ship one version under one serial (one mobile family);
 * - dates are `YYYY-MM-DD`.
 */
fun validateChangelog(events: List<ChangelogEvent>): List<String> {
    val problems = mutableListOf<String>()
    val seqs = events.map { it.seq }
    if (seqs.any { it <= 0 }) problems += "seq must be a positive integer"
    seqs.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { problems += "duplicate seq $it" }
    if (seqs != seqs.sortedDescending()) problems += "events must be listed newest (highest seq) first"
    events.forEach { event ->
        if (event.ships.isEmpty()) problems += "seq ${event.seq} ships nowhere"
        if (event.entries.isEmpty()) problems += "seq ${event.seq} has no entries"
        event.entries.forEach { entry ->
            if (entry.title.isBlank()) problems += "seq ${event.seq} has an entry without a title"
            if (entry.platforms.isEmpty()) problems += "seq ${event.seq} '${entry.title}' names no platform"
            val stray = entry.platforms - event.ships.keys
            if (stray.isNotEmpty()) problems += "seq ${event.seq} '${entry.title}' names $stray, which it does not ship on"
        }
        event.ships.forEach { (platform, ship) ->
            if (ship.version.isBlank()) problems += "seq ${event.seq} $platform has no version"
            if (ship.date != null && !isIsoDate(ship.date)) problems += "seq ${event.seq} $platform date '${ship.date}' is not YYYY-MM-DD"
        }
        val android = event.ships[ChangelogPlatform.ANDROID]
        val ios = event.ships[ChangelogPlatform.IOS]
        if (android != null && ios != null && (android.serial != ios.serial || android.version != ios.version)) {
            problems += "seq ${event.seq} ships Android and iOS under different versions or serials"
        }
    }
    ChangelogPlatform.entries.forEach { platform ->
        val line = events.filter { platform in it.ships }.sortedBy { it.seq }.map { it.seq to it.ships.getValue(platform) }
        line.zipWithNext().forEach { (a, b) ->
            if (b.second.serial <= a.second.serial) problems += "$platform serial does not increase from seq ${a.first} to ${b.first}"
            if (b.second.version == a.second.version) problems += "$platform version ${b.second.version} repeats at seq ${b.first}"
            if (a.second.date == null && b.second.date != null) problems += "$platform seq ${b.first} is released after unreleased seq ${a.first}"
        }
    }
    return problems
}
