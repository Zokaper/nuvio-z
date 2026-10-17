package com.nuvio.app.features.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nuvio.app.core.debug.SelfSigningPlatform
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Debug-only (Phase 2): "Keep Nuvio Z working". When this copy stops opening, renewing it the way SideStore's
 * Refresh does (by hand here, or by itself from Shortcuts, on opening the app and in the background), the daily
 * refresh set-up, the update test and the tester's report. Literal strings, like the other debug rows: this never
 * reaches a shipped build.
 *
 * The password lives in this screen's state only until a renew starts; with Remember ticked the platform keeps it
 * in this iPhone's Keychain.
 */
@Composable
internal fun KeepNuvioWorkingScreen(platform: SelfSigningPlatform, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var embedded by remember { mutableStateOf<String?>(null) }
    var installed by remember { mutableStateOf<String?>(null) }
    var installedNote by remember { mutableStateOf("Checking the phone…") }
    var vpn by remember { mutableStateOf<Boolean?>(null) }
    var saved by remember { mutableStateOf(platform.savedAccount()) }
    var last by remember { mutableStateOf(platform.lastRefresh()) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rememberAccount by remember { mutableStateOf(true) }
    var code by remember { mutableStateOf("") }
    var running by remember { mutableStateOf<String?>(null) }
    var waiting by remember { mutableStateOf(false) }
    var certificates by remember { mutableStateOf<List<JsonObject>?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var showGuide by remember { mutableStateOf(false) }
    var updateUrl by remember { mutableStateOf("") }
    var updateSha by remember { mutableStateOf("") }
    val updateIpa = remember { platform.updateTestIpa() }

    suspend fun checkPhone() {
        installedNote = "Checking the phone…"
        vpn = platform.vpnReachable()
        if (vpn != true) {
            installedNote = "LocalDevVPN isn’t connected: open it and tap Connect."
            return
        }
        val root = parse(platform.installedProfiles())
        if (root != null && root.text("ok") == "true") {
            installed = (root["profiles"] as? JsonArray)?.firstOrNull()?.jsonObject?.text("expires")
            installedNote = "Read from the phone via ${root.text("route")}"
        } else {
            installedNote = "Couldn’t read it: ${root?.text("error") ?: "no result"}"
        }
    }

    LaunchedEffect(Unit) {
        embedded = parse(platform.status())?.text("expires")
        checkPhone()
    }
    LaunchedEffect(running) {
        while (running != null) {
            waiting = platform.waitingForCode()
            certificates = platform.pendingCertificates()?.let { list ->
                runCatching { Json.parseToJsonElement(list).jsonArray.map { it.jsonObject } }.getOrNull()
            }
            progress = platform.updateProgress()?.let(::parse)?.let { p ->
                val stage = when (p.text("stage")) { "download" -> "Downloading"; "sign" -> "Signing"; "install" -> "Installing"; else -> "Working" }
                val pct = p.text("fraction")?.toDoubleOrNull()?.let { (it * 100).toInt() } ?: 0
                "$stage… $pct%"
            }
            delay(500)
        }
        waiting = false
        certificates = null
        progress = null
    }

    fun renew() {
        val typed = saved == null
        running = "Renewing… keep LocalDevVPN connected."
        message = null
        scope.launch {
            val result = platform.refresh(if (typed) email else saved!!, if (typed) password else null, typed && rememberAccount)
            password = ""
            message = describeRenew(result)
            last = platform.lastRefresh()
            saved = platform.savedAccount()
            running = null
            checkPhone()
        }
    }

    fun update(source: String, sha: String?) {
        running = "Installing the update… Nuvio Z closes when it finishes. Open it again afterwards."
        message = null
        scope.launch {
            message = describeRenew(platform.installUpdate(source, sha))
            running = null
        }
    }

    Dialog(onDismissRequest = { if (running == null) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Keep Nuvio Z working", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(enabled = running == null, onClick = onDismiss) { Text("Close") }
                }

                Section {
                    val best = listOfNotNull(embedded, installed).maxOrNull()
                    Text(best?.let { "Opens until ${day(it)}" } ?: "Opens until …", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("This copy’s own signature: ${embedded?.let(::day) ?: "…"}")
                    Text("Newest renewal on the phone: ${installed?.let(::day) ?: "—"}")
                    Text(installedNote, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    Text(
                        "LocalDevVPN: " + when (vpn) { true -> "connected"; false -> "not connected"; null -> "checking…" },
                        color = if (vpn == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    Text("Last renewal: ${describeLast(last)}", fontSize = 13.sp)
                }

                Section {
                    Text("Apple Account", fontWeight = FontWeight.SemiBold)
                    if (saved != null) {
                        Text("Saved on this iPhone: $saved")
                        Text("Nuvio Z renews itself with it when you open the app, from Shortcuts and in the background.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(enabled = running == null, onClick = { platform.forgetAccount(); saved = null }) { Text("Forget this account") }
                    } else if (running == null) {
                        OutlinedTextField(
                            value = email, onValueChange = { email = it.trim() }, singleLine = true,
                            label = { Text("Apple Account email") }, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        )
                        OutlinedTextField(
                            value = password, onValueChange = { password = it }, singleLine = true,
                            label = { Text("Password") }, modifier = Modifier.fillMaxWidth(),
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = rememberAccount, onCheckedChange = { rememberAccount = it })
                            Text("Remember on this iPhone so Nuvio Z can renew itself")
                        }
                        Text(
                            "Your password goes only to Apple. With Remember ticked it’s kept in this iPhone’s Keychain (this device only, never backed up).",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        enabled = running == null && (saved != null || (email.isNotBlank() && password.isNotBlank())),
                        onClick = ::renew,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Renew now") }
                }

                running?.let { Text(progress ?: it, fontWeight = FontWeight.SemiBold) }
                if (waiting) {
                    Section {
                        Text("Apple sent a code to this account’s devices. Tap Allow there, then type it here.")
                        OutlinedTextField(
                            value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) }, singleLine = true,
                            label = { Text("6-digit code") }, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        )
                        Button(enabled = code.length == 6, onClick = { platform.answerCode(code); code = "" }) { Text("Send code") }
                    }
                }
                certificates?.let { list -> CertificateChooser(list, onAnswer = { platform.answerCertificates(it); certificates = null }) }
                message?.let { Text(it) }

                Section {
                    Text("Daily refresh", fontWeight = FontWeight.SemiBold)
                    Text("A Shortcuts automation connects LocalDevVPN and runs “Refresh Nuvio Z” every day, so it never runs out.", fontSize = 13.sp)
                    OutlinedButton(onClick = { showGuide = true }, modifier = Modifier.fillMaxWidth()) { Text("Set up daily refresh") }
                }

                Section {
                    Text("Update test", fontWeight = FontWeight.SemiBold)
                    Text("Signs a newer Nuvio Z on this iPhone and installs it over this one. Needs the saved account.", fontSize = 13.sp)
                    if (updateIpa != null) {
                        Button(enabled = running == null && saved != null, onClick = { update(updateIpa, null) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Install the IPA the installer left")
                        }
                    }
                    OutlinedTextField(value = updateUrl, onValueChange = { updateUrl = it.trim() }, singleLine = true, label = { Text("https:// link to an IPA") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = updateSha, onValueChange = { updateSha = it.trim() }, singleLine = true, label = { Text("SHA-256 (optional)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(
                        enabled = running == null && saved != null && updateUrl.startsWith("https://"),
                        onClick = { update(updateUrl, updateSha.ifBlank { null }) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Install from the link") }
                }

                Section {
                    Text("For the tester", fontWeight = FontWeight.SemiBold)
                    Button(enabled = running == null, modifier = Modifier.fillMaxWidth(), onClick = {
                        running = "Running every check…"
                        scope.launch {
                            val report = platform.testReport()
                            clipboard.setText(AnnotatedString(report))
                            message = "Report copied. Paste it into your message.\n\n$report"
                            running = null
                        }
                    }) { Text("Copy test report") }
                    OutlinedButton(enabled = running == null, modifier = Modifier.fillMaxWidth(), onClick = {
                        scope.launch {
                            val ok = parse(platform.resetSignIn())?.text("ok") == "true"
                            message = if (ok) "Sign-in data reset. The next renewal looks like a new device to Apple and asks for a code." else "Couldn’t reset."
                        }
                    }) { Text("Reset sign-in data") }
                }
                Spacer(Modifier.size(24.dp))
            }
        }
    }
    if (showGuide) DailyRefreshGuide(platform, onDismiss = { showGuide = false })
}

@Composable
private fun Section(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** The account is out of certificates and none is ours: which to revoke (the same rule as the installer). */
@Composable
private fun CertificateChooser(list: List<JsonObject>, onAnswer: (List<String>?) -> Unit) {
    var picked by remember(list) { mutableStateOf(if (list.size == 1) setOfNotNull(list[0].text("serial")) else emptySet()) }
    Section {
        Text("This Apple Account is out of certificates", fontWeight = FontWeight.SemiBold)
        Text("Each sideloading tool makes its own, and a free account holds only a few. Pick one to revoke. Apps signed with it stop opening until the tool that made it signs them again.", fontSize = 13.sp)
        list.forEach { c ->
            val serial = c.text("serial") ?: return@forEach
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = serial in picked, onCheckedChange = { on -> picked = if (on) picked + serial else picked - serial })
                Column {
                    Text(c.text("madeBy") ?: c.text("name") ?: "Unknown tool", fontWeight = FontWeight.SemiBold)
                    Text("${c.text("name") ?: ""}${c.text("expires")?.let { " · expires ${day(it)}" } ?: ""}", fontSize = 12.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { onAnswer(null) }) { Text("Cancel") }
            Button(enabled = picked.isNotEmpty(), onClick = { onAnswer(picked.toList()) }) { Text("Revoke and continue") }
        }
    }
}

/**
 * The daily refresh, as pictures of the screens to tap: iOS lets only the person create an automation. With a hosted
 * shortcut the first four steps become one tap.
 */
@Composable
private fun DailyRefreshGuide(platform: SelfSigningPlatform, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    var shortcutUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { shortcutUrl = platform.refreshShortcutUrl() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Set up daily refresh", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
                Text("About two minutes, once. Afterwards your iPhone renews Nuvio Z every day by itself.")
                val url = shortcutUrl
                if (url != null) {
                    Button(onClick = { uriHandler.openUri(url) }, modifier = Modifier.fillMaxWidth()) { Text("Get the Nuvio Z Refresh shortcut") }
                    Text("Tap Add Shortcut, then make the automation:", fontWeight = FontWeight.SemiBold)
                } else {
                    Text("First, the shortcut", fontWeight = FontWeight.SemiBold)
                    GuideStep(1, "Open Shortcuts and tap +", MockScreen("All Shortcuts", listOf(MockRow("+", highlight = true, trailing = true))))
                    GuideStep(2, "Search “Set VPN” and add it. Tap VPN and pick LocalDevVPN; leave it on Connect", MockScreen("New Shortcut", listOf(MockRow("Set LocalDevVPN to Connect", highlight = true))))
                    GuideStep(3, "Search “Refresh Nuvio Z” and add it", MockScreen("New Shortcut", listOf(MockRow("Set LocalDevVPN to Connect"), MockRow("Refresh Nuvio Z", highlight = true))))
                    GuideStep(
                        4, "Add Set VPN once more, set it to Disconnect, and name the shortcut “Nuvio Z Refresh”",
                        MockScreen("Nuvio Z Refresh", listOf(MockRow("Set LocalDevVPN to Connect"), MockRow("Refresh Nuvio Z"), MockRow("Set LocalDevVPN to Disconnect", highlight = true))),
                    )
                    Text("Then the automation", fontWeight = FontWeight.SemiBold)
                }
                val base = if (url != null) 0 else 4
                GuideStep(base + 1, "Tap Automation at the bottom, then + (New Automation), then Time of Day", MockScreen("New Automation", listOf(MockRow("Time of Day", highlight = true), MockRow("Alarm"), MockRow("Sleep"))))
                GuideStep(
                    base + 2, "Pick a time the iPhone is usually on Wi-Fi, keep Daily, choose Run Immediately, then Next",
                    MockScreen("When", listOf(MockRow("4:00 AM"), MockRow("Daily"), MockRow("Run Immediately", highlight = true, trailing = true))),
                )
                GuideStep(base + 3, "Pick “Nuvio Z Refresh”. Done", MockScreen("My Shortcuts", listOf(MockRow("Nuvio Z Refresh", highlight = true))))
                OutlinedButton(onClick = { uriHandler.openUri("shortcuts://") }, modifier = Modifier.fillMaxWidth()) { Text("Open Shortcuts") }
                Text("To check it: in Shortcuts, run Nuvio Z Refresh once by hand. It should say “Nuvio Z renewed.”", fontSize = 13.sp)
                Spacer(Modifier.size(24.dp))
            }
        }
    }
}

private data class MockRow(val label: String, val highlight: Boolean = false, val trailing: Boolean = false)

private data class MockScreen(val title: String, val rows: List<MockRow>)

private val IosBlue = Color(0xFF0A84FF)
private val Accent = Color(0xFF8F78FF)

/** One step: its number, what to do, and the iOS screen drawn small with the thing to tap ringed. */
@Composable
private fun GuideStep(n: Int, text: String, screen: MockScreen) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).background(Accent, CircleShape), contentAlignment = Alignment.Center) {
                Text("$n", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Spacer(Modifier.width(10.dp))
            Text(text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        Column(
            Modifier.fillMaxWidth().background(Color(0xFF1C1C1E), RoundedCornerShape(18.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(screen.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Column(Modifier.fillMaxWidth().background(Color(0xFF2C2C2E), RoundedCornerShape(10.dp))) {
                screen.rows.forEachIndexed { i, row ->
                    if (i > 0) HorizontalDivider(color = Color(0xFF3A3A3C), thickness = 0.5.dp)
                    Row(
                        Modifier.fillMaxWidth()
                            .then(if (row.highlight) Modifier.border(BorderStroke(2.dp, Accent), RoundedCornerShape(10.dp)) else Modifier)
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(row.label, color = if (row.label == "+") IosBlue else Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        if (row.highlight) {
                            Box(Modifier.size(20.dp).background(Accent, CircleShape), contentAlignment = Alignment.Center) {
                                Text("$n", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (row.trailing && row.label != "+") {
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.size(width = 40.dp, height = 24.dp).background(Color(0xFF34C759), RoundedCornerShape(12.dp)))
                        }
                    }
                }
            }
        }
    }
}

private fun parse(text: String?): JsonObject? = text?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content

/** "2026-10-24T12:00:00Z" → "2026-10-24 12:00 UTC". */
private fun day(iso: String): String = iso.replace('T', ' ').take(16) + " UTC"

private fun describeLast(json: String?): String {
    val last = parse(json) ?: return "never"
    val at = last.text("at")?.let(::day) ?: "?"
    return if (last.text("ok") == "true") "$at by ${last.text("trigger")}, worked (via ${last.text("route")})"
    else "$at by ${last.text("trigger")}, failed: ${last.text("summary")}"
}

private fun describeRenew(json: String): String = runCatching {
    val root = Json.parseToJsonElement(json).jsonObject
    if (root.text("ok") != "true") {
        val error = root["error"]?.jsonObject
        val code = error?.text("code")?.let { " $it" } ?: ""
        return@runCatching when (error?.text("kind")) {
            "tryAgain" -> "Apple couldn’t finish the sign-in$code. Tap Reset sign-in data below, then renew again."
            "codeNeeded" -> "Apple asked for a code. Renew once here by hand."
            "refused" -> "Apple refused the sign-in (-80045). Wait an hour, then try again."
            "wrongPassword" -> "Apple says the email or password is wrong."
            else -> "Failed (${error?.text("kind")}$code): ${error?.text("summary")}"
        }
    }
    val report = root["report"]!!.jsonObject
    val profiles = report["profiles"] as? JsonArray
    if (profiles != null) {
        "Renewed via ${report.text("route")}: " + profiles.joinToString("; ") { p ->
            val o = p.jsonObject
            "${o.text("bundleId")?.substringAfterLast(".nuvio.app.z")?.ifEmpty { "app" } ?: "?"} until ${o.text("expires")?.let(::day) ?: "?"}"
        }
    } else {
        "Installed version ${report.text("version")} via ${report.text("route")}."
    }
}.getOrElse { json }
