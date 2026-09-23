package com.nuvio.z.iossetup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

private val NuvioBlue = Color(0xFF4D8DFF)
private val BlueText = Color(0xFF9FC4FF)
private val Success = Color(0xFF67D99B)
private val Warning = Color(0xFFFFD166)
private val AppBackground = Color(0xFF09111E)
private val RailColor = Color(0xFF0E1828)
private val CardColor = Color(0xFF172337)
private val TextPrimary = Color(0xFFF6F8FC)
private val TextSecondary = Color(0xFFC3CEE0)
private val TextMuted = Color(0xFF94A6BF)

fun main(args: Array<String>) = application {
    val diagnostics = remember { Diagnostics() }
    val store = remember { ProgressStore() }
    val initial = remember { store.load() ?: SetupState() }
    val ops = remember { platformSetupOps(diagnostics) }
    val controller = remember { SetupController(initial, store, ops.isMac) }
    val windowState = rememberWindowState(width = 1440.dp, height = 900.dp)

    Window(onCloseRequest = ::exitApplication, title = "Nuvio Z iOS Setup", state = windowState) {
        LaunchedEffect(Unit) { window.minimumSize = Dimension(1120, 720) }
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = NuvioBlue,
                onPrimary = Color.White,
                primaryContainer = Color(0xFF1C4075),
                onPrimaryContainer = Color.White,
                secondary = BlueText,
                onSecondary = Color(0xFF07111F),
                background = AppBackground,
                onBackground = TextPrimary,
                surface = CardColor,
                onSurface = TextPrimary,
                surfaceVariant = Color(0xFF111C2D),
                onSurfaceVariant = TextSecondary,
            ),
        ) {
            SetupApp(controller, ops, diagnostics, args.contains("--developer"))
        }
    }
}

