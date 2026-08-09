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
            category = WhatsNewCategory.NewFeatures,
            items = listOfNotNull(
                WhatsNewItem(
                    title = "A quality picker you can actually read",
                    description = "Choosing a quality now shows a grid of cards rather than a stack of " +
                        "rows: each one leads with its resolution, names the release and provider it " +
                        "would really open, and draws how much of your connection it would take.",
                ),
                WhatsNewItem(
                    title = "It fits the window it is in",
                    description = "On a tablet or a desktop window the picker is a centred panel two or " +
                        "three cards across instead of a phone dialog with everything hidden below the " +
                        "fold.",
                ).takeIf { isDesktop },
            ),
        ),
        WhatsNewSection(
            category = WhatsNewCategory.Improvements,
            items = listOf(
                WhatsNewItem(
                    title = "The numbers stop changing under you",
                    description = "The picker used to appear as soon as the first sources arrived, so a " +
                        "card could say one speed and size and then quietly say another as more addons " +
                        "answered. It now waits until the figures are settled and shows placeholders " +
                        "until then - nothing you read is about to be replaced.",
                ),
                WhatsNewItem(
                    title = "Backing out of the picker goes back",
                    description = "Dismissing the quality picker returns you to the title instead of " +
                        "dropping you into the full source list. \"Choose source manually\" still opens " +
                        "that list whenever you want it.",
                ),
            ),
        ),
    )
}
