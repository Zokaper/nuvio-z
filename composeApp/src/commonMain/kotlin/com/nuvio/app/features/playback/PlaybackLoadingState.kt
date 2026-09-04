package com.nuvio.app.features.playback

import com.nuvio.app.features.downloads.SourceFacts

// The pre-player loading surface's whole vocabulary, with no Compose in it.
//
// [PlaybackProgressStep], [PlaybackProgressInputs], [PlaybackProgress] and
// [PlaybackProgressFailure] used to live in `PlaybackProgressOverlay.kt` beside the composable
// that drew them. That put a derived, wholly testable state machine inside a file the pure
// suite cannot compile, so the rule these types exist to enforce - *derived, never faked* - was
// the one rule nothing could execute. They moved here when the loading screen became shared,
// which is the same reason: two call sites now build this state, and a vocabulary owned by one
// of them is a vocabulary the other will fork.

/**
 * What the automatic playback path is doing, for the overlay Streamlined and Instant show
 * instead of the source list.
 *
 * Every value maps to state that already exists in `entry<StreamRoute>` - see
 * [PlaybackProgress.step]. Nothing here is a timed or faked sequence: a step that cannot be
 * observed is a step that lies about what the app is waiting for.
 */
enum class PlaybackProgressStep {
    /** Addons and plugins are still returning candidates. */
    FindingSources,

    /**
     * Instant only: the candidates are in but the connection measurement has not settled.
     *
     * Instant picks a quality *from* that measurement, so choosing before it lands would be
     * choosing from the unmeasured platform guess - the fault the mode was withdrawn for the
     * first time. The wait is bounded by `NetworkStrengthProbe.PROBE_DEADLINE_MS` and is
     * usually invisible, because the probe runs alongside the fetch and the fetch is slower.
     * It gets its own step for the case where it is not, because "Choosing a source" while the
     * app is measuring a line is the same small lie the connection gauge work spent three
     * passes removing.
     */
    CheckingConnection,

    /** Candidates are in; `PlaybackSourceSelector` is ranking them. */
    ChoosingSource,

    /** A debrid link is being minted for the chosen candidate. Usually the real wait. */
    ResolvingLink,

    /** Chosen and resolved; handing off to the player. */
    StartingPlayback,
}

/**
 * Everything the overlay's state depends on, gathered by the caller.
 *
 * Plain data on purpose - the route entry gathers, this decides, and a test can cover the
 * whole table without a Compose runtime.
 */
data class PlaybackProgressInputs(
    /** `streamsUiState.isAnyLoading`, or the request token not yet matching. */
    val isLoadingSources: Boolean,
    /** `instantSelectionHandled` for Instant, the tier pick for Streamlined. */
    val hasChosenSource: Boolean,
    /** The existing `resolvingDebridStream` flag. */
    val isResolvingLink: Boolean,
    /** 1-based. Above 1 means the failure chain has moved on from a dead candidate. */
    val attempt: Int = 1,
    /**
     * Instant only: `!connectionSettled`, the same signal the quality sheet withholds its
     * figure on.
     *
     * Deliberately not passed by the remembered-band path, which does not need an estimate -
     * its band is exact - and must not claim to be waiting for one.
     */
    val isMeasuringConnection: Boolean = false,
)

object PlaybackProgress {

    /**
     * The retry budget the failure chain runs to, so the overlay and the chain cannot disagree
     * about how many tries the user is being told about.
     *
     * Defined in `StreamRouteSurface.kt` and aliased here. That file has no imports and is the
     * one thing `scripts/run-pure-suites.sh` can actually execute, so the budget and the
     * function that spends it ([playbackChain]) are covered by a test that runs without Gradle -
     * which is how the drift this fixes would have been caught.
     */
    const val MAX_ATTEMPTS: Int = PLAYBACK_MAX_ATTEMPTS

