package com.nuvio.app.features.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Both sources panels are the same person making the same choice, so both must route the same way.
 *
 * This reads the source rather than the behaviour, deliberately, because the defect it exists for
 * is invisible to every other kind of test. `switchToSource` and `switchToUserSelectedSource` do
 * almost the same thing: the difference is that only the second one tells the party, and the
 * native/HTML player controls called the first. The desktop host changed source, loaded and played
 * it, and every guest stayed on the old source and went on syncing its timeline against a host
 * that had left it. Nothing was thrown, nothing was logged, and the compiler was satisfied -
 * `switchToSource` is a legitimate call from a dozen automatic paths, and the panel looked like
 * one more. Reproduced on hardware 2026-09-20, desktop host to Android guest.
 *
 * Kept to the two panel call sites. It is not a rule that `switchToSource` may not be called; it
 * is a rule that **a person picking a source may not be what calls it.**
 */
class PlayerSourcePickRoutingTest {

    private fun sourceFile(name: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val suffix = "src/commonMain/kotlin/com/nuvio/app/features/player/$name"
        while (dir != null) {
            for (candidate in listOf(File(dir, suffix), File(dir, "composeApp/$suffix"))) {
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        fail("could not locate $name from ${System.getProperty("user.dir")}")
    }

    /** The block the native controls dispatch a sources-panel tap to. */
    private fun nativeSelectSourceHandler(): String {
        val ui = sourceFile("PlayerScreenRuntimeUi.kt")
        val start = ui.indexOf("\"selectSource\" -> {")
        assertTrue(start >= 0, "the native controls no longer have a \"selectSource\" handler")
        val end = ui.indexOf("\"selectEpisode\" -> {", start)
        assertTrue(end > start, "could not find the end of the \"selectSource\" handler")
        return ui.substring(start, end)
    }

    @Test
    fun theNativeSourcesPanelPublishesItsPickToTheParty() {
        val handler = nativeSelectSourceHandler()
        assertTrue(
            handler.contains("switchToUserSelectedSource(stream)"),
            "the native sources panel must route through switchToUserSelectedSource, which is the " +
                "only path that publishes the pick to the party:\n$handler",
        )
    }

    @Test
    fun theNativeSourcesPanelDoesNotCallTheInternalSwitch() {
        val handler = nativeSelectSourceHandler()
        assertTrue(
            !handler.contains("\n            switchToSource("),
            "switchToSource() says nothing to the party and must not be a panel's call:\n$handler",
        )
    }

    @Test
    fun theComposeSourcesPanelPublishesItsPickToTheParty() {
        val ui = sourceFile("PlayerScreenRuntimeUi.kt")
        assertTrue(
            ui.contains("onSourceStreamSelected = { stream -> switchToUserSelectedSource(stream) }"),
            "the Compose sources panel must route through switchToUserSelectedSource",
        )
    }

    /**
     * And the far side of the P2P consent dialog, which is where both panels resume.
     *
     * A pick parked on that dialog has not happened yet, so the publish moved there with it. Both
     * continuations therefore have to be the user-selected one, or a host enabling P2P for a
     * hand-picked torrent silently keeps the party on the old source - the same bug, one dialog
     * further along.
     */
    @Test
    fun bothP2pConsentContinuationsPublishAUserPick() {
        val ui = sourceFile("PlayerScreenRuntimeUi.kt")
        assertTrue(
            ui.contains("switchToUserSelectedSourceAfterP2pConsent(pending.stream)"),
            "the native consent continuation must publish a user pick",
        )
        assertTrue(
            ui.contains("if (userSelected) switchToUserSelectedSourceAfterP2pConsent(stream)"),
            "the Compose consent continuation must publish a user pick",
        )
    }

    /**
     * And the automatic paths must keep saying nothing.
     *
     * The fix must not have been "make everything publish". `switchToSource` itself is still the
     * internal one, and the consent continuation still has a branch for the automatic chain that
     * arrived at a P2P stream on its own.
     */
    @Test
    fun theAutomaticPathsStaySilent() {
        val actions = sourceFile("PlayerScreenRuntimeSourceActions.kt")
        val start = actions.indexOf("internal fun PlayerScreenRuntime.switchToSource(stream: StreamItem) {")
        assertTrue(start >= 0, "switchToSource is gone")
        val end = actions.indexOf("internal fun PlayerScreenRuntime.switchToEpisodeStream(", start)
        assertTrue(end > start, "could not find the end of switchToSource")
        assertTrue(
            !actions.substring(start, end).contains("publishPartySourceChange("),
            "switchToSource must stay local: automatic retries, party adoption and re-mints all " +
                "reach it, and none of them is somebody choosing what everyone watches",
        )
        val ui = sourceFile("PlayerScreenRuntimeUi.kt")
        assertTrue(
            ui.contains("else -> switchToP2pSourceStream(stream)") ||
                ui.contains("else switchToP2pSourceStream(stream)"),
            "the consent dialog must still have a local branch for a switch nobody picked",
        )
    }
}
