package com.nuvio.app.features.whatsnew

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReleaseNotesMarkdownTest {

    @Test
    fun aRealGeneratedReleaseBodyRendersWithoutItsMarkup() {
        // The shape scripts/generate-release-notes.sh actually produces.
        val body = """
            ## Fixes

            - **fix(playback):** never auto-play a debrid source that is not known to be cached
            - fix(sync): clear only the keys a payload carries

            ## Notes

            See [the full diff](https://github.com/Zokaper/nuvio-z/compare/a...b).
        """.trimIndent()

        val lines = parseReleaseNotes(body)

        assertEquals(
            listOf(
                ReleaseNoteLine.Heading("Fixes"),
                ReleaseNoteLine.Bullet("fix(playback): never auto-play a debrid source that is not known to be cached"),
                ReleaseNoteLine.Bullet("fix(sync): clear only the keys a payload carries"),
                ReleaseNoteLine.Heading("Notes"),
                ReleaseNoteLine.Paragraph("See the full diff."),
            ),
            lines,
        )
        assertTrue(lines.none { "**" in it.text || it.text.startsWith("#") || it.text.startsWith("- ") })
    }

    @Test
    fun everyBulletStyleIsRecognised() {
        val lines = parseReleaseNotes("- dash\n* star\n+ plus\n1. first\n2. second")
        assertEquals(5, lines.size)
        assertTrue(lines.all { it is ReleaseNoteLine.Bullet })
        assertEquals(listOf("dash", "star", "plus", "first", "second"), lines.map { it.text })
    }

    @Test
    fun unrecognisedSyntaxFallsThroughAsText() {
        // The safe direction to be wrong in: showing a line we did not understand beats
        // silently dropping it.
        val lines = parseReleaseNotes("> a quote\n| a | table |")
        assertEquals(2, lines.size)
        assertTrue(lines.all { it is ReleaseNoteLine.Paragraph })
    }

    @Test
    fun codeFencesAreDropped() {
        val lines = parseReleaseNotes("```bash\n./gradlew test\n```")
        assertEquals(listOf(ReleaseNoteLine.Paragraph("./gradlew test")), lines)
    }

    @Test
    fun anEmptyBodyRendersNothingRatherThanABlankLine() {
        assertEquals(emptyList(), parseReleaseNotes(""))
        assertEquals(emptyList(), parseReleaseNotes("\n\n   \n"))
        // "##" with no text would otherwise become an empty heading.
        assertEquals(emptyList(), parseReleaseNotes("##"))
    }
}
