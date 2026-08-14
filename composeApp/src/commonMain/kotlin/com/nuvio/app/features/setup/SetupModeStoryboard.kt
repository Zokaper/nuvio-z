package com.nuvio.app.features.setup

// No imports, and none may be added. This file is pure by design so it can be compiled and run
// outside Gradle (`AGENTS.md`, "Verifying without Gradle", item 2), exactly like
// `SetupWizardSteps.kt` and `StreamRouteSurface.kt`. The animation it describes is drawn by
// Compose in `SetupDiagram.kt`, which no test in this repository can reach - so if the sequences
// are not decided here they are not decided anywhere a test can see.

/**
 * What the playback-mode animation is showing at one moment.
 *
 * The step asks how much of the choosing Nuvio does, and revision 4 answered with three static
 * grey bars and an arrow. The maintainer's verdict was "vague as hell", which was fair: a still
 * picture cannot show a *process*, and the difference between these three modes is entirely a
 * difference in process.
 *
 * So each mode gets a short loop that walks its own path from tapping a title to playing, and
 * the loop is what carries the answer:
 *
 * - **Classic** stops on a long list and waits for the user to read it.
 * - **Streamlined** asks one short question and then decides the rest itself.
 * - **Instant** never asks.
 */
enum class SetupStoryboardStage {
    /** A title card, before anything has been asked. Every mode starts here. */
    Title,

    /** The source list: every release, which is the thing Classic makes the user read. */
    Sources,

    /** Streamlined's short question - a few resolutions rather than a wall of releases. */
    Quality,

    /** One release has been settled on, whether by the user (Classic) or by Nuvio. */
    Chosen,

    /** Playing. */
    Playing,
}

/**
 * One frame of the loop.
 *
 * @param stage what the frame is showing.
 * @param holdMillis how long to rest on it before advancing. Per-frame rather than one constant
 *   because the frames are not equally interesting: the frame that *is* the point of a mode -
 *   Classic's wall of releases, Instant's immediate play - has to be held long enough to read,
 *   and the connective frames must not be.
 * @param pointerVisible whether the user's finger is on screen. ⚠ **This is the whole message.**
 *   A frame Nuvio decided has no pointer; a frame the user decided has one. Streamlined's release
 *   pick and Classic's release pick look otherwise identical and mean opposite things.
 * @param tapping whether the pointer is mid-tap on this frame, so the drawing can ripple.
 * @param highlightedRow which item of whatever list is on screen is settled on, or null while
 *   none is - a release row when [visibleRows] is non-zero, a quality chip when [chipsVisible].
 * @param visibleRows how many release rows the list is showing. Classic shows all of them
 *   because that is what it does; Streamlined never shows the list at all.
 * @param chipsVisible whether the quality chips are on screen. Streamlined's settled frame keeps
 *   them up with the unchosen ones faded, because the chips *are* the thing that was answered -
 *   drawing its pick as a fresh list would say the user had been shown a list.
 */
data class SetupStoryboardFrame(
    val stage: SetupStoryboardStage,
    val holdMillis: Int,
    val pointerVisible: Boolean = false,
    val tapping: Boolean = false,
    val highlightedRow: Int? = null,
    val visibleRows: Int = 0,
    val chipsVisible: Boolean = false,
)

/**
 * The resolutions Streamlined's quality step offers in the drawing.
 *
 * ⚠ **Not string resources, deliberately.** `4K`, `1080p` and `720p` are locale-independent
 * format names that the app already renders verbatim on the real quality sheet - there is
 * nothing here to translate, and nothing stranded when this provisional drawing is deleted. The
 * same rule covers [setupStoryboardReleases]; between them they are all the text in the diagram,
 * and the panel underneath does the explaining.
 */
val setupStoryboardQualityTokens: List<String> = listOf("4K", "1080p", "720p")

/**
 * The releases Classic's source list shows.
 *
 * ⚠ **Text, and Kotlin constants rather than string resources** - same rule as
 * [setupStoryboardQualityTokens], for the same reason. Resolutions, source tags and file sizes are
 * locale-independent and the app already renders them verbatim on the real source list, so there
 * is nothing here to translate and nothing stranded when this provisional drawing is deleted.
 *
 * ⚠ **Revision 5 drew these as five blank grey bars and it did not work.** "You read the releases
 * and pick one" is the whole of what separates Classic from Streamlined, and a bar cannot be read.
 * The strings are deliberately *similar* to each other - that near-sameness, four lines of it, is
 * what the mode actually asks of the user.
 *
 * Three, not five: the band is 150 dp and the playback-mode step has the tallest panel in the
 * flow. See `SetupSpecimen.Diagram`.
 */
val setupStoryboardReleases: List<String> = listOf(
    "2160p · HDR · 18.4 GB",
    "1080p · WEB-DL · 4.2 GB",
    "1080p · BluRay · 8.1 GB",
)

/** How many release rows Classic's source list draws. */
val SETUP_STORYBOARD_SOURCE_ROWS: Int = setupStoryboardReleases.size

