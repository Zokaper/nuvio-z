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
    fun sections(isDesktop: Boolean): List<WhatsNewSection> = buildList {
        add(
            WhatsNewSection(
                category = WhatsNewCategory.NewFeatures,
                items = listOf(
                    WhatsNewItem(
                        title = "What's New after each update",
                        description = "This screen. It opens once when you first launch a new version, " +
                            "and it is always available from Settings under About - along with the notes " +
                            "for the last few releases.",
                    ),
                    WhatsNewItem(
                        title = "Show advanced settings",
                        description = "A switch in Settings under Advanced hides the tuning options - " +
                            "playback engine, buffering, auto-pick and stream selection - so the screens " +
                            "are easier to scan. Search still finds them either way.",
                    ),
                ),
            ),
        )
        add(
            WhatsNewSection(
                category = WhatsNewCategory.Improvements,
                items = buildList {
                    add(
                        WhatsNewItem(
                            title = "Instant and Streamlined feel instant",
                            description = "Neither mode shows you the source list any more. You get a " +
                                "progress indicator that says what it is doing - looking for sources, " +
                                "choosing one, preparing the link - and it moves on quietly if a source " +
                                "turns out to be dead.",
                        ),
                    )
                    add(
                        WhatsNewItem(
                            title = "The playback modes explain themselves",
                            description = "Each mode is now a card describing what it does for streaming " +
                                "and for downloading, so the choice is not a guess.",
                        ),
                    )
                    if (isDesktop) {
                        add(
                            WhatsNewItem(
                                title = "Faster desktop startup",
                                description = "Player setup runs asynchronously, and the packaged native " +
                                    "player and P2P files no longer need extracting from the app JAR " +
                                    "before the window opens.",
                            ),
                        )
                    }
                },
            ),
        )
        add(
            WhatsNewSection(
                category = WhatsNewCategory.BugFixes,
                items = listOf(
                    WhatsNewItem(
                        title = "Settings sync no longer deletes settings",
                        description = "Syncing could wipe any setting added since your account last " +
                            "uploaded its settings - including your playback mode, MDBList, TMDB, " +
                            "stream badge, Trakt comment and debrid preferences. Fixed everywhere it " +
                            "could happen.",
                    ),
                ),
            ),
        )
    }
}
