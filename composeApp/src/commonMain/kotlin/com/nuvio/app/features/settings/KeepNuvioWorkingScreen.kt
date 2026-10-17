package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nuvio.app.core.debug.SelfSigningPlatform
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Debug-only (Phase 2): "Keep Nuvio Z working". When this copy stops opening, renewing it the way SideStore's
 * Refresh does (by hand here, or by itself from Shortcuts, on opening the app and in the background), the daily
 * refresh set-up, the update test and the tester's report. Literal strings, like the other debug rows: this never
 * reaches a shipped build. The pieces it shares with the launch screen are in SelfSigningSetup.kt.
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
    var automation by remember { mutableStateOf(platform.automationSetUp()) }
    var running by remember { mutableStateOf<String?>(null) }
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
        val root = parseSigningJson(platform.installedProfiles())
        if (root != null && root.signingField("ok") == "true") {
            installed = (root["profiles"] as? JsonArray)?.firstOrNull()?.jsonObject?.signingField("expires")
            installedNote = "Read from the phone via ${root.signingField("route")}"
        } else {
            installedNote = "Couldn’t read it: ${root?.signingField("error") ?: "no result"}"
        }
    }

    LaunchedEffect(Unit) {
        embedded = parseSigningJson(platform.status())?.signingField("expires")
        checkPhone()
    }
    LaunchedEffect(running) {
        while (running != null) {
            progress = platform.updateProgress()?.let(::parseSigningJson)?.let { p ->
                val stage = when (p.signingField("stage")) { "download" -> "Downloading"; "sign" -> "Signing"; "install" -> "Installing"; else -> "Working" }
                val pct = p.signingField("fraction")?.toDoubleOrNull()?.let { (it * 100).toInt() } ?: 0
                "$stage… $pct%"
            }
            delay(500)
        }
        progress = null
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

                SigningSection {
                    val best = listOfNotNull(embedded, installed).maxOrNull()
                    Text(best?.let { "Opens until ${utcMinute(it)}" } ?: "Opens until …", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("This copy’s own signature: ${embedded?.let(::utcMinute) ?: "…"}")
                    Text("Newest renewal on the phone: ${installed?.let(::utcMinute) ?: "—"}")
                    Text(installedNote, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    Text(
                        "LocalDevVPN: " + when (vpn) { true -> "connected"; false -> "not connected"; null -> "checking…" },
                        color = if (vpn == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    Text("Last renewal: ${describeLast(last)}", fontSize = 13.sp)
                }

                AppleAccountRenewal(platform, onRenewed = {
                    saved = platform.savedAccount()
                    last = platform.lastRefresh()
                    scope.launch { checkPhone() }
                })

                SigningSection {
                    Text("Daily refresh", fontWeight = FontWeight.SemiBold)
                    Text(if (automation) "Set up on this iPhone." else "Not set up yet: a Shortcuts automation renews Nuvio Z every day so it never runs out.", fontSize = 13.sp)
                    OutlinedButton(onClick = { showGuide = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (automation) "Show the set-up steps" else "Set up daily refresh")
                    }
                }

                SigningSection {
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
                    running?.let {
                        Text(progress ?: it, fontWeight = FontWeight.SemiBold)
                        AppleQuestions(platform)
                    }
                }
                message?.let { Text(it) }

                SigningSection {
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
                            val ok = parseSigningJson(platform.resetSignIn())?.signingField("ok") == "true"
                            message = if (ok) "Sign-in data reset. The next renewal looks like a new device to Apple and asks for a code." else "Couldn’t reset."
                        }
                    }) { Text("Reset sign-in data") }
                }
                Spacer(Modifier.size(24.dp))
            }
        }
    }
    if (showGuide) {
        Dialog(onDismissRequest = { showGuide = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Set up daily refresh", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showGuide = false }) { Text("Close") }
                    }
                    DailyRefreshSteps(platform)
                    Button(onClick = { platform.markAutomationSetUp(true); automation = true; showGuide = false }, modifier = Modifier.fillMaxWidth()) {
                        Text("Done, it’s set up")
                    }
                    Spacer(Modifier.size(24.dp))
                }
            }
        }
    }
}

private fun describeLast(json: String?): String {
    val last = parseSigningJson(json) ?: return "never"
    val at = last.signingField("at")?.let(::utcMinute) ?: "?"
    return if (last.signingField("ok") == "true") "$at by ${last.signingField("trigger")}, worked (via ${last.signingField("route")})"
    else "$at by ${last.signingField("trigger")}, failed: ${last.signingField("summary")}"
}
