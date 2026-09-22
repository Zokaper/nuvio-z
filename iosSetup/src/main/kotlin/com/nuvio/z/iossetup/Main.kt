package com.nuvio.z.iossetup

import androidx.compose.foundation.Image
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

private val NuvioBlue = Color(0xFF4D8DFF)
private val RailColor = Color(0xFF101827)

fun main(args: Array<String>) = application {
    val diagnostics = remember { Diagnostics() }
    val store = remember { ProgressStore() }
    val developerFlag = args.contains("--developer")
    val initial = remember { store.load() ?: SetupState() }
    val ops = remember { platformSetupOps(diagnostics) }
    val controller = remember { SetupController(initial, store, ops.isMac) }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Nuvio Z iOS Setup",
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme(primary = NuvioBlue, surface = Color(0xFF182235), background = Color(0xFF0B1220)),
        ) {
            SetupApp(controller, ops, diagnostics, developerFlag)
        }
    }
}

@Composable
private fun SetupApp(controller: SetupController, ops: PlatformSetupOps, diagnostics: Diagnostics, developerFlag: Boolean) {
    var state by remember { mutableStateOf(controller.state) }
    var check by remember { mutableStateOf<ComputerCheck?>(null) }
    var deviceDetected by remember { mutableStateOf(false) }
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
        working = true
        workingLabel = label
        operation = null
        scope.launch {
            operation = withContext(Dispatchers.IO) { block() }
            after()
            working = false
            workingLabel = ""
        }
    }

    LaunchedEffect(state.currentStep) {
        diagnostics.step(state.currentStep)
        operation = null
        if (state.currentStep == SetupStep.COMPUTER_CHECK || state.currentStep == SetupStep.APPLE_DEVICE_SUPPORT) {
            working = true
            check = withContext(Dispatchers.IO) { ops.checkComputer() }
            working = false
        }
        if (state.currentStep == SetupStep.ILOADER_INSTALL) iloaderDetected = withContext(Dispatchers.IO) { ops.findIloader() != null }
    }

    LaunchedEffect(state.currentStep) {
        if (state.currentStep == SetupStep.CONNECT_IPHONE || state.currentStep == SetupStep.PAIRING) {
            while (true) {
                deviceDetected = withContext(Dispatchers.IO) { ops.isDeviceConnected() }
                delay(2_000)
            }
        }
    }

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ProgressRail(state, controller.activeSteps(), Modifier.width(270.dp).fillMaxHeight())
        Column(Modifier.weight(1f).fillMaxHeight().padding(40.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { showDiagnostics = true }) { Text("View diagnostics") }
                TextButton(onClick = { showAdvanced = true }) { Text("Settings") }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                StepPage(
                    state = state,
                    check = check,
                    deviceDetected = deviceDetected,
                    iloaderDetected = iloaderDetected,
                    operation = operation,
                    working = working,
                    workingLabel = workingLabel,
                    onConfirmed = { controller.confirmCurrent(it); sync() },
                    onRepair = { showRepair = true; controller.setRepairMode(true); sync() },
                    onInstallApple = {
                        runOperation("Downloading and installing Apple device support…", ops::installAppleDeviceSupport) {
                            workingLabel = "Installation finished. Checking Apple device support again…"
                            check = withContext(Dispatchers.IO) { ops.checkComputer() }
                        }
                    },
                    onRecheck = {
                        runOperation("Checking again…", {
                            when (state.currentStep) {
                                SetupStep.COMPUTER_CHECK, SetupStep.APPLE_DEVICE_SUPPORT -> { check = ops.checkComputer(); OperationResult(check?.canContinue == true, "Checks updated.") }
                                SetupStep.CONNECT_IPHONE -> { deviceDetected = ops.isDeviceConnected(); OperationResult(deviceDetected, if (deviceDetected) "iPhone detected." else "No iPhone detected yet.") }
                                SetupStep.ILOADER_INSTALL -> { iloaderDetected = ops.findIloader() != null; OperationResult(iloaderDetected, if (iloaderDetected) "iloader is installed." else "iloader was not found.") }
                                else -> OperationResult(true, "Checked.")
                            }
                        })
                    },
                    onInstallIloader = {
                        runOperation("Downloading and installing the current official iloader…", ops::installIloader) {
                            workingLabel = "Installation finished. Checking for iloader…"
                            iloaderDetected = withContext(Dispatchers.IO) { ops.findIloader() != null }
                        }
                    },
                    onOpenIloader = { runOperation("Opening iloader…", ops::openIloader) },
                )
            }
            val autoVerified = when (state.currentStep) {
                SetupStep.COMPUTER_CHECK -> check?.let { it.supportedOs.state == CheckState.PASS && it.internet.state == CheckState.PASS } == true
                SetupStep.APPLE_DEVICE_SUPPORT -> check?.canContinue == true
                SetupStep.CONNECT_IPHONE -> deviceDetected || state.advancedDeviceOverride
                SetupStep.ILOADER_INSTALL -> iloaderDetected
                else -> false
            }
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = state.currentStep != SetupStep.WELCOME, onClick = { controller.back(); sync() }) { Text("Back") }
                Spacer(Modifier.weight(1f))
                if (state.currentStep == SetupStep.FINISH) {
                    Button(onClick = { controller.startOver(); sync() }) { Text("Done") }
                } else {
                    Button(
                        enabled = controller.canAdvance(autoVerified) && !working,
                        onClick = { controller.advance(autoVerified); sync() },
                    ) { Text(primaryLabel(state.currentStep)) }
                }
            }
        }
    }

    if (showAdvanced) AdvancedDialog(
        state = state,
        onDismiss = { showAdvanced = false },
        onStable = { controller.setChannel(SetupChannel.STABLE); sync(); showAdvanced = false },
        onDeveloper = { controller.setChannel(SetupChannel.DEVELOPER, true); sync(); showAdvanced = false },
        onRepair = { controller.enterRepairPairing(); sync(); showAdvanced = false; showRepair = true },
        onOverride = { controller.setDeviceOverride(it); sync() },
        onStartOver = { controller.startOver(); sync(); showAdvanced = false },
    )
    if (showResume) AlertDialog(
        onDismissRequest = {},
        title = { Text("Resume setup?") },
        text = { Text("Saved progress was found at “${state.currentStep.title}”. Resume where you left off, or start over without changing anything already installed on your iPhone.") },
        confirmButton = { Button(onClick = { showResume = false }) { Text("Resume setup") } },
        dismissButton = { TextButton(onClick = { controller.startOver(); sync(); showResume = false }) { Text("Start over") } },
    )
    if (showRepair) RepairPairingDialog(
        deviceDetected = deviceDetected,
        onOpenIloader = { runOperation("Opening iloader…", ops::openIloader) },
        onDismiss = { showRepair = false },
    )
    if (showDiagnostics) DiagnosticsDialog(
        report = diagnostics.report(state.currentStep),
        onDismiss = { showDiagnostics = false },
    )
}

