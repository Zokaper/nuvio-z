package com.nuvio.app.features.downloads

/**
 * How downloads pick their source - its own profile-level mode, independent of Playback Mode
 * (Phase 9). Import-free, so the model, the size table and the entry routing run in the pure
 * suites.
 *
 * - **Automatic** (recommended): Nuvio picks under the user's rules and asks only when a rule
 *   cannot be met.
 * - **Assisted**: the same rules, but the user picks the resolution per download from one best
 *   match per resolution.
 * - **Manual** (advanced): the user picks every source, from a download source list that never
 *   opens the player.
 */
enum class DownloadMode { AUTOMATIC, ASSISTED, MANUAL }

enum class DownloadResolutionPreference(val height: Int) {
    P720(720),
    P1080(1080),
    P2160(2160),

    /** The highest resolution that fits the size level. */
    BEST_AVAILABLE(Int.MAX_VALUE),
}

/** Named size levels, each relative to the resolution being downloaded. */
enum class DownloadSizeLevel { SMALL, MEDIUM, LARGE, HUGE, ANY }

/** Among the files that fit, after range and language have ranked them. */
enum class DownloadPickRule { BEST_THAT_FITS, BALANCED, SMALLEST_THAT_FITS }

enum class DownloadRange { SAME_AS_PLAYBACK, PREFER_HDR, AVOID_HDR, ANY }

/** What to do when the preferred resolution has no source at all. */
enum class DownloadResolutionFallback { LOWER, HIGHER, ASK }

/** Playback Mode, restated here so this file stays import-free. */
enum class PlaybackModeForDownloads { CLASSIC, STREAMLINED, INSTANT }

data class DownloadPolicy(
    /**
     * Null means "never answered", and **only** that: the effective mode is then derived from
     * Playback Mode. It never decides whether onboarding is due - the wizard revision does - so a
     * user who skips the question is not asked again forever.
     */
    val mode: DownloadMode? = null,
    val preferredResolution: DownloadResolutionPreference = DownloadResolutionPreference.P1080,
    val sizeLevel: DownloadSizeLevel = DownloadSizeLevel.MEDIUM,
    val pickRule: DownloadPickRule = DownloadPickRule.BEST_THAT_FITS,
    val range: DownloadRange = DownloadRange.SAME_AS_PLAYBACK,
    val resolutionFallback: DownloadResolutionFallback = DownloadResolutionFallback.ASK,
) {
    fun effectiveMode(playbackMode: PlaybackModeForDownloads): DownloadMode =
        mode ?: derivedMode(playbackMode)

    companion object {
        /** Closest to how downloads behaved before they had a mode of their own. */
        fun derivedMode(playbackMode: PlaybackModeForDownloads): DownloadMode = when (playbackMode) {
            PlaybackModeForDownloads.CLASSIC -> DownloadMode.MANUAL
            PlaybackModeForDownloads.STREAMLINED -> DownloadMode.ASSISTED
            PlaybackModeForDownloads.INSTANT -> DownloadMode.AUTOMATIC
        }
    }
}

/**
 * Gigabytes per hour of video for each size level, **relative to the resolution** - so "Medium"
 * is a sensible medium file at whatever resolution is downloaded, and 4K is never "too big for
 * Medium" by construction.
 *
 * Calibrated 2026-09-26 from 3,521 distinct cached files (5 films, ~10 shows of 21-65 min
 * episodes; debug `size_sample` lines, `sample=spread`), by meaning rather than round numbers:
 * - Small: the compressed end of the candidates (about the bottom quarter);
 * - Standard (`MEDIUM`): an ordinary streaming file - a little above what a streaming service
 *   serves (1080p WEB-DL median 2.5 GB/h);
 * - Large: a high-quality encode (about the upper quartile of BluRay encodes);
 * - Huge: the top of normal encodes, **stopping below REMUX** at every resolution (1080p REMUX
 *   starts ~12, 4K ~19) - Any is the level with no limit.
 * 1440p has almost no sources and 4320p none; those rows are interpolated, not measured.
 */
object DownloadSizeLevels {
    private val table: Map<Int, DoubleArray> = mapOf(
        //           SMALL STANDARD LARGE  HUGE   (MEDIUM is shown as "Standard")
        480 to doubleArrayOf(0.4, 1.0, 1.5, 2.5),
        720 to doubleArrayOf(0.6, 2.0, 3.0, 4.0),
        1080 to doubleArrayOf(1.2, 3.0, 6.0, 10.0),
        1440 to doubleArrayOf(2.5, 5.0, 9.0, 14.0),
        2160 to doubleArrayOf(4.0, 8.0, 14.0, 18.0),
        4320 to doubleArrayOf(10.0, 20.0, 40.0, 80.0),
    )

    /** Null for [DownloadSizeLevel.ANY]: no limit. */
    fun gigabytesPerHour(level: DownloadSizeLevel, resolutionHeight: Int): Double? {
        val index = when (level) {
            DownloadSizeLevel.SMALL -> 0
            DownloadSizeLevel.MEDIUM -> 1
            DownloadSizeLevel.LARGE -> 2
            DownloadSizeLevel.HUGE -> 3
            DownloadSizeLevel.ANY -> return null
        }
        val row = table.entries
            .minByOrNull { (height, _) -> kotlin.math.abs(height - resolutionHeight) }
            ?.value
            ?: return null
        return row[index]
    }

