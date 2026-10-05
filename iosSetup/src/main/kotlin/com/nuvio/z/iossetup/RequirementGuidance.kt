package com.nuvio.z.iossetup

/**
 * The v1 wizard's troubleshooting copy was written per page. Each v2 requirement maps to the page whose
 * help covers the same task, so that hard-won copy (Mac vs Windows, pairing repair, extensions) is reused
 * instead of rewritten.
 */
fun Requirement.legacyStep(): SetupStep = when (this) {
    Requirement.COMPUTER -> SetupStep.COMPUTER_CHECK
    Requirement.DEVICE, Requirement.TRUST -> SetupStep.CONNECT_IPHONE
    Requirement.LOOPBACK_APP -> SetupStep.LOCAL_DEV_VPN
    Requirement.SIDESTORE -> SetupStep.SIDESTORE_INSTALL
    Requirement.PAIRING -> SetupStep.PAIRING
    Requirement.DEVELOPER_MODE -> SetupStep.DEVELOPER_MODE
    Requirement.PROFILE_TRUST -> SetupStep.TRUST_PROFILE
    Requirement.NOTIFICATIONS, Requirement.SIDESTORE_READY -> SetupStep.SIDESTORE_PRIME
    Requirement.SOURCE_ADDED -> SetupStep.ADD_SOURCE
    Requirement.NUVIO -> SetupStep.INSTALL_NUVIO
}

/** Static tips for the requirement, with a state-driven [error] explanation (if any) placed first. */
fun troubleshootingFor(requirement: Requirement, channel: SetupChannel, isMac: Boolean, error: SetupError? = null): List<TroubleTip> {
    val fromError = error?.takeIf { requirement == Requirement.DEVICE || requirement == Requirement.TRUST }?.recovery(isMac).orEmpty()
    val static = guidanceFor(requirement.legacyStep(), SetupState(channel = channel), isMac).troubleshooting
    // The error already covers the connection basics, so drop the static tips that say the same thing.
    return fromError + if (fromError.isEmpty()) static else static.filterNot { tip -> fromError.any { it.problem == tip.problem } }
}

/** What the user is asked to vouch for when the computer cannot check a requirement itself. */
fun confirmationTextFor(requirement: Requirement, channel: SetupChannel): String = when (requirement) {
    Requirement.COMPUTER -> "Apple device support is already installed on this computer"
    Requirement.DEVICE -> "My iPhone is connected by USB and unlocked"
    Requirement.TRUST -> "I tapped Trust on the iPhone"
    Requirement.LOOPBACK_APP -> "LocalDevVPN is installed on my iPhone"
    Requirement.SIDESTORE -> "I see SideStore on my Home Screen"
    Requirement.PAIRING -> "iloader showed ‘Pairing file placed successfully!’"
    Requirement.DEVELOPER_MODE -> "Developer Mode is on after the restart"
    Requirement.PROFILE_TRUST -> "I trusted the developer profile"
    Requirement.NOTIFICATIONS -> "I turned on Allow Notifications for SideStore"
    Requirement.SIDESTORE_READY -> "SideStore signed in and completed its first refresh"
    Requirement.SOURCE_ADDED -> "I see the ${channel.appName} source in SideStore"
    Requirement.NUVIO -> "I see ${channel.appName} on my Home Screen"
}
