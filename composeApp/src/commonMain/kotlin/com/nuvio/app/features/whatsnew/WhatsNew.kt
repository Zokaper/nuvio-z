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
            category = WhatsNewCategory.NewFeatures,
            items = listOf(
                WhatsNewItem(
                    title = "Quality choices built from what's actually available",
                    description = "The quality sheet no longer offers a fixed list of presets. It now " +
                        "shows what this title actually has - 4K High and Low, 1080p High and Low, 720p - " +
                        "and a quality nobody released simply doesn't appear.",
                ),
                WhatsNewItem(
                    title = "Every quality says what it needs",
                    description = "Each option shows the connection speed the real file requires and how " +
                        "big it is, so the choice is yours to make rather than a guess.",
                ),
            ),
        ),
        WhatsNewSection(
            category = WhatsNewCategory.Improvements,
            items = listOf(
                WhatsNewItem(
                    title = "Instant reaches the quality your connection can carry",
                    description = "Instant now compares what each source really costs against your " +
                        "connection instead of a fixed assumption, and the estimate improves as you " +
                        "watch. Previously it rarely offered more than 1080p.",
                ),
                WhatsNewItem(
                    title = "Next episode uses the same choices",
                    description = "Auto-playing the next episode now picks from the same options the " +
                        "quality sheet would show, using that episode's own runtime.",
                ),
            ),
        ),
        WhatsNewSection(
            category = WhatsNewCategory.BugFixes,
            items = listOf(
                WhatsNewItem(
                    title = "The quality sheet can no longer hang",
                    description = "If a search finished with sources that none of them could be played " +
                        "from, the sheet used to spin forever with every row disabled. It now says so " +
                        "and offers the source list.",
                ),
                WhatsNewItem(
                    title = "Mislabelled sources no longer fake a 4K option",
                    description = "Some addons put \"UHD\" in every stream name. A source too small to " +
                        "be what it claims is now listed as the quality it really is.",
                ),
            ),
        ),
    )
}