@Composable
private fun ProgressRail(state: SetupState, steps: List<SetupStep>, modifier: Modifier = Modifier) {
    Column(modifier.background(RailColor).padding(24.dp)) {
        Text("Nuvio Z", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("iOS Setup", color = Color(0xFF9CB1D2))
        Spacer(Modifier.height(28.dp))
        steps.forEachIndexed { index, step ->
            val completed = step in state.completedSteps
            val current = step == state.currentStep
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(99.dp), color = when { completed -> Color(0xFF2EAD72); current -> NuvioBlue; else -> Color(0xFF334155) }) {
                    Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                        Text(if (completed) "✓" else "${index + 1}", color = Color.White, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(step.title, color = if (current || completed) Color.White else Color(0xFF8190A8), fontSize = 13.sp, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun StepPage(
    state: SetupState,
    check: ComputerCheck?,
    deviceDetected: Boolean,
    iloaderDetected: Boolean,
    operation: OperationResult?,
    working: Boolean,
    workingLabel: String,
    onConfirmed: (Boolean) -> Unit,
    onRepair: () -> Unit,
    onInstallApple: () -> Unit,
    onRecheck: () -> Unit,
    onInstallIloader: () -> Unit,
    onOpenIloader: () -> Unit,
) {
    val step = state.currentStep
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(end = 16.dp)) {
        StatusPill(statusFor(step, deviceDetected, iloaderDetected, state))
        Spacer(Modifier.height(14.dp))
        Text(pageTitle(step, state), fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(pageSubtitle(step, state), color = Color(0xFFB8C4D8), fontSize = 16.sp)
        Spacer(Modifier.height(24.dp))
        ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(24.dp)) {
                when (step) {
                    SetupStep.WELCOME -> WelcomeContent(state)
                    SetupStep.COMPUTER_CHECK, SetupStep.APPLE_DEVICE_SUPPORT -> CheckContent(check, step, working, onInstallApple, onRecheck)
                    SetupStep.CONNECT_IPHONE -> ConnectContent(deviceDetected, working, onRecheck)
                    SetupStep.LOCAL_DEV_VPN -> Instructions(listOf("Install LocalDevVPN from the App Store.", "Open LocalDevVPN and allow the VPN configuration.", "Make sure the iPhone is on Wi-Fi, then tap Connect."))
                    SetupStep.ILOADER_INSTALL -> IloaderContent(iloaderDetected, working, onInstallIloader, onRecheck, onOpenIloader)
                    SetupStep.SIDESTORE_INSTALL -> SideStoreInstallContent(onOpenIloader)
                    SetupStep.PAIRING -> PairingContent(state.repairMode, onOpenIloader, onRepair)
                    SetupStep.TRUST_PROFILE -> Instructions(listOf("On iPhone, open Settings.", "Go to General → VPN & Device Management.", "Under Developer App, choose the profile named after your Apple Account.", "Tap Trust and confirm. On newer iOS versions this may say Allow & Restart."))
                    SetupStep.DEVELOPER_MODE -> Instructions(listOf("Open Settings → Privacy & Security.", "Scroll to Developer Mode and turn it on.", "Allow the iPhone to restart.", "After reboot, unlock it, confirm Turn On, and enter the iPhone passcode."))
                    SetupStep.SIDESTORE_PRIME -> PrimeContent(onRepair)
                    SetupStep.ADD_SOURCE -> SourceContent(state)
                    SetupStep.INSTALL_NUVIO -> InstallNuvioContent(state)
                    SetupStep.FINISH -> FinishContent()
                }
                if (working) {
                    Spacer(Modifier.height(18.dp))
                    WorkingBanner(workingLabel)
                }
                if (operation != null) {
                    Spacer(Modifier.height(16.dp))
                    ResultBanner(operation)
                }
                if (step.manual && step != SetupStep.FINISH) {
                    Spacer(Modifier.height(20.dp))
                    val checked = step in state.manualConfirmations
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked, onCheckedChange = onConfirmed)
                        Text(confirmationText(step, state), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable private fun WelcomeContent(state: SetupState) {
    Text("Nuvio Z isn't distributed through the App Store yet. SideStore lets your iPhone install and refresh it.")
    Spacer(Modifier.height(16.dp))
    InfoRows(listOf("Usually a one-time setup", "USB is needed during initial setup", "SideStore normally refreshes wirelessly afterward", "An Apple Account is required", "Nuvio Z never receives your Apple password"))
    if (state.channel == SetupChannel.DEVELOPER) {
        Spacer(Modifier.height(16.dp)); ResultBanner(OperationResult(false, "Developer Channel selected: ${state.channel.appName} (${state.channel.bundleId}). This is an experimental, separate app."))
    }
}

@Composable private fun CheckContent(check: ComputerCheck?, step: SetupStep, working: Boolean, onInstall: () -> Unit, onRecheck: () -> Unit) {
    if (working && check == null) { CircularProgressIndicator(); return }
    check?.let { value ->
        listOfNotNull(value.supportedOs, value.internet, value.appleSupport, value.appleService).forEach { CheckRow(it) }
        Spacer(Modifier.height(18.dp))
        if (!value.canContinue && step == SetupStep.APPLE_DEVICE_SUPPORT) Button(enabled = !working, onClick = onInstall) { Text("Install recommended Apple device support") }
        Spacer(Modifier.height(8.dp)); OutlinedButton(enabled = !working, onClick = onRecheck) { Text("Check again") }
    }
}

@Composable private fun ConnectContent(detected: Boolean, working: Boolean, onRecheck: () -> Unit) {
    Instructions(listOf("Connect your iPhone by USB.", "Unlock it.", "If asked “Trust This Computer?”, tap Trust.", "Enter the iPhone passcode.", "Make sure the iPhone is connected to Wi-Fi."))
    Spacer(Modifier.height(18.dp))
    Text(if (detected) "✓ iPhone detected" else "Waiting for iPhone…", color = if (detected) Color(0xFF67D99B) else Color(0xFFFFC857), fontWeight = FontWeight.Bold)
    if (!detected) { Spacer(Modifier.height(12.dp)); OutlinedButton(enabled = !working, onClick = onRecheck) { Text("Check again") } }
}

@Composable private fun IloaderContent(detected: Boolean, working: Boolean, onInstall: () -> Unit, onRecheck: () -> Unit, onOpen: () -> Unit) {
    Text("iloader is the official tool used once to install SideStore and place its pairing file. Apple sign-in stays inside iloader.")
    Spacer(Modifier.height(16.dp))
    Text(if (detected) "✓ iloader installed" else "iloader is not installed yet", color = if (detected) Color(0xFF67D99B) else Color(0xFFFFC857), fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(16.dp))
    if (!detected) Button(enabled = !working, onClick = onInstall) { Text(if (working) "Working…" else "Download and install iloader") }
    else Button(onClick = onOpen) { Text("Open iloader") }
    Spacer(Modifier.width(8.dp)); OutlinedButton(enabled = !working, onClick = onRecheck) { Text("Check again") }
}

@Composable private fun SideStoreInstallContent(onOpen: () -> Unit) {
    Text("iloader is now used once to put SideStore onto your iPhone.")
    Spacer(Modifier.height(16.dp))
    Instructions(listOf("Open iloader and select your connected iPhone.", "Sign in to your Apple Account inside iloader and complete 2FA if requested.", "Choose Install SideStore (Stable).", "Wait until SideStore appears on the iPhone Home Screen."))
    Spacer(Modifier.height(12.dp)); Text("Your Apple Account is handled by iloader. Nuvio Z Setup never receives or stores your password.", color = Color(0xFF9FC4FF))
    Spacer(Modifier.height(16.dp)); Button(onClick = onOpen) { Text("Open iloader") }
    Spacer(Modifier.height(10.dp)); Text("Closing iloader does not complete this step. Confirm below only after you can see SideStore.", color = Color(0xFFFFC857))
}

@Composable private fun PairingContent(repair: Boolean, onOpen: () -> Unit, onRepair: () -> Unit) {
    Text(if (repair) "Replace SideStore's pairing file" else "Place the pairing file into SideStore")
    Spacer(Modifier.height(12.dp))
    if (repair) Instructions(listOf("In SideStore Settings, tap Reset Pairing File.", "In iloader, choose Delete Stored Pairing.", "Reconnect the iPhone by USB, select it, and tap Trust on the iPhone.", "Choose Manage Pairing File.", "Beside SideStore choose Place, or use Place in All Apps.", "Wait for the green “Pairing file placed successfully!” message."))
    else Instructions(listOf("Keep the iPhone connected and unlocked.", "In iloader, choose Manage Pairing File.", "Find SideStore and choose Place.", "Wait for the green “Pairing file placed successfully!” message."))
    Spacer(Modifier.height(16.dp)); Button(onClick = onOpen) { Text("Open iloader") }
    if (!repair) { Spacer(Modifier.width(8.dp)); TextButton(onClick = onRepair) { Text("Pairing problem?") } }
}

@Composable private fun PrimeContent(onRepair: () -> Unit) {
    Instructions(listOf("Make sure Wi-Fi and LocalDevVPN are connected.", "Open SideStore and sign in with the same Apple Account used in iloader.", "Open My Apps.", "Tap the 7 DAYS counter beside SideStore to refresh it.", "If asked to revoke or create a signing certificate, choose Yes or Refresh Now.", "Success returns you to the Home Screen with a notification; SideStore becomes available again shortly."))
    Spacer(Modifier.height(12.dp)); TextButton(onClick = onRepair) { Text("Developer Portal or pairing error?") }
}

@Composable private fun SourceContent(state: SetupState) {
    val qr = remember(state.sourceDeepLink) { QrCode.image(state.sourceDeepLink) }
    Text("The manual URL is the most reliable method. The QR contains SideStore's official source deep link and is optional.")
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = Color.White, shape = RoundedCornerShape(12.dp)) { Image(qr, "QR code for the Nuvio Z SideStore source", Modifier.size(190.dp).padding(8.dp)) }
        Spacer(Modifier.width(22.dp))
        Column(Modifier.weight(1f)) {
            Text(state.sourceUrl, fontSize = 13.sp, color = Color(0xFF9FC4FF))
            Spacer(Modifier.height(12.dp)); Button(onClick = { copyText(state.sourceUrl) }) { Text("Copy source URL") }
        }
    }
    Spacer(Modifier.height(18.dp)); Instructions(listOf("On iPhone, open SideStore → Sources.", "Tap +.", "Paste the source URL shown above.", "Tap Add."))
}

@Composable private fun InstallNuvioContent(state: SetupState) {
    Instructions(listOf("Keep Wi-Fi on and LocalDevVPN connected.", "In SideStore, open Browse and select ${state.channel.appName}.", "Tap Free or Install.", "When “App contains extensions” appears, choose “Keep app extensions (use main profile)”.", "Wait until ${state.channel.appName} appears on the Home Screen."))
    Spacer(Modifier.height(12.dp)); Text("Nuvio Z includes DownloadsWidgetExtension.appex. Keeping app extensions with the main profile preserves the Downloads widget without using a separate App ID.", color = Color(0xFF9FC4FF))
}

@Composable private fun FinishContent() {
    InfoRows(listOf("SideStore installed", "Pairing configured", "Nuvio Z installed"))
    Spacer(Modifier.height(18.dp))
    Text("SideStore signs free-account apps for a 7-day window. Keep Wi-Fi available and turn on LocalDevVPN when installing or refreshing. Pairing can occasionally need replacement after an iOS update, reset, or Apple pairing lifecycle change.")
    Spacer(Modifier.height(18.dp)); Text("Test wireless refresh", fontWeight = FontWeight.Bold)
    Instructions(listOf("Unplug USB.", "Keep Wi-Fi on.", "Connect LocalDevVPN.", "Open SideStore → My Apps.", "Tap Refresh All."))
}

@Composable private fun Instructions(items: List<String>) { items.forEachIndexed { i, text -> Row(Modifier.padding(vertical = 6.dp)) { Text("${i + 1}.", fontWeight = FontWeight.Bold, modifier = Modifier.width(30.dp)); Text(text) } } }
@Composable private fun InfoRows(items: List<String>) { items.forEach { Row(Modifier.padding(vertical = 5.dp)) { Text("✓", color = Color(0xFF67D99B)); Spacer(Modifier.width(10.dp)); Text(it) } } }
@Composable private fun CheckRow(value: CheckResult) { Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) { Text(if (value.state == CheckState.PASS) "✓" else "!", color = if (value.state == CheckState.PASS) Color(0xFF67D99B) else Color(0xFFFFC857), fontWeight = FontWeight.Bold); Spacer(Modifier.width(12.dp)); Column { Text(value.label, fontWeight = FontWeight.SemiBold); if (value.detail.isNotBlank()) Text(value.detail, color = Color(0xFF9AA9C0), fontSize = 13.sp) } } }
@Composable private fun StatusPill(text: String) { Surface(color = if (text == "Complete" || text == "Ready") Color(0xFF174B37) else Color(0xFF4A3914), shape = RoundedCornerShape(99.dp)) { Text(text, Modifier.padding(horizontal = 12.dp, vertical = 5.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold) } }
@Composable private fun ResultBanner(result: OperationResult) { Surface(color = if (result.success) Color(0xFF173E31) else Color(0xFF4A2E20), shape = RoundedCornerShape(10.dp)) { Column(Modifier.fillMaxWidth().padding(14.dp)) { Text(result.message); if (result.details.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(result.details.take(1_000), fontSize = 11.sp, color = Color(0xFFB8C4D8)) } } } }
@Composable private fun WorkingBanner(label: String) { Surface(color = Color(0xFF152D50), shape = RoundedCornerShape(10.dp)) { Column(Modifier.fillMaxWidth().padding(14.dp)) { Text(label.ifBlank { "Working…" }, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(10.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) } } }

@Composable
private fun AdvancedDialog(state: SetupState, onDismiss: () -> Unit, onStable: () -> Unit, onDeveloper: () -> Unit, onRepair: () -> Unit, onOverride: (Boolean) -> Unit, onStartOver: () -> Unit) {
    var confirmDeveloper by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Settings and advanced") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Install channel", fontWeight = FontWeight.Bold)
            RadioRow("Nuvio Z (recommended)", state.channel == SetupChannel.STABLE, onStable)
            RadioRow("Developer Channel", state.channel == SetupChannel.DEVELOPER) { confirmDeveloper = true }
            Spacer(Modifier.height(12.dp)); OutlinedButton(onClick = onRepair) { Text("Repair SideStore") }
            Spacer(Modifier.height(8.dp)); Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(state.advancedDeviceOverride, onCheckedChange = onOverride); Text("Advanced: continue without USB detection") }
            Spacer(Modifier.height(8.dp)); TextButton(onClick = onStartOver) { Text("Start over") }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
    if (confirmDeveloper) AlertDialog(
        onDismissRequest = { confirmDeveloper = false },
        title = { Text("Use Developer Channel?") },
        text = { Text("This installs Nuvio Z Debug (com.nuvio.app.z.debug). It has independent local data, contains experimental builds, and uses an extra SideStore slot. SideStore + stable + debug can fill all 3 normal free-account active app slots.") },
        confirmButton = { Button(onClick = { confirmDeveloper = false; onDeveloper() }) { Text("Yes, use Developer Channel") } },
        dismissButton = { TextButton(onClick = { confirmDeveloper = false }) { Text("No") } },
    )
}

@Composable private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) { Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected, onClick); Text(label) } }

@Composable private fun RepairPairingDialog(deviceDetected: Boolean, onOpenIloader: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Repair SideStore pairing") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(if (deviceDetected) "✓ iPhone detected" else "Connect and unlock the iPhone by USB first.", color = if (deviceDetected) Color(0xFF67D99B) else Color(0xFFFFC857)); Spacer(Modifier.height(12.dp)); Instructions(listOf("In SideStore Settings, tap Reset Pairing File.", "In iloader, choose Delete Stored Pairing.", "Select the iPhone and tap Trust on it if asked.", "Choose Manage Pairing File.", "Choose Place beside SideStore (or Place in All Apps).", "Wait for “Pairing file placed successfully!” in green.", "Reconnect LocalDevVPN and retry SideStore's refresh.")); Spacer(Modifier.height(12.dp)); Button(onClick = onOpenIloader) { Text("Open iloader") } } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

@Composable private fun DiagnosticsDialog(report: String, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Diagnostics") }, text = { Column { Text("This report excludes credentials, 2FA codes, tokens, and secrets."); Spacer(Modifier.height(12.dp)); Surface(color = Color(0xFF0C1422), shape = RoundedCornerShape(8.dp)) { Text(report, Modifier.padding(12.dp).heightIn(max = 340.dp).verticalScroll(rememberScrollState()), fontSize = 11.sp) }; Spacer(Modifier.height(12.dp)); Button(onClick = { copyText(report) }) { Text("Copy diagnostics") } } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

private fun copyText(value: String) { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(value), null) }
private fun primaryLabel(step: SetupStep) = when (step) { SetupStep.WELCOME -> "Set up my iPhone"; SetupStep.FINISH -> "Done"; else -> "Next" }
private fun statusFor(step: SetupStep, device: Boolean, iloader: Boolean, state: SetupState) = when { step == SetupStep.CONNECT_IPHONE && !device -> "Waiting for iPhone"; step == SetupStep.ILOADER_INSTALL && !iloader -> "Needs action"; step in state.completedSteps -> "Complete"; step.manual && step !in state.manualConfirmations -> "Needs action"; else -> "Ready" }
private fun pageTitle(step: SetupStep, state: SetupState) = when (step) { SetupStep.INSTALL_NUVIO -> "Install ${state.channel.appName}"; SetupStep.ADD_SOURCE -> "Add ${state.channel.appName} to SideStore"; else -> step.title }
private fun pageSubtitle(step: SetupStep, state: SetupState) = when (step) {
    SetupStep.WELCOME -> "A friendly, one-time guide for installing ${state.channel.appName} on your iPhone."
    SetupStep.COMPUTER_CHECK -> "We’ll check the few things your computer needs."
    SetupStep.APPLE_DEVICE_SUPPORT -> "Windows needs Apple’s iPhone drivers to communicate with your phone."
    SetupStep.CONNECT_IPHONE -> "The wizard will wait until it can really see your phone."
    SetupStep.LOCAL_DEV_VPN -> "SideStore uses LocalDevVPN for local communication during installs and refreshes."
    SetupStep.ILOADER_INSTALL -> "Install the current official iloader release."
    SetupStep.SIDESTORE_INSTALL -> "Complete this human-controlled step in iloader, then confirm the result here."
    SetupStep.PAIRING -> "SideStore needs a trusted pairing file to communicate with your iPhone."
    SetupStep.TRUST_PROFILE -> "Tell iOS that you trust apps signed with your Apple Account."
    SetupStep.DEVELOPER_MODE -> "Apple requires Developer Mode for SideStore apps on iOS 16 and later."
    SetupStep.SIDESTORE_PRIME -> "A successful first refresh proves SideStore, LocalDevVPN, and pairing work together."
    SetupStep.ADD_SOURCE -> "Use the canonical URL even if the optional QR does not open on your iPhone."
    SetupStep.INSTALL_NUVIO -> "One final install inside SideStore."
    SetupStep.FINISH -> "Your iPhone is ready."
}
private fun confirmationText(step: SetupStep, state: SetupState) = when (step) {
    SetupStep.LOCAL_DEV_VPN -> "LocalDevVPN is installed and shows Connected"
    SetupStep.SIDESTORE_INSTALL -> "I can see SideStore on my iPhone"
    SetupStep.PAIRING -> "iloader says the SideStore pairing file was placed successfully"
    SetupStep.TRUST_PROFILE -> "My developer profile is trusted"
    SetupStep.DEVELOPER_MODE -> "Developer Mode is enabled"
    SetupStep.SIDESTORE_PRIME -> "SideStore opens and refreshes successfully"
    SetupStep.ADD_SOURCE -> "The ${state.channel.appName} source appears in SideStore"
    SetupStep.INSTALL_NUVIO -> "${state.channel.appName} appears on my Home Screen"
    else -> "I completed this step"
}
