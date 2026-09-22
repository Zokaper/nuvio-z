package com.nuvio.z.iossetup

import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

const val STABLE_SOURCE_URL = "https://raw.githubusercontent.com/Zokaper/nuvio-z/main/distribution/sidestore/source.json"
const val DEVELOPER_SOURCE_URL = "https://raw.githubusercontent.com/Zokaper/nuvio-z/main/distribution/sidestore/source-debug.json"

@Serializable
enum class SetupChannel(val appName: String, val bundleId: String, val sourceUrl: String) {
    STABLE("Nuvio Z", "com.nuvio.app.z", STABLE_SOURCE_URL),
    DEVELOPER("Nuvio Z Debug", "com.nuvio.app.z.debug", DEVELOPER_SOURCE_URL),
}

@Serializable
enum class SetupStep(val title: String, val manual: Boolean = false) {
    WELCOME("Welcome"),
    COMPUTER_CHECK("Computer check"),
    APPLE_DEVICE_SUPPORT("Apple device support"),
    CONNECT_IPHONE("Connect iPhone"),
    LOCAL_DEV_VPN("Install LocalDevVPN", true),
    ILOADER_INSTALL("Install iloader"),
    SIDESTORE_INSTALL("Install SideStore", true),
    PAIRING("Pair SideStore", true),
    TRUST_PROFILE("Trust the app", true),
    DEVELOPER_MODE("Enable Developer Mode", true),
    SIDESTORE_PRIME("Open and sign into SideStore", true),
    ADD_SOURCE("Add Nuvio Z source", true),
    INSTALL_NUVIO("Install Nuvio Z", true),
    FINISH("Finish and verify"),
}

@Serializable
data class SetupState(
    val schemaVersion: Int = 1,
    val currentStep: SetupStep = SetupStep.WELCOME,
    val completedSteps: Set<SetupStep> = emptySet(),
    val channel: SetupChannel = SetupChannel.STABLE,
    val developerWarningAccepted: Boolean = false,
    val manualConfirmations: Set<SetupStep> = emptySet(),
    val repairMode: Boolean = false,
    val advancedDeviceOverride: Boolean = false,
) {
    val sourceUrl: String get() = channel.sourceUrl
    val sourceDeepLink: String get() = "sidestore://source?url=" +
        URLEncoder.encode(sourceUrl, StandardCharsets.UTF_8).replace("+", "%20")
}

enum class CheckState { CHECKING, PASS, ACTION, FAIL }

data class CheckResult(val label: String, val state: CheckState, val detail: String = "")

data class ComputerCheck(
    val supportedOs: CheckResult,
    val internet: CheckResult,
    val appleSupport: CheckResult,
    val appleService: CheckResult?,
) {
    val canContinue: Boolean get() = supportedOs.state == CheckState.PASS &&
        internet.state == CheckState.PASS && appleSupport.state == CheckState.PASS &&
        (appleService == null || appleService.state == CheckState.PASS)
}

data class OperationResult(
    val success: Boolean,
    val message: String,
    val exitCode: Int? = null,
    val details: String = "",
)
