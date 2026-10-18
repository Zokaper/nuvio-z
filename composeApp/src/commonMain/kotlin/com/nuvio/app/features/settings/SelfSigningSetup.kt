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
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.selfsign_account_heading
import nuvio.composeapp.generated.resources.selfsign_account_renews_with
import nuvio.composeapp.generated.resources.selfsign_certs_body
import nuvio.composeapp.generated.resources.selfsign_certs_expires
import nuvio.composeapp.generated.resources.selfsign_certs_title
import nuvio.composeapp.generated.resources.selfsign_certs_unknown_tool
import nuvio.composeapp.generated.resources.selfsign_code_label
import nuvio.composeapp.generated.resources.selfsign_code_prompt
import nuvio.composeapp.generated.resources.selfsign_connect_vpn_first
import nuvio.composeapp.generated.resources.selfsign_daily_cover_body
import nuvio.composeapp.generated.resources.selfsign_daily_intro
import nuvio.composeapp.generated.resources.selfsign_done_set_up
import nuvio.composeapp.generated.resources.selfsign_email
import nuvio.composeapp.generated.resources.selfsign_err_busy
import nuvio.composeapp.generated.resources.selfsign_err_code_needed
import nuvio.composeapp.generated.resources.selfsign_err_other
import nuvio.composeapp.generated.resources.selfsign_err_refused
import nuvio.composeapp.generated.resources.selfsign_err_try_again
import nuvio.composeapp.generated.resources.selfsign_err_vpn
import nuvio.composeapp.generated.resources.selfsign_err_wrong_password
import nuvio.composeapp.generated.resources.selfsign_forget_account
import nuvio.composeapp.generated.resources.selfsign_guide_1
import nuvio.composeapp.generated.resources.selfsign_guide_2
import nuvio.composeapp.generated.resources.selfsign_guide_3
import nuvio.composeapp.generated.resources.selfsign_guide_4
import nuvio.composeapp.generated.resources.selfsign_guide_5
import nuvio.composeapp.generated.resources.selfsign_guide_6
import nuvio.composeapp.generated.resources.selfsign_guide_footer
import nuvio.composeapp.generated.resources.selfsign_intro
import nuvio.composeapp.generated.resources.selfsign_later
import nuvio.composeapp.generated.resources.selfsign_later_note
import nuvio.composeapp.generated.resources.selfsign_open_shortcuts
import nuvio.composeapp.generated.resources.selfsign_password
import nuvio.composeapp.generated.resources.selfsign_password_note
import nuvio.composeapp.generated.resources.selfsign_remember
import nuvio.composeapp.generated.resources.selfsign_renew_now
import nuvio.composeapp.generated.resources.selfsign_renewed
import nuvio.composeapp.generated.resources.selfsign_renewed_until
import nuvio.composeapp.generated.resources.selfsign_renewing
import nuvio.composeapp.generated.resources.selfsign_resend_code
import nuvio.composeapp.generated.resources.selfsign_revoke_continue
import nuvio.composeapp.generated.resources.selfsign_save_account_first
import nuvio.composeapp.generated.resources.selfsign_saved_on_iphone
import nuvio.composeapp.generated.resources.selfsign_send_code
import nuvio.composeapp.generated.resources.selfsign_show_steps_here
import nuvio.composeapp.generated.resources.selfsign_step_account
import nuvio.composeapp.generated.resources.selfsign_step_daily
import nuvio.composeapp.generated.resources.selfsign_title
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * The first thing a self-signing build shows, over everything (the account sign-in included) until it is done on
 * this iPhone: save the Apple Account Nuvio Z renews itself with, then set up the daily refresh. "Later" hides it
 * until the next launch; done is remembered per device (see `SelfSigningPlatform.automationSetUp`). Presented by
 * SelfSigning.swift (`selfSigningSetup()`). The rest of the feature is the "Keep Nuvio Z working" settings page.
 */
