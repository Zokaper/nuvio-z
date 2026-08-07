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
                        title = "Next episode in the player",
                        description = "Jump to the next aired episode from the player controls. Episode " +
                            "switching now keeps Instant and Streamlined source selection active.",
                    ),
                    WhatsNewItem(
                        title = "Open details from Continue Watching",
                        description = "Continue Watching cards now have a direct details button, without " +
                            "changing the normal tap-to-resume action.",
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
                            title = "Playback mode is easier to reach",
                            description = "Playback mode now appears at the top level of Settings. Advanced " +
                                "rows are visibly labelled, and the first-run selector uses the available " +
                                "desktop width instead of a narrow mobile layout.",
                        ),
                    )
                    add(
                        WhatsNewItem(
                            title = "Cleaner playback hand-off",
                            description = "Instant and Streamlined keep the source list covered while they " +
                                "choose and resolve a source. Backing out of the player now returns to the " +
                                "title details page.",
                        ),
                    )
                    if (isDesktop) {
                        add(
                            WhatsNewItem(
                                title = "Desktop network awareness",
                                description = "Windows now detects Wi-Fi, Ethernet and metered connections " +
                                    "for Instant mode instead of treating every desktop network as unknown.",
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
                        title = "Streamlined can select debrid sources again",
                        description = "Cached debrid results that provide an infohash are recognized as " +
                            "safe automatic sources. Uncached results still require your confirmation.",
                    ),
                    WhatsNewItem(
                        title = "Selected downloads stay selected",
                        description = "Choosing Download from a source's action menu now queues that exact " +
                            "source instead of opening the automatic preset picker.",
                    ),
                ),
            ),
        )
    }
}
