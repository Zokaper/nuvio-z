package com.nuvio.z.iossetup

enum class SetupPhase(val title: String) {
    GET_READY("Get ready"),
    CONNECT("Connect iPhone"),
    SET_UP_SIDESTORE("Set up SideStore"),
    INSTALL_NUVIO("Install Nuvio Z"),
    FINISH("Finish"),
}

fun phaseFor(step: SetupStep): SetupPhase = when (step) {
    SetupStep.WELCOME, SetupStep.COMPUTER_CHECK, SetupStep.APPLE_DEVICE_SUPPORT -> SetupPhase.GET_READY
    SetupStep.CONNECT_IPHONE, SetupStep.LOCAL_DEV_VPN -> SetupPhase.CONNECT
    SetupStep.ILOADER_INSTALL, SetupStep.SIDESTORE_INSTALL, SetupStep.PAIRING,
    SetupStep.TRUST_PROFILE, SetupStep.DEVELOPER_MODE, SetupStep.SIDESTORE_PRIME -> SetupPhase.SET_UP_SIDESTORE
    SetupStep.ADD_SOURCE, SetupStep.INSTALL_NUVIO -> SetupPhase.INSTALL_NUVIO
    SetupStep.FINISH -> SetupPhase.FINISH
}

data class TroubleTip(
    val problem: String,
    val recovery: List<String>,
)

data class StepGuidance(
    val purpose: String,
    val success: String,
    val troubleshooting: List<TroubleTip> = emptyList(),
)

