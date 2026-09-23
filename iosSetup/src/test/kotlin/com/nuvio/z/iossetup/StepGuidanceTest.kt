package com.nuvio.z.iossetup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StepGuidanceTest {
    @Test fun everyStepHasPurposeSuccessVisualAndTroubleshootingWhereNeeded() {
        SetupStep.entries.forEach { step ->
            val guide = guidanceFor(step)
            assertTrue(guide.purpose.length > 30, "$step needs a useful purpose")
            assertTrue(guide.success.length > 20, "$step needs an explicit success state")
            assertTrue(guide.visualTitle.isNotBlank(), "$step needs a visual title")
            if (step !in setOf(SetupStep.WELCOME)) {
                assertTrue(guide.troubleshooting.isNotEmpty(), "$step needs contextual troubleshooting")
            }
        }
    }

    @Test fun requiredFailureScenariosAreCoveredContextually() {
        fun help(step: SetupStep) = guidanceFor(step).troubleshooting
            .flatMap { listOf(it.problem) + it.recovery }
            .joinToString(" ")
            .lowercase()

        assertContainsAll(help(SetupStep.CONNECT_IPHONE), "not detected", "charge", "unlock", "trust", "apple mobile device")
        assertContainsAll(help(SetupStep.SIDESTORE_INSTALL), "not listed", "don’t know what to choose", "missing")
        assertContainsAll(help(SetupStep.PAIRING), "pairing was not placed", "replace pairing", "disappears")
        assertContainsAll(help(SetupStep.TRUST_PROFILE), "developer profile", "untrusted developer", "will not open")
        assertContainsAll(help(SetupStep.DEVELOPER_MODE), "missing", "restarted", "will not open")
        assertContainsAll(help(SetupStep.SIDESTORE_PRIME), "not connected", "replace pairing", "refresh or signing")
        assertContainsAll(help(SetupStep.ADD_SOURCE), "qr or deep link", "invalid", "no app")
        assertContainsAll(help(SetupStep.INSTALL_NUVIO), "install or refresh", "app extensions", "will not open")
    }

    @Test fun manualConfirmationsNameTheExactObservedResult() {
        val state = SetupState(channel = SetupChannel.DEVELOPER, developerWarningAccepted = true)
        val expected = mapOf(
            SetupStep.LOCAL_DEV_VPN to "Connected",
            SetupStep.SIDESTORE_INSTALL to "SideStore",
            SetupStep.PAIRING to "Pairing file placed successfully",
            SetupStep.TRUST_PROFILE to "trusted the developer profile",
            SetupStep.DEVELOPER_MODE to "Developer Mode is enabled",
            SetupStep.SIDESTORE_PRIME to "first refresh",
            SetupStep.ADD_SOURCE to "Nuvio Z Debug source",
            SetupStep.INSTALL_NUVIO to "Nuvio Z Debug",
        )
        expected.forEach { (step, phrase) ->
            assertTrue(phrase in confirmationText(step, state), "$step confirmation should name '$phrase'")
        }
    }

    @Test fun everyStepMapsToOneDistinctVisual() {
        val visuals = SetupStep.entries.map { guidanceFor(it).visual }
        assertEquals(SetupStep.entries.size, visuals.distinct().size)
    }

    private fun assertContainsAll(text: String, vararg phrases: String) {
        phrases.forEach { phrase -> assertTrue(phrase in text, "Missing '$phrase' in: $text") }
    }
}