@Composable
private fun SetupApp(controller: SetupController, ops: PlatformSetupOps, diagnostics: Diagnostics, developerFlag: Boolean) {
    var state by remember { mutableStateOf(controller.state) }
    var check by remember { mutableStateOf<ComputerCheck?>(null) }
    var deviceDetected by remember { mutableStateOf(false) }
    var transportReady by remember { mutableStateOf(ops.isMac) }
    var iloaderDetected by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<OperationResult?>(null) }
    var working by remember { mutableStateOf(false) }
    var workingLabel by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(developerFlag) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showRepair by remember { mutableStateOf(state.repairMode) }
    var showResume by remember { mutableStateOf(state.currentStep != SetupStep.WELCOME) }
    val scope = rememberCoroutineScope()

    fun sync() { state = controller.state; diagnostics.step(state.currentStep) }
    fun runOperation(label: String, block: () -> OperationResult, after: suspend () -> Unit = {}) {
        working = true; workingLabel = label; operation = null
        scope.launch {
            operation = withContext(Dispatchers.IO) { block() }
            after()
            working = false; workingLabel = ""
        }
    }

    LaunchedEffect(state.currentStep) {
        diagnostics.step(state.currentStep)
        operation = null
        if (state.currentStep == SetupStep.COMPUTER_CHECK || state.currentStep == SetupStep.APPLE_DEVICE_SUPPORT) {
            working = true; check = withContext(Dispatchers.IO) { ops.checkComputer() }; working = false
        }
        if (state.currentStep == SetupStep.ILOADER_INSTALL) {
            iloaderDetected = withContext(Dispatchers.IO) { ops.findIloader() != null }
        }
    }
    LaunchedEffect(state.currentStep) {
        if (state.currentStep == SetupStep.CONNECT_IPHONE || state.currentStep == SetupStep.PAIRING) {
            while (true) {
                deviceDetected = withContext(Dispatchers.IO) { ops.isDeviceConnected() }
                transportReady = withContext(Dispatchers.IO) { ops.isDeviceTransportReady() }
                delay(2_000)
            }
        }
    }

    val autoVerified = when (state.currentStep) {
        SetupStep.COMPUTER_CHECK -> check?.let { it.supportedOs.state == CheckState.PASS && it.internet.state != CheckState.FAIL } == true
        SetupStep.APPLE_DEVICE_SUPPORT -> check?.canContinue == true || state.appleSupportConfirmed
        SetupStep.CONNECT_IPHONE -> (deviceDetected && transportReady) || state.advancedDeviceOverride
        SetupStep.ILOADER_INSTALL -> iloaderDetected
        else -> false
    }

    Row(Modifier.fillMaxSize().background(AppBackground)) {
        ProgressRail(state, controller.activeSteps(), Modifier.width(218.dp).fillMaxHeight())
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 32.dp, vertical = 22.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { showDiagnostics = true }) { Text("Help & diagnostics") }
                TextButton(onClick = { showAdvanced = true }) { Text("Advanced settings") }
            }
            StepPage(
                Modifier.weight(1f).fillMaxWidth(), state, check, deviceDetected, transportReady,
                iloaderDetected, operation, working, workingLabel,
                onConfirmed = { controller.confirmCurrent(it); sync() },
                onRepair = { controller.setRepairMode(true); sync(); showRepair = true },
                onInstallApple = {
                    runOperation("Downloading and installing Apple device support…", ops::installAppleDeviceSupport) {
                        workingLabel = "Checking Apple device support again…"
                        check = withContext(Dispatchers.IO) { ops.checkComputer() }
                    }
                },
                onAppleInstalled = { controller.confirmAppleSupportInstalled(); sync() },
                onRecheck = {
                    runOperation("Checking again…", {
                        when (state.currentStep) {
                            SetupStep.COMPUTER_CHECK, SetupStep.APPLE_DEVICE_SUPPORT -> {
                                check = ops.checkComputer(); OperationResult(check?.canContinue == true, "Checks updated.")
                            }
                            SetupStep.CONNECT_IPHONE -> {
                                deviceDetected = ops.isDeviceConnected(); transportReady = ops.isDeviceTransportReady()
                                OperationResult(deviceDetected && transportReady, when {
                                    !deviceDetected -> "No iPhone detected. Open troubleshooting for cable, lock-screen, and trust checks."
                                    !transportReady -> "The iPhone is visible, but Apple device communication is not ready."
                                    else -> "iPhone detected and Apple device communication is ready."
                                })
                            }
                            SetupStep.ILOADER_INSTALL -> {
                                iloaderDetected = ops.findIloader() != null
                                OperationResult(iloaderDetected, if (iloaderDetected) "iloader is installed." else "iloader was not found yet. Finish its installer, then check again.")
                            }
                            else -> OperationResult(true, "Checked.")
                        }
                    })
                },
                onInstallIloader = {
                    runOperation("Downloading and installing the current official iloader…", ops::installIloader) {
                        workingLabel = "Checking for iloader…"
                        iloaderDetected = withContext(Dispatchers.IO) { ops.findIloader() != null }
                    }
                },
                onOpenServices = { runOperation("Opening Windows Services…", ops::openAppleServiceManager) },
                onOpenIloader = { runOperation("Opening iloader…", ops::openIloader) },
            )
            NavigationBar(
                state.currentStep,
                controller.canAdvance(autoVerified) && !working,
                state.currentStep in state.manualConfirmations,
                onBack = { controller.back(); sync() },
                onNext = { controller.advance(autoVerified); sync() },
                onDone = { controller.startOver(); sync() },
            )
        }
    }

    if (showAdvanced) AdvancedDialog(
        state, { showAdvanced = false },
        onStable = { controller.setChannel(SetupChannel.STABLE); sync(); showAdvanced = false },
        onDeveloper = { controller.setChannel(SetupChannel.DEVELOPER, true); sync(); showAdvanced = false },
        onRepair = { controller.enterRepairPairing(); sync(); showAdvanced = false; showRepair = true },
        onOverride = { controller.setDeviceOverride(it); sync() },
        onStartOver = { controller.startOver(); sync(); showAdvanced = false },
    )
    if (showResume) AlertDialog(
        onDismissRequest = {}, title = { Text("Welcome back") },
        text = { Text("Your progress is saved at “${state.currentStep.title}”. Resume there, or start over without undoing anything already installed on your iPhone.") },
        confirmButton = { Button(onClick = { showResume = false }) { Text("Resume setup") } },
        dismissButton = { TextButton(onClick = { controller.startOver(); sync(); showResume = false }) { Text("Start over") } },
    )
    if (showRepair) RepairPairingDialog(deviceDetected, { runOperation("Opening iloader…", ops::openIloader) }) { showRepair = false }
    if (showDiagnostics) DiagnosticsDialog(diagnostics.report(state.currentStep)) { showDiagnostics = false }
}

