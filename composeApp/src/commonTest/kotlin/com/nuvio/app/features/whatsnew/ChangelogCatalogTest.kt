package com.nuvio.app.features.whatsnew

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChangelogCatalogTest {

    @Test
    fun aGlobalEventParsesWithItsShipsAndEntries() {
        val events = ChangelogCatalog.parse(
            """{"events":[{"seq":2,"summary":"Big one",
              "ships":{"desktop":{"version":"0.1.26-alpha-z1","serial":132,"date":"unreleased"},
                       "android":{"version":"0.5.4-z1","serial":127,"date":"2026-10-10"},
                       "ios":{"version":"0.5.4-z1","serial":127,"date":"2026-10-10"}},
              "entries":[{"category":"feature","platforms":["desktop","android","ios"],"title":"Everywhere","body":"B","action":"advanced_setup","qa":"open"},
                         {"category":"fix","platforms":["ios"],"title":"iOS fix"}]}]}""",
        )
        val event = events.single()
        assertEquals(2, event.seq)
        assertEquals("Big one", event.summary)
        assertEquals(ChangelogShip("0.1.26-alpha-z1", 132, null), event.ships[ChangelogPlatform.DESKTOP])
        assertEquals(ChangelogShip("0.5.4-z1", 127, "2026-10-10"), event.ships[ChangelogPlatform.IOS])
        assertEquals(listOf("Everywhere", "iOS fix"), event.entries.map { it.title })
        assertEquals(ChangelogAction.ADVANCED_SETUP, event.entries[0].action)
        assertEquals(setOf(ChangelogPlatform.IOS), event.entries[1].platforms)
        assertNull(event.entries[1].body)
    }

    @Test
    fun anEventOnOnePlatformHasOnlyThatPlatformsVersion() {
        val event = ChangelogCatalog.parse(
            """{"events":[{"seq":3,"ships":{"ios":{"version":"0.5.4-z2","serial":128,"date":"2026-10-20"}},
              "entries":[{"category":"fix","platforms":["ios"],"title":"x"}]}]}""",
        ).single()
        assertEquals(setOf(ChangelogPlatform.IOS), event.ships.keys)
    }

    @Test
    fun malformedItemsAreDroppedOneByOne() {
        val events = ChangelogCatalog.parse(
            """{"events":[
              {"seq":5,"ships":{"desktop":{"version":"v","serial":5}},"entries":[
                 {"category":"feature","platforms":["desktop"],"title":"Kept"},
                 {"category":"novelty","platforms":["desktop"],"title":"Unknown category"},
                 {"category":"feature","platforms":["tizen"],"title":"Unknown platform only"},
                 {"category":"feature","platforms":["desktop","tizen"],"title":"Partly known"},
                 {"category":"feature","platforms":["desktop"],"title":"  "},
                 {"category":"feature","platforms":["desktop"],"title":"Future action","action":"open_downloads"},
                 "not an object"]},
              {"seq":4,"ships":{"desktop":{"serial":4}},"entries":[]},
              {"seq":3,"ships":{"tizen":{"version":"t","serial":1}},"entries":[]},
              {"ships":{"desktop":{"version":"v","serial":2}},"entries":[]},
              {"seq":"1","ships":{"desktop":{"version":"v","serial":1}},"entries":[]},
              {"seq":0,"ships":{"desktop":{"version":"v","serial":1}},"entries":[]}
            ]}""",
        )
        // 4 has no version, 3 ships on no known platform, the next has no seq, "1" is a string, 0 is not positive.
        val event = events.single()
        assertEquals(5, event.seq)
        assertEquals(listOf("Kept", "Partly known", "Future action"), event.entries.map { it.title })
        assertEquals(setOf(ChangelogPlatform.DESKTOP), event.entries[1].platforms)
        assertNull(event.entries[2].action)
        // A missing date is an unreleased ship.
        assertNull(event.ships.getValue(ChangelogPlatform.DESKTOP).date)
    }

    @Test
    fun unreadableTextIsAnEmptyChangelog() {
        assertTrue(ChangelogCatalog.parse("not json").isEmpty())
        assertTrue(ChangelogCatalog.parse("[]").isEmpty())
        // The pre-event format has no events: an old file reads as empty, never as a crash.
        assertTrue(ChangelogCatalog.parse("""{"releases":[{"family":"mobile","version":"x","serial":1,"entries":[]}]}""").isEmpty())
    }

    @Test
    fun debugNotesBelongToOneFamily() {
        val text = """{"family":"desktop","notes":[{"build":81,"text":"a"},{"build":"82","text":"b"},{"build":83,"text":" "},{"build":84,"text":"d"}]}"""
        assertEquals(listOf(81, 84), ChangelogCatalog.parseDebug(text, "desktop").map { it.build })
        assertTrue(ChangelogCatalog.parseDebug(text, "mobile").isEmpty())
        assertTrue(ChangelogCatalog.parseDebug("{", "desktop").isEmpty())
    }
}