@Composable
internal fun SelfSigningSetupScreen(platform: SelfSigningPlatform, onClose: () -> Unit) {
    var saved by remember { mutableStateOf(platform.savedAccount()) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(Res.string.selfsign_title), fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(Res.string.selfsign_intro))

            StepHeading(1, stringResource(Res.string.selfsign_step_account), done = saved != null)
            val account = saved
            if (account != null) {
                Text(stringResource(Res.string.selfsign_saved_on_iphone, account), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(stringResource(Res.string.selfsign_connect_vpn_first), fontSize = 14.sp)
                AppleAccountRenewal(platform, onRenewed = { saved = platform.savedAccount() })
            }

            StepHeading(2, stringResource(Res.string.selfsign_step_daily), done = false)
            Text(stringResource(Res.string.selfsign_daily_cover_body))
            val uriHandler = LocalUriHandler.current
            OutlinedButton(onClick = { uriHandler.openUri("shortcuts://") }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.selfsign_open_shortcuts))
            }
            var showHere by remember { mutableStateOf(false) }
            if (showHere) DailyRefreshSteps()
            else TextButton(onClick = { showHere = true }) { Text(stringResource(Res.string.selfsign_show_steps_here)) }

            Button(
                enabled = saved != null,
                onClick = { platform.markAutomationSetUp(true); onClose() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(Res.string.selfsign_done_set_up)) }
            if (saved == null) {
                Text(stringResource(Res.string.selfsign_save_account_first), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.selfsign_later)) }
            Text(stringResource(Res.string.selfsign_later_note), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Text(stringResource(Res.string.selfsign_account_heading), fontWeight = FontWeight.SemiBold)
        val account = saved.value
        if (account != null) {
            Text(stringResource(Res.string.selfsign_saved_on_iphone, account))
            Text(stringResource(Res.string.selfsign_account_renews_with), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(enabled = !running, onClick = { platform.forgetAccount(); saved.value = null; onRenewed() }) {
                Text(stringResource(Res.string.selfsign_forget_account))
            }
        } else if (!running) {
            OutlinedTextField(
                value = email, onValueChange = { email = it.trim() }, singleLine = true,
                label = { Text(stringResource(Res.string.selfsign_email)) }, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it }, singleLine = true,
                label = { Text(stringResource(Res.string.selfsign_password)) }, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = rememberAccount, onCheckedChange = { rememberAccount = it })
                Text(stringResource(Res.string.selfsign_remember))
            }
            Text(stringResource(Res.string.selfsign_password_note), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    message = describeRenew(result, platform)
                    saved.value = platform.savedAccount()
                    running = false
                    onRenewed()
                }
            },
        ) { Text(stringResource(if (running) Res.string.selfsign_renewing else Res.string.selfsign_renew_now)) }
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
        Text(stringResource(Res.string.selfsign_code_prompt))
        OutlinedTextField(
            value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) }, singleLine = true,
            label = { Text(stringResource(Res.string.selfsign_code_label)) }, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = code.length == 6, onClick = { platform.answerCode(code); code = "" }) { Text(stringResource(Res.string.selfsign_send_code)) }
            TextButton(onClick = { platform.resendCode(); code = "" }) { Text(stringResource(Res.string.selfsign_resend_code)) }
        }
    }
    certificates?.let { list -> CertificateChooser(list, platform, onAnswer = { platform.answerCertificates(it); certificates = null }) }
}

/** The account is out of certificates and none is ours: which to revoke (the same rule as the installer). */
@Composable
private fun CertificateChooser(list: List<JsonObject>, platform: SelfSigningPlatform, onAnswer: (List<String>?) -> Unit) {
    var picked by remember(list) { mutableStateOf(if (list.size == 1) setOfNotNull(list[0].signingField("serial")) else emptySet()) }
    SigningSection {
        Text(stringResource(Res.string.selfsign_certs_title), fontWeight = FontWeight.SemiBold)
        Text(stringResource(Res.string.selfsign_certs_body), fontSize = 13.sp)
        list.forEach { c ->
            val serial = c.signingField("serial") ?: return@forEach
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = serial in picked, onCheckedChange = { on -> picked = if (on) picked + serial else picked - serial })
                Column {
                    Text(c.signingField("madeBy") ?: c.signingField("name") ?: stringResource(Res.string.selfsign_certs_unknown_tool), fontWeight = FontWeight.SemiBold)
                    val expires = c.signingField("expires")?.let { platform.localDate(it) }
                    Text(listOfNotNull(c.signingField("name"), expires?.let { stringResource(Res.string.selfsign_certs_expires, it) }).joinToString(" · "), fontSize = 12.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { onAnswer(null) }) { Text(stringResource(Res.string.action_cancel)) }
            Button(enabled = picked.isNotEmpty(), onClick = { onAnswer(picked.toList()) }) { Text(stringResource(Res.string.selfsign_revoke_continue)) }
        }
    }
}

