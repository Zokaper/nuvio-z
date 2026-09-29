package com.nuvio.app.features.whatsnew

import com.nuvio.app.core.build.AppVersionConfig
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shipped changelog parses, is well formed, and has notes for this build's release serial -
 * the same guard `scripts/check-changelog.py` applies in the release workflow, run on every CI push
 * so a bump without notes is red before anyone dispatches a release.
 */
class ChangelogFileTest {
    private val releases = ChangelogCatalog.parse(File("src/commonMain/composeResources/files/changelog.json").readText())

    @Test
    fun everyEntryParsesWithACategoryAndAPlatform() {
        assertTrue(releases.isNotEmpty())
        releases.forEach { release ->
            assertTrue(release.entries.isNotEmpty(), "${release.family} ${release.serial} has no entries")
            release.entries.forEach { entry ->
                assertTrue(entry.platforms.isNotEmpty(), "${release.serial} '${entry.title}' names no platform")
                assertTrue(entry.title.isNotBlank())
            }
        }
    }

    @Test
    fun serialsAreUniqueWithinALine() {
        releases.groupBy { it.family }.forEach { (family, lines) ->
            assertEquals(lines.size, lines.map { it.serial }.toSet().size, "duplicate serial in $family")
        }
    }

    @Test
    fun thisBuildsReleaseSerialHasNotes() {
        assertTrue(
            changelogHasRelease(releases, "mobile", AppVersionConfig.RELEASE_SERIAL),
            "changelog.json has no mobile notes for RELEASE_SERIAL ${AppVersionConfig.RELEASE_SERIAL}",
        )
    }

    @Test
    fun anActionIsParsedLenientlyAndOptional() {
        val parsed = ChangelogCatalog.parse(
            """{"releases":[{"family":"mobile","version":"x","serial":1,"entries":[
              {"category":"feature","platforms":["android"],"title":"A","action":"advanced_setup"},
              {"category":"feature","platforms":["android"],"title":"B","action":"from_the_future"},
              {"category":"feature","platforms":["android"],"title":"C"}]}]}""",
        ).single().entries
        assertEquals(listOf(ChangelogAction.ADVANCED_SETUP, null, null), parsed.map { it.action })
    }

    @Test
    fun thisReleaseOffersAdvancedSetupOnEveryPlatform() {
        // Plan section 8: existing users meet Advanced Setup through this release's What's New card.
        listOf("mobile" to listOf(ChangelogPlatform.ANDROID, ChangelogPlatform.IOS), "desktop" to listOf(ChangelogPlatform.DESKTOP))
            .forEach { (family, platforms) ->
                val newest = releases.filter { it.family == family }.maxBy { it.serial }
                platforms.forEach { platform ->
                    assertTrue(
                        newest.entries.any { it.action == ChangelogAction.ADVANCED_SETUP && platform in it.platforms },
                        "$family ${newest.serial} has no Advanced Setup card for $platform",
                    )
                }
            }
    }
}
