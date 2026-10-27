package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.*
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** Upstream 0.6.2 Exo buffer/cache controls, hosted by Z's Advanced settings page. */
@Composable
internal fun PlaybackBufferSettingsRows(
    autoPlayPlayerSettings: PlayerSettingsUiState,
    isTablet: Boolean,
    exoOptionsEnabled: Boolean,
) {
    SettingsGroupDivider(isTablet = isTablet)
    val nativeMemorySupported = isExoNativeMemorySupported()
    val nativeMemoryInfo = if (nativeMemorySupported && autoPlayPlayerSettings.exoNativeMemoryEnabled) {
        exoNativeMemoryInfo()
    } else {
        null
    }
    SettingsSwitchRow(
        title = stringResource(Res.string.settings_playback_exo_native_memory),
        description = if (nativeMemorySupported) {
            stringResource(Res.string.settings_playback_exo_native_memory_description)
        } else {
            stringResource(Res.string.settings_playback_exo_native_memory_unsupported)
        },
        checked = nativeMemorySupported && autoPlayPlayerSettings.exoNativeMemoryEnabled,
        enabled = exoOptionsEnabled && nativeMemorySupported,
        isTablet = isTablet,
        onCheckedChange = PlayerSettingsRepository::setExoNativeMemoryEnabled,
    )
    if (nativeMemoryInfo != null) {
        Text(
            text = stringResource(
                Res.string.settings_playback_exo_native_memory_device,
                nativeMemoryInfo.ramLabel,
                nativeMemoryInfo.safeLimitMb,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 4.dp),
        )
    }
    SettingsGroupDivider(isTablet = isTablet)
    SettingsSwitchRow(
        title = stringResource(Res.string.settings_playback_buffer_custom),
        description = stringResource(Res.string.settings_playback_buffer_custom_description),
        checked = autoPlayPlayerSettings.bufferEngineEnabled,
        enabled = exoOptionsEnabled,
        isTablet = isTablet,
        onCheckedChange = PlayerSettingsRepository::setBufferEngineEnabled,
    )
    if (autoPlayPlayerSettings.bufferEngineEnabled) {
        val nativeBuffers = nativeMemorySupported && autoPlayPlayerSettings.exoNativeMemoryEnabled
        val maxDurationSec = if (nativeBuffers) {
            PlaybackBufferSettings.MAX_DURATION_SEC_NATIVE
        } else {
            PlaybackBufferSettings.MAX_DURATION_SEC_HEAP
        }
        val durationStep = if (nativeBuffers) {
            PlaybackBufferSettings.DURATION_STEP_NATIVE_SEC
        } else {
            PlaybackBufferSettings.DURATION_STEP_HEAP_SEC
        }
        val minSeconds = (autoPlayPlayerSettings.minBufferMs / 1_000)
            .coerceIn(PlaybackBufferSettings.MIN_DURATION_SEC, maxDurationSec)
        val maxSeconds = (autoPlayPlayerSettings.maxBufferMs / 1_000)
            .coerceIn(minSeconds, maxDurationSec)
        Text(
            text = stringResource(Res.string.settings_playback_buffer_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 8.dp),
        )
        SettingsSliderRow(
            title = stringResource(Res.string.settings_playback_buffer_min),
            value = minSeconds,
            valueText = { seconds ->
                stringResource(Res.string.settings_playback_buffer_seconds, seconds)
            },
            valueRange = PlaybackBufferSettings.MIN_DURATION_SEC..maxDurationSec,
            step = durationStep,
            isTablet = isTablet,
            enabled = exoOptionsEnabled,
            onValueChange = { PlayerSettingsRepository.setMinBufferMs(it * 1_000) },
        )
        SettingsSliderRow(
            title = stringResource(Res.string.settings_playback_buffer_max),
            value = maxSeconds.coerceAtLeast(minSeconds),
            valueText = { seconds ->
                if (seconds <= minSeconds) {
                    stringResource(Res.string.settings_playback_buffer_same_as_min, minSeconds)
                } else {
                    stringResource(Res.string.settings_playback_buffer_seconds, seconds)
                }
            },
            valueRange = minSeconds.coerceAtLeast(PlaybackBufferSettings.MIN_DURATION_SEC)..maxDurationSec,
            step = durationStep,
            isTablet = isTablet,
            enabled = exoOptionsEnabled,
            onValueChange = { PlayerSettingsRepository.setMaxBufferMs(maxOf(it, minSeconds) * 1_000) },
        )
        SettingsSliderRow(
            title = stringResource(Res.string.settings_playback_buffer_initial),
            value = autoPlayPlayerSettings.bufferForPlaybackMs / 1_000,
            valueText = { seconds ->
                stringResource(Res.string.settings_playback_buffer_seconds, seconds)
            },
            valueRange = PlaybackBufferSettings.MIN_START_SEC..PlaybackBufferSettings.MAX_START_SEC,
            step = 1,
            isTablet = isTablet,
            enabled = exoOptionsEnabled,
            onValueChange = { PlayerSettingsRepository.setBufferForPlaybackMs(it * 1_000) },
        )
        SettingsSliderRow(
            title = stringResource(Res.string.settings_playback_buffer_rebuffer),
            value = autoPlayPlayerSettings.bufferForPlaybackAfterRebufferMs / 1_000,
            valueText = { seconds ->
                stringResource(Res.string.settings_playback_buffer_seconds, seconds)
            },
            valueRange = PlaybackBufferSettings.MIN_START_SEC..PlaybackBufferSettings.MAX_REBUFFER_SEC,
            step = 1,
            isTablet = isTablet,
            enabled = exoOptionsEnabled,
            onValueChange = { PlayerSettingsRepository.setBufferForPlaybackAfterRebufferMs(it * 1_000) },
        )
        SettingsSliderRow(
            title = stringResource(Res.string.settings_playback_buffer_back),
            value = autoPlayPlayerSettings.backBufferDurationMs / 1_000,
            valueText = { seconds ->
                stringResource(Res.string.settings_playback_buffer_seconds, seconds)
            },
            valueRange = 0..PlaybackBufferSettings.MAX_BACK_SEC,
            step = PlaybackBufferSettings.BACK_STEP_SEC,
            isTablet = isTablet,
            enabled = exoOptionsEnabled,
            onValueChange = { PlayerSettingsRepository.setBackBufferDurationMs(it * 1_000) },
        )
        val memoryInfo = if (nativeBuffers) exoNativeMemoryInfo() else null
        val safeTargetMb = if (nativeBuffers) {
            memoryInfo?.safeLimitMb ?: PlaybackBufferSettings.MIN_TARGET_MB
        } else {
            playbackHeapBufferMaxMb()
        }
        val maxTargetMb = if (nativeBuffers) {
            memoryInfo?.warningLimitMb ?: safeTargetMb
        } else {
            safeTargetMb
        }
        val targetMb = autoPlayPlayerSettings.targetBufferSizeMb.coerceIn(
            PlaybackBufferSettings.MIN_TARGET_MB,
            maxTargetMb,
        )
        SettingsSliderRow(
            title = stringResource(Res.string.settings_playback_buffer_target),
            value = targetMb,
            valueText = { megabytes ->
                stringResource(Res.string.settings_playback_vod_cache_size_value, megabytes)
            },
            valueRange = PlaybackBufferSettings.MIN_TARGET_MB..maxTargetMb,
            step = PlaybackBufferSettings.TARGET_STEP_MB,
            isTablet = isTablet,
            enabled = exoOptionsEnabled && maxTargetMb > PlaybackBufferSettings.MIN_TARGET_MB,
            onValueChange = PlayerSettingsRepository::setTargetBufferSizeMb,
        )
        if (targetMb > safeTargetMb) {
            Text(
                text = stringResource(
                    Res.string.settings_playback_buffer_target_warning,
                    safeTargetMb,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 4.dp),
            )
        }
    }
    SettingsGroupDivider(isTablet = isTablet)
    SettingsSwitchRow(
        title = stringResource(Res.string.settings_playback_vod_cache),
        description = stringResource(Res.string.settings_playback_vod_cache_description),
        checked = autoPlayPlayerSettings.vodCacheEnabled,
        enabled = exoOptionsEnabled,
        isTablet = isTablet,
        onCheckedChange = PlayerSettingsRepository::setVodCacheEnabled,
    )
    if (autoPlayPlayerSettings.vodCacheEnabled) {
        SettingsGroupDivider(isTablet = isTablet)
        SettingsSwitchRow(
            title = stringResource(Res.string.settings_playback_vod_cache_auto_size),
            description = stringResource(Res.string.settings_playback_vod_cache_auto_size_description),
            checked = autoPlayPlayerSettings.vodCacheSizeMode == VodCacheSizeMode.AUTO,
            enabled = exoOptionsEnabled,
            isTablet = isTablet,
            onCheckedChange = { enabled ->
                PlayerSettingsRepository.setVodCacheSizeMode(
                    if (enabled) VodCacheSizeMode.AUTO else VodCacheSizeMode.MANUAL,
                )
            },
        )
        if (autoPlayPlayerSettings.vodCacheSizeMode == VodCacheSizeMode.MANUAL) {
            val freeBytes = playbackCacheUsableSpaceBytes()
            val maxMb = VodCacheSizing.resolveManualMaxMb(freeBytes)
            val sizeMb = autoPlayPlayerSettings.vodCacheSizeMb.coerceIn(
                VodCacheSizing.MIN_SIZE_MB,
                maxMb,
            )
            if (maxMb > VodCacheSizing.MIN_SIZE_MB) {
                SettingsSliderRow(
                    title = stringResource(Res.string.settings_playback_vod_cache_size),
                    value = sizeMb,
                    valueText = { megabytes ->
                        stringResource(Res.string.settings_playback_vod_cache_size_value, megabytes)
                    },
                    valueRange = VodCacheSizing.MIN_SIZE_MB..maxMb,
                    step = vodCacheSliderStep(VodCacheSizing.MIN_SIZE_MB, maxMb),
                    isTablet = isTablet,
                    enabled = exoOptionsEnabled,
                    onValueChange = PlayerSettingsRepository::setVodCacheSizeMb,
                )
            }
        }
        Text(
            text = stringResource(Res.string.settings_playback_vod_cache_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 8.dp),
        )
    }
}

private fun vodCacheSliderStep(minMb: Int, maxMb: Int): Int {
    val span = (maxMb - minMb).coerceAtLeast(1)
    val rough = span / 40
    return when {
        rough <= 50 -> 50
        rough <= 100 -> 100
        rough <= 250 -> 250
        rough <= 500 -> 500
        else -> 1024
    }
}