@Composable
private fun ProgressRail(state: SetupState, steps: List<SetupStep>, modifier: Modifier) {
    val currentIndex = steps.indexOf(state.currentStep)
    val currentPhase = phaseFor(state.currentStep)
    val currentPhaseIndex = SetupPhase.entries.indexOf(currentPhase)
    Column(modifier.background(RailColor).padding(horizontal = 20.dp, vertical = 24.dp)) {
        Text("Nuvio Z", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("iPhone setup", color = TextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(26.dp))
        Column(Modifier.weight(1f)) {
            SetupPhase.entries.forEachIndexed { index, phase ->
                val completed = index < currentPhaseIndex
                val current = phase == currentPhase
                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(99.dp), color = when { completed -> Color(0xFF25895B); current -> Color(0xFF245EAF); else -> Color(0xFF28384D) }) {
                        Box(Modifier.size(27.dp), contentAlignment = Alignment.Center) {
                            Text(if (completed) "✓" else "${index + 1}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(phase.title, color = if (current || completed) Color.White else TextMuted, fontSize = 13.sp, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("CURRENT STEP", color = BlueText, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text("${currentIndex + 1} of ${steps.size}", color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
            Text(state.currentStep.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 3.dp))
        }
        Text("Progress saves automatically", color = TextMuted, fontSize = 11.sp)
    }
}

@Composable
private fun StepPage(
    modifier: Modifier, state: SetupState, check: ComputerCheck?, device: Boolean, transport: Boolean,
    iloader: Boolean, operation: OperationResult?, working: Boolean, workingLabel: String,
    onConfirmed: (Boolean) -> Unit, onRepair: () -> Unit, onInstallApple: () -> Unit,
    onAppleInstalled: () -> Unit, onRecheck: () -> Unit, onInstallIloader: () -> Unit,
    onOpenServices: () -> Unit, onOpenIloader: () -> Unit,
) {
    val step = state.currentStep
    val guide = guidanceFor(step, state)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(end = 4.dp)) {
            Text("STEP ${step.ordinal + 1} OF ${SetupStep.entries.size}  ·  ${phaseFor(step).title.uppercase()}", color = BlueText, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(9.dp))
            Text(pageTitle(step, state), fontSize = 31.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(Modifier.height(8.dp))
            Text(guide.purpose, color = TextSecondary, fontSize = 15.sp, lineHeight = 22.sp)
            Spacer(Modifier.height(20.dp))
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.elevatedCardColors(containerColor = CardColor)) {
                Column(Modifier.padding(22.dp)) {
                    SectionLabel("WHAT TO DO")
                    Spacer(Modifier.height(12.dp))
                    when (step) {
                        SetupStep.WELCOME -> WelcomeContent(state)
                        SetupStep.COMPUTER_CHECK, SetupStep.APPLE_DEVICE_SUPPORT -> CheckContent(check, step, working, state.appleSupportConfirmed, onInstallApple, onAppleInstalled, onRecheck)
                        SetupStep.CONNECT_IPHONE -> ConnectContent(device, transport, working, onRecheck, onOpenServices, onInstallApple)
                        SetupStep.LOCAL_DEV_VPN -> Instructions(listOf("On the iPhone, install LocalDevVPN from the App Store.", "Open it and allow the VPN configuration when iOS asks.", "Make sure Wi-Fi is on, then tap Connect.", "Leave LocalDevVPN showing Connected."))
                        SetupStep.ILOADER_INSTALL -> IloaderContent(iloader, working, onInstallIloader, onRecheck, onOpenIloader)
                        SetupStep.SIDESTORE_INSTALL -> SideStoreInstallContent(onOpenIloader)
                        SetupStep.PAIRING -> PairingContent(state.repairMode, onOpenIloader, onRepair)
                        SetupStep.TRUST_PROFILE -> Instructions(listOf("On iPhone, open Settings.", "Go to General → VPN & Device Management.", "Under Developer App, select the profile named after your Apple Account.", "Tap Trust and confirm. If iOS says Allow & Restart, accept it and return after the restart."))
                        SetupStep.DEVELOPER_MODE -> Instructions(listOf("Open Settings → Privacy & Security.", "Scroll to Developer Mode and turn it on.", "Allow the iPhone to restart.", "After reboot, unlock it, tap Turn On, and enter the iPhone passcode."))
                        SetupStep.SIDESTORE_PRIME -> PrimeContent(onRepair)
                        SetupStep.ADD_SOURCE -> SourceContent(state)
                        SetupStep.INSTALL_NUVIO -> InstallNuvioContent(state)
                        SetupStep.FINISH -> FinishContent(state)
                    }
                    if (working) { Spacer(Modifier.height(18.dp)); WorkingBanner(workingLabel) }
                    if (operation != null) { Spacer(Modifier.height(16.dp)); ResultBanner(operation) }
                    if (step.manual && step != SetupStep.FINISH) {
                        Spacer(Modifier.height(16.dp))
                        ManualConfirmation(confirmationText(step, state), step in state.manualConfirmations, onConfirmed)
                    }
                    if (guide.troubleshooting.isNotEmpty()) { Spacer(Modifier.height(14.dp)); Troubleshooting(guide.troubleshooting) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        StepVisualPanel(step, state, Modifier.width(290.dp))
    }
}

@Composable
private fun NavigationBar(step: SetupStep, canAdvance: Boolean, confirmed: Boolean, onBack: () -> Unit, onNext: () -> Unit, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(enabled = step != SetupStep.WELCOME, onClick = onBack) { Text("Back") }
        Spacer(Modifier.weight(1f))
        if (step == SetupStep.FINISH) Button(onClick = onDone) { Text("Finish") }
        else Button(enabled = canAdvance, onClick = onNext, modifier = Modifier.widthIn(min = 150.dp)) {
            Text(when { step == SetupStep.WELCOME -> "Start setup"; step.manual && !confirmed -> "Confirm above to continue"; else -> "Continue" })
        }
    }
}

@Composable private fun WelcomeContent(state: SetupState) {
    Instructions(listOf("Have your iPhone and a USB data cable nearby.", "Know the Apple Account you’ll use in SideStore; free accounts are supported.", "Keep the iPhone on Wi-Fi during setup.", "Allow about 10–15 minutes. Progress saves automatically."))
    Spacer(Modifier.height(14.dp)); InfoCallout("Privacy", "Apple Account sign-in happens inside iloader and SideStore. This setup app never receives or stores your password or verification code.")
    if (state.channel == SetupChannel.DEVELOPER) { Spacer(Modifier.height(12.dp)); WarningCallout("Developer Channel selected", "${state.channel.appName} is experimental, has separate data, and uses an extra SideStore app slot.") }
}

@Composable private fun CheckContent(check: ComputerCheck?, step: SetupStep, working: Boolean, confirmed: Boolean, onInstall: () -> Unit, onAlreadyInstalled: () -> Unit, onRecheck: () -> Unit) {
    if (working && check == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Checking your computer…", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("This should take less than 15 seconds, even on a slow connection.", color = TextSecondary, fontSize = 12.sp)
            }
        }
        return
    }
    check?.let { value ->
        listOfNotNull(value.supportedOs, value.internet, value.appleSupport, value.appleService).forEach { CheckRow(it) }
        if (!value.canContinue && step == SetupStep.APPLE_DEVICE_SUPPORT) {
            Spacer(Modifier.height(14.dp)); Button(enabled = !working, onClick = onInstall) { Text("Install recommended Apple device support") }
            Spacer(Modifier.height(8.dp)); OutlinedButton(enabled = !working && !confirmed, onClick = onAlreadyInstalled) { Text(if (confirmed) "Marked installed" else "Apple Devices or iTunes is already installed") }
            Text("The next page still verifies the real USB connection.", color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(10.dp)); OutlinedButton(enabled = !working, onClick = onRecheck) { Text("Check again") }
    }
}

@Composable private fun ConnectContent(detected: Boolean, transport: Boolean, working: Boolean, onRecheck: () -> Unit, onServices: () -> Unit, onRepair: () -> Unit) {
    Instructions(listOf("Connect the iPhone with a cable that supports data, not charging only.", "Unlock it and keep the screen on.", "On the iPhone, tap Trust when ‘Trust This Computer?’ appears.", "Enter the iPhone passcode, then keep Wi-Fi on."))
    Spacer(Modifier.height(14.dp)); LiveStatusRow(detected, if (detected) "iPhone detected by this computer" else "Waiting for an unlocked iPhone")
    Spacer(Modifier.height(8.dp)); LiveStatusRow(transport, if (transport) "Apple device communication is ready" else "Apple device communication is not ready")
    if (detected && !transport) {
        Spacer(Modifier.height(12.dp)); WarningCallout("Apple communication needs attention", "iloader needs Apple Mobile Device Support. The Apple desktop installer is the recommended repair.")
        Spacer(Modifier.height(10.dp)); Button(enabled = !working, onClick = onRepair) { Text("Repair with Apple’s desktop installer") }
        Spacer(Modifier.height(8.dp)); OutlinedButton(enabled = !working, onClick = onServices) { Text("Advanced: open Windows Services") }
    }
    if (!detected || !transport) { Spacer(Modifier.height(10.dp)); OutlinedButton(enabled = !working, onClick = onRecheck) { Text("Re-check connection") } }
}

@Composable private fun IloaderContent(detected: Boolean, working: Boolean, onInstall: () -> Unit, onRecheck: () -> Unit, onOpen: () -> Unit) {
    Text("Install the official desktop helper. You’ll use it on the next two pages; you do not need to choose anything in iloader yet.", color = TextSecondary)
    Spacer(Modifier.height(14.dp)); LiveStatusRow(detected, if (detected) "iloader is installed" else "iloader is not installed yet")
    Spacer(Modifier.height(14.dp)); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!detected) Button(enabled = !working, onClick = onInstall) { Text(if (working) "Working…" else "Download and install iloader") }
        else Button(enabled = !working, onClick = onOpen) { Text("Open iloader") }
        OutlinedButton(enabled = !working, onClick = onRecheck) { Text("Check again") }
    }
}

@Composable private fun SideStoreInstallContent(onOpen: () -> Unit) {
    Instructions(listOf("Open iloader and select your connected iPhone.", "Sign in to your Apple Account inside iloader and complete Apple’s verification if asked.", "Choose Install SideStore (Stable).", "Wait for iloader to finish, then find SideStore on the Home Screen or in the App Library."))
    Spacer(Modifier.height(12.dp)); InfoCallout("Your sign-in stays private", "It is handled by iloader. Nuvio Z Setup never receives or stores your Apple password.")
    Spacer(Modifier.height(12.dp)); Button(onClick = onOpen) { Text("Open iloader") }
    Text("Closing iloader is not success. Confirm only when you can see SideStore on the iPhone.", color = Warning, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 9.dp))
}

