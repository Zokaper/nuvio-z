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
    fun sections(isDesktop: Boolean): List<WhatsNewSection> = listOf(
        WhatsNewSection(
            category = WhatsNewCategory.BugFixes,
            items = listOfNotNull(
                WhatsNewItem(
                    title = "The connection figure is back",
                    description = "0.4.13 hid it whenever the speed had not been measured yet - which " +
                        "also took the connection bars off every option, so a picker that could not " +
                        "measure showed less than one that never tried. It now always shows a figure " +
                        "and says which kind it is: \"Estimated\" before anything has been measured, " +
                        "\"Your connection\" once it has.",
                ),
                WhatsNewItem(
                    title = "The check no longer cancels itself",
                    description = "It was being restarted every time another source arrived, so on a " +
                        "title with many sources it could be interrupted repeatedly and never finish. " +
                        "It now runs to completion alongside the source list, as intended.",
                ),
                WhatsNewItem(
                    title = "Mobile data is measured too",
                    description = "The check used to skip metered connections, which left mobile data - " +
                        "where speed varies most - as the one case still decided by a guess. It now " +
                        "measures there as well, using about 4 MB once per network.",
                ),
            ),
        ),
    )
}
