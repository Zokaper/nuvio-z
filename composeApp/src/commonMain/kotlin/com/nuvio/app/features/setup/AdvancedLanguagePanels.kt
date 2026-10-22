package com.nuvio.app.features.setup

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.language.OtherLanguageAudio
import com.nuvio.app.core.language.SubtitleKind
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.language.LanguagePreferencesRepository
import com.nuvio.app.features.player.languageLabelForCode
import com.nuvio.app.features.settings.ChoiceRow
import com.nuvio.app.features.settings.LanguageSettingsSection
import com.nuvio.app.features.settings.LanguageSettingsSections
import com.nuvio.app.features.settings.SubtitleScene
import com.nuvio.app.features.settings.myLanguageName
import com.nuvio.app.features.settings.otherScene
import com.nuvio.app.features.settings.ownScene
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.zlang_audio
import nuvio.composeapp.generated.resources.zlang_audio_footer
import nuvio.composeapp.generated.resources.zlang_audio_mine_body
import nuvio.composeapp.generated.resources.zlang_audio_original
import nuvio.composeapp.generated.resources.zlang_audio_original_body
import nuvio.composeapp.generated.resources.zlang_no_subtitle
import nuvio.composeapp.generated.resources.zlang_sub_forced
import nuvio.composeapp.generated.resources.zlang_sub_forced_body
import nuvio.composeapp.generated.resources.zlang_sub_full
import nuvio.composeapp.generated.resources.zlang_sub_full_body
import nuvio.composeapp.generated.resources.zlang_sub_off
import nuvio.composeapp.generated.resources.zlang_sub_off_body
import nuvio.composeapp.generated.resources.zlang_sub_sdh
import nuvio.composeapp.generated.resources.zlang_sub_sdh_body
import nuvio.composeapp.generated.resources.zlang_subtitles
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Advanced Setup's three Language panels (2026-10-22): a preview like every other panel, and short
 * controls under it. Settings → Playback → Language keeps the plain rows of `LanguageSettingsSections`.
 *
 * Bodies are radio lists (picked over a segmented bar, 2026-10-22). The preview is the two moments of one scene as the player would show them - the line said in the
 * title's own language, then the line said in another - so the difference between Forced (only the
 * second) and Full (both) is a picture rather than a sentence.
 */
/** Narcos is made in English and full of Spanish: an English scene, then a Spanish one. */
private val OwnStills = listOf(
    SetupSampleTitle.episodeStillUrl("tt2707408", 1, 2),
    SetupSampleTitle.episodeStillUrl("tt2707408", 1, 1),
)

/** Squid Game, a title made in Korean. */
private val OtherStills = listOf(
    SetupSampleTitle.episodeStillUrl("tt10919420", 1, 1),
    SetupSampleTitle.episodeStillUrl("tt10919420", 1, 2),
)

@Composable
internal fun AdvancedLanguagePreview(panel: AdvancedSetupPanel, desktop: Boolean) {
    val preferences by LanguagePreferencesRepository.uiState.collectAsStateWithLifecycle()
    val mine = myLanguageName(preferences)
    when (panel) {
        AdvancedSetupPanel.Languages -> TrackPreview(
            audio = mine,
            fallback = preferences.fallbackLanguage?.let { languageLabelForCode(it) },
            subtitles = preferences.subtitleLanguage?.let { languageLabelForCode(it) } ?: mine,
            desktop = desktop,
        )
        AdvancedSetupPanel.OwnLanguageTitles -> MomentsPreview(ownScene(mine), preferences.ownSubtitles, OwnStills, desktop)
        AdvancedSetupPanel.OtherLanguageTitles -> MomentsPreview(otherScene(), preferences.otherSubtitles, OtherStills, desktop)
        else -> Unit
    }
}

