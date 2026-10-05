package com.nuvio.z.iossetup

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
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

internal val NuvioBlue = Color(0xFF4D8DFF)
internal val BlueText = Color(0xFF9FC4FF)
internal val Success = Color(0xFF67D99B)
internal val Warning = Color(0xFFFFD166)
internal val AppBackground = Color(0xFF09111E)
internal val RailColor = Color(0xFF0E1828)
internal val CardColor = Color(0xFF172337)
internal val TextPrimary = Color(0xFFF6F8FC)
internal val TextSecondary = Color(0xFFC3CEE0)
internal val TextMuted = Color(0xFF94A6BF)

@Composable internal fun ManualConfirmation(text: String, confirmed: Boolean, onConfirmed: (Boolean) -> Unit) {
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
@Composable internal fun Troubleshooting(tips: List<TroubleTip>) {
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
@Composable internal fun SectionLabel(text: String) = Text(text, color = BlueText, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
@Composable internal fun Instructions(items: List<String>) { items.forEachIndexed { index, text -> Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) { Surface(color = Color(0xFF244A7D), shape = RoundedCornerShape(99.dp)) { Box(Modifier.size(25.dp), contentAlignment = Alignment.Center) { Text("${index + 1}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) } }; Spacer(Modifier.width(11.dp)); Text(text, color = TextPrimary, lineHeight = 20.sp, modifier = Modifier.padding(top = 2.dp)) } } }
@Composable internal fun InfoRows(items: List<String>) { items.forEach { Row(Modifier.padding(vertical = 4.dp)) { Text("✓", color = Success, fontWeight = FontWeight.Bold); Spacer(Modifier.width(9.dp)); Text(it, color = TextPrimary) } } }
@Composable internal fun InfoCallout(title: String, text: String) = Callout(Color(0xFF152D50), BlueText, title, text)
@Composable internal fun WarningCallout(title: String, text: String) = Callout(Color(0xFF3B3018), Warning, title, text)
@Composable internal fun Callout(background: Color, accent: Color, title: String, text: String) { Surface(color = background, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(title, color = accent, fontWeight = FontWeight.Bold, fontSize = 13.sp); Text(text, color = Color.White, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp)) } } }
@Composable internal fun CheckRow(value: CheckResult) { val pass = value.state == CheckState.PASS; Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) { Text(if (pass) "✓" else "!", color = if (pass) Success else Warning, fontWeight = FontWeight.Bold); Spacer(Modifier.width(11.dp)); Column { Text(value.label, color = Color.White, fontWeight = FontWeight.SemiBold); if (value.detail.isNotBlank()) Text(value.detail, color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp) } } }
@Composable internal fun LiveStatusRow(success: Boolean, text: String) { Surface(color = if (success) Color(0xFF123526) else Color(0xFF3B3018), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp)) { Text(if (success) "✓" else "…", color = if (success) Success else Warning, fontWeight = FontWeight.Bold); Spacer(Modifier.width(10.dp)); Text(text, color = Color.White, fontWeight = FontWeight.SemiBold) } } }
@Composable internal fun ResultBanner(result: OperationResult) { Surface(color = if (result.success) Color(0xFF173E31) else Color(0xFF4A2E20), shape = RoundedCornerShape(10.dp)) { Column(Modifier.fillMaxWidth().padding(14.dp)) { Text(result.message, color = Color.White); if (result.details.isNotBlank()) Text(result.details.take(1_000), color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)) } } }
@Composable internal fun WorkingBanner(label: String) { Surface(color = Color(0xFF152D50), shape = RoundedCornerShape(10.dp)) { Column(Modifier.fillMaxWidth().padding(14.dp)) { Text(label.ifBlank { "Working…" }, color = Color.White, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(10.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) } } }
@Composable internal fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) { Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected, onClick); Text(label) } }
@Composable internal fun DiagnosticsDialog(report: String, onDismiss: () -> Unit) { AlertDialog(onDismissRequest = onDismiss, title = { Text("Help & diagnostics") }, text = { Column { Text("Each page has step-specific help. This report is useful for support and excludes credentials, verification codes, tokens, and secrets.", color = TextSecondary); Spacer(Modifier.height(10.dp)); Surface(color = Color(0xFF0C1422), shape = RoundedCornerShape(8.dp)) { Text(report, Modifier.padding(12.dp).heightIn(max = 300.dp).verticalScroll(rememberScrollState()), color = Color.White, fontSize = 11.sp) }; Spacer(Modifier.height(10.dp)); Button(onClick = { copyText(report) }) { Text("Copy diagnostics") } } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }) }
internal fun copyText(value: String) { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(value), null) }
