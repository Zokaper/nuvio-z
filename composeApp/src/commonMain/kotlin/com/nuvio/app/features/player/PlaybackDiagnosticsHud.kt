package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.nuvio.app.core.debug.PlatformPlaybackDebugTools
import com.nuvio.app.core.debug.PlaybackDebugSettings
import com.nuvio.app.core.debug.isDebugBuild
import com.nuvio.app.core.network.NetworkQualityRepository
import com.nuvio.app.features.playback.AutoDownshiftDetector
import com.nuvio.app.features.playback.PlaybackSourceCandidate
import com.nuvio.app.features.playback.SwapDiagnosticsLog
import com.nuvio.app.features.playback.qualityLabel
import kotlin.math.roundToInt

/** Live, session-only instrumentation used to make a connection drop reproducible. */
@Composable
internal fun PlayerScreenRuntime.RenderPlaybackDiagnosticsHud() {
    if (!isDebugBuild || !PlaybackDebugSettings.hudEnabled) return

    val current = sourceStreamsState.groups
        .asSequence()
        .flatMap { it.streams.asSequence() }
        .map { PlaybackSourceCandidate(stream = it) }
        .firstOrNull { matchesActiveSource(it.stream) }
    val providerKey = current?.facts?.providerId ?: activeProviderAddonId
    val network = NetworkQualityRepository.current(providerKey)
    val state = autoDownshiftState
    val nowMs = autoDownshiftClock.elapsedNow().inWholeMilliseconds
    val untilTriggerMs = when {
        state.runStartedAtMs != null ->
            (AutoDownshiftDetector.SUSTAINED_MS - (nowMs - state.runStartedAtMs)).coerceAtLeast(0L)
        state.settledAtMs != null ->
            (AutoDownshiftDetector.SETTLE_GRACE_MS - (nowMs - state.settledAtMs)).coerceAtLeast(0L) +
                AutoDownshiftDetector.SUSTAINED_MS
        else -> null
    }
    val bufferAheadMs = (playbackSnapshot.bufferedPositionMs - playbackSnapshot.positionMs)
        .coerceAtLeast(0L)
    var selectedThrottle by remember {
        mutableIntStateOf(PlatformPlaybackDebugTools.throttleMbps)
    }
    var showLog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        Surface(
            modifier = Modifier
                .padding(horizontalSafePadding + 8.dp, 8.dp)
                .widthIn(max = 560.dp),
            color = Color.Black.copy(alpha = 0.82f),
            contentColor = Color.White,
            shape = MaterialTheme.shapes.small,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                HudLine("${playbackSnapshot.engineName} buffer=${formatDebugMs(bufferAheadMs)} " +
                    "pos=${formatDebugMs(playbackSnapshot.positionMs)} / ${formatDebugMs(playbackSnapshot.durationMs)}")
                HudLine("loading=${playbackSnapshot.isLoading} playing=${playbackSnapshot.isPlaying} " +
                    "ended=${playbackSnapshot.isEnded}")
                HudLine("source=${current?.facts?.resolution.qualityLabel.ifBlank { "?" }} | " +
                    "group=${current?.facts?.releaseGroup ?: "?"} | " +
                    "provider=${current?.facts?.providerName ?: current?.facts?.providerId ?: "?"} | " +
                    "addon=${current?.stream?.addonName ?: activeProviderName.ifBlank { "?" }}")
                HudLine("network=${formatDebugMbps(network.estimatedMbps)} Mbps " +
                    "confidence=${network.confidence} key=${network.providerId ?: "generic"}")
                HudLine("downshift settled=${state.settledAtMs != null} run=${state.runStartedAtMs != null} " +
                    "samples=${state.samplesInRun} swaps=${state.swapsUsed}/${AutoDownshiftDetector.MAX_SWAPS_PER_SESSION} " +
                    "triggerIn=${untilTriggerMs?.let(::formatDebugMs) ?: "waiting"}")

                if (PlatformPlaybackDebugTools.throttleOptionsMbps.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        PlatformPlaybackDebugTools.throttleOptionsMbps.forEach { mbps ->
                            TextButton(
                                onClick = {
                                    selectedThrottle = mbps
                                    PlatformPlaybackDebugTools.throttleMbps = mbps
                                },
                            ) {
                                Text(
                                    if (mbps == 0) "Off" else "$mbps M",
                                    color = if (selectedThrottle == mbps) Color(0xFF80CBC4) else Color.White,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                } else {
                    HudLine("throttle=unavailable on ${playbackSnapshot.engineName}")
                }

                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = { forceDebugSourceSwap(upshift = false) }) {
                        Text("Force down", fontSize = 11.sp)
                    }
                    TextButton(onClick = { forceDebugSourceSwap(upshift = true) }) {
                        Text("Force up", fontSize = 11.sp)
                    }
                    TextButton(onClick = ::resetDebugSwapBudget) {
                        Text("Reset budget", fontSize = 11.sp)
                    }
                    TextButton(onClick = { showLog = true }) {
                        Text("Log (${SwapDiagnosticsLog.entries.size})", fontSize = 11.sp)
                    }
                }
                debugStatusMessage?.let { HudLine(it, Color(0xFFFFD180)) }
                if (selectedThrottle > 0 && playbackSnapshot.engineName != "ExoPlayer") {
                    HudLine("Throttle is inactive: the live engine is not ExoPlayer.", Color(0xFFFF8A80))
                }
            }
        }
    }

    if (showLog) PlaybackSwapLogDialog(onDismiss = { showLog = false })
}

@Composable
private fun HudLine(text: String, color: Color = Color.White) {
    Text(text = text, color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
}

@Suppress("DEPRECATION")
@Composable
private fun PlaybackSwapLogDialog(onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(min = 320.dp, max = 760.dp),
            color = Color(0xFF161616),
            contentColor = Color.White,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Playback source swap log", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = SwapDiagnosticsLog.format(),
                    modifier = Modifier
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState())
                        .background(Color.Black.copy(alpha = 0.35f))
                        .padding(10.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(SwapDiagnosticsLog.format())) }) {
                        Text("Copy")
                    }
                    TextButton(onClick = SwapDiagnosticsLog::clear) { Text("Clear") }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

private fun formatDebugMs(value: Long): String {
    val totalSeconds = value.coerceAtLeast(0L) / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val tenths = (value.coerceAtLeast(0L) % 1_000L) / 100L
    return "$minutes:${seconds.toString().padStart(2, '0')}.$tenths"
}

private fun formatDebugMbps(value: Double): String =
    ((value * 10.0).roundToInt() / 10.0).toString()