@Composable private fun PairingContent(repair: Boolean, onOpen: () -> Unit, onRepair: () -> Unit) {
    if (repair) {
        WarningCallout("Replacing an old pairing record", "Use this path when SideStore asks you to replace pairing or use iloader.")
        Spacer(Modifier.height(10.dp)); Instructions(listOf("In SideStore Settings, tap Reset Pairing File.", "In iloader, choose Delete Stored Pairing.", "Reconnect and unlock the iPhone; tap Trust if asked.", "Choose Manage Pairing File.", "Beside SideStore choose Place, or choose Place in All Apps.", "Wait for the green ‘Pairing file placed successfully!’ message."))
    } else Instructions(listOf("Keep the iPhone connected and unlocked.", "In iloader, choose Manage Pairing File.", "Find SideStore and choose Place.", "Wait for the green ‘Pairing file placed successfully!’ message."))
    Spacer(Modifier.height(12.dp)); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = onOpen) { Text("Open iloader") }; if (!repair) OutlinedButton(onClick = onRepair) { Text("Repair pairing instead") } }
}

@Composable private fun PrimeContent(onRepair: () -> Unit) {
    Instructions(listOf("Confirm Wi-Fi and LocalDevVPN are connected.", "Open SideStore and sign in with the same Apple Account used in iloader.", "Open My Apps.", "Tap the 7 DAYS counter beside SideStore.", "Accept certificate prompts such as Revoke or Refresh Now.", "Wait for the success notification and for SideStore to become available again."))
    Spacer(Modifier.height(10.dp)); OutlinedButton(onClick = onRepair) { Text("Repair SideStore pairing") }
}

