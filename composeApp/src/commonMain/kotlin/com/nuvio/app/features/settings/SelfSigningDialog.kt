package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.debug.SelfSigningSpike
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Debug-only (Phase 2): renew this copy of Nuvio Z on the phone, the way SideStore's Refresh does it, and show
 * whether it took. Literal strings, like the other debug rows: this never reaches a shipped build.
 *
 * The password lives only in this dialog's state for the length of the refresh; nothing here stores it.
 */
@Composable
internal fun SelfSigningDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var embedded by remember { mutableStateOf("…") }
    var installed by remember { mutableStateOf("Tap “Check phone” (LocalDevVPN must be connected)") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var waiting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        embedded = SelfSigningSpike.status?.invoke()?.let(::describeStatus) ?: "unavailable"
    }
    LaunchedEffect(running) {
        while (running) {
            waiting = SelfSigningSpike.waitingForCode?.invoke() == true
            delay(500)
        }
        waiting = false
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("Self-signing (debug)") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("This copy’s own signature", fontWeight = FontWeight.SemiBold)
                Text(embedded)
                Text("Profiles installed on the phone", fontWeight = FontWeight.SemiBold)
                Text(installed)
                if (!running) {
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
                }
                if (waiting) {
                    Text("Apple sent a code to this account’s devices. Tap Allow there, then type it here.")
                    OutlinedTextField(
                        value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) }, singleLine = true,
                        label = { Text("6-digit code") }, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                    TextButton(enabled = code.length == 6, onClick = {
                        SelfSigningSpike.answerCode?.invoke(code)
                        code = ""
                    }) { Text("Send code") }
                }
                result?.let { Text(it) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !running && email.isNotBlank() && password.isNotBlank(),
                onClick = {
                    running = true
                    result = "Renewing… (keep LocalDevVPN connected)"
                    scope.launch {
                        val json = SelfSigningSpike.refresh?.invoke(email, password)
                        password = ""
                        result = json?.let(::describeRefresh) ?: "unavailable"
                        running = false
                    }
                },
            ) { Text("Renew now") }
        },
        dismissButton = {
            TextButton(enabled = !running, onClick = {
                installed = "Checking…"
                scope.launch { installed = SelfSigningSpike.installedProfiles?.invoke()?.let(::describeInstalled) ?: "unavailable" }
            }) { Text("Check phone") }
            TextButton(enabled = !running, onClick = onDismiss) { Text("Close") }
        },
    )
}

private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content?.takeIf { it != "null" }

/** "2026-10-24T12:00:00Z" → "2026-10-24 12:00 UTC". */
private fun day(iso: String?): String = iso?.replace('T', ' ')?.take(16)?.plus(" UTC") ?: "unknown"

private fun describeStatus(json: String): String = runCatching {
    val root = Json.parseToJsonElement(json).jsonObject
    val ids = root["bundleIds"]?.jsonArray?.size ?: 0
    "Expires ${day(root.text("expires"))} ($ids bundle ids)"
}.getOrElse { json }

private fun describeProfiles(profiles: List<JsonObject>): String = profiles.joinToString("\n") { p ->
    val id = p.text("bundleId").orEmpty().substringAfterLast(".nuvio.app.z")
    "${id.ifEmpty { "app" }}: ${day(p.text("expires"))}"
}

private fun describeInstalled(json: String): String = runCatching {
    val root = Json.parseToJsonElement(json).jsonObject
    if (root.text("ok") != "true") return@runCatching "Failed: ${root.text("error")}"
    "Via ${root.text("route")}:\n" + describeProfiles(root["profiles"]!!.jsonArray.map { it.jsonObject })
}.getOrElse { json }

private fun describeRefresh(json: String): String = runCatching {
    val root = Json.parseToJsonElement(json).jsonObject
    if (root.text("ok") != "true") {
        val error = root["error"]?.jsonObject
        return@runCatching "Failed (${error?.text("kind")}${error?.text("code")?.let { " $it" } ?: ""}): ${error?.text("summary")}"
    }
    val report = root["report"]!!.jsonObject
    "Renewed via ${report.text("route")}:\n" + describeProfiles(report["profiles"]!!.jsonArray.map { it.jsonObject })
}.getOrElse { json }