fun guidanceFor(step: SetupStep, state: SetupState = SetupState()): StepGuidance {
    val app = state.channel.appName
    return when (step) {
        SetupStep.WELCOME -> StepGuidance(
            purpose = "We’ll prepare your computer and iPhone, install SideStore, then use it to install $app. No technical knowledge is expected.",
            success = "You know what the setup will do and have your iPhone, cable, and Apple Account ready.",
        )
        SetupStep.COMPUTER_CHECK -> StepGuidance(
            purpose = "First, we check that this computer can download the official tools and communicate with an iPhone.",
            success = "The required checks show green and Continue becomes available.",
            troubleshooting = listOf(
                TroubleTip("The internet check fails", listOf("Confirm a web page opens in your browser.", "Temporarily allow this app through a firewall or VPN, then choose Check again.")),
                TroubleTip("This computer is unsupported", listOf("Use a 64-bit Windows PC or a supported Mac.", "You can move this portable app to another computer and resume there.")),
            ),
        )
        SetupStep.APPLE_DEVICE_SUPPORT -> StepGuidance(
            purpose = "Windows needs Apple’s device drivers before iloader can see and securely talk to your iPhone.",
            success = "Apple device support is detected. The next step will verify the real USB connection.",
            troubleshooting = listOf(
                TroubleTip("Apple Devices or iTunes is already installed", listOf("Choose the already-installed button, then verify the iPhone on the next page.", "If USB detection fails there, return and run the recommended Apple installer.")),
                TroubleTip("The installer closes without helping", listOf("Restart Windows.", "Open Apple Devices or iTunes once, reconnect the unlocked iPhone, then choose Check again.")),
            ),
        )
        SetupStep.CONNECT_IPHONE -> StepGuidance(
            purpose = "A trusted USB connection lets iloader install SideStore and create the small pairing record SideStore needs.",
            success = "Both ‘iPhone detected’ and ‘Apple device communication is ready’ are green.",
            troubleshooting = listOf(
                TroubleTip("The iPhone is not detected", listOf("Unlock the phone and keep its screen on.", "Try another USB port and a data-capable cable; some cables charge only.", "Disconnect USB hubs or adapters if possible, then choose Check again.")),
                TroubleTip("‘Trust This Computer?’ did not appear", listOf("Unplug and reconnect while the iPhone is unlocked.", "Open Apple Devices or iTunes once.", "If you previously tapped Don’t Trust, reset Location & Privacy in iPhone Settings, then reconnect.")),
                TroubleTip("Windows sees the phone, but communication is not ready", listOf("Use Repair with Apple’s desktop installer first.", "If it remains blocked, restart Apple Mobile Device Service or restart Windows.")),
            ),
        )
        SetupStep.LOCAL_DEV_VPN -> StepGuidance(
            purpose = "LocalDevVPN gives SideStore a private on-device connection for signing, installing, and refreshing apps. It does not route your normal internet traffic like a commercial VPN.",
            success = "LocalDevVPN shows Connected while the iPhone is also on Wi-Fi.",
            troubleshooting = listOf(
                TroubleTip("LocalDevVPN will not connect", listOf("Open LocalDevVPN and accept the iOS ‘Add VPN Configurations’ prompt.", "Confirm Wi-Fi is on; cellular alone is not enough for this setup.", "If another VPN is active, disconnect it temporarily and try again.")),
                TroubleTip("The VPN option is missing", listOf("Delete and reinstall LocalDevVPN from the App Store, then accept the configuration prompt again.")),
            ),
        )
        SetupStep.ILOADER_INSTALL -> StepGuidance(
            purpose = "iloader is the official desktop helper used for the one-time SideStore install and for placing its pairing record.",
            success = "This page says ‘iloader installed’ and can open it.",
            troubleshooting = listOf(
                TroubleTip("The installer opened, but I’m unsure what to do", listOf("Complete the installer using its default choices.", "Return here and choose Check again. Do not use iloader yet—the next page explains exactly what to select.")),
                TroubleTip("iloader is not found after installation", listOf("Finish or close the installer, then choose Check again.", "If needed, restart this portable setup app; your progress is saved.")),
            ),
        )
        SetupStep.SIDESTORE_INSTALL -> StepGuidance(
            purpose = "Now iloader signs SideStore with your Apple Account and puts the SideStore app on your iPhone.",
            success = "You can see the SideStore icon on the iPhone Home Screen.",
            troubleshooting = listOf(
                TroubleTip("My iPhone is not listed in iloader", listOf("Keep the phone unlocked and connected with a data-capable cable.", "Accept Trust This Computer, then close and reopen iloader.", "Return to Connect iPhone if this app did not show both checks in green.")),
                TroubleTip("iloader opened, but I don’t know what to choose", listOf("Select your iPhone.", "Sign in and complete Apple’s verification if asked.", "Choose Install SideStore (Stable)—not a beta option.")),
                TroubleTip("iloader says install succeeded, but SideStore is missing", listOf("Search the iPhone App Library for SideStore.", "Keep the phone unlocked and run Install SideStore (Stable) again.")),
            ),
        )
        SetupStep.PAIRING -> StepGuidance(
            purpose = "A pairing record is a small trust file created for this iPhone. SideStore uses it to refresh apps without keeping the USB cable attached.",
            success = "iloader shows the green ‘Pairing file placed successfully!’ message for SideStore.",
            troubleshooting = listOf(
                TroubleTip("SideStore installed, but pairing was not placed", listOf("Keep the iPhone connected and unlocked.", "In iloader choose Manage Pairing File, then Place beside SideStore.")),
                TroubleTip("SideStore asks to replace pairing or use iloader", listOf("Choose Repair pairing on this page.", "Follow Reset Pairing File → Delete Stored Pairing → Place, then retry SideStore.")),
                TroubleTip("The iPhone disappears from iloader", listOf("Reconnect it, unlock it, and accept Trust if asked.", "Close and reopen iloader after Windows can see the phone.")),
            ),
        )
        SetupStep.TRUST_PROFILE -> StepGuidance(
            purpose = "Because SideStore was signed with your Apple Account instead of the App Store, iOS asks you to explicitly trust that developer profile.",
            success = "The profile for your Apple Account shows as trusted in VPN & Device Management.",
            troubleshooting = listOf(
                TroubleTip("I cannot find the developer profile", listOf("Confirm SideStore is visible on the Home Screen first.", "Open SideStore once, note the message, then return to VPN & Device Management.")),
                TroubleTip("SideStore says Untrusted Developer", listOf("Open the profile named after the Apple Account used in iloader.", "Tap Trust and confirm while the iPhone has internet access.")),
                TroubleTip("SideStore still will not open", listOf("Complete Developer Mode on the next page if iOS requests it.", "If the profile is no longer present, reinstall SideStore with iloader.")),
            ),
        )
        SetupStep.DEVELOPER_MODE -> StepGuidance(
            purpose = "iOS 16 and later requires Developer Mode before it can run apps installed outside the App Store.",
            success = "After the required restart, Developer Mode is on and you confirmed Turn On.",
            troubleshooting = listOf(
                TroubleTip("Developer Mode is missing", listOf("Try to open SideStore once after installing and trusting it.", "Reconnect the iPhone to iloader, then check Settings → Privacy & Security again.")),
                TroubleTip("The iPhone restarted—what now?", listOf("Unlock it.", "Tap Turn On in the Developer Mode prompt and enter the iPhone passcode.")),
                TroubleTip("SideStore still will not open", listOf("Confirm both the developer profile and Developer Mode are enabled.", "Restart the iPhone once more, then open SideStore.")),
            ),
        )
        SetupStep.SIDESTORE_PRIME -> StepGuidance(
            purpose = "The first refresh proves that SideStore, LocalDevVPN, your Apple sign-in, and the pairing record all work together.",
            success = "SideStore refreshes itself and returns without a pairing or Developer Portal error.",
            troubleshooting = listOf(
                TroubleTip("LocalDevVPN is not connected", listOf("Leave SideStore, connect LocalDevVPN, confirm Wi-Fi is on, then retry the refresh.")),
                TroubleTip("SideStore asks to replace pairing or use iloader", listOf("Choose Repair SideStore pairing below.", "Reconnect USB and place a fresh pairing record with iloader.")),
                TroubleTip("Refresh or signing fails", listOf("Confirm you used the same Apple Account in SideStore and iloader.", "Accept certificate prompts such as Revoke or Refresh Now.", "Wait a minute and retry with LocalDevVPN connected.")),
            ),
        )
        SetupStep.ADD_SOURCE -> StepGuidance(
            purpose = "A SideStore source is simply a catalog address. Adding Nuvio Z’s official source makes the app appear in SideStore.",
            success = "The $app source and its app listing appear in SideStore.",
            troubleshooting = listOf(
                TroubleTip("The QR or deep link does nothing", listOf("Use Copy source URL on this page.", "In SideStore open Sources, tap +, paste the URL, and tap Add.")),
                TroubleTip("SideStore says the source is invalid", listOf("Copy the URL again instead of typing it.", "Check internet access, then remove the failed source and add it again.")),
                TroubleTip("The source was added, but no app appears", listOf("Pull to refresh the Sources or Browse page.", "Close and reopen SideStore with LocalDevVPN connected.")),
            ),
        )
        SetupStep.INSTALL_NUVIO -> StepGuidance(
            purpose = "SideStore can now download, sign, and install $app from the source you added.",
            success = "$app appears on the iPhone Home Screen and opens.",
            troubleshooting = listOf(
                TroubleTip("Install or refresh fails", listOf("Confirm Wi-Fi and LocalDevVPN are connected.", "Refresh SideStore itself in My Apps, then retry $app.", "If SideStore mentions pairing, return to Repair SideStore pairing.")),
                TroubleTip("SideStore asks about app extensions", listOf("Choose Keep app extensions (use main profile). This preserves the Downloads widget without using another app slot.")),
                TroubleTip("The app appears but will not open", listOf("Recheck the trusted developer profile and Developer Mode.", "Restart the iPhone, then try again.")),
            ),
        )
        SetupStep.FINISH -> StepGuidance(
            purpose = "$app is installed. SideStore will keep it signed as long as you refresh within the seven-day window.",
            success = "A wireless Refresh All completes with USB unplugged.",
            troubleshooting = listOf(
                TroubleTip("A future refresh fails", listOf("Connect LocalDevVPN and retry on Wi-Fi.", "If SideStore names a pairing problem, use Settings → Repair SideStore in this wizard.")),
                TroubleTip("An app is close to expiring", listOf("Open SideStore → My Apps and tap Refresh All before the counter reaches zero.")),
            ),
        )
    }
}

fun confirmationText(step: SetupStep, state: SetupState): String = when (step) {
    SetupStep.LOCAL_DEV_VPN -> "I see Connected in LocalDevVPN"
    SetupStep.SIDESTORE_INSTALL -> "I see SideStore on my Home Screen"
    SetupStep.PAIRING -> "I saw ‘Pairing file placed successfully!’ in iloader"
    SetupStep.TRUST_PROFILE -> "I trusted the developer profile"
    SetupStep.DEVELOPER_MODE -> "Developer Mode is enabled after the restart"
    SetupStep.SIDESTORE_PRIME -> "SideStore completed its first refresh"
    SetupStep.ADD_SOURCE -> "I see the ${state.channel.appName} source in SideStore"
    SetupStep.INSTALL_NUVIO -> "I see ${state.channel.appName} on my Home Screen"
    else -> "I completed this step"
}