    /**
     * The byte limit for one file. Runtime unknown: 45 minutes for an episode, 120 for a film -
     * the assumption presets always made, so an unknown runtime neither admits nor rejects
     * anything it did not before.
     */
    fun limitBytes(level: DownloadSizeLevel, resolutionHeight: Int, runtimeMinutes: Int?, isEpisode: Boolean): Long? {
        val perHour = gigabytesPerHour(level, resolutionHeight) ?: return null
        val minutes = runtimeMinutes?.takeIf { it > 0 } ?: if (isEpisode) 45 else 120
        return (perHour * GIGABYTE * minutes / 60.0).toLong()
    }

    /**
     * Assisted "Choose now": roughly what [runtimesMinutes] of video comes to at [resolutionHeight]
     * under [level], as a low-high range in bytes, **before** any source is found. The pick takes
     * the best file inside the level, so the range runs from the level below to the level itself
     * (half of Small below Small).
     *
     * Null - "Size estimate unavailable" - when the level has no limit (Any), or when not one
     * runtime is known: an estimate made of assumed runtimes alone would be fake precision.
     * Unknown runtimes among known ones count as the known average. Provisional with the table.
     */
    fun estimateBytes(level: DownloadSizeLevel, resolutionHeight: Int, runtimesMinutes: List<Int?>): LongRange? {
        val high = gigabytesPerHour(level, resolutionHeight) ?: return null
        val low = when (level) {
            DownloadSizeLevel.SMALL -> high / 2.0
            DownloadSizeLevel.MEDIUM -> gigabytesPerHour(DownloadSizeLevel.SMALL, resolutionHeight)
            DownloadSizeLevel.LARGE -> gigabytesPerHour(DownloadSizeLevel.MEDIUM, resolutionHeight)
            DownloadSizeLevel.HUGE -> gigabytesPerHour(DownloadSizeLevel.LARGE, resolutionHeight)
            DownloadSizeLevel.ANY -> null
        } ?: return null
        val known = runtimesMinutes.filterNotNull().filter { it > 0 }
        if (known.isEmpty()) return null
        val minutes = known.sum() + known.average() * (runtimesMinutes.size - known.size)
        val hours = minutes / 60.0
        return (low * hours * GIGABYTE).toLong()..(high * hours * GIGABYTE).toLong()
    }

    const val GIGABYTE: Double = 1_000_000_000.0
}

/** Where a download request goes first, by mode and by what was asked for. */
enum class DownloadEntryRoute {
    /** Start at once; a toast offers Change. */
    START_NOW,

    /** Whole show: choose seasons, then start. */
    SEASON_CHOOSER_THEN_START,

    /** One row per available resolution, pre-selecting the preferred one. */
    ASSISTED_SHEET,

    /** Whole show in Assisted: seasons first, then the resolution rows with season totals. */
    SEASON_CHOOSER_THEN_ASSISTED,

    /** The download source list for one film or episode; a tap enqueues, never plays. */
    MANUAL_SOURCE_LIST,

    /** One screen listing every episode with a pick button, plus "Let Nuvio pick the rest". */
    MANUAL_CHOOSE_SOURCES,

    /** Whole show in Manual: seasons first, then Choose sources. */
    SEASON_CHOOSER_THEN_MANUAL,
}

enum class DownloadRequestScope {
    /** A film or one episode. */
    SINGLE,

    /** One season (all or unwatched), chosen on the season itself. */
    SEASON,

    /** The whole show, from the title's Download button: the seasons are still to choose. */
    WHOLE_SHOW,
}

object DownloadEntryRouter {
    fun route(mode: DownloadMode, scope: DownloadRequestScope): DownloadEntryRoute = when (mode) {
        DownloadMode.AUTOMATIC -> when (scope) {
            DownloadRequestScope.SINGLE, DownloadRequestScope.SEASON -> DownloadEntryRoute.START_NOW
            DownloadRequestScope.WHOLE_SHOW -> DownloadEntryRoute.SEASON_CHOOSER_THEN_START
        }
        DownloadMode.ASSISTED -> when (scope) {
            DownloadRequestScope.SINGLE, DownloadRequestScope.SEASON -> DownloadEntryRoute.ASSISTED_SHEET
            DownloadRequestScope.WHOLE_SHOW -> DownloadEntryRoute.SEASON_CHOOSER_THEN_ASSISTED
        }
        DownloadMode.MANUAL -> when (scope) {
            DownloadRequestScope.SINGLE -> DownloadEntryRoute.MANUAL_SOURCE_LIST
            DownloadRequestScope.SEASON -> DownloadEntryRoute.MANUAL_CHOOSE_SOURCES
            DownloadRequestScope.WHOLE_SHOW -> DownloadEntryRoute.SEASON_CHOOSER_THEN_MANUAL
        }
    }
}
