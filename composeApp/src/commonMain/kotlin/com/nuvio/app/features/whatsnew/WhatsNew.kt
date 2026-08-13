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
                    title = "A setup wizard, with a live preview",
                    description = "First launch now walks you through how Nuvio picks sources and " +
                        "how it looks. A working preview of the app fills the screen behind each " +
                        "question and changes as you answer, so you are choosing from what you can " +
                        "see rather than from a list of names. Run it again any time from Settings.",
                ),
            ),
        ),
        WhatsNewSection(
            category = WhatsNewCategory.BugFixes,
            items = listOfNotNull(
                WhatsNewItem(
                    title = "A failing source no longer loops",
                    description = "A debrid link that died shortly after starting was retried " +
                        "endlessly - the player reopened on the loading screen, played a second, " +
                        "and went round again with no way out. It now retries once, then names " +
                        "the source and moves to the next one.",
                ),
                WhatsNewItem(
                    title = "Coming back from the player works",
                    description = "In Streamlined and Instant, backing out of the player with the " +
                        "system Back gesture could land on a blank screen you could not read and " +
                        "could not leave. It now returns you to the source list or to the show.",
                ),
                WhatsNewItem(
                    title = "No more spinner that never ends",
                    description = "Several ways of ending up with no source - declining the torrent " +
                        "prompt, a chain of dead sources running out, an add-on that never answers - " +
                        "left \"Starting playback\" on screen for a playback that had already been " +
                        "called off. Every one of them now hands you back the source list and says why.",
                ),
                WhatsNewItem(
                    title = "A source that dies mid-episode retries properly",
                    description = "When a source failed after playback had already started, the next " +
                        "one was lined up and then immediately thrown away, so nothing happened. The " +
                        "retry now actually runs.",
                ),
                WhatsNewItem(
                    title = "Best available stops picking season packs",
                    description = "The top card in the quality picker was ranked by different rules " +
                        "from every other card, so an entire season advertised as one file would head " +
                        "it - and quietly show no size or speed figure at all. It now follows the " +
                        "same rules as the rest, and prefers sources known to be ready.",
                ),
                WhatsNewItem(
                    title = "Downloads that go quiet give up",
                    description = "On Android, a source that stopped sending without disconnecting " +
                        "held its place in the queue and sat on its last percentage. It is now " +
                        "detected and retried, as it already was on desktop.",
                ),
            ),
        ),
        WhatsNewSection(
            category = WhatsNewCategory.Improvements,
            items = listOfNotNull(
                WhatsNewItem(
                    title = "Your codec and HDR preferences apply to playback",
                    description = "Preferred codec and preferred dynamic range are now settings for " +
                        "watching, not only for downloading, and your audio language preference is " +
                        "taken into account when a source is picked for you. Find them under " +
                        "Settings \u2192 Playback. Leave them on Automatic and nothing changes.",
                ),
            ),
        ),
    )
}