@Composable private fun SourceContent(state: SetupState) {
    Text("Recommended: add the source manually. This works even when camera scanning or deep links do not.", color = TextSecondary)
    Spacer(Modifier.height(10.dp)); Surface(color = Color(0xFF0C1626), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("SOURCE URL", color = BlueText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(state.sourceUrl, color = Color.White, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(vertical = 7.dp))
            Button(onClick = { copyText(state.sourceUrl) }) { Text("Copy source URL") }
        }
    }
    Spacer(Modifier.height(12.dp)); Instructions(listOf("On iPhone, open SideStore → Sources.", "Tap +.", "Paste the copied URL.", "Tap Add and wait for ${state.channel.appName} to appear."))
    Text("Prefer the QR? Scan the code in the panel. Manual entry above is always available.", color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
}

@Composable private fun InstallNuvioContent(state: SetupState) {
    Instructions(listOf("Keep Wi-Fi on and LocalDevVPN connected.", "In SideStore, open Browse and select ${state.channel.appName}.", "Tap Free or Install.", "If ‘App contains extensions’ appears, choose Keep app extensions (use main profile).", "Wait until ${state.channel.appName} appears on the Home Screen, then open it once."))
    Spacer(Modifier.height(10.dp)); InfoCallout("Why keep app extensions?", "This preserves Nuvio Z’s Downloads widget without consuming another SideStore app slot.")
}

@Composable private fun FinishContent(state: SetupState) {
    InfoRows(listOf("SideStore is installed and trusted", "Wireless pairing is configured", "${state.channel.appName} is installed"))
    Spacer(Modifier.height(14.dp)); Text("Keep your apps active", fontWeight = FontWeight.Bold, fontSize = 17.sp)
    Text("Free Apple Accounts sign apps for seven days. Refresh every 5–6 days so the counter never reaches zero.", color = TextSecondary, modifier = Modifier.padding(top = 5.dp))
    Spacer(Modifier.height(12.dp)); Instructions(listOf("Unplug USB.", "Keep the iPhone on Wi-Fi.", "Connect LocalDevVPN.", "Open SideStore → My Apps.", "Tap Refresh All and wait for success."))
    Spacer(Modifier.height(10.dp)); InfoCallout("If pairing expires later", "Reopen this assistant and choose Advanced settings → Repair SideStore. This can happen after an iOS update or reset.")
}

@Composable private fun ManualConfirmation(text: String, confirmed: Boolean, onConfirmed: (Boolean) -> Unit) {
    Surface(color = if (confirmed) Color(0xFF123526) else Color(0xFF172D4B), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(if (confirmed) "STEP CONFIRMED" else "CONFIRM BEFORE CONTINUING", color = if (confirmed) Success else BlueText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(11.dp))
            if (confirmed) Row(verticalAlignment = Alignment.CenterVertically) { Text("✓ Ready to continue", color = Success, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f)); TextButton(onClick = { onConfirmed(false) }) { Text("Undo") } }
            else Button(onClick = { onConfirmed(true) }, modifier = Modifier.fillMaxWidth()) { Text("I’ve completed this step") }
        }
    }
}

