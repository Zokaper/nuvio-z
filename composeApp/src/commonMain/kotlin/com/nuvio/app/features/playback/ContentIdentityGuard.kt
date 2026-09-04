package com.nuvio.app.features.playback

/**
 * Catches a source that is confidently *not* the thing that was asked for.
 *
 * The reported case, reproduced on a real profile: asking for **Daredevil S2E6 "Regrets Only"**
 * returns an AIOStreams group whose two top-ranked entries are
 * `Daredevil.Born.Again.2026.S02E06.Requiem...` and `Daredevil.Rinascita.S02E06.Requiem...` -
 * a different show whose sixth episode of its second season happens to exist. The correct
 * releases were in the same list, ranked third and fourth, so **rejecting the wrong ones lands
 * the user on a good source rather than on a dead end**. That is what makes this worth doing at
 * all; a guard that only ever produced failures would not be.
 *
 * ⚠ **Auto modes only.** A manual pick is the user reading the release name themselves and
 * choosing anyway, and second-guessing that is a refusal wearing a helper's name.
 *
 * ⚠ **Reject only on positive evidence of different content.** Absent, unparseable or foreign
 * metadata always passes. The failure mode this guard could introduce - silently discarding
 * good sources for a title whose releases simply do not parse - is worse than the failure it
 * fixes, because it would be invisible and would look like "no sources found".
 *
 * ⚠ **The limitation, stated plainly:** the addon is very likely matching on release names too,
 * so this catches the symptom on our side and cannot fix a source that was mislabelled
 * upstream, nor one whose name is correct-looking and wrong. Every rejection is logged so the
 * false-positive rate is measurable before this is trusted.
 *
 * Pure and import-free so the pure suite can run the whole table, including the false-positive
 * set - which is the half that actually needs the coverage.
 */
object ContentIdentityGuard {

    /** Why a candidate was rejected. The text goes to the loading screen and the log. */
    enum class Rejection(val reason: String) {
        WRONG_EPISODE("wrong episode"),
        WRONG_SEASON("wrong season"),
        WRONG_YEAR("wrong year"),
    }

    /**
     * How far a parsed year may sit from the requested one before it counts as disagreement.
     *
     * One year each way. A release is routinely tagged with the year it was *published* rather
     * than the year the season aired, and a December air date crossing into January is the
     * ordinary case rather than the exception.
     */
    const val YEAR_TOLERANCE: Int = 1

    private val SEASON_EPISODE = Regex("""(?:^|[^a-z0-9])s(\d{1,2})[ ._-]?e(\d{1,3})(?:[^0-9]|$)""", RegexOption.IGNORE_CASE)
    private val SEASON_X_EPISODE = Regex("""(?:^|[^a-z0-9])(\d{1,2})x(\d{2,3})(?:[^0-9]|$)""", RegexOption.IGNORE_CASE)
    private val YEAR = Regex("""(?:^|[^0-9])((?:19|20)\d{2})(?:[^0-9]|$)""")

    /** `S02E06` or `2x06`, or null when the name says nothing about it. */
    fun parseSeasonEpisode(releaseName: String): Pair<Int, Int>? {
        SEASON_EPISODE.find(releaseName)?.let { m ->
            return m.groupValues[1].toInt() to m.groupValues[2].toInt()
        }
        SEASON_X_EPISODE.find(releaseName)?.let { m ->
            return m.groupValues[1].toInt() to m.groupValues[2].toInt()
        }
        return null
    }

    /**
     * The first plausible four-digit year, or null.
     *
     * **The first, not the last**, because release names put the title's year early and the
     * encoder's tags late - `2160p`, `x265`, `DDP5.1` - and a scan from the end finds numbers
     * that are not years at all.
     */
    fun parseYear(releaseName: String): Int? =
        YEAR.find(releaseName)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Whether this candidate is confidently the wrong content.
     *
     * Every parameter describing the request is nullable, and a null means "not known" - which
     * always passes. That is the whole conservatism of this function: it can only ever reject
     * on two facts that both exist and disagree.
     */
    fun evaluate(
        releaseName: String?,
        requestedSeason: Int?,
        requestedEpisode: Int?,
        requestedYear: Int? = null,
    ): Rejection? {
        val name = releaseName?.takeIf { it.isNotBlank() } ?: return null

        // Season and episode first: it is the cheapest signal, the most reliably encoded, and
        // the only one a season pack and a single episode disagree about in a useful way.
        if (requestedSeason != null && requestedEpisode != null) {
            parseSeasonEpisode(name)?.let { (season, episode) ->
                if (season != requestedSeason) return Rejection.WRONG_SEASON
                if (episode != requestedEpisode) return Rejection.WRONG_EPISODE
            }
        }

        if (requestedYear != null) {
            parseYear(name)?.let { year ->
                // ⚠ Only for the *episode* case does a year disagreement mean wrong content in
                // the way this guard cares about; for a film the requested year is the film's
                // own and the same tolerance applies. Either way, both numbers must exist.
                if (kotlin.math.abs(year - requestedYear) > YEAR_TOLERANCE) {
                    return Rejection.WRONG_YEAR
                }
            }
        }

        return null
    }
}
