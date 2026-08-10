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
            category = WhatsNewCategory.Improvements,
            items = listOfNotNull(
                WhatsNewItem(
                    title = "Your connection is measured, not guessed",
                    description = "The quality picker used to assume every Wi-Fi network was the same " +
                        "speed and label that assumption \"your connection\". It now measures what your " +
                        "connection actually carries - briefly, before the first play, and continuously " +
                        "while something is playing - so the speed beside each option is a real one.",
                ),
                WhatsNewItem(
                    title = "It measures the source, not just the line",
                    description = "Speed through a debrid service is the service's, not your " +
                        "broadband's. The check runs against the host that would actually serve your " +
                        "stream and is remembered per provider, so a fast connection behind a slow host " +
                        "no longer reads as fast.",
                ),
                WhatsNewItem(
                    title = "What it learns survives a restart",
                    description = "Measurements are kept per network for a week, so opening the app " +
                        "does not throw away everything it knew about your connection. Nothing is " +
                        "measured on mobile data.",
                ),
                WhatsNewItem(
                    title = "\"Best available\" says what you would get",
                    description = "It named the release type and the host - neither of which is what " +
                        "you are choosing between. It now leads with resolution, HDR and file size, and " +
                        "quotes the speed that file really needs.",
                ),
                WhatsNewItem(
                    title = "No number until there is one",
                    description = "While the check is running the picker says so, and if nothing can be " +
                        "measured it shows nothing rather than a figure it made up.",
                ),
            ),
        ),
    )
}