@Composable private fun Troubleshooting(tips: List<TroubleTip>) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) { Text(if (expanded) "Hide troubleshooting" else "I need help with this step") }
    if (expanded) {
        Spacer(Modifier.height(9.dp)); Surface(color = Color(0xFF231F18), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("COMMON PROBLEMS", color = Warning, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                tips.forEachIndexed { index, tip ->
                    if (index > 0) HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color(0xFF514936))
                    Text(tip.problem, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    tip.recovery.forEach { action -> Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.Top) { Text("•", color = Warning); Spacer(Modifier.width(8.dp)); Text(action, color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp) } }
                }
            }
        }
    }
}

@Composable private fun SectionLabel(text: String) = Text(text, color = BlueText, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
@Composable private fun Instructions(items: List<String>) { items.forEachIndexed { index, text -> Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) { Surface(color = Color(0xFF244A7D), shape = RoundedCornerShape(99.dp)) { Box(Modifier.size(25.dp), contentAlignment = Alignment.Center) { Text("${index + 1}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) } }; Spacer(Modifier.width(11.dp)); Text(text, color = TextPrimary, lineHeight = 20.sp, modifier = Modifier.padding(top = 2.dp)) } } }
@Composable private fun InfoRows(items: List<String>) { items.forEach { Row(Modifier.padding(vertical = 4.dp)) { Text("✓", color = Success, fontWeight = FontWeight.Bold); Spacer(Modifier.width(9.dp)); Text(it, color = TextPrimary) } } }
@Composable private fun InfoCallout(title: String, text: String) = Callout(Color(0xFF152D50), BlueText, title, text)
@Composable private fun WarningCallout(title: String, text: String) = Callout(Color(0xFF3B3018), Warning, title, text)
@Composable private fun Callout(background: Color, accent: Color, title: String, text: String) { Surface(color = background, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(title, color = accent, fontWeight = FontWeight.Bold, fontSize = 13.sp); Text(text, color = Color.White, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp)) } } }
@Composable private fun CheckRow(value: CheckResult) { val pass = value.state == CheckState.PASS; Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) { Text(if (pass) "✓" else "!", color = if (pass) Success else Warning, fontWeight = FontWeight.Bold); Spacer(Modifier.width(11.dp)); Column { Text(value.label, color = Color.White, fontWeight = FontWeight.SemiBold); if (value.detail.isNotBlank()) Text(value.detail, color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp) } } }
@Composable private fun LiveStatusRow(success: Boolean, text: String) { Surface(color = if (success) Color(0xFF123526) else Color(0xFF3B3018), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp)) { Text(if (success) "✓" else "…", color = if (success) Success else Warning, fontWeight = FontWeight.Bold); Spacer(Modifier.width(10.dp)); Text(text, color = Color.White, fontWeight = FontWeight.SemiBold) } } }
@Composable private fun ResultBanner(result: OperationResult) { Surface(color = if (result.success) Color(0xFF173E31) else Color(0xFF4A2E20), shape = RoundedCornerShape(10.dp)) { Column(Modifier.fillMaxWidth().padding(14.dp)) { Text(result.message, color = Color.White); if (result.details.isNotBlank()) Text(result.details.take(1_000), color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)) } } }
@Composable private fun WorkingBanner(label: String) { Surface(color = Color(0xFF152D50), shape = RoundedCornerShape(10.dp)) { Column(Modifier.fillMaxWidth().padding(14.dp)) { Text(label.ifBlank { "Working…" }, color = Color.White, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(10.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) } } }

@Composable private fun AdvancedDialog(state: SetupState, onDismiss: () -> Unit, onStable: () -> Unit, onDeveloper: () -> Unit, onRepair: () -> Unit, onOverride: (Boolean) -> Unit, onStartOver: () -> Unit) {
    var confirmDeveloper by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Advanced settings") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Most people can leave these unchanged.", color = TextSecondary); Spacer(Modifier.height(12.dp)); Text("Install channel", fontWeight = FontWeight.Bold)
        RadioRow("Nuvio Z Stable (recommended)", state.channel == SetupChannel.STABLE, onStable); RadioRow("Developer Channel (experimental)", state.channel == SetupChannel.DEVELOPER) { confirmDeveloper = true }
        Spacer(Modifier.height(12.dp)); OutlinedButton(onClick = onRepair) { Text("Repair SideStore pairing") }; Spacer(Modifier.height(10.dp)); HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(state.advancedDeviceOverride, onCheckedChange = onOverride); Column { Text("Continue without USB detection"); Text("Advanced recovery only; iloader may still fail.", color = TextMuted, fontSize = 11.sp) } }
        TextButton(onClick = onStartOver) { Text("Clear saved wizard progress") }
    } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
    if (confirmDeveloper) AlertDialog(onDismissRequest = { confirmDeveloper = false }, title = { Text("Use Developer Channel?") }, text = { Text("This installs Nuvio Z Debug (${SetupChannel.DEVELOPER.bundleId}). It has separate data, may be unstable, and uses an extra SideStore slot. SideStore + Stable + Debug fills all three normal free-account slots.") }, confirmButton = { Button(onClick = { confirmDeveloper = false; onDeveloper() }) { Text("Use Developer Channel") } }, dismissButton = { TextButton(onClick = { confirmDeveloper = false }) { Text("Keep Stable") } })
}
@Composable private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) { Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected, onClick); Text(label) } }
@Composable private fun RepairPairingDialog(device: Boolean, onOpen: () -> Unit, onDismiss: () -> Unit) { AlertDialog(onDismissRequest = onDismiss, title = { Text("Repair SideStore pairing") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { LiveStatusRow(device, if (device) "iPhone detected" else "Connect and unlock the iPhone by USB first"); Spacer(Modifier.height(10.dp)); Instructions(listOf("In SideStore Settings, tap Reset Pairing File.", "In iloader, choose Delete Stored Pairing.", "Select the iPhone and tap Trust if asked.", "Choose Manage Pairing File.", "Choose Place beside SideStore, or Place in All Apps.", "Wait for the green success message.", "Reconnect LocalDevVPN and retry the refresh.")); Spacer(Modifier.height(10.dp)); Button(onClick = onOpen) { Text("Open iloader") } } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }) }
@Composable private fun DiagnosticsDialog(report: String, onDismiss: () -> Unit) { AlertDialog(onDismissRequest = onDismiss, title = { Text("Help & diagnostics") }, text = { Column { Text("Each page has step-specific help. This report is useful for support and excludes credentials, verification codes, tokens, and secrets.", color = TextSecondary); Spacer(Modifier.height(10.dp)); Surface(color = Color(0xFF0C1422), shape = RoundedCornerShape(8.dp)) { Text(report, Modifier.padding(12.dp).heightIn(max = 300.dp).verticalScroll(rememberScrollState()), color = Color.White, fontSize = 11.sp) }; Spacer(Modifier.height(10.dp)); Button(onClick = { copyText(report) }) { Text("Copy diagnostics") } } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }) }

private fun copyText(value: String) { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(value), null) }
private fun pageTitle(step: SetupStep, state: SetupState) = when (step) { SetupStep.INSTALL_NUVIO -> "Install ${state.channel.appName}"; SetupStep.ADD_SOURCE -> "Add ${state.channel.appName} to SideStore"; else -> step.title }
