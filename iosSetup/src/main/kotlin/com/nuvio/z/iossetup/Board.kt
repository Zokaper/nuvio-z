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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Root of the v2 assistant: a live checklist beside the one thing the user needs to do next. */
@Composable
internal fun SetupApp(session: SetupSession, developerFlag: Boolean) {
    val scope = rememberCoroutineScope()
    var showAdvanced by remember { mutableStateOf(developerFlag) }
    var showDiagnostics by remember { mutableStateOf(false) }

    LaunchedEffect(developerFlag) { if (developerFlag) session.setChannel(SetupChannel.DEVELOPER, warningAccepted = true) }
    LaunchedEffect(Unit) {
        while (isActive) {
            withContext(Dispatchers.IO) { runCatching { session.tick() } }
            delay(3_000)
        }
    }

    SetupLayout(session, onDiagnostics = { showDiagnostics = true }, onAdvanced = { showAdvanced = true })

    if (showAdvanced) AdvancedDialog(session, { showAdvanced = false }) { showAdvanced = false }
    if (showDiagnostics) DiagnosticsDialog(session.diagnostics.report(session.focus?.requirement?.name ?: "complete")) { showDiagnostics = false }
}

/** The whole window minus dialogs and the polling loop, so it can be rendered on its own. */
@Composable
internal fun SetupLayout(session: SetupSession, onDiagnostics: () -> Unit, onAdvanced: () -> Unit) {
    val progress = session.progress
    val completed = progress.setupCompleted && !progress.repairMode
    Row(Modifier.fillMaxSize().background(AppBackground)) {
        ProgressRail(session, Modifier.width(218.dp).fillMaxHeight())
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 32.dp, vertical = 22.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDiagnostics) { Text("Help & diagnostics") }
                TextButton(onClick = onAdvanced) { Text("Advanced settings") }
            }
            if (completed) CompletedPage(session, Modifier.weight(1f).fillMaxWidth())
            else Board(session, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

// ---- rail ------------------------------------------------------------------------------------------

@Composable
private fun ProgressRail(session: SetupSession, modifier: Modifier) {
    val results = session.results
    val completed = session.progress.setupCompleted && !session.progress.repairMode
    val currentPhase = if (completed) SetupPhase.FINISH else session.focus?.requirement?.phase ?: SetupPhase.FINISH
    fun phaseDone(phase: SetupPhase) = completed || results.filter { it.requirement.phase == phase }.let { rs -> rs.isNotEmpty() && rs.all { it.done } }

    Column(modifier.background(RailColor).padding(horizontal = 20.dp, vertical = 24.dp)) {
        Text("Nuvio Z", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("iPhone setup", color = TextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(26.dp))
        Column(Modifier.weight(1f)) {
            SetupPhase.entries.forEachIndexed { index, phase ->
                val done = phaseDone(phase) && phase != SetupPhase.FINISH || (phase == SetupPhase.FINISH && completed)
                val current = !completed && phase == currentPhase
                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(99.dp), color = when { done -> Color(0xFF25895B); current -> Color(0xFF245EAF); else -> Color(0xFF28384D) }) {
                        Box(Modifier.size(27.dp), contentAlignment = Alignment.Center) {
                            Text(if (done) "✓" else "${index + 1}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(phase.title, color = if (current || done) Color.White else TextMuted, fontSize = 13.sp, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        Text("Progress is checked automatically", color = TextMuted, fontSize = 11.sp)
    }
}

// ---- board -----------------------------------------------------------------------------------------

@Composable
private fun Board(session: SetupSession, modifier: Modifier) {
    Row(modifier) {
        Checklist(session, Modifier.width(290.dp).fillMaxHeight())
        Spacer(Modifier.width(28.dp))
        FocusPane(session, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun Checklist(session: SetupSession, modifier: Modifier) {
    val results = session.results
    val focus = session.focus?.requirement
    Column(modifier.verticalScroll(rememberScrollState())) {
        SectionLabel("YOUR PROGRESS")
        Spacer(Modifier.height(10.dp))
        results.forEach { result ->
            val current = result.requirement == focus
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                Text(
                    when { result.done -> "✓"; current -> "●"; else -> "○" },
                    color = when { result.done -> Success; current -> NuvioBlue; else -> TextMuted },
                    fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp),
                )
                Column {
                    Text(result.requirement.title, color = if (result.done || current) TextPrimary else TextMuted, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp)
                    val note = when {
                        result.confirmedByUser -> "confirmed by you"
                        result.done && result.requirement == Requirement.NUVIO && result.detail.isNotBlank() -> "version ${result.detail}"
                        else -> ""
                    }
                    if (note.isNotEmpty()) Text(note, color = TextMuted, fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = Color(0xFF28384D))
        Spacer(Modifier.height(10.dp))
        ToolsRow(session.tools)
    }
}

@Composable
private fun ToolsRow(tools: ToolsState) {
    val (mark, color, text) = when (tools) {
        ToolsState.Idle -> Triple("○", TextMuted, "Install tool: waiting")
        is ToolsState.Working -> Triple("↓", NuvioBlue, "Install tool: downloading${tools.total?.takeIf { it > 0 }?.let { " ${(tools.downloaded * 100 / it).coerceIn(0, 100)}%" } ?: "…"}")
        ToolsState.Ready -> Triple("✓", Success, "Install tool: ready")
        is ToolsState.Failed -> Triple("!", Warning, "Install tool: needs attention")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(mark, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
        Text(text, color = TextSecondary, fontSize = 12.sp)
    }
}

// ---- focus -----------------------------------------------------------------------------------------

private fun focusTitle(requirement: Requirement, channel: SetupChannel) = when (requirement) {
    Requirement.COMPUTER -> "Get this computer ready"
    Requirement.DEVICE -> "Connect your iPhone"
    Requirement.TRUST -> "Trust this computer"
    Requirement.LOOPBACK_APP -> "Install LocalDevVPN"
    Requirement.SIDESTORE -> "Install SideStore"
    Requirement.PAIRING -> "Securing the connection"
    Requirement.DEVELOPER_MODE -> "Turn on Developer Mode"
    Requirement.PROFILE_TRUST -> "Trust the developer profile"
    Requirement.NOTIFICATIONS -> "Allow SideStore notifications"
    Requirement.SIDESTORE_READY -> "Sign in to SideStore"
    Requirement.SOURCE_ADDED -> "Add ${channel.appName} to SideStore"
    Requirement.NUVIO -> "Install ${channel.appName}"
}

private fun focusPurpose(requirement: Requirement, channel: SetupChannel) = when (requirement) {
    Requirement.COMPUTER -> "We check this computer and set up what’s needed. You won’t have to install any helper tools yourself."
    Requirement.DEVICE -> "Plug your iPhone into this computer. We’ll notice it as soon as it’s connected."
    Requirement.TRUST -> "iOS asks before a computer may talk to your iPhone. This only needs to happen once."
    Requirement.LOOPBACK_APP -> "LocalDevVPN lets SideStore refresh your apps over Wi-Fi. It doesn’t route your normal internet traffic."
    Requirement.SIDESTORE -> "SideStore is what installs and refreshes ${channel.appName}. We’ll open the installer for you."
    Requirement.PAIRING -> "We’re giving SideStore the small pairing file it needs to refresh apps without a cable. This is automatic."
    Requirement.DEVELOPER_MODE -> "iOS needs Developer Mode on before it will run apps installed this way."
    Requirement.PROFILE_TRUST -> "Because SideStore was signed with your own Apple Account, iOS asks you to trust that account once."
    Requirement.NOTIFICATIONS -> "SideStore can’t refresh itself or your apps unless iOS lets it send notifications. Skipping this causes ‘Repository could not save notification’."
    Requirement.SIDESTORE_READY -> "A first refresh proves SideStore, LocalDevVPN, your Apple sign-in and the pairing file all work together."
    Requirement.SOURCE_ADDED -> "A SideStore source is just a catalog address. Adding ours makes ${channel.appName} appear in SideStore."
    Requirement.NUVIO -> "SideStore can now download, sign and install ${channel.appName}. We’ll notice when it appears."
}

@Composable
private fun FocusPane(session: SetupSession, modifier: Modifier) {
    val progress = session.progress
    val focus = session.focus
    Box(modifier) {
        Column(Modifier.widthIn(max = 800.dp).fillMaxHeight().align(Alignment.TopStart).verticalScroll(rememberScrollState())) {
            if (progress.repairMode) RepairPanel(session)
            if (focus == null) {
                if (!progress.repairMode) Text("Finishing up…", color = TextSecondary, fontSize = 16.sp)
                return@Column
            }
            val requirement = focus.requirement
            val index = Requirement.entries.indexOf(requirement) + 1
            Text("STEP $index OF ${Requirement.entries.size}  ·  ${requirement.phase.title.uppercase()}", color = BlueText, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(9.dp))
            Text(focusTitle(requirement, progress.channel), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(Modifier.height(8.dp))
            Text(focusPurpose(requirement, progress.channel), color = TextSecondary, fontSize = 15.sp, lineHeight = 22.sp)
            Spacer(Modifier.height(20.dp))
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.elevatedCardColors(containerColor = CardColor)) {
                Column(Modifier.padding(22.dp)) {
                    SectionLabel(if (requirement.manualOnly || !session.helper.available || progress.manualMode) "WHAT TO DO" else "WHAT TO DO · WE’LL DETECT IT")
                    Spacer(Modifier.height(12.dp))
                    RequirementContent(session, focus)
                    session.busyLabel?.let { Spacer(Modifier.height(14.dp)); WorkingBanner(it) }
                    session.lastOperation?.takeIf { !it.success }?.let { Spacer(Modifier.height(12.dp)); ResultBanner(it) }
                    if (focus.status == RequirementStatus.UNKNOWN && !(requirement == Requirement.COMPUTER && session.computer == null)) {
                        Spacer(Modifier.height(16.dp))
                        val confirmed = requirement in progress.confirmed
                        ManualConfirmation(confirmationTextFor(requirement, progress.channel), confirmed) { session.confirm(requirement, it) }
                    }
                    val tips = troubleshootingFor(requirement, progress.channel, session.isMac, session.error)
                    if (tips.isNotEmpty()) { Spacer(Modifier.height(14.dp)); Troubleshooting(tips) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun RequirementContent(session: SetupSession, focus: RequirementResult) {
    val scope = rememberCoroutineScope()
    val channel = session.progress.channel
    val app = channel.appName
    when (focus.requirement) {
        Requirement.COMPUTER -> {
            Instructions(listOf("Have your iPhone and a USB data cable nearby.", "Know the Apple Account you’ll sign in with; a free account works.", "Keep the iPhone on Wi-Fi during setup. Allow about 10–15 minutes."))
            Spacer(Modifier.height(14.dp))
            val computer = session.computer
            if (computer == null) {
                Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp); Spacer(Modifier.width(12.dp)); Text("Checking this computer…", color = TextSecondary) }
            } else {
                listOfNotNull(computer.supportedOs, computer.internet, computer.appleSupport, computer.appleService).forEach { CheckRow(it) }
                if (computer.appleSupport.state != CheckState.PASS && !session.isMac) {
                    Spacer(Modifier.height(12.dp))
                    Button(enabled = session.busyLabel == null, onClick = { scope.launch(Dispatchers.IO) { session.installAppleSupport() } }) { Text("Install Apple device support") }
                    Text("Windows will ask for permission. This downloads Apple’s own installer and checks its signature first.", color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { scope.launch(Dispatchers.IO) { session.checkComputer() } }) { Text("Check again") }
            }
            Spacer(Modifier.height(14.dp))
            InfoCallout("Privacy", "Your Apple Account is entered only inside Apple’s sign-in in the installer. This app never sees your password or verification code.")
        }
        Requirement.DEVICE, Requirement.TRUST -> {
            Instructions(listOf("Connect the iPhone with a cable that supports data, not charging only.", "Unlock it and keep the screen on.", "When ‘Trust This Computer?’ appears, tap Trust and enter your passcode."))
            Spacer(Modifier.height(14.dp))
            DeviceStatus(session)
            if (session.isMac && session.helperStatus?.device == null) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { scope.launch(Dispatchers.IO) { session.openServices() } }) { Text("Open Finder") }
            }
            if (!session.isMac && session.error?.code == ErrorCode.TRANSPORT_DOWN) {
                Spacer(Modifier.height(12.dp))
                Button(enabled = session.busyLabel == null, onClick = { scope.launch(Dispatchers.IO) { session.installAppleSupport() } }) { Text("Repair with Apple’s installer") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { scope.launch(Dispatchers.IO) { session.openServices() } }) { Text("Advanced: open Windows Services") }
            }
        }
        Requirement.LOOPBACK_APP -> {
            Instructions(listOf("On the iPhone, open the Camera and point it at this code, or search the App Store for LocalDevVPN.", "Install LocalDevVPN. You don’t need to open it yet.", "This page continues by itself once it’s installed."))
            Spacer(Modifier.height(12.dp))
            QrBlock(SourceLink.LOCALDEVVPN_APP_STORE, "Scan with the iPhone camera to open LocalDevVPN in the App Store.")
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { copyText(SourceLink.LOCALDEVVPN_APP_STORE) }) { Text("Copy App Store link") }
            Spacer(Modifier.height(12.dp))
            DeviceStatus(session, waitingText = "Waiting for LocalDevVPN to appear on the iPhone")
        }
        Requirement.SIDESTORE -> {
            when (val tools = session.tools) {
                ToolsState.Idle -> Text("Preparing the SideStore installer…", color = TextSecondary)
                is ToolsState.Working -> {
                    val fraction = tools.total?.takeIf { it > 0 }?.let { (tools.downloaded.toFloat() / it).coerceIn(0f, 1f) }
                    Text("Downloading the SideStore installer (iloader)…", color = TextSecondary)
                    Spacer(Modifier.height(8.dp))
                    if (fraction != null) LinearProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is ToolsState.Failed -> {
                    WarningCallout("The installer couldn’t be downloaded", tools.result.message)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { session.retryTools() }) { Text("Try again") }
                    Text("You can also install iloader yourself from iloader.app, then use it as described below.", color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
                ToolsState.Ready -> {
                    Instructions(listOf("Click the button below. The SideStore installer opens.", "Select your iPhone and sign in with your Apple Account. Complete Apple’s verification if asked.", "Choose Install SideStore (Stable). Wait until it finishes.", "Come back here: this page continues by itself."))
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { scope.launch(Dispatchers.IO) { session.openIloader() } }) { Text("Open the SideStore installer") }
                }
            }
            Spacer(Modifier.height(12.dp))
            DeviceStatus(session, waitingText = "Waiting for SideStore to appear on the iPhone")
            Spacer(Modifier.height(12.dp))
            InfoCallout("Your sign-in stays private", "It’s handled by the installer and Apple. Nuvio Z Setup never receives your password.")
        }
        Requirement.PAIRING -> {
            when {
                session.pairingRunning -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp); Spacer(Modifier.width(12.dp)); Text("Setting up the secure connection… keep the iPhone unlocked.", color = TextSecondary) }
                session.pairingResult?.ok == false -> {
                    WarningCallout("We couldn’t finish this automatically", session.pairingResult!!.userMessage)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { session.retryPairing() }) { Text("Try again") }
                    Spacer(Modifier.height(10.dp))
                    Text("Or do it by hand in iloader:", color = TextSecondary)
                    Instructions(listOf("Keep the iPhone connected and unlocked.", "In iloader choose Manage Pairing File.", "Find SideStore and choose Place.", "Wait for the green ‘Pairing file placed successfully!’ message."))
                    OutlinedButton(onClick = { scope.launch(Dispatchers.IO) { session.openIloader() } }) { Text("Open iloader") }
                }
                else -> Text("Starting…", color = TextSecondary)
            }
        }
        Requirement.DEVELOPER_MODE -> {
            Instructions(listOf("On the iPhone open Settings → Privacy & Security.", "Scroll to Developer Mode and turn it on. (We’ve made the switch appear for you.)", "Allow the iPhone to restart.", "After it restarts, unlock it, tap Turn On and enter your passcode."))
            Spacer(Modifier.height(12.dp))
            DeviceStatus(session, waitingText = "Waiting for Developer Mode to turn on")
        }
        Requirement.PROFILE_TRUST -> {
            Instructions(listOf("On the iPhone open Settings → General → VPN & Device Management.", "Under Developer App, select the profile named after your Apple Account.", "Tap Trust and confirm. If iOS says Allow & Restart, accept it."))
        }
        Requirement.NOTIFICATIONS -> {
            Instructions(listOf(
                "On the iPhone open Settings → Notifications → SideStore.",
                "Turn on Allow Notifications.",
                "If SideStore isn’t in the list yet, open SideStore once and tap Allow when iOS asks, then check Settings again.",
            ))
            Spacer(Modifier.height(10.dp))
            WarningCallout("Don’t skip this", "Without it SideStore fails to refresh with ‘Failed to refresh SideStore: Repository could not save notification’. It is a known SideStore requirement, seen on iOS 27.")
        }
        Requirement.SIDESTORE_READY -> {
            Instructions(listOf("Open LocalDevVPN, make sure Wi-Fi is on, and tap Connect.", "Open SideStore and sign in with the same Apple Account you used in the installer. If iOS asks whether SideStore may send notifications, tap Allow: refreshing fails without it.", "Go to My Apps and tap the 7 DAYS counter beside SideStore.", "Accept any certificate prompts, then wait for the success message."))
        }
        Requirement.SOURCE_ADDED -> {
            Instructions(listOf(
                "On the iPhone, open the Camera and point it at this code.",
                "Tap the banner that appears. SideStore opens and asks to add the $app source. Tap Add.",
                "Check that $app appears under SideStore → Browse, then confirm below.",
            ))
            Spacer(Modifier.height(10.dp))
            QrBlock(SourceLink.sourceQr(channel), "Scan with the iPhone Camera. SideStore must already be installed.")
            Spacer(Modifier.height(12.dp))
            SourceFallback(channel)
        }
        Requirement.NUVIO -> {
            Instructions(listOf("Keep Wi-Fi on and LocalDevVPN connected.", "Recommended: in SideStore open Browse → $app → Free. This installs from the source you just added, which is how SideStore knows to offer updates later.", "Shortcut: scan the code below to install the same release directly. It does not add the source, so only use it once the source step above is done.", "If ‘App contains extensions’ appears, choose Keep app extensions (use main profile).", "Wait for the install to finish. This page continues by itself."))
            Spacer(Modifier.height(10.dp))
            var ipaUrl by remember(channel) { mutableStateOf<String?>(null) }
            LaunchedEffect(channel) { ipaUrl = withContext(Dispatchers.IO) { SourceLink.fetchLatestIpaUrl(channel) } }
            val installPayload = ipaUrl?.let(SourceLink::installDeepLink)
            if (installPayload != null) { QrBlock(installPayload, "Shortcut: installs $app directly. Add the source first if you haven’t, so updates appear."); Spacer(Modifier.height(10.dp)) }
            InfoCallout("Why keep app extensions?", "It preserves the Downloads widget without using another of your three free app slots.")
            Spacer(Modifier.height(12.dp))
            DeviceStatus(session, waitingText = "Waiting for $app to appear on the iPhone")
        }
    }
}

/** One live line saying what the computer currently sees, or that it cannot see anything yet. */
@Composable
private fun DeviceStatus(session: SetupSession, waitingText: String = "Waiting for your iPhone") {
    if (!session.helper.available || session.progress.manualMode) {
        InfoCallout("Manual mode", "The automatic device checker isn’t running, so you’ll confirm each step yourself below.")
        return
    }
    if (!session.deviceProbed) { Text("Looking for your iPhone…", color = TextSecondary, fontWeight = FontWeight.SemiBold); return }
    val device = session.helperStatus?.device
    when {
        session.error?.code == ErrorCode.TRANSPORT_DOWN -> LiveStatusRow(false, "Apple device communication is not ready")
        device == null -> LiveStatusRow(false, session.error?.headline ?: "Waiting for an unlocked iPhone")
        device.trust != TrustState.VALID -> { LiveStatusRow(true, "iPhone detected"); Spacer(Modifier.height(8.dp)); LiveStatusRow(false, "Waiting for you to tap Trust on the iPhone") }
        else -> { LiveStatusRow(true, "iPhone connected and trusted${device.iosVersion?.let { " (iOS $it)" }.orEmpty()}"); Spacer(Modifier.height(8.dp)); LiveStatusRow(false, waitingText) }
    }
    session.error?.takeIf { it.code == ErrorCode.PROBE_FAILED }?.let { Spacer(Modifier.height(8.dp)); InfoCallout(it.headline, "Keep the iPhone unlocked. Anything we can’t check will ask for your confirmation instead.") }
}

/** The manual route, tucked away: only needed when the Camera shows a web page or text instead of opening SideStore. */
@Composable
private fun SourceFallback(channel: SetupChannel) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text(if (open) "Hide the manual option" else "The Camera shows a web page or text, or nothing happens?") }
    if (open) {
        Text("The code only works when SideStore is installed on this iPhone. Add the source by hand instead:", color = TextSecondary, fontSize = 13.sp)
        Instructions(listOf("Copy the URL below.", "In SideStore open Sources, tap +, paste it, and tap Add."))
        Surface(color = Color(0xFF0C1626), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("SOURCE URL", color = BlueText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(channel.sourceUrl, color = Color.White, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(vertical = 7.dp))
                Button(onClick = { copyText(channel.sourceUrl) }) { Text("Copy source URL") }
            }
        }
    }
}

@Composable
private fun QrBlock(payload: String, caption: String) {
    val qr = remember(payload) { QrCode.image(payload) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = Color.White, shape = RoundedCornerShape(12.dp)) { Image(qr, "QR code", Modifier.size(170.dp).padding(8.dp)) }
        Spacer(Modifier.width(14.dp))
        Text(caption, color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

// ---- repair and completion -------------------------------------------------------------------------

@Composable
private fun RepairPanel(session: SetupSession) {
    val scope = rememberCoroutineScope()
    ElevatedCard(Modifier.fillMaxWidth().padding(bottom = 18.dp), shape = RoundedCornerShape(18.dp), colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF3B3018))) {
        Column(Modifier.padding(20.dp)) {
            Text("Repair mode", color = Warning, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("Use this when SideStore says the pairing is invalid, or asks you to reconnect to a computer. Connect and unlock the iPhone, then replace the pairing file.", color = TextSecondary, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !session.pairingRunning, onClick = { scope.launch(Dispatchers.IO) { session.placePairing() } }) { Text(if (session.pairingRunning) "Working…" else "Replace the pairing file") }
                OutlinedButton(onClick = { session.leaveRepair() }) { Text("Done") }
            }
            session.pairingResult?.let { Spacer(Modifier.height(10.dp)); ResultBanner(OperationResult(it.ok, it.userMessage)) }
            if (session.pairingResult?.ok == false) {
                Spacer(Modifier.height(10.dp))
                Text("If that doesn’t work, in iloader choose Manage Pairing File, then Place beside SideStore.", color = TextSecondary, fontSize = 13.sp)
                OutlinedButton(onClick = { scope.launch(Dispatchers.IO) { session.openIloader() } }, modifier = Modifier.padding(top = 6.dp)) { Text("Open iloader") }
            }
        }
    }
}

@Composable
private fun CompletedPage(session: SetupSession, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val app = session.progress.channel.appName
    var removable by remember { mutableStateOf(session.ops.iloaderBootstrap.isOurs) }
    val version = session.results.firstOrNull { it.requirement == Requirement.NUVIO }?.detail.orEmpty()
    Box(modifier) {
        Column(Modifier.widthIn(max = 760.dp).align(Alignment.TopCenter).verticalScroll(rememberScrollState()).padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = RoundedCornerShape(99.dp), color = Color(0xFF25895B)) { Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) { Text("✓", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold) } }
            Spacer(Modifier.height(18.dp))
            Text("You’re all set", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text("$app${if (version.isNotBlank()) " $version" else ""} is installed on your iPhone.", color = TextSecondary, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(24.dp))
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.elevatedCardColors(containerColor = CardColor)) {
                Column(Modifier.padding(24.dp)) {
                    SectionLabel("FROM NOW ON")
                    Spacer(Modifier.height(12.dp))
                    InfoRows(listOf(
                        "Apps from SideStore are signed with your own Apple Account, and Apple limits a free account’s signature to 7 days.",
                        "If $app won’t open: connect Wi-Fi and LocalDevVPN, then in SideStore → My Apps tap Refresh All. You don’t need this computer or a cable.",
                        "Installing an update from SideStore also starts a fresh 7 days and keeps your data.",
                        "If SideStore says the pairing is invalid, come back here and choose Repair pairing.",
                        "If SideStore itself won’t open, reconnect the iPhone here, use Repair, then reinstall SideStore with the installer.",
                    ))
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { session.enterRepair() }) { Text("Repair pairing") }
                if (removable) OutlinedButton(onClick = { scope.launch(Dispatchers.IO) { val r = session.removeInstalledTools(); removable = !r.success } }) { Text("Remove the install tool from this computer") }
                OutlinedButton(onClick = { session.startOver() }) { Text("Start over") }
            }
            Text("You can close this assistant now.", color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
        }
    }
}

@Composable
private fun AdvancedDialog(session: SetupSession, onDismiss: () -> Unit, onRepair: () -> Unit) {
    var confirmDeveloper by remember { mutableStateOf(false) }
    val progress = session.progress
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Advanced settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Most people can leave these unchanged.", color = TextSecondary); Spacer(Modifier.height(12.dp))
                Text("Install channel", fontWeight = FontWeight.Bold)
                RadioRow("Nuvio Z Stable (recommended)", progress.channel == SetupChannel.STABLE) { session.setChannel(SetupChannel.STABLE) }
                RadioRow("Developer Channel (experimental)", progress.channel == SetupChannel.DEVELOPER) { confirmDeveloper = true }
                Spacer(Modifier.height(10.dp)); HorizontalDivider(); Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { session.enterRepair(); onRepair() }) { Text("Repair SideStore pairing") }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(progress.manualMode, onCheckedChange = { session.setManualMode(it) })
                    Column { Text("Manual mode"); Text("Don’t use the automatic device checker; confirm each phone step yourself.", color = TextMuted, fontSize = 12.sp) }
                }
                Spacer(Modifier.height(6.dp))
                if (session.ops.iloaderBootstrap.isOurs) TextButton(onClick = { scope.launch(Dispatchers.IO) { session.removeInstalledTools() } }) { Text("Remove the downloaded install tool") }
                TextButton(onClick = { session.startOver(); onDismiss() }) { Text("Clear saved progress") }
                Text("Device checker: ${if (session.helper.available) "available" else "not found (manual mode)"}", color = TextMuted, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
    if (confirmDeveloper) AlertDialog(
        onDismissRequest = { confirmDeveloper = false }, title = { Text("Use Developer Channel?") },
        text = { Text("This installs Nuvio Z Debug (${SetupChannel.DEVELOPER.bundleId}). It has separate data, may be unstable, and uses an extra SideStore app slot: a free Apple Account allows three.") },
        confirmButton = { TextButton(onClick = { session.setChannel(SetupChannel.DEVELOPER, warningAccepted = true); confirmDeveloper = false }) { Text("Use Developer Channel") } },
        dismissButton = { TextButton(onClick = { confirmDeveloper = false }) { Text("Cancel") } },
    )
}