/** Two moments, side by side in a phone's band and stacked in a desktop pane. */
@Composable
private fun MomentsPreview(scene: SubtitleScene, kind: SubtitleKind, stills: List<String?>, desktop: Boolean) {
    val (first, second) = scene.lines.getValue(kind)
    val moments = listOf(Triple(scene.first, first, stills[0]), Triple(scene.second, second, stills[1]))
    if (desktop) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
            // Both stills and their labels fit the pane's height: a still is as wide as half of it allows.
            val stillHeight = (maxHeight - 18.dp - 60.dp) / 2
            val width = minOf(560.dp, maxWidth, stillHeight * 16f / 9f)
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                moments.forEach { (label, line, still) -> Moment(label, line, still, Modifier.width(width), big = true) }
            }
        }
    } else {
        // Side by side, each still was ~190dp wide on a phone - too small to read. One full-width
        // still instead, alternating between the two moments, with a marker for which one is up.
        var shown by remember { mutableStateOf(0) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(MomentHoldMillis)
                shown = 1 - shown
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            val width = minOf(maxWidth, (maxHeight - 34.dp) * 16f / 9f)
            Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Crossfade(targetState = shown, animationSpec = tween(320), label = "language_moment") { index ->
                    val (label, line, still) = moments[index]
                    Moment(label, line, still, Modifier.fillMaxWidth(), big = false)
                }
                Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    moments.indices.forEach { index ->
                        Box(
                            Modifier.width(if (index == shown) 18.dp else 6.dp).height(6.dp).clip(RoundedCornerShape(3.dp))
                                .background(Color.White.copy(alpha = if (index == shown) 0.9f else 0.3f)),
                        )
                    }
                }
            }
        }
    }
}

private const val MomentHoldMillis = 2_600L

@Composable
private fun Moment(label: String, line: String?, still: String?, modifier: Modifier, big: Boolean) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            style = if (big) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
            color = MaterialTheme.nuvio.colors.textMuted,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        StillFrame(still, Modifier.fillMaxWidth().aspectRatio(16f / 9f), corner = if (big) 14.dp else 10.dp) {
            if (line != null) {
                Text(
                    line,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp, vertical = if (big) 18.dp else 8.dp),
                    style = TextStyle(
                        color = Color.White,
                        fontSize = if (big) 22.sp else 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        shadow = Shadow(Color.Black, blurRadius = 6f),
                    ),
                    maxLines = 2,
                )
            } else {
                // A label about the frame, not a line in it: a quiet chip, so it never reads as a subtitle.
                Text(
                    stringResource(Res.string.zlang_no_subtitle),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (big) 18.dp else 10.dp)
                        .clip(RoundedCornerShape(999.dp)).background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = if (big) 13.sp else 11.sp,
                    fontStyle = FontStyle.Italic,
                )
            }
        }
    }
}

@Composable
private fun StillFrame(url: String?, modifier: Modifier, corner: Dp, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(corner)).background(
            Brush.radialGradient(0f to Color(0xFF2A2F38), 0.6f to Color(0xFF15181E), 1f to Color(0xFF07080A)),
        ),
    ) {
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))))
        content()
    }
}

/** What the player will reach for: the audio, what it falls back to, and the subtitle language. */
@Composable
private fun TrackPreview(audio: String, fallback: String?, subtitles: String, desktop: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(if (desktop) 40.dp else 14.dp), contentAlignment = Alignment.Center) {
        StillFrame(
            SetupSampleTitle.episodeStillUrl("tt0903747", 1, 1),
            Modifier.widthIn(max = 620.dp).fillMaxWidth().aspectRatio(16f / 9f),
            corner = if (desktop) 14.dp else 10.dp,
        ) {
            Column(
                Modifier.align(Alignment.BottomEnd).padding(if (desktop) 18.dp else 10.dp)
                    .clip(RoundedCornerShape(if (desktop) 14.dp else 10.dp))
                    .background(Color(0xE6101114))
                    .padding(horizontal = if (desktop) 16.dp else 10.dp, vertical = if (desktop) 12.dp else 8.dp),
                verticalArrangement = Arrangement.spacedBy(if (desktop) 8.dp else 4.dp),
            ) {
                TrackLine(stringResource(Res.string.zlang_audio), audio, fallback?.let { "→ $it" }, desktop)
                TrackLine(stringResource(Res.string.zlang_subtitles), subtitles, null, desktop)
            }
        }
    }
}