/**
 * The daily refresh, as pictures of the screens to tap: iOS lets only the person create an automation. It is one
 * automation holding the three actions itself, not a shortcut it runs: a shortcut shared as an iCloud link would name
 * the action by this copy's bundle id, which ends with each person's own team id, so it would break for everyone else.
 * The drawn screens keep iOS's own English labels, like the installer's pictures: they show the phone, not our text.
 */
@Composable
internal fun DailyRefreshSteps() {
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(Res.string.selfsign_daily_intro))
        OutlinedButton(onClick = { uriHandler.openUri("shortcuts://") }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.selfsign_open_shortcuts))
        }
        GuideStep(1, stringResource(Res.string.selfsign_guide_1), MockScreen("Automation", listOf(MockRow("New Automation", highlight = true))))
        GuideStep(2, stringResource(Res.string.selfsign_guide_2), MockScreen("Personal Automation", listOf(MockRow("Time of Day", highlight = true), MockRow("Alarm"), MockRow("Sleep"))))
        GuideStep(
            3, stringResource(Res.string.selfsign_guide_3),
            MockScreen("When", listOf(MockRow("4:00 AM"), MockRow("Daily"), MockRow("Run Immediately", highlight = true))),
        )
        GuideStep(4, stringResource(Res.string.selfsign_guide_4), MockScreen("Get Started", listOf(MockRow("Create New Shortcut", highlight = true))))
        GuideStep(
            5, stringResource(Res.string.selfsign_guide_5),
            MockScreen("New automation", listOf(MockRow("Connect to LocalDevVPN", highlight = true), MockRow("Refresh Nuvio Z", highlight = true))),
        )
        GuideStep(
            6, stringResource(Res.string.selfsign_guide_6),
            MockScreen("New automation", listOf(MockRow("Connect to LocalDevVPN"), MockRow("Refresh Nuvio Z"), MockRow("Disconnect from LocalDevVPN", highlight = true))),
        )
        Text(stringResource(Res.string.selfsign_guide_footer), fontSize = 13.sp)
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

/** "2026-10-24T12:00:00Z" → "2026-10-24 12:00 UTC". For the debug tools and reports, which want exact times. */
internal fun utcMinute(iso: String): String = iso.replace('T', ' ').take(16) + " UTC"

/** A renewal's or an update's result as one sentence for the person. */
internal suspend fun describeRenew(json: String, platform: SelfSigningPlatform): String {
    val root = parseSigningJson(json) ?: return getString(Res.string.selfsign_err_other, json.take(200))
    if (root.signingField("ok") != "true") {
        val error = root["error"] as? JsonObject
        val summary = error?.signingField("summary") ?: root.signingField("error") ?: json.take(200)
        return when (error?.signingField("kind")) {
            "tryAgain" -> getString(Res.string.selfsign_err_try_again)
            "serverUnavailable" -> getString(Res.string.selfsign_err_busy)
            "codeNeeded" -> getString(Res.string.selfsign_err_code_needed)
            "refused" -> getString(Res.string.selfsign_err_refused)
            "wrongPassword" -> getString(Res.string.selfsign_err_wrong_password)
            "network" -> getString(Res.string.selfsign_err_vpn)
            else -> getString(Res.string.selfsign_err_other, summary)
        }
    }
    val report = root["report"] as? JsonObject
    val profiles = report?.get("profiles") as? JsonArray
    val until = profiles?.firstOrNull()?.jsonObject?.signingField("expires")?.let { platform.localDate(it) }
    return if (until != null) getString(Res.string.selfsign_renewed_until, until) else getString(Res.string.selfsign_renewed)
}
