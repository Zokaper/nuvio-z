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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Schedule
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
import androidx.compose.runtime.produceState
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
import com.nuvio.app.core.debug.isDebugBuild
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.selfsign_checking
import nuvio.composeapp.generated.resources.selfsign_copy_report
import nuvio.composeapp.generated.resources.selfsign_copy_report_description
import nuvio.composeapp.generated.resources.selfsign_daily_off
import nuvio.composeapp.generated.resources.selfsign_daily_on
import nuvio.composeapp.generated.resources.selfsign_done_set_up
import nuvio.composeapp.generated.resources.selfsign_last_failed
import nuvio.composeapp.generated.resources.selfsign_last_ok
import nuvio.composeapp.generated.resources.selfsign_last_renewal
import nuvio.composeapp.generated.resources.selfsign_never
import nuvio.composeapp.generated.resources.selfsign_opens_until
import nuvio.composeapp.generated.resources.selfsign_report_copied
import nuvio.composeapp.generated.resources.selfsign_reset_done
import nuvio.composeapp.generated.resources.selfsign_reset_failed
import nuvio.composeapp.generated.resources.selfsign_reset_sign_in
import nuvio.composeapp.generated.resources.selfsign_reset_sign_in_description
import nuvio.composeapp.generated.resources.selfsign_section_daily
import nuvio.composeapp.generated.resources.selfsign_section_help
import nuvio.composeapp.generated.resources.selfsign_section_status
import nuvio.composeapp.generated.resources.selfsign_set_up_daily
import nuvio.composeapp.generated.resources.selfsign_show_steps
import nuvio.composeapp.generated.resources.selfsign_trigger_app
import nuvio.composeapp.generated.resources.selfsign_trigger_background
import nuvio.composeapp.generated.resources.selfsign_trigger_open
import nuvio.composeapp.generated.resources.selfsign_trigger_shortcut
import nuvio.composeapp.generated.resources.selfsign_vpn
import nuvio.composeapp.generated.resources.selfsign_vpn_connected
import nuvio.composeapp.generated.resources.selfsign_vpn_not_connected
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * Settings › Keep Nuvio Z working (iPhone builds that sign themselves only): when this copy stops opening, the last
 * renewal, LocalDevVPN, renewing by hand, the daily refresh, and help (a report to send, resetting the sign-in).
 * Debug builds add a Developer section with the update test and the route probe; those strings stay literal, like
 * the other debug rows. The launch set-up shares its pieces from SelfSigningSetup.kt.
 */
internal fun LazyListScope.keepNuvioWorkingSettingsContent(isTablet: Boolean, platform: SelfSigningPlatform) {
    item(key = "selfsign-status") { SigningStatus(platform, isTablet) }
    item(key = "selfsign-account") {
        Column(Modifier.padding(horizontal = if (isTablet) 0.dp else 16.dp, vertical = 8.dp)) {
            AppleAccountRenewal(platform, onRenewed = { SigningStatusRefresh.bump() })
        }
    }
    item(key = "selfsign-daily") { DailyRefreshSection(platform, isTablet) }
    item(key = "selfsign-help") { SigningHelp(platform, isTablet) }
    if (isDebugBuild) item(key = "selfsign-developer") { SigningDeveloperTools(platform, isTablet) }
}

/** Renewing elsewhere on the page re-reads the status. */
private object SigningStatusRefresh {
    var generation by mutableStateOf(0)
    fun bump() { generation++ }
}

@Composable
private fun SigningStatus(platform: SelfSigningPlatform, isTablet: Boolean) {
    val generation = SigningStatusRefresh.generation
    val until by produceState<String?>(null, generation) { value = platform.signedUntilLabel() }
    val vpn by produceState<Boolean?>(null, generation) { value = platform.vpnReachable() }
    val last = remember(generation) { describeLast(platform) }
    val lastText by produceState<String?>(null, last) { value = last() }
    val checking = stringResource(Res.string.selfsign_checking)
    SettingsSection(title = stringResource(Res.string.selfsign_section_status), isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            SettingsNavigationRow(
                title = stringResource(Res.string.selfsign_opens_until),
                description = until ?: checking,
                icon = Icons.Rounded.Schedule,
                isTablet = isTablet,
                onClick = { SigningStatusRefresh.bump() },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = stringResource(Res.string.selfsign_last_renewal),
                description = lastText ?: checking,
                icon = Icons.Rounded.Autorenew,
                isTablet = isTablet,
                onClick = { SigningStatusRefresh.bump() },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = stringResource(Res.string.selfsign_vpn),
                description = when (vpn) {
                    true -> stringResource(Res.string.selfsign_vpn_connected)
                    false -> stringResource(Res.string.selfsign_vpn_not_connected)
                    null -> checking
                },
                isTablet = isTablet,
                onClick = { SigningStatusRefresh.bump() },
            )
        }
    }
}

/** The last renewal as one line ("Today 04:00, by the daily refresh"), read when the status is. */
private fun describeLast(platform: SelfSigningPlatform): suspend () -> String = suspend {
    val last = parseSigningJson(platform.lastRefresh())
    if (last == null) {
        getString(Res.string.selfsign_never)
    } else {
        val at = last.signingField("at")?.let { platform.localDateTime(it) } ?: "?"
        if (last.signingField("ok") == "true") {
            val how = when (last.signingField("trigger")) {
                "shortcut" -> getString(Res.string.selfsign_trigger_shortcut)
                "open" -> getString(Res.string.selfsign_trigger_open)
                "background" -> getString(Res.string.selfsign_trigger_background)
                else -> getString(Res.string.selfsign_trigger_app)
            }
            getString(Res.string.selfsign_last_ok, at, how)
        } else {
            getString(Res.string.selfsign_last_failed, at, last.signingField("summary") ?: last.signingField("kind") ?: "?")
        }
    }
}