@Composable
private fun TrackLine(label: String, value: String, after: String?, desktop: Boolean) {
    val size = if (desktop) 15.sp else 10.sp
    Row(horizontalArrangement = Arrangement.spacedBy(if (desktop) 10.dp else 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White.copy(alpha = 0.55f), fontSize = size, modifier = Modifier.widthIn(min = if (desktop) 78.dp else 50.dp))
        Text(value, color = Color.White, fontSize = size, fontWeight = FontWeight.SemiBold)
        if (after != null) Text(after, color = Color.White.copy(alpha = 0.55f), fontSize = size)
    }
}

// --- bodies ------------------------------------------------------------------------------------

@Composable
internal fun AdvancedLanguageBody(panel: AdvancedSetupPanel, isTablet: Boolean) {
    val preferences by LanguagePreferencesRepository.uiState.collectAsStateWithLifecycle()
    val mine = myLanguageName(preferences)
    when (panel) {
        AdvancedSetupPanel.Languages -> LanguageSettingsSections(isTablet = isTablet, section = LanguageSettingsSection.Languages, showTitle = false)
        AdvancedSetupPanel.OwnLanguageTitles -> SubtitleQuestion(preferences.ownSubtitles) { kind ->
            LanguagePreferencesRepository.update { it.copy(ownSubtitles = kind) }
        }
        AdvancedSetupPanel.OtherLanguageTitles -> Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            AudioQuestion(preferences.otherAudio, mine) { audio -> LanguagePreferencesRepository.update { it.copy(otherAudio = audio) } }
            SubtitleQuestion(preferences.otherSubtitles) { kind -> LanguagePreferencesRepository.update { it.copy(otherSubtitles = kind) } }
        }
        else -> Unit
    }
}

private val SubtitleChoices: List<Triple<SubtitleKind, StringResource, StringResource>> = listOf(
    Triple(SubtitleKind.OFF, Res.string.zlang_sub_off, Res.string.zlang_sub_off_body),
    Triple(SubtitleKind.FORCED, Res.string.zlang_sub_forced, Res.string.zlang_sub_forced_body),
    Triple(SubtitleKind.FULL, Res.string.zlang_sub_full, Res.string.zlang_sub_full_body),
    Triple(SubtitleKind.SDH, Res.string.zlang_sub_sdh, Res.string.zlang_sub_sdh_body),
)

@Composable
private fun SubtitleQuestion(selected: SubtitleKind, onSelect: (SubtitleKind) -> Unit) {
    Question(stringResource(Res.string.zlang_subtitles)) {
        SubtitleChoices.forEach { (kind, name, body) ->
            ChoiceRow(selected = kind == selected, enabled = true, title = stringResource(name), body = stringResource(body), onClick = { onSelect(kind) })
        }
    }
}

@Composable
private fun AudioQuestion(selected: OtherLanguageAudio, mine: String, onSelect: (OtherLanguageAudio) -> Unit) {
    Question(stringResource(Res.string.zlang_audio)) {
        ChoiceRow(
            selected = selected == OtherLanguageAudio.ORIGINAL, enabled = true,
            title = stringResource(Res.string.zlang_audio_original), body = stringResource(Res.string.zlang_audio_original_body),
            onClick = { onSelect(OtherLanguageAudio.ORIGINAL) },
        )
        ChoiceRow(
            selected = selected == OtherLanguageAudio.MY_LANGUAGE, enabled = true,
            title = mine, body = stringResource(Res.string.zlang_audio_mine_body, mine),
            onClick = { onSelect(OtherLanguageAudio.MY_LANGUAGE) },
        )
        Detail(stringResource(Res.string.zlang_audio_footer, mine))
    }
}

@Composable
private fun Question(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.nuvio.colors.textPrimary, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun Detail(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.nuvio.colors.textMuted,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}
