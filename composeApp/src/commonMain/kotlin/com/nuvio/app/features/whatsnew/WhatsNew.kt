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
                    title = "One card per resolution",
                    description = "The quality picker no longer repeats a resolution once for every " +
                        "option it holds. Each resolution is a single card that names itself once, with " +
                        "its higher and lower options stacked inside it as the things you tap - so you " +
                        "choose the resolution first and the trade-off second.",
                ),
                WhatsNewItem(
                    title = "Every option still says what it would open",
                    description = "Each option keeps the release and provider it would really play and " +
                        "the share of your connection it would take, because those differ between the " +
                        "options inside one resolution.",
                ),
                WhatsNewItem(
                    title = "\"Best available\" reads as one thing",
                    description = "It used to carry a star, then say \"Best available\" underneath it, " +
                        "then repeat the picker's own description a third time. It now says it once.",
                ),
                WhatsNewItem(
                    title = "Still two or three across on a wide window",
                    description = "The cards are taller now that they hold their options, so the wide " +
                        "layout was widened to match, so a desktop window keeps three columns instead " +
                        "of quietly dropping to two.",
                ).takeIf { isDesktop },
            ),
        ),
    )
}
