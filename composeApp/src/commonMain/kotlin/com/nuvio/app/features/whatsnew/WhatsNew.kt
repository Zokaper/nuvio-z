package com.nuvio.app.features.whatsnew

enum class WhatsNewCategory {
    NewFeatures,
    Improvements,
    BugFixes,
}

data class WhatsNewItem(
    val title: String,
    val description: String,
)

data class WhatsNewSection(
    val category: WhatsNewCategory,
    val items: List<WhatsNewItem>,
)

internal expect object WhatsNewStorage {
    val isDesktop: Boolean

    fun loadLastSeenVersion(): String?
    fun saveLastSeenVersion(versionName: String)
}

internal fun shouldShowWhatsNew(
    lastSeenVersion: String?,
    currentVersion: String,
    sections: List<WhatsNewSection>,
): Boolean =
    currentVersion.isNotBlank() &&
        sections.any { it.items.isNotEmpty() } &&
        lastSeenVersion != currentVersion

/**
 * The current release's notes, written by hand.
 *
 * Curated rather than fetched, for three reasons: it has to work offline on the very first
 * launch after an update, it has to work on builds where the in-app updater is disabled, and
 * the generated GitHub notes read as a commit log rather than a highlight reel. The older
 * releases below this one *are* fetched - see `fetchRecentReleaseNotes` - because there the
 * commit log is exactly what someone catching up wants.
 *
 * ⚠ **This needs an entry per release, committed before the version bump.** `AGENTS.md`'s
 * bump-last rule is enforced, so a docs commit after the bump fails the release.
 */
object CurrentReleaseNotes {
    fun sections(@Suppress("UNUSED_PARAMETER") isDesktop: Boolean): List<WhatsNewSection> = listOf(
        WhatsNewSection(
            category = WhatsNewCategory.BugFixes,
            items = listOf(
                WhatsNewItem(
                    title = "Streamlined waits for source results",
                    description = "Choosing a quality tier now waits for the stream request to produce " +
                        "results. A brief empty state at the start of loading can no longer send you " +
                        "back to the source list with a no-match message.",
                ),
            ),
        ),
    )
}
