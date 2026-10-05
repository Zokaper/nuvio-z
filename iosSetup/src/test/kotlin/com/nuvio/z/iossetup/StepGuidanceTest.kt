package com.nuvio.z.iossetup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StepGuidanceTest {
    @Test fun firstRefreshHelpNamesTheNotificationPermissionFailure() {
        val tips = guidanceFor(SetupStep.SIDESTORE_PRIME).troubleshooting
        val tip = tips.first()
        kotlin.test.assertTrue(tip.problem.contains("could not save notification"))
        kotlin.test.assertTrue(tip.recovery.any { it.contains("Settings → Notifications → SideStore") })
    }

    @Test fun everyStepHasPurposeSuccessVisualAndTroubleshootingWhereNeeded() {
        SetupStep.entries.forEach { step ->
            val guide = guidanceFor(step)
            assertTrue(guide.purpose.length > 30, "$step needs a useful purpose")
            assertTrue(guide.success.length > 20, "$step needs an explicit success state")
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

    @Test fun simplifiedProgressPhasesCoverTheWholeFlowInOrder() {
        val phases = SetupStep.entries.map(::phaseFor)
        assertEquals(SetupPhase.entries, phases.distinct())
        assertEquals(SetupPhase.GET_READY, phaseFor(SetupStep.WELCOME))
        assertEquals(SetupPhase.CONNECT, phaseFor(SetupStep.CONNECT_IPHONE))
        assertEquals(SetupPhase.SET_UP_SIDESTORE, phaseFor(SetupStep.PAIRING))
        assertEquals(SetupPhase.INSTALL_NUVIO, phaseFor(SetupStep.ADD_SOURCE))
        assertEquals(SetupPhase.FINISH, phaseFor(SetupStep.FINISH))
    }

    @Test fun macGuidanceNeverSendsUsersToWindowsTools() {
        SetupStep.entries.filterNot { it == SetupStep.APPLE_DEVICE_SUPPORT }.forEach { step ->
            val text = guidanceFor(step, isMac = true).let { guide -> listOf(guide.purpose, guide.success) + guide.troubleshooting.flatMap { listOf(it.problem) + it.recovery } }
                .joinToString(" ").lowercase()
            listOf("windows", "apple mobile device service", "itunes").forEach { forbidden ->
                assertTrue(forbidden !in text, "$step Mac guidance mentions '$forbidden': $text")
            }
        }
        val connect = guidanceFor(SetupStep.CONNECT_IPHONE, isMac = true).troubleshooting.flatMap { listOf(it.problem) + it.recovery }.joinToString(" ").lowercase()
        assertContainsAll(connect, "not detected", "charge", "unlock", "trust", "allow", "finder", "restart the mac")
    }

    @Test fun finishFramesRefreshAsARecoveryNotAChore() {
        val text = guidanceFor(SetupStep.FINISH).let { listOf(it.purpose, it.success) + it.troubleshooting.flatMap { tip -> listOf(tip.problem) + tip.recovery } }
            .joinToString(" ").lowercase()
        assertTrue("every 5" !in text && "before the counter" !in text, text)
        assertContainsAll(text, "refresh all", "sidestore itself will not open", "install sidestore")
    }

    private fun assertContainsAll(text: String, vararg phrases: String) {
        phrases.forEach { phrase -> assertTrue(phrase in text, "Missing '$phrase' in: $text") }
    }
}
