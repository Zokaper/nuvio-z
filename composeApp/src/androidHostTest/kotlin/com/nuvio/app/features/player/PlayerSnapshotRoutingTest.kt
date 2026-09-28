package com.nuvio.app.features.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The engine's snapshots must reach the runtime through `updatePlaybackSnapshot`.
 *
 * This reads the source rather than the behaviour, like [PlayerSourcePickRoutingTest], because the
 * defect it exists for is invisible to the runtime tests: they call `updatePlaybackSnapshot`
 * themselves. Upstream's keyed player lifecycle (0.5.x) stamps `playbackSnapshotKey` there, and the
 * next-episode threshold, the restored launch and the release of a scrub target held while the engine
 * buffered all read it. The 0.5.4-beta sync kept Z's surface callback, which assigned
 * `playbackSnapshot` directly: everything compiled, every test passed, and on a device the
 * next-episode card could never appear and a scrub released during buffering pinned the timeline.
 */
class PlayerSnapshotRoutingTest {

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

    /** The surface's snapshot callback, up to the error callback that follows it. */
    private fun onSnapshotHandler(): String {
        val ui = sourceFile("PlayerScreenRuntimeUi.kt")
        val start = ui.indexOf("onSnapshot = { snapshot ->")
        assertTrue(start >= 0, "the player surface no longer has an onSnapshot callback")
        val end = ui.indexOf("onError = {", start)
        assertTrue(end > start, "could not find the end of the onSnapshot callback")
        return ui.substring(start, end)
    }

    @Test
    fun snapshotsGoThroughTheKeyedUpdate() {
        val handler = onSnapshotHandler()
        assertTrue(
            handler.contains("updatePlaybackSnapshot(snapshot"),
            "the surface's snapshots must go through updatePlaybackSnapshot:\n$handler",
        )
    }

    @Test
    fun snapshotsAreNotAssignedDirectly() {
        val handler = onSnapshotHandler()
        assertFalse(
            Regex("""playbackSnapshot\s*=\s*snapshot\b""").containsMatchIn(handler),
            "assigning playbackSnapshot directly skips the playback key and the scrub release:\n$handler",
        )
    }
}
