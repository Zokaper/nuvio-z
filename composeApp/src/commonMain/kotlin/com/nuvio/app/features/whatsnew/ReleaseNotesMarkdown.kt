package com.nuvio.app.features.whatsnew

/**
 * One rendered line of a GitHub release body.
 *
 * Release notes come out of `scripts/generate-release-notes.sh` as markdown, and the existing
 * `ReleaseNotesDialog` pushed them through a plain `Text` - so headings showed as `## Fixes`
 * and every bullet kept its literal `- `. This is the smallest thing that stops that looking
 * broken: headings, bullets and paragraphs, with inline emphasis markers stripped.
 *
 * Deliberately **not** a markdown parser. Anything it does not recognise falls through as a
 * paragraph, which is the safe direction to be wrong in - unrecognised syntax renders as its
 * own text rather than disappearing.
 */
sealed class ReleaseNoteLine {
    abstract val text: String

    data class Heading(override val text: String) : ReleaseNoteLine()
    data class Bullet(override val text: String) : ReleaseNoteLine()
    data class Paragraph(override val text: String) : ReleaseNoteLine()
}

private val bulletPrefixes = listOf("- ", "* ", "+ ")

/** Matches `1. `, `2. ` and so on. */
private val orderedBullet = Regex("""^\d+\.\s+""")

fun parseReleaseNotes(body: String): List<ReleaseNoteLine> =
    body.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        // A fenced code block's ``` markers would otherwise render as a paragraph of backticks.
        .filterNot { it.startsWith("```") }
        .map { line ->
            when {
                line.startsWith("#") ->
                    ReleaseNoteLine.Heading(stripInline(line.trimStart('#').trim()))

                bulletPrefixes.any { line.startsWith(it) } ->
                    ReleaseNoteLine.Bullet(stripInline(line.drop(2).trim()))

                orderedBullet.containsMatchIn(line) ->
                    ReleaseNoteLine.Bullet(stripInline(orderedBullet.replace(line, "").trim()))

                else -> ReleaseNoteLine.Paragraph(stripInline(line))
            }
        }
        .filter { it.text.isNotBlank() }
        .toList()

/**
 * Removes the emphasis and code markers rather than styling them.
 *
 * Rendering `**bold**` as bold would mean an `AnnotatedString` builder and a second set of
 * edge cases; showing the asterisks is the actual bug. Link syntax keeps the label and drops
 * the URL, because a release note's link target is rarely the point and is never tappable
 * here.
 */
private fun stripInline(text: String): String =
    text
        .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .trim()
