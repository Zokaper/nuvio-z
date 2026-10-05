package com.nuvio.z.iossetup

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension

fun main(args: Array<String>) = application {
    val diagnostics = remember { Diagnostics() }
    val store = remember { ProgressStore() }
    val ops = remember { platformSetupOps(diagnostics) }
    val helper = remember { DeviceHelper(DeviceHelper.locate(), diagnostics) }
    val session = remember {
        SetupSession(ops, helper, store, diagnostics, store.loadProgress() ?: SetupProgress())
    }
    val windowState = rememberWindowState(width = 1440.dp, height = 900.dp)

    Window(onCloseRequest = ::exitApplication, title = "Nuvio Z iOS Setup", state = windowState) {
        LaunchedEffect(Unit) { window.minimumSize = Dimension(1120, 720) }
        NuvioTheme { SetupApp(session, developerFlag = args.contains("--developer")) }
    }
}

@Composable
internal fun NuvioTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = NuvioBlue,
        onPrimary = Color.White,
        primaryContainer = Color(0xFF1C4075),
        onPrimaryContainer = Color.White,
        secondary = BlueText,
        onSecondary = Color(0xFF07111F),
        background = AppBackground,
        onBackground = TextPrimary,
        surface = CardColor,
        onSurface = TextPrimary,
        surfaceVariant = Color(0xFF111C2D),
        onSurfaceVariant = TextSecondary,
    ),
    content = content,
)
