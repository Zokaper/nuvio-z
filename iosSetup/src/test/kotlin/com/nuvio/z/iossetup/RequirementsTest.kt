package com.nuvio.z.iossetup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequirementsTest {
    private val goodComputer = ComputerCheck(
        CheckResult("os", CheckState.PASS), CheckResult("net", CheckState.PASS), CheckResult("apple", CheckState.PASS), null,
    )
    private val humanSteps = setOf(Requirement.PROFILE_TRUST, Requirement.NOTIFICATIONS, Requirement.SIDESTORE_READY, Requirement.SOURCE_ADDED)

    /** A real `nuvioz-device-helper status` line from a fully set-up iPhone (team id masked). */
    private fun readyLine() = javaClass.getResource("/helper-status-ready.json")!!.readText()

    private fun ready(): HelperStatus = assertNotNull(HelperStatus.parse(readyLine()))

    private fun device(transform: (DeviceSnapshot) -> DeviceSnapshot): HelperStatus =
        ready().let { it.copy(device = transform(it.device!!)) }

    private fun evaluate(
        helper: HelperStatus?, channel: SetupChannel = SetupChannel.DEVELOPER,
        confirmed: Set<Requirement> = emptySet(), computer: ComputerCheck? = goodComputer,
    ) = Requirements.evaluate(WorldSnapshot(computer, helper), channel, confirmed)

    private fun List<RequirementResult>.status(r: Requirement) = first { it.requirement == r }.status

    @Test fun realHelperOutputParses() {
        val status = ready()
        val device = assertNotNull(status.device)
        assertEquals(TrustState.VALID, device.trust)
        assertEquals(true, device.developerMode)
        assertEquals("1.3.0", device.localDevVpn?.version)
        assertNull(device.nuvioStable)
        assertEquals(PairingFileState.PRESENT, device.pairingFile)
        assertTrue(device.appsKnown)
    }

    @Test fun fullySetUpPhoneNeedsOnlyTheHumanAnswers() {
        val results = evaluate(ready())
        assertEquals(humanSteps.toList(), results.filter { !it.done }.map { it.requirement })
        assertEquals(RequirementStatus.SATISFIED, results.status(Requirement.NUVIO))
    }

    @Test fun allConfirmedMeansNothingLeftToDo() {
        assertNull(Requirements.focus(evaluate(ready(), confirmed = humanSteps)))
    }

    @Test fun stableChannelLooksForTheStableApp() {
        val results = evaluate(ready(), channel = SetupChannel.STABLE)
        assertEquals(RequirementStatus.MISSING, results.status(Requirement.NUVIO))
    }

    @Test fun noPhoneBlocksEverythingThatNeedsOne() {
        val results = evaluate(ready().copy(device = null, usbDeviceCount = 0))
        assertEquals(RequirementStatus.MISSING, results.status(Requirement.DEVICE))
        listOf(Requirement.TRUST, Requirement.SIDESTORE, Requirement.PAIRING, Requirement.DEVELOPER_MODE, Requirement.NUVIO)
            .forEach { assertEquals(RequirementStatus.BLOCKED, results.status(it), it.name) }
        assertEquals(Requirement.DEVICE, Requirements.focus(results)?.requirement)
    }

    @Test fun untrustedPhoneFocusesOnTrustAndHidesGuesses() {
        val results = evaluate(device { it.copy(trust = TrustState.INVALID) })
        assertEquals(Requirement.TRUST, Requirements.focus(results)?.requirement)
        assertEquals(RequirementStatus.BLOCKED, results.status(Requirement.SIDESTORE))
    }

    @Test fun missingPairingFileIsTheFocusOnceSideStoreExists() {
        val results = evaluate(device { it.copy(pairingFile = PairingFileState.ABSENT) })
        assertEquals(Requirement.PAIRING, Requirements.focus(results)?.requirement)
    }

    @Test fun missingSideStoreBlocksPairingInsteadOfBlamingIt() {
        val results = evaluate(device { it.copy(sidestore = null, pairingFile = PairingFileState.SIDESTORE_MISSING) })
        assertEquals(Requirement.SIDESTORE, Requirements.focus(results)?.requirement)
        assertEquals(RequirementStatus.BLOCKED, results.status(Requirement.PAIRING))
    }

    @Test fun missingLoopbackAppComesBeforeSideStore() {
        val results = evaluate(device { it.copy(localDevVpn = null, sidestore = null) })
        assertEquals(Requirement.LOOPBACK_APP, Requirements.focus(results)?.requirement)
    }

    @Test fun developerModeOffIsMissingAndUnreadableIsUnknown() {
        assertEquals(RequirementStatus.MISSING, evaluate(device { it.copy(developerMode = false) }).status(Requirement.DEVELOPER_MODE))
        assertEquals(RequirementStatus.UNKNOWN, evaluate(device { it.copy(developerMode = null) }).status(Requirement.DEVELOPER_MODE))
    }

    @Test fun confirmationStandsInOnlyForWhatTheComputerCannotDecide() {
        val vouched = evaluate(device { it.copy(developerMode = null) }, confirmed = setOf(Requirement.DEVELOPER_MODE))
        assertEquals(RequirementStatus.SATISFIED, vouched.status(Requirement.DEVELOPER_MODE))
        assertTrue(vouched.first { it.requirement == Requirement.DEVELOPER_MODE }.confirmedByUser)

        // A stale "I did it" must never override a probe that says it is not so.
        val off = evaluate(device { it.copy(developerMode = false) }, confirmed = setOf(Requirement.DEVELOPER_MODE))
        assertEquals(RequirementStatus.MISSING, off.status(Requirement.DEVELOPER_MODE))
    }

    @Test fun unreadableAppListFallsBackToConfirmationNotAbsence() {
        val results = evaluate(device { it.copy(appsKnown = false, sidestore = null, localDevVpn = null, nuvioDebug = null) })
        assertEquals(RequirementStatus.UNKNOWN, results.status(Requirement.SIDESTORE))
        assertEquals(RequirementStatus.UNKNOWN, results.status(Requirement.LOOPBACK_APP))
    }

    @Test fun humanOnlyStepsStayBlockedUntilSideStoreExists() {
        val results = evaluate(device { it.copy(sidestore = null, pairingFile = PairingFileState.SIDESTORE_MISSING) })
        assertEquals(RequirementStatus.BLOCKED, results.status(Requirement.PROFILE_TRUST))
    }

    @Test fun reconcileDropsHumanAnswersWhenSideStoreWasDeleted() {
        val confirmed = setOf(Requirement.PROFILE_TRUST, Requirement.SOURCE_ADDED, Requirement.DEVELOPER_MODE)
        val gone = evaluate(device { it.copy(sidestore = null, pairingFile = PairingFileState.SIDESTORE_MISSING) }, confirmed = confirmed)
        assertEquals(setOf(Requirement.DEVELOPER_MODE), Requirements.reconcile(confirmed, gone))
        assertEquals(confirmed, Requirements.reconcile(confirmed, evaluate(ready(), confirmed = confirmed)))
    }

    @Test fun unprobedComputerAndHelperAreUnknownNotFailed() {
        val results = evaluate(null, computer = null)
        assertEquals(RequirementStatus.UNKNOWN, results.status(Requirement.COMPUTER))
        assertEquals(RequirementStatus.UNKNOWN, results.status(Requirement.DEVICE))
    }

    @Test fun missingAppleDriverIsConfirmableButAnUnsupportedOsIsNot() {
        val noDriver = goodComputer.copy(appleSupport = CheckResult("apple", CheckState.ACTION))
        assertEquals(RequirementStatus.UNKNOWN, evaluate(ready(), computer = noDriver).status(Requirement.COMPUTER))
        val vouched = evaluate(ready(), computer = noDriver, confirmed = setOf(Requirement.COMPUTER))
        assertEquals(RequirementStatus.SATISFIED, vouched.status(Requirement.COMPUTER))
        val offline = goodComputer.copy(internet = CheckResult("net", CheckState.FAIL))
        assertEquals(RequirementStatus.MISSING, evaluate(ready(), computer = offline, confirmed = setOf(Requirement.COMPUTER)).status(Requirement.COMPUTER))
    }

    @Test fun unsupportedComputerIsTheFirstFocus() {
        val bad = goodComputer.copy(supportedOs = CheckResult("os", CheckState.FAIL))
        assertEquals(Requirement.COMPUTER, Requirements.focus(evaluate(ready(), computer = bad))?.requirement)
    }

    @Test fun requirementOrderMatchesTheUserJourney() {
        assertEquals(
            listOf(
                "COMPUTER", "DEVICE", "TRUST", "LOOPBACK_APP", "SIDESTORE", "PAIRING",
                "DEVELOPER_MODE", "PROFILE_TRUST", "NOTIFICATIONS", "SIDESTORE_READY", "SOURCE_ADDED", "NUVIO",
            ),
            Requirement.entries.map { it.name },
        )
        assertEquals(Requirement.entries, evaluate(ready()).map { it.requirement })
    }

    @Test fun unknownProtocolAndGarbageAreRejected() {
        assertNull(HelperStatus.parse(readyLine().replace("\"protocol\":1", "\"protocol\":99")))
        assertNull(HelperStatus.parse("not json"))
        assertNull(HelperStatus.parse(""))
    }

    @Test fun errorsExplainWhyInPlainLanguage() {
        assertEquals(ErrorCode.HELPER_UNAVAILABLE, SetupError.from(null)?.code)
        assertEquals(ErrorCode.TRANSPORT_DOWN, SetupError.from(ready().copy(usbmuxdReachable = false))?.code)
        assertEquals(ErrorCode.NO_DEVICE, SetupError.from(ready().copy(device = null))?.code)
        assertEquals(ErrorCode.TRUST_PENDING, SetupError.from(device { it.copy(trust = TrustState.INVALID) })?.code)
        assertEquals(ErrorCode.PROBE_FAILED, SetupError.from(ready().copy(errors = mapOf("apps" to "timeout")))?.code)
        assertNull(SetupError.from(ready()))
    }

    @Test fun macAndWindowsRecoveryNeverCrossOver() {
        fun text(isMac: Boolean) = SetupError(ErrorCode.TRANSPORT_DOWN).recovery(isMac).flatMap { it.recovery + it.problem }.joinToString(" ")
        assertFalse(text(true).contains("Windows", true) || text(true).contains("iTunes", true))
        assertTrue(text(false).contains("Apple Mobile Device Service"))
        assertTrue(SetupError(ErrorCode.NO_DEVICE).recovery(true).flatMap { it.recovery }.any { it.contains("Apple silicon") })
        assertFalse(SetupError(ErrorCode.NO_DEVICE).recovery(false).flatMap { it.recovery }.any { it.contains("Apple silicon") })
    }

    @Test fun v1ProgressMigratesKeepingOnlyHumanAnswers() {
        val old = SetupState(
            currentStep = SetupStep.INSTALL_NUVIO,
            channel = SetupChannel.DEVELOPER, developerWarningAccepted = true,
            manualConfirmations = setOf(SetupStep.LOCAL_DEV_VPN, SetupStep.PAIRING, SetupStep.TRUST_PROFILE, SetupStep.ADD_SOURCE),
            repairMode = true,
        )
        val text = kotlinx.serialization.json.Json.encodeToString(SetupState.serializer(), old)
        val migrated = assertNotNull(SetupProgress.decode(text))
        assertEquals(SetupProgress.SCHEMA_VERSION, migrated.schemaVersion)
        assertEquals(setOf(Requirement.PROFILE_TRUST, Requirement.SOURCE_ADDED), migrated.confirmed)
        assertEquals(SetupChannel.DEVELOPER, migrated.channel)
        assertTrue(migrated.developerWarningAccepted && migrated.repairMode)
    }

    @Test fun v2ProgressRoundTripsAndHoldsNoCredentialFields() {
        val progress = SetupProgress(channel = SetupChannel.DEVELOPER, developerWarningAccepted = true)
            .confirm(Requirement.PROFILE_TRUST).confirm(Requirement.SOURCE_ADDED).confirm(Requirement.SOURCE_ADDED, false)
        val text = SetupProgress.encode(progress)
        assertEquals(progress, SetupProgress.decode(text))
        listOf("password", "token", "secret", "apple", "udid", "email").forEach {
            assertFalse(text.contains(it, ignoreCase = true), "state file mentions '$it'")
        }
    }

    @Test fun corruptProgressIsIgnoredNotFatal() {
        assertNull(SetupProgress.decode("{ broken"))
        assertNull(SetupProgress.decode(""))
    }
}
