package com.nuvio.z.iossetup

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SetupControllerTest {
    @Test fun wingetInstalledPackageIsAnAppleSupportSignal() {
        assertTrue(appleSupportDetected(registryOrDriver = false, serviceInstalled = false, wingetInstalled = true))
        assertFalse(appleSupportDetected(registryOrDriver = false, serviceInstalled = false, wingetInstalled = false))
    }

    @Test fun explicitAppleSupportConfirmationLeavesUsbDetectionAsNextGate() {
        val controller = at(SetupStep.APPLE_DEVICE_SUPPORT)
        controller.confirmAppleSupportInstalled()
        assertTrue(controller.state.appleSupportConfirmed)
        assertTrue(controller.advance(autoVerified = true))
        assertEquals(SetupStep.CONNECT_IPHONE, controller.state.currentStep)
        assertFalse(controller.advance(autoVerified = false))
    }

    @Test fun installedAppleSupportDoesNotBlockOnOneServiceState() {
        val check = ComputerCheck(
            supportedOs = CheckResult("Windows", CheckState.PASS),
            internet = CheckResult("Internet", CheckState.PASS),
            appleSupport = CheckResult("Apple support", CheckState.PASS),
            appleService = CheckResult("Apple service", CheckState.ACTION, "Installed but stopped"),
        )
        assertTrue(check.canContinue)
    }

    @Test fun inconclusiveInternetProbeDoesNotTrapUserOnComputerCheck() {
        val check = ComputerCheck(
            supportedOs = CheckResult("Windows", CheckState.PASS),
            internet = CheckResult("Internet", CheckState.ACTION, "Could not verify"),
            appleSupport = CheckResult("Apple support", CheckState.PASS),
            appleService = null,
        )
        assertTrue(check.canContinue)
    }

    @Test fun unsupportedComputerStillCannotContinue() {
        val check = ComputerCheck(
            supportedOs = CheckResult("Windows", CheckState.FAIL),
            internet = CheckResult("Internet", CheckState.ACTION),
            appleSupport = CheckResult("Apple support", CheckState.PASS),
            appleService = null,
        )
        assertFalse(check.canContinue)
    }

    @Test fun cannotAdvancePastIphoneWithoutDetectionOrExplicitOverride() {
        val controller = at(SetupStep.CONNECT_IPHONE)
        assertFalse(controller.advance(autoVerified = false))
        controller.setDeviceOverride(true)
        assertTrue(controller.advance(autoVerified = false))
    }

    @Test fun iloaderProcessExitCannotAdvanceInstallerOrSideStoreStep() {
        val install = at(SetupStep.ILOADER_INSTALL)
        assertFalse(install.advance(autoVerified = false))
        val sideStore = at(SetupStep.SIDESTORE_INSTALL)
        assertFalse(sideStore.advance(autoVerified = true), "Unrelated process verification cannot satisfy a manual step")
    }

    @Test fun sideStoreInstallRequiresExplicitConfirmation() {
        val controller = at(SetupStep.SIDESTORE_INSTALL)
        assertFalse(controller.advance())
        controller.confirmCurrent(true)
        assertTrue(controller.advance())
        assertEquals(SetupStep.PAIRING, controller.state.currentStep)
    }

    @Test fun pairingCannotBeSkippedSilently() {
        val controller = at(SetupStep.PAIRING)
        assertFalse(controller.advance())
        controller.confirmCurrent(true)
        assertTrue(controller.advance())
    }

    @Test fun developerModeStateSurvivesSaveAndResume() {
        val dir = kotlin.io.path.createTempDirectory("nuvio-setup-test")
        val store = ProgressStore(dir.resolve("state.json"))
        val first = SetupController(SetupState(currentStep = SetupStep.DEVELOPER_MODE), store, false)
        first.confirmCurrent(true)
        val resumed = SetupController(store.load()!!, store, false)
        assertEquals(SetupStep.DEVELOPER_MODE, resumed.state.currentStep)
        assertTrue(SetupStep.DEVELOPER_MODE in resumed.state.manualConfirmations)
    }

    @Test fun stableAndDeveloperSourcesAreIsolated() {
        assertEquals(STABLE_SOURCE_URL, SetupState(channel = SetupChannel.STABLE).sourceUrl)
        assertEquals(DEVELOPER_SOURCE_URL, SetupState(channel = SetupChannel.DEVELOPER, developerWarningAccepted = true).sourceUrl)
    }

    @Test fun debugModeRequiresDeliberateConfirmation() {
        val controller = SetupController(isMac = false)
        assertFalse(controller.setChannel(SetupChannel.DEVELOPER, warningAccepted = false))
        assertEquals(SetupChannel.STABLE, controller.state.channel)
        assertTrue(controller.setChannel(SetupChannel.DEVELOPER, warningAccepted = true))
    }

    @Test fun qrPayloadDecodesExactlyToTheDeepLink() {
        val payload = SetupState().sourceDeepLink
        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(QrCode.buffered(payload)))))
        assertEquals(payload, decoded.text)
    }

    @Test fun persistedStateContainsNoCredentialFieldsOrValues() {
        val serialized = ProgressStore(kotlin.io.path.createTempDirectory().resolve("state.json")).serialized(
            SetupState(currentStep = SetupStep.SIDESTORE_PRIME)
        ).lowercase()
        listOf("password", "appleid", "apple_id", "2fa", "token", "secret", "credential").forEach { forbidden ->
            assertFalse(forbidden in serialized, "Persisted state included forbidden field $forbidden")
        }
    }

    @Test fun repairPairingCanBeEnteredFromAnyRelevantErrorPath() {
        val controller = at(SetupStep.SIDESTORE_PRIME)
        controller.enterRepairPairing()
        assertEquals(SetupStep.PAIRING, controller.state.currentStep)
        assertTrue(controller.state.repairMode)
    }

    @Test fun backAndResumeKeepConfirmationsConsistent() {
        val controller = at(SetupStep.SIDESTORE_INSTALL)
        controller.confirmCurrent(true)
        assertTrue(controller.advance())
        assertTrue(controller.back())
        assertEquals(SetupStep.SIDESTORE_INSTALL, controller.state.currentStep)
        assertTrue(SetupStep.SIDESTORE_INSTALL in controller.state.manualConfirmations)
        assertTrue(controller.advance())
        assertEquals(SetupStep.PAIRING, controller.state.currentStep)
    }

    private fun at(step: SetupStep) = SetupController(SetupState(currentStep = step), isMac = false)
}