@Composable
private fun DailyRefreshSection(platform: SelfSigningPlatform, isTablet: Boolean) {
    var automation by remember { mutableStateOf(platform.automationSetUp()) }
    var showGuide by remember { mutableStateOf(false) }
    SettingsSection(title = stringResource(Res.string.selfsign_section_daily), isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            SettingsNavigationRow(
                title = stringResource(if (automation) Res.string.selfsign_show_steps else Res.string.selfsign_set_up_daily),
                description = stringResource(if (automation) Res.string.selfsign_daily_on else Res.string.selfsign_daily_off),
                icon = Icons.Rounded.Schedule,
                isTablet = isTablet,
                onClick = { showGuide = true },
            )
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
                        Text(stringResource(Res.string.selfsign_set_up_daily), fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showGuide = false }) { Text(stringResource(Res.string.action_close)) }
                    }
                    DailyRefreshSteps()
                    Button(onClick = { platform.markAutomationSetUp(true); automation = true; showGuide = false }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.selfsign_done_set_up))
                    }
                    Spacer(Modifier.size(24.dp))
                }
            }
        }
    }
}

@Composable
private fun SigningHelp(platform: SelfSigningPlatform, isTablet: Boolean) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var note by remember { mutableStateOf<String?>(null) }
    SettingsSection(title = stringResource(Res.string.selfsign_section_help), isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            SettingsNavigationRow(
                title = stringResource(Res.string.selfsign_copy_report),
                description = stringResource(Res.string.selfsign_copy_report_description),
                icon = Icons.Rounded.ContentCopy,
                isTablet = isTablet,
                onClick = {
                    note = null
                    scope.launch {
                        clipboard.setText(AnnotatedString(platform.testReport()))
                        note = getString(Res.string.selfsign_report_copied)
                    }
                },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = stringResource(Res.string.selfsign_reset_sign_in),
                description = stringResource(Res.string.selfsign_reset_sign_in_description),
                icon = Icons.Rounded.RestartAlt,
                isTablet = isTablet,
                onClick = {
                    note = null
                    scope.launch {
                        val ok = parseSigningJson(platform.resetSignIn())?.signingField("ok") == "true"
                        note = getString(if (ok) Res.string.selfsign_reset_done else Res.string.selfsign_reset_failed)
                    }
                },
            )
        }
        note?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Debug builds only: the update test (an IPA the installer left, or a link) and the route probe. Literal strings. */
@Composable
private fun SigningDeveloperTools(platform: SelfSigningPlatform, isTablet: Boolean) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var updateUrl by remember { mutableStateOf("") }
    var updateSha by remember { mutableStateOf("") }
    val updateIpa = remember { platform.updateTestIpa() }
    LaunchedEffect(running) {
        while (running != null) {
            progress = platform.updateProgress()?.let(::parseSigningJson)?.let { p ->
                val stage = when (p.signingField("stage")) { "download" -> "Downloading"; "sign" -> "Signing"; "install" -> "Installing"; else -> "Working" }
                "$stage… ${p.signingField("fraction")?.toDoubleOrNull()?.let { (it * 100).toInt() } ?: 0}%"
            }
            delay(500)
        }
        progress = null
    }
    fun update(source: String, sha: String?) {
        running = "Installing… Nuvio Z closes when it finishes. Open it again afterwards."
        message = null
        scope.launch {
            message = describeRenew(platform.installUpdate(source, sha), platform)
            running = null
        }
    }
    SettingsSection(title = "Developer (debug builds)", isTablet = isTablet) {
        Column(Modifier.padding(horizontal = if (isTablet) 0.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SigningSection {
                Text("Update test", fontWeight = FontWeight.SemiBold)
                Text("Signs an IPA on this iPhone and installs it over this copy. Needs the saved account.", fontSize = 13.sp)
                if (updateIpa != null) {
                    Button(enabled = running == null, onClick = { update(updateIpa, null) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Install the IPA the installer left")
                    }
                }
                OutlinedTextField(value = updateUrl, onValueChange = { updateUrl = it.trim() }, singleLine = true, label = { Text("https:// link to an IPA") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = updateSha, onValueChange = { updateSha = it.trim() }, singleLine = true, label = { Text("SHA-256 (optional)") }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(
                    enabled = running == null && updateUrl.startsWith("https://"),
                    onClick = { update(updateUrl, updateSha.ifBlank { null }) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Install from the link") }
                running?.let {
                    Text(progress ?: it, fontWeight = FontWeight.SemiBold)
                    AppleQuestions(platform)
                }
                message?.let { Text(it) }
            }
            SigningSection {
                var probe by remember { mutableStateOf<String?>(null) }
                Text("Reach this iPhone", fontWeight = FontWeight.SemiBold)
                Text(probe ?: "Connects through LocalDevVPN by both routes and lists the apps.", fontSize = 13.sp)
                OutlinedButton(onClick = { probe = "Connecting…"; scope.launch { probe = describeProbe(platform.probe()) } }, modifier = Modifier.fillMaxWidth()) {
                    Text("Probe")
                }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}

private fun describeProbe(json: String): String = runCatching {
    val routes = parseSigningJson(json)?.get("probe")?.jsonObject?.get("routes") as? JsonArray ?: return@runCatching json.take(300)
    routes.joinToString("\n") { element ->
        val route = element.jsonObject
        if (route.signingField("ok") == "true") "${route.signingField("route")}: works, ${route.signingField("apps")} apps (${route.signingField("millis")} ms)"
        else "${route.signingField("route")}: ${route.signingField("error")}"
    }
}.getOrElse { json.take(300) }