    /**
     * Resolving is checked first because it is the only step with a real, observable wait: a
     * debrid mint can take seconds while `isLoadingSources` is still true for a slow addon
     * that nothing is waiting on any more.
     */
    fun step(inputs: PlaybackProgressInputs): PlaybackProgressStep = when {
        inputs.isResolvingLink -> PlaybackProgressStep.ResolvingLink
        inputs.isLoadingSources -> PlaybackProgressStep.FindingSources
        // Below the fetch, because the two run concurrently and the fetch is nearly always the
        // longer of the two; above the choice, because Instant genuinely cannot choose yet.
        inputs.isMeasuringConnection && !inputs.hasChosenSource ->
            PlaybackProgressStep.CheckingConnection
        !inputs.hasChosenSource -> PlaybackProgressStep.ChoosingSource
        else -> PlaybackProgressStep.StartingPlayback
    }

    // `isVisible` used to live here and answered only "does the overlay cover the list?".
    // That was half the question: the route also paints an opaque hand-off surface under the
    // overlay, and hiding the overlay while that surface stayed up traded a blank screen for
    // a blank screen one layer down - which is what backing out of the player actually did.
    // The whole stack is decided by `streamRouteSurface` in StreamRouteSurface.kt, so the two
    // cannot disagree - and that file has no imports, so unlike this one it actually runs.
}

/**
 * The source an automatic path has just given up on, for the overlay to name.
 *
 * [label] comes from `PlaybackSourceSelector.describe` - `1080p · WEB-DL · TorBox` - falling
 * back to the stream's own label when nothing is known about it. [reason] is the provider's
 * words when it gave any, and null when it simply failed.
 */
data class PlaybackProgressFailure(
    val label: String,
    val reason: String? = null,
)


/**
 * Everything the one loading surface shows, gathered by whichever side owns the moment.
 *
 * Both the route (`PlaybackProgressOverlay`) and the player (`OpeningOverlay`) build one of
 * these and hand it to [PlaybackLoadingScreen]. That is the whole point: crossing from the
 * route to the player must move nothing on screen, and two call sites rendering one state
 * object is the only version of that guarantee a test can hold.
 *
 * **Derived, never faked** - the rule [PlaybackProgressStep] already carries, extended to the
 * rest of the screen. Every field here is read from state that exists; nothing is a timed
 * sequence and nothing is placeholdered. A source that reported no metadata renders a screen
 * with no chips rather than a screen full of invented ones.
 */
data class PlaybackLoadingState(
    val step: PlaybackProgressStep,
    /** 1-based. Above 1 means the failure chain has moved on from a dead candidate. */
    val attempt: Int = 1,
    val maxAttempts: Int = PLAYBACK_MAX_ATTEMPTS,
    /**
     * What is being opened, once something has been chosen.
     *
     * Null before the choice - Instant's first frames, and every mode's "Looking for
     * sources…" - and null for a source nothing could be parsed from. Both render the same
     * screen minus the band's contents, which is why nothing below may assume it.
     */
    val facts: SourceFacts? = null,
    /** The candidate that just died, when one did. Colours the stage line. */
    val failure: PlaybackProgressFailure? = null,
    /** Whether the way out to the source list is offered yet. */
    val offerManualEscape: Boolean = false,
) {
    /** Above 1, and never past the budget - "Attempt 5 of 3" is unreachable by construction. */
    val displayAttempt: Int get() = attempt.coerceIn(1, maxAttempts)

    val showsAttempt: Boolean get() = attempt > 1

    /**
     * The release name, small and dimmed at the foot of the screen.
     *
     * Kept deliberately: it is how a wrong-show or wrong-release pick becomes visible *before*
     * it plays, which is the user-facing half of the content-identity guard. A guard that
     * rejects silently and a screen that shows nothing are the same failure.
     */
    val releaseName: String? get() = facts?.filename?.takeIf { it.isNotBlank() }
}

/**
 * One chip: a single fact about the file, or nothing.
 *
 * A list of these rather than a formatted string so the screen can lay them out and a test can
 * assert on them without parsing text back apart.
 */
data class PlaybackLoadingChip(val label: String)

object PlaybackLoadingFacts {