/**
 * The frames for [modeName], in order, looping back to the first once the last has been held.
 *
 * Takes the mode's **name** rather than the `PlaybackMode` enum so this file can stay import-free.
 * `SetupDiagram` passes `mode.name`. An unrecognised name answers with the Classic sequence rather
 * than with an empty list, because an empty list would leave the band blank on a step that gates
 * the app.
 */
fun setupStoryboardFrames(modeName: String): List<SetupStoryboardFrame> = when (modeName) {
    "STREAMLINED" -> streamlinedFrames
    "INSTANT" -> instantFrames
    else -> classicFrames
}

/**
 * Classic: the user reads every release and picks one.
 *
 * The list is held for a long beat with the pointer moving down it, because *reading* is the
 * thing this mode asks of you and a frame that flicks past would say the opposite.
 */
private val classicFrames: List<SetupStoryboardFrame> = listOf(
    SetupStoryboardFrame(SetupStoryboardStage.Title, holdMillis = 900),
    SetupStoryboardFrame(
        SetupStoryboardStage.Title,
        holdMillis = 460,
        pointerVisible = true,
        tapping = true,
    ),
    SetupStoryboardFrame(
        SetupStoryboardStage.Sources,
        holdMillis = 820,
        visibleRows = SETUP_STORYBOARD_SOURCE_ROWS,
    ),
    // ⚠ The pointer walks every row in order, and the drawing animates its offset between these
    // frames rather than redrawing it somewhere else. Reading the list *is* the mode's cost, and
    // a pointer that teleported down it - which is what revision 5 did - showed the cost as an
    // instant. Skipping a row here would reintroduce that.
    SetupStoryboardFrame(
        SetupStoryboardStage.Sources,
        holdMillis = 560,
        pointerVisible = true,
        highlightedRow = 0,
        visibleRows = SETUP_STORYBOARD_SOURCE_ROWS,
    ),
    SetupStoryboardFrame(
        SetupStoryboardStage.Sources,
        holdMillis = 560,
        pointerVisible = true,
        highlightedRow = 1,
        visibleRows = SETUP_STORYBOARD_SOURCE_ROWS,
    ),
    SetupStoryboardFrame(
        SetupStoryboardStage.Sources,
        holdMillis = 560,
        pointerVisible = true,
        highlightedRow = 2,
        visibleRows = SETUP_STORYBOARD_SOURCE_ROWS,
    ),
    SetupStoryboardFrame(
        SetupStoryboardStage.Chosen,
        holdMillis = 560,
        pointerVisible = true,
        tapping = true,
        highlightedRow = 2,
        visibleRows = SETUP_STORYBOARD_SOURCE_ROWS,
    ),
    SetupStoryboardFrame(SetupStoryboardStage.Playing, holdMillis = 1400),
)

/**
 * Streamlined: one short question, then Nuvio picks the release.
 *
 * ⚠ **The release frame carries no pointer, and that is the entire difference from Classic.**
 * Both modes end with one release settled on; only in Classic did the user settle it.
 */
private val streamlinedFrames: List<SetupStoryboardFrame> = listOf(
    SetupStoryboardFrame(SetupStoryboardStage.Title, holdMillis = 900),
    SetupStoryboardFrame(
        SetupStoryboardStage.Title,
        holdMillis = 420,
        pointerVisible = true,
        tapping = true,
    ),
    SetupStoryboardFrame(SetupStoryboardStage.Quality, holdMillis = 780, chipsVisible = true),
    SetupStoryboardFrame(
        SetupStoryboardStage.Quality,
        holdMillis = 500,
        pointerVisible = true,
        tapping = true,
        highlightedRow = 0,
        chipsVisible = true,
    ),
    // Nuvio's own pick. Same chip still lit, the rest gone, and no pointer: nobody touched this.
    SetupStoryboardFrame(
        SetupStoryboardStage.Chosen,
        holdMillis = 820,
        highlightedRow = 0,
        chipsVisible = true,
    ),
    SetupStoryboardFrame(SetupStoryboardStage.Playing, holdMillis = 1300),
)

/**
 * Instant: press play, it plays.
 *
 * Three frames, and the shortest path to [SetupStoryboardStage.Playing] of the three modes -
 * which is the claim the mode makes. Playing is held long so the loop does not read as frantic.
 */
private val instantFrames: List<SetupStoryboardFrame> = listOf(
    SetupStoryboardFrame(SetupStoryboardStage.Title, holdMillis = 950),
    SetupStoryboardFrame(
        SetupStoryboardStage.Title,
        holdMillis = 400,
        pointerVisible = true,
        tapping = true,
    ),
    SetupStoryboardFrame(SetupStoryboardStage.Playing, holdMillis = 1800),
)

/**
 * The frame index after [index], wrapping to 0 at the end.
 *
 * A named function rather than a `%` at the call site so the loop rule is covered by a test.
 * An out-of-range or negative [index] answers 0: the drawing runs off a `remember`ed counter and
 * a mode change re-seeds it, so a stale index outliving its frame list is reachable.
 */
fun nextSetupStoryboardFrame(index: Int, frameCount: Int): Int =
    if (frameCount <= 0 || index < 0 || index >= frameCount - 1) 0 else index + 1
