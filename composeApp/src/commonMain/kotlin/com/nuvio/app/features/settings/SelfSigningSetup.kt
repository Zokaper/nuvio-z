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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * Phase 2: the first thing a self-signing build shows, over everything (the account sign-in included) until it is
 * done on this iPhone: save the Apple Account Nuvio Z renews itself with, then set up the daily refresh. "Later"
 * hides it until the next launch; done is remembered per device (see `SelfSigningPlatform.automationSetUp`).
 * Presented by SelfSigning.swift (`selfSigningSetup()`); debug-only literal strings like the rest of this feature.
 */
@Composable
internal fun SelfSigningSetupScreen(platform: SelfSigningPlatform, onClose: () -> Unit) {
    var saved by remember { mutableStateOf(platform.savedAccount()) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Keep Nuvio Z working", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                "Apps from a free Apple Account stop opening after 7 days unless they’re renewed. Nuvio Z renews itself " +
                    "every day once these two things are done. About three minutes, once.",
            )

            StepHeading(1, "Your Apple Account", done = saved != null)
            if (saved != null) {
                Text("Saved on this iPhone: $saved", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Connect LocalDevVPN first (open it and tap Connect), then renew once here.", fontSize = 14.sp)
                AppleAccountRenewal(platform, onRenewed = { saved = platform.savedAccount() })
            }

            StepHeading(2, "The daily refresh", done = false)
            Text(
                "One Shortcuts automation renews Nuvio Z every day. Make it in the Shortcuts app while you follow the " +
                    "installer on your computer: its last screens show every tap. Then come back and tap Done.",
            )
            var showHere by remember { mutableStateOf(false) }
            if (showHere) DailyRefreshSteps()
            else TextButton(onClick = { showHere = true }) { Text("No computer nearby? Show the steps here") }

            Button(
                enabled = saved != null,
                onClick = { platform.markAutomationSetUp(true); onClose() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Done, it’s set up") }
            if (saved == null) {
                Text("Save your Apple Account first: the daily refresh renews with it.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Later") }
            Text(
                "Later: this comes back the next time Nuvio Z opens. It’s also in Settings › Advanced › Diagnostics › Keep Nuvio Z working.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun StepHeading(n: Int, title: String, done: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).background(if (done) Color(0xFF34C759) else Accent, CircleShape), contentAlignment = Alignment.Center) {
            Text(if (done) "✓" else "$n", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Spacer(Modifier.width(10.dp))
        Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun SigningSection(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/**
 * Renew now: with the saved account, or with a typed one (saved to the Keychain when Remember stays ticked). Answers
 * Apple's code and the certificate chooser while it runs. The password lives in this state only until the renew starts.
 */
@Composable
internal fun AppleAccountRenewal(platform: SelfSigningPlatform, onRenewed: () -> Unit) {
    val scope = rememberCoroutineScope()
    val saved = remember { mutableStateOf(platform.savedAccount()) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rememberAccount by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    SigningSection {
        Text("Apple Account", fontWeight = FontWeight.SemiBold)
        val account = saved.value
        if (account != null) {
            Text("Saved on this iPhone: $account")
            Text("Nuvio Z renews itself with it when you open the app, from Shortcuts and in the background.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(enabled = !running, onClick = { platform.forgetAccount(); saved.value = null; onRenewed() }) { Text("Forget this account") }
        } else if (!running) {
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
            enabled = !running && (account != null || (email.isNotBlank() && password.isNotBlank())),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                running = true
                message = null
                scope.launch {
                    val typed = account == null
                    val result = platform.refresh(if (typed) email else account!!, if (typed) password else null, typed && rememberAccount)
                    password = ""
                    message = describeRenew(result)
                    saved.value = platform.savedAccount()
                    running = false
                    onRenewed()
                }
            },
        ) { Text(if (running) "Renewing… keep LocalDevVPN connected" else "Renew now") }
        if (running) AppleQuestions(platform)
        message?.let { Text(it) }
    }
}

/** While a sign-in runs: Apple's code and the certificate chooser, when either is waiting. */
@Composable
internal fun AppleQuestions(platform: SelfSigningPlatform) {
    var waiting by remember { mutableStateOf(false) }
    var certificates by remember { mutableStateOf<List<JsonObject>?>(null) }
    var code by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            waiting = platform.waitingForCode()
            certificates = platform.pendingCertificates()?.let { list ->
                runCatching { Json.parseToJsonElement(list).jsonArray.map { it.jsonObject } }.getOrNull()
            }
            delay(500)
        }
    }
    if (waiting) {
        Text("Apple sent a code to this account’s devices. Tap Allow there, then type it here.")
        OutlinedTextField(
            value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) }, singleLine = true,
            label = { Text("6-digit code") }, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        )
        Button(enabled = code.length == 6, onClick = { platform.answerCode(code); code = "" }) { Text("Send code") }
    }
    certificates?.let { list -> CertificateChooser(list, onAnswer = { platform.answerCertificates(it); certificates = null }) }
}

/** The account is out of certificates and none is ours: which to revoke (the same rule as the installer). */
@Composable
private fun CertificateChooser(list: List<JsonObject>, onAnswer: (List<String>?) -> Unit) {
    var picked by remember(list) { mutableStateOf(if (list.size == 1) setOfNotNull(list[0].signingField("serial")) else emptySet()) }
    SigningSection {
        Text("This Apple Account is out of certificates", fontWeight = FontWeight.SemiBold)
        Text("Each sideloading tool makes its own, and a free account holds only a few. Pick one to revoke. Apps signed with it stop opening until the tool that made it signs them again.", fontSize = 13.sp)
        list.forEach { c ->
            val serial = c.signingField("serial") ?: return@forEach
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = serial in picked, onCheckedChange = { on -> picked = if (on) picked + serial else picked - serial })
                Column {
                    Text(c.signingField("madeBy") ?: c.signingField("name") ?: "Unknown tool", fontWeight = FontWeight.SemiBold)
                    Text("${c.signingField("name") ?: ""}${c.signingField("expires")?.let { " · expires ${utcMinute(it)}" } ?: ""}", fontSize = 12.sp)
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
 * The daily refresh, as pictures of the screens to tap: iOS lets only the person create an automation. It is one
 * automation holding the three actions itself, not a shortcut it runs: a shortcut shared as an iCloud link would name
 * the action by this copy's bundle id, which ends with each person's own team id, so it would break for everyone else.
 */
@Composable
internal fun DailyRefreshSteps() {
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("One Shortcuts automation connects LocalDevVPN, runs “Refresh Nuvio Z” and disconnects, every day, so Nuvio Z never runs out.")
        OutlinedButton(onClick = { uriHandler.openUri("shortcuts://") }, modifier = Modifier.fillMaxWidth()) { Text("Open Shortcuts") }
        GuideStep(1, "Tap Automation at the bottom, then + (New Automation), then Time of Day", MockScreen("New Automation", listOf(MockRow("Time of Day", highlight = true), MockRow("Alarm"), MockRow("Sleep"))))
        GuideStep(
            2, "Pick a time the iPhone is usually on Wi-Fi, keep Daily, choose Run Immediately, then Next",
            MockScreen("When", listOf(MockRow("4:00 AM"), MockRow("Daily"), MockRow("Run Immediately", highlight = true, trailing = true))),
        )
        GuideStep(3, "Tap New Blank Automation", MockScreen("Automation", listOf(MockRow("New Blank Automation", highlight = true))))
        GuideStep(
            4, "Add Action: search “Set VPN”, add it, tap VPN and pick LocalDevVPN (it stays on Connect). Then search “Refresh Nuvio Z” and add it",
            MockScreen("Do", listOf(MockRow("Set LocalDevVPN to Connect", highlight = true), MockRow("Refresh Nuvio Z", highlight = true))),
        )
        GuideStep(
            5, "Add Set VPN once more, tap Connect and change it to Disconnect. Tap Done",
            MockScreen("Do", listOf(MockRow("Set LocalDevVPN to Connect"), MockRow("Refresh Nuvio Z"), MockRow("Set LocalDevVPN to Disconnect", highlight = true))),
        )
        Text("It runs by itself from tomorrow. Nuvio Z notices it once it has renewed on two different days.", fontSize = 13.sp)
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

internal fun parseSigningJson(text: String?): JsonObject? = text?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }

internal fun JsonObject.signingField(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content

/** "2026-10-24T12:00:00Z" → "2026-10-24 12:00 UTC". */
internal fun utcMinute(iso: String): String = iso.replace('T', ' ').take(16) + " UTC"

internal fun describeRenew(json: String): String = runCatching {
    val root = Json.parseToJsonElement(json).jsonObject
    if (root.signingField("ok") != "true") {
        val error = root["error"]?.jsonObject
        val code = error?.signingField("code")?.let { " $it" } ?: ""
        return@runCatching when (error?.signingField("kind")) {
            "tryAgain" -> "Apple couldn’t finish the sign-in$code. Wait a minute, then renew again."
            "serverUnavailable" -> "Apple’s sign-in service is busy. Try again in a minute."
            "codeNeeded" -> "Apple asked for a code. Renew once here by hand."
            "refused" -> "Apple refused the sign-in (-80045). Wait an hour, then try again."
            "wrongPassword" -> "Apple says the email or password is wrong."
            else -> "Failed (${error?.signingField("kind")}$code): ${error?.signingField("summary")}"
        }
    }
    val report = root["report"]!!.jsonObject
    val profiles = report["profiles"] as? JsonArray
    if (profiles != null) {
        "Renewed via ${report.signingField("route")}: " + profiles.joinToString("; ") { p ->
            val o = p.jsonObject
            "${o.signingField("bundleId")?.substringAfterLast(".nuvio.app.z")?.ifEmpty { "app" } ?: "?"} until ${o.signingField("expires")?.let(::utcMinute) ?: "?"}"
        }
    } else {
        "Installed version ${report.signingField("version")} via ${report.signingField("route")}."
    }
}.getOrElse { json }
