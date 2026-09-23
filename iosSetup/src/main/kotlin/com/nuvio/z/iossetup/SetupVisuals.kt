package com.nuvio.z.iossetup

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PanelBlue = Color(0xFF72A7FF)
private val PanelGreen = Color(0xFF72D9A2)
private val PanelMuted = Color(0xFFB9C8DD)

@Composable
fun StepVisualPanel(step: SetupStep, state: SetupState, modifier: Modifier = Modifier) {
    val guide = guidanceFor(step, state)
    Surface(
        modifier = modifier.fillMaxHeight(),
        color = Color(0xFF101B2D),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("VISUAL GUIDE", color = PanelBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Spacer(Modifier.height(18.dp))
            when (guide.visual) {
                StepVisual.WELCOME -> DeviceFlow("Computer", "Guided setup", "iPhone")
                StepVisual.COMPUTER -> ChecklistVisual(listOf("64-bit computer", "Internet connection", "Ready to continue"))
                StepVisual.APPLE_SUPPORT -> DeviceFlow("Windows", "Apple drivers", "iPhone")
                StepVisual.CONNECT -> ConnectVisual()
                StepVisual.VPN -> PhoneScreen("LocalDevVPN", listOf("Wi-Fi", "Connected"), success = true)
                StepVisual.ILOADER -> DesktopWindow("iloader", listOf("Official helper", "Installed"), success = true)
                StepVisual.SIDESTORE -> DeviceFlow("iloader", "Install", "SideStore")
                StepVisual.PAIRING -> DesktopWindow("Manage Pairing File", listOf("SideStore     Place", "Pairing file placed successfully!"), success = true)
                StepVisual.TRUST -> PhoneScreen("VPN & Device Management", listOf("Developer App", "Trusted"), success = true)
                StepVisual.DEVELOPER_MODE -> PhoneScreen("Privacy & Security", listOf("Developer Mode", "On after restart"), success = true)
                StepVisual.REFRESH -> PhoneScreen("SideStore · My Apps", listOf("SideStore", "7 DAYS  ✓"), success = true)
                StepVisual.SOURCE -> SourceVisual(state)
                StepVisual.INSTALL -> PhoneScreen("SideStore · Browse", listOf(state.channel.appName, "Installed  ✓"), success = true)
                StepVisual.FINISH -> ChecklistVisual(listOf("SideStore installed", "Pairing ready", "${state.channel.appName} installed"))
            }
            Spacer(Modifier.height(20.dp))
            Text(guide.visualTitle, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(guide.visualCaption, color = PanelMuted, fontSize = 12.sp, lineHeight = 17.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Surface(color = Color(0xFF123526), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("DONE WHEN", color = PanelGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text(guide.success, color = Color.White, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun DeviceFlow(left: String, action: String, right: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        DeviceTile(left, "DESKTOP")
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("→", color = PanelBlue, fontSize = 32.sp, modifier = Modifier.padding(horizontal = 10.dp))
            Text(action, color = PanelMuted, fontSize = 11.sp)
        }
        DeviceTile(right, "IPHONE")
    }
}

@Composable
private fun ConnectVisual() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeviceTile("Computer", "USB")
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("━━━━", color = PanelBlue, fontWeight = FontWeight.Bold)
                Text("data cable", color = PanelMuted, fontSize = 10.sp)
            }
            DeviceTile("Unlocked", "IPHONE")
        }
        Spacer(Modifier.height(18.dp))
        Surface(color = Color(0xFF203552), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Trust This Computer?", color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Don’t Trust       Trust", color = PanelBlue, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun DeviceTile(label: String, kind: String) {
    Surface(color = Color(0xFF172942), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.size(width = 105.dp, height = 130.dp).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(48.dp).background(Color(0xFF28466E), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Text(if (kind == "IPHONE") "▯" else "▰", color = PanelBlue, fontSize = 28.sp)
            }
            Spacer(Modifier.height(10.dp))
            Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, fontSize = 13.sp)
            Text(kind, color = PanelMuted, fontSize = 9.sp)
        }
    }
}

@Composable
private fun DesktopWindow(title: String, rows: List<String>, success: Boolean) {
    Surface(color = Color(0xFF172942), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                repeat(3) { Box(Modifier.padding(end = 5.dp).size(7.dp).background(Color(0xFF69809E), RoundedCornerShape(99.dp))) }
                Spacer(Modifier.width(6.dp))
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Spacer(Modifier.height(16.dp))
            rows.forEachIndexed { index, row ->
                Surface(color = if (success && index == rows.lastIndex) Color(0xFF174B37) else Color(0xFF203552), shape = RoundedCornerShape(9.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(row, Modifier.padding(10.dp), color = if (success && index == rows.lastIndex) PanelGreen else Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun PhoneScreen(title: String, rows: List<String>, success: Boolean) {
    Surface(color = Color(0xFF172942), shape = RoundedCornerShape(28.dp), modifier = Modifier.width(230.dp)) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(56.dp).height(5.dp).background(Color(0xFF526783), RoundedCornerShape(99.dp)))
            Spacer(Modifier.height(20.dp))
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, fontSize = 14.sp)
            Spacer(Modifier.height(14.dp))
            rows.forEachIndexed { index, row ->
                Surface(color = if (success && index == rows.lastIndex) Color(0xFF174B37) else Color(0xFF203552), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(row, Modifier.padding(11.dp), color = if (success && index == rows.lastIndex) PanelGreen else Color.White, fontSize = 12.sp, textAlign = TextAlign.Center)
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun ChecklistVisual(items: List<String>) {
    Column(Modifier.fillMaxWidth()) {
        items.forEach { item ->
            Surface(color = Color(0xFF172942), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("✓", color = PanelGreen, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    Text(item, color = Color.White, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun SourceVisual(state: SetupState) {
    val qr = remember(state.sourceDeepLink) { QrCode.image(state.sourceDeepLink) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = Color.White, shape = RoundedCornerShape(12.dp)) {
            Image(qr, "QR code for the ${state.channel.appName} SideStore source", Modifier.size(140.dp).padding(8.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text("OR", color = PanelMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Sources", color = Color.White, fontWeight = FontWeight.Bold)
            Text("+ Paste URL", color = PanelBlue, fontSize = 13.sp)
        }
    }
}
