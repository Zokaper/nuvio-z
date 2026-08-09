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
                    title = "A middle quality, where there is one",
                    description = "Titles with a wide range of releases now offer High, Mid and Low " +
                        "rather than only two ends. Titles without a real middle still show two, or " +
                        "one - the rows come from what exists, not from a fixed list.",
                ),
                WhatsNewItem(
                    title = "A quality sheet that says what it will play",
                    description = "Each row now shows its resolution as a badge and names the release " +
                        "and provider it would actually open - not the first source in the list, which " +
                        "is often not the one that gets used. Rows that ask for more than your " +
                        "connection is likely to carry say so, and stay pickable anyway.",
                ),
            ),
        ),
        WhatsNewSection(
            category = WhatsNewCategory.Improvements,
            items = listOf(
                WhatsNewItem(
                    title = "Known-cached sources are preferred",
                    description = "Where two releases are otherwise equal, the one the provider says " +
                        "it already has ready is played first, rather than one whose state is only " +
                        "hoped for.",
                ),
                WhatsNewItem(
                    title = "Instant is unavailable for now",
                    description = "Instant chooses a quality from your measured connection but has no " +
                        "ceiling to hold it to, so it reached for releases the connection could not " +
                        "carry. It is greyed out until that is fixed. If you were using it, playback " +
                        "behaves as Streamlined - the source is still chosen for you - and your choice " +
                        "is remembered for when it returns.",
                ),
            ),
        ),
        WhatsNewSection(
            category = WhatsNewCategory.BugFixes,
            items = listOf(
                WhatsNewItem(
                    title = "Streamlined no longer gives up on the first bad source",
                    description = "\"Stream not cached\" and similar provider errors used to end the " +
                        "attempt outright. Streamlined now moves on to the next source, says which one " +
                        "failed and why, and shows you the full source list only once it has run out.",
                ),
                WhatsNewItem(
                    title = "Downloads can no longer retry forever",
                    description = "A download that trickled a little and then dropped kept resetting " +
                        "its own retry budget, so it counted down and retried and never finished or " +
                        "failed - pausing did not help. It now restarts once from the beginning on a " +
                        "fresh link, and if that does not work it stops and says so.",
                ),
                WhatsNewItem(
                    title = "Retrying downloads say which attempt they are on",
                    description = "A countdown with no end in sight is what made a stalled download " +
                        "look like a hang. The row now shows the attempt number and says when it is " +
                        "starting the file over.",
                ),
            ),
        ),
    )
}
