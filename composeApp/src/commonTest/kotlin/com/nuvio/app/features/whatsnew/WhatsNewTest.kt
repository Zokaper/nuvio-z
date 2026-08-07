package com.nuvio.app.features.whatsnew

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WhatsNewTest {
    private val sections = listOf(
        WhatsNewSection(
            category = WhatsNewCategory.NewFeatures,
            items = listOf(WhatsNewItem("A feature", "Its description")),
        ),
    )

    @Test
    fun `shows when the installed version has not been seen`() {
        assertTrue(shouldShowWhatsNew(null, "1.2.0", sections))
        assertTrue(shouldShowWhatsNew("1.1.0", "1.2.0", sections))
    }

    @Test
    fun `does not show twice for the same version`() {
        assertFalse(shouldShowWhatsNew("1.2.0", "1.2.0", sections))
    }

    @Test
    fun `does not show without a usable version or release notes`() {
        assertFalse(shouldShowWhatsNew(null, "", sections))
        assertFalse(shouldShowWhatsNew(null, "1.2.0", emptyList()))
        assertFalse(
            shouldShowWhatsNew(
                null,
                "1.2.0",
                listOf(WhatsNewSection(WhatsNewCategory.BugFixes, emptyList())),
            ),
        )
    }
}