    /**
     * The chips, in the order the maintainer asked for them: resolution and dynamic range,
     * then audio and language, then size.
     *
     * **Every part is omitted when unknown rather than placeholdered**, the same rule
     * [PlaybackSourceSelector.describeBestRelease] carries. An empty list is a valid answer and
     * the screen is required to render correctly for it.
     *
     * [formatSize] is passed rather than called: this file is reachable from the pure suite,
     * and `formatFileSize` reaches the generated resource bundle.
     */
    fun chips(
        facts: SourceFacts?,
        formatSize: (Long) -> String,
    ): List<PlaybackLoadingChip> {
        if (facts == null) return emptyList()
        return listOfNotNull(
            facts.resolution.qualityLabel.takeIf { it.isNotBlank() },
            PlaybackSourceSelector.dynamicRangeLabel(facts),
            audioLabel(facts),
            languageLabel(facts),
            facts.sizeBytes?.takeIf { it > 0L }?.let(formatSize),
        ).map(::PlaybackLoadingChip)
    }

    /**
     * `EAC3 5.1`, `Atmos 7.1`, `5.1`, or null.
     *
     * One codec word, best first, for the same reason [PlaybackSourceSelector.dynamicRangeLabel]
     * gives one: a release routinely carries Atmos *and* its TrueHD base layer, and printing
     * both spends a chip on two names for one track.
     *
     * Channels alone are still worth a chip - "5.1" is a fact the user chooses on even when the
     * codec went unstated.
     */
    fun audioLabel(facts: SourceFacts?): String? {
        val codec = codecLabel(facts?.audioCodecs.orEmpty())
        val channels = channelLabel(facts?.audioChannels)
        return listOfNotNull(codec, channels).joinToString(" ").takeIf { it.isNotBlank() }
    }

    private fun codecLabel(codecs: Set<String>): String? = when {
        "ATMOS" in codecs -> "Atmos"
        "DTS_X" in codecs -> "DTS:X"
        "TRUEHD" in codecs -> "TrueHD"
        "DTS_HD_MA" in codecs -> "DTS-HD MA"
        "FLAC" in codecs -> "FLAC"
        "DTS_HD" in codecs -> "DTS-HD"
        "DTS_ES" in codecs -> "DTS-ES"
        "DTS" in codecs -> "DTS"
        "DD_PLUS" in codecs -> "DD+"
        "DD" in codecs -> "DD"
        "OPUS" in codecs -> "Opus"
        "AAC" in codecs -> "AAC"
        else -> null
    }

    private fun channelLabel(channels: Int?): String? = when (channels) {
        8 -> "7.1"
        7 -> "6.1"
        6 -> "5.1"
        2 -> "2.0"
        else -> null
    }

    /**
     * `EN`, `EN +2`, or null.
     *
     * **Empty is not a language claim.** Most English releases say nothing about language at
     * all, so an absent set draws no chip rather than a chip reading "EN" the release never
     * earned - the rule `SourceFacts.languages` documents and the ranking already honours.
     *
     * A multi-language release with no named tracks says so, because that is the one thing
     * `MULTi` actually tells you.
     */
    fun languageLabel(facts: SourceFacts?): String? {
        val languages = facts?.languages.orEmpty()
        if (languages.isEmpty()) return if (facts?.isMultiLanguage == true) "MULTi" else null
        val first = languages.first().uppercase()
        val rest = languages.size - 1
        return if (rest > 0) "$first +$rest" else first
    }

    /**
     * `TorBox · Cached`, `Torrentio`, or null.
     *
     * The cached word is only ever printed from a **positive** answer.
     * `SourceFacts.isDebridReady` is nullable precisely because a service that names its cache
     * state only in a display string leaves it unknown, and "Cached" over a link that turns out
     * to need minting is the untruth the tri-state exists to prevent.
     */
    fun providerLine(facts: SourceFacts?): String? {
        val provider = (facts?.debridService ?: facts?.providerName)?.takeIf { it.isNotBlank() }
        val cached = when (facts?.isDebridReady) {
            true -> "Cached"
            else -> null
        }
        return listOfNotNull(provider, cached).joinToString(" · ").takeIf { it.isNotBlank() }
    }
}
