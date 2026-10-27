package com.nuvio.app.features.whatsnew

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import nuvio.composeapp.generated.resources.zwhatsnew_action_review_settings
import nuvio.composeapp.generated.resources.zwhatsnew_all_platforms
import org.jetbrains.compose.resources.decodeToImageBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.updater.AppReleaseNotes
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_done
import nuvio.composeapp.generated.resources.whats_new_action_advanced_setup
import nuvio.composeapp.generated.resources.whats_new_debug_build
import nuvio.composeapp.generated.resources.whats_new_bug_fixes
import nuvio.composeapp.generated.resources.whats_new_improvements
import nuvio.composeapp.generated.resources.whats_new_new_features
import nuvio.composeapp.generated.resources.whats_new_no_notes
import nuvio.composeapp.generated.resources.whats_new_platform_android
import nuvio.composeapp.generated.resources.whats_new_platform_desktop
import nuvio.composeapp.generated.resources.whats_new_platform_ios
import nuvio.composeapp.generated.resources.whats_new_previous_versions
import nuvio.composeapp.generated.resources.whats_new_previous_versions_loading
import nuvio.composeapp.generated.resources.whats_new_previous_versions_unavailable
import nuvio.composeapp.generated.resources.whats_new_title
import nuvio.composeapp.generated.resources.whats_new_unreleased
import nuvio.composeapp.generated.resources.whats_new_version
import org.jetbrains.compose.resources.stringResource

/**
 * The global changelog, as release events: each one leads with its date (and optional one-line
 * summary), then the versions it shipped in - only the platforms it really shipped on, this device's
 * own emphasised. Picture cards lead, smaller changes follow as text, and fixes fold away. Every
 * entry keeps its platform tags beside it.
 *
 * @param events newest first. After an update: the events this device has not acknowledged. From
 *   Settings: every event released to this platform.
 * @param history earlier GitHub releases the changelog does not cover, newest first. Null while
 *   loading and empty when it could not be fetched - the two render differently, and neither one
 *   blocks [events], which are always available offline.
 * @param dismissible true when opened from Settings rather than shown after an update. The
 *   post-update showing is modal on purpose: it is dismissed by the button that records it as seen.
 */
@Composable
fun WhatsNewScreen(
    versionName: String,
    events: List<WhatsNewEventUi>,
    onContinue: () -> Unit,
    debugSection: WhatsNewSection? = null,
    history: List<AppReleaseNotes>? = null,
    dismissible: Boolean = false,
    /** False after an update: that screen is the missed events; history lives in Settings. */
    showHistory: Boolean = true,
    /**
     * An action card was pressed ("Try Advanced Setup"). The caller closes this screen - and, after
     * an update, acknowledges it - before doing it. Null hides the cards' buttons.
     */
    onAction: ((ChangelogAction) -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = { if (dismissible) onContinue() },
        properties = DialogProperties(
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible,
            usePlatformDefaultWidth = false,
        ),
    ) {
        WhatsNewContent(versionName, events, onContinue, debugSection, history, showHistory, onAction)
    }
}

/** The actual dialog surface, also used by the desktop render harness. */
@Composable
internal fun WhatsNewContent(
    versionName: String,
    events: List<WhatsNewEventUi>,
    onContinue: () -> Unit,
    debugSection: WhatsNewSection? = null,
    history: List<AppReleaseNotes>? = null,
    showHistory: Boolean = true,
    onAction: ((ChangelogAction) -> Unit)? = null,
    modifier: Modifier = Modifier,
    imageContent: @Composable (String) -> Unit = { WhatsNewImage(it) },
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = modifier
            .padding(16.dp)
            .widthIn(max = 680.dp)
            .fillMaxWidth(),
        color = tokens.colors.surfaceDialog,
        shape = tokens.shapes.dialog,
    ) {
        // Z (2026-10-23): the newest update's hero is the top of the panel itself - edge to edge, fading
        // into the dialog - with the title centred under it. Without a hero, the plain header as before.
        val hero = events.firstOrNull()?.image
        val padding = tokens.spacing.dialogPadding
        Column {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                item(key = "header") { WhatsNewHeader(versionName, hero, imageContent) }
                debugSection?.let { section ->
                    item(key = "debug") {
                        Box(Modifier.padding(horizontal = padding)) { WhatsNewSectionContent(section, onAction = null) }
                    }
                }
                if (events.isEmpty() && debugSection == null) {
                    item(key = "empty") {
                        Text(
                            text = stringResource(Res.string.whats_new_no_notes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.textMuted,
                            modifier = Modifier.padding(horizontal = padding),
                        )
                    }
                }
                itemsIndexed(events, key = { _, event -> "event-${event.seq}" }) { index, event ->
                    Box(Modifier.padding(horizontal = padding)) {
                        // The header already shows the newest update's hero.
                        WhatsNewEventContent(event, onAction, imageContent, showImage = index > 0)
                    }
                }
                if (showHistory) {
                    item(key = "history") {
                        Box(Modifier.padding(horizontal = padding)) { PreviousVersions(history = history) }
                    }
                }
            }

            Button(
                onClick = onContinue,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(start = padding, end = padding, top = 20.dp, bottom = padding),
                shape = tokens.shapes.button,
            ) {
                Text(stringResource(Res.string.action_done))
            }
        }
    }
}

/** The dialog's top: the hero bleeding to the panel's edges with the title centred under it, or the plain title. */
@Composable
private fun WhatsNewHeader(versionName: String, hero: String?, imageContent: @Composable (String) -> Unit) {
    val tokens = MaterialTheme.nuvio
    val padding = tokens.spacing.dialogPadding
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (hero != null) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        if (hero != null) {
            Box(Modifier.fillMaxWidth()) {
                imageContent(hero)
                // Fade the picture's lower edge into the panel so the title sits on the same surface.
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                0.55f to Color.Transparent,
                                1f to tokens.colors.surfaceDialog,
                            ),
                        ),
                )
            }
        } else {
            Spacer(Modifier.height(padding))
        }
        Text(
            text = stringResource(Res.string.whats_new_title),
            style = MaterialTheme.typography.headlineMedium,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = padding),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(Res.string.whats_new_version, versionName),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
            modifier = Modifier.padding(horizontal = padding),
        )
    }
}

@Composable
private fun platformLabel(platform: ChangelogPlatform): String = stringResource(
    when (platform) {
        ChangelogPlatform.DESKTOP -> Res.string.whats_new_platform_desktop
        ChangelogPlatform.ANDROID -> Res.string.whats_new_platform_android
        ChangelogPlatform.IOS -> Res.string.whats_new_platform_ios
    },
)

@Composable
private fun WhatsNewEventContent(
    event: WhatsNewEventUi,
    onAction: ((ChangelogAction) -> Unit)?,
    imageContent: @Composable (String) -> Unit,
    showImage: Boolean = true,
) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        HorizontalDivider(color = tokens.colors.borderSubtle)
        // Z (2026-10-23): one hero picture per update; the details go in the announcement's trailer.
        event.image?.takeIf { showImage }?.let { image ->
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))) { imageContent(image) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = event.date ?: stringResource(Res.string.whats_new_unreleased),
                style = MaterialTheme.typography.titleLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.Bold,
            )
            // Z (2026-10-23): no per-platform version line under the date - the dialog's subtitle
            // already names this build's version, and three versions beside a date read as clutter.
            event.summary?.let { summary ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary,
                )
            }
        }
        // Z (2026-10-23): the New features / Improvements / Bug fixes headings are back (maintainer).
        event.sections.forEach { section -> WhatsNewSectionContent(section, onAction, imageContent) }
    }
}

@Composable
private fun WhatsNewSectionContent(
    section: WhatsNewSection,
    onAction: ((ChangelogAction) -> Unit)?,
    imageContent: @Composable (String) -> Unit = { WhatsNewImage(it) },
) {
    val tokens = MaterialTheme.nuvio
    // Fixes fold away behind their count; everything else is open.
    val foldable = section.category == WhatsNewCategory.BugFixes
    var expanded by remember(section) { mutableStateOf(!foldable) }
    val accent = when (section.category) {
        // Its own colour: the theme accent is white in the default theme, the same as the text.
        WhatsNewCategory.NewFeatures -> Color(0xFFFFB547)
        WhatsNewCategory.Improvements -> Color(0xFF49B6FF)
        WhatsNewCategory.BugFixes -> Color(0xFF66C98D)
        WhatsNewCategory.DebugBuild -> tokens.colors.textSecondary
    }
    val label = when (section.category) {
        WhatsNewCategory.NewFeatures -> stringResource(Res.string.whats_new_new_features)
        WhatsNewCategory.Improvements -> stringResource(Res.string.whats_new_improvements)
        WhatsNewCategory.BugFixes -> stringResource(Res.string.whats_new_bug_fixes)
        WhatsNewCategory.DebugBuild -> stringResource(Res.string.whats_new_debug_build)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .then(if (foldable) Modifier.clickable { expanded = !expanded } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(accent, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (foldable) "$label (${section.items.size})" else label,
                style = MaterialTheme.typography.labelLarge,
                color = accent,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (foldable) {
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = tokens.colors.textMuted,
                )
            }
        }
        if (expanded) {
            section.items.forEach { item ->
                if (item.image != null) WhatsNewPictureCard(item, onAction, imageContent) else WhatsNewLine(item, onAction)
            }
        }
    }
}

/** An entry without a picture: its title with the platforms beside it, then the body. */
@Composable
private fun WhatsNewLine(item: WhatsNewItem, onAction: ((ChangelogAction) -> Unit)?) {
    val tokens = MaterialTheme.nuvio
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 7.dp).size(6.dp).background(tokens.colors.textSecondary, CircleShape))
        Column(Modifier.weight(1f)) {
            // Z (2026-10-23): every entry is a line now (the hero carries the pictures), so lines are
            // no longer the quiet tier under picture cards: full-strength titles, secondary bodies.
            WhatsNewTitleRow(item, MaterialTheme.typography.bodyLarge)
            if (item.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary,
                )
            }
            WhatsNewActionButton(item, onAction)
        }
    }
}

/** A feature with a picture (option A): the picture, then the title with its platforms beside it. */
@Composable
private fun WhatsNewPictureCard(
    item: WhatsNewItem,
    onAction: ((ChangelogAction) -> Unit)?,
    imageContent: @Composable (String) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(tokens.colors.surfaceCard),
    ) {
        item.image?.let { imageContent(it) }
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WhatsNewTitleRow(item, MaterialTheme.typography.titleMedium)
            if (item.description.isNotBlank()) {
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary,
                )
            }
            WhatsNewActionButton(item, onAction)
        }
    }
}

@Composable
private fun WhatsNewTitleRow(
    item: WhatsNewItem,
    style: androidx.compose.ui.text.TextStyle,
    emphasized: Boolean = true,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = item.title,
            style = style,
            color = if (emphasized) MaterialTheme.nuvio.colors.textPrimary else MaterialTheme.nuvio.colors.textSecondary,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        WhatsNewPlatformTags(item.platforms)
    }
}

/** One tag per platform; all three collapse to "All platforms" so the common case stays quiet. */
@Composable
private fun WhatsNewPlatformTags(platforms: List<ChangelogPlatform>) {
    if (platforms.isEmpty()) return
    val tokens = MaterialTheme.nuvio
    val labels = if (platforms.containsAll(ChangelogPlatformOrder)) {
        listOf(stringResource(Res.string.zwhatsnew_all_platforms))
    } else {
        platforms.map { platformLabel(it) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEach { label ->
            Text(
                text = label,
                modifier = Modifier
                    .clip(CircleShape)
                    .border(1.dp, tokens.colors.borderDefault, CircleShape)
                    .padding(horizontal = 9.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textSecondary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun WhatsNewActionButton(item: WhatsNewItem, onAction: ((ChangelogAction) -> Unit)?) {
    val action = item.action ?: return
    onAction ?: return
    TextButton(
        onClick = { onAction(action) },
        shape = CircleShape,
        colors = ButtonDefaults.textButtonColors(
            containerColor = MaterialTheme.nuvio.colors.accent.copy(alpha = 0.16f),
            contentColor = MaterialTheme.nuvio.colors.accent,
        ),
    ) {
        Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(
                when (action) {
                    ChangelogAction.ADVANCED_SETUP -> Res.string.whats_new_action_advanced_setup
                    ChangelogAction.REVIEW_SETTINGS -> Res.string.zwhatsnew_action_review_settings
                },
            ),
        )
    }
}

/**
 * The entry's picture, shipped in the app (`composeResources/files/whatsnew/`). Nothing is drawn
 * until it decodes, and nothing at all if it does not - a missing file costs the card its picture,
 * never the entry.
 */
@Composable
private fun WhatsNewImage(file: String) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, file) {
        value = runCatching { Res.readBytes("files/whatsnew/$file").decodeToImageBitmap() }.getOrNull()
    }
    bitmap?.let { image ->
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f),
        )
    }
}

/**
 * Earlier releases, straight from the GitHub releases feed - only those the changelog does not
 * already cover.
 *
 * Rendered through [parseReleaseNotes] rather than a plain `Text`, because those bodies are
 * markdown - without it every heading shows as `## Fixes` and every bullet keeps its `- `.
 */
@Composable
private fun PreviousVersions(history: List<AppReleaseNotes>?) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(color = tokens.colors.borderSubtle)
        Text(
            text = stringResource(Res.string.whats_new_previous_versions),
            style = MaterialTheme.typography.labelLarge,
            color = tokens.colors.textMuted,
            fontWeight = FontWeight.SemiBold,
        )
        when {
            history == null -> Text(
                text = stringResource(Res.string.whats_new_previous_versions_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
            )

            history.isEmpty() -> Text(
                text = stringResource(Res.string.whats_new_previous_versions_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
            )

            else -> history.forEach { release ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = release.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = tokens.colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val lines = parseReleaseNotes(release.notes)
                    if (lines.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.whats_new_no_notes),
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.colors.textMuted,
                        )
                    } else {
                        lines.forEach { line -> ReleaseNoteLineContent(line) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseNoteLineContent(line: ReleaseNoteLine) {
    val tokens = MaterialTheme.nuvio
    when (line) {
        is ReleaseNoteLine.Heading -> Text(
            text = line.text,
            style = MaterialTheme.typography.labelMedium,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 6.dp),
        )

        is ReleaseNoteLine.Bullet -> Row(modifier = Modifier.padding(start = 4.dp)) {
            Text(
                text = "•",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = line.text,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
        }

        is ReleaseNoteLine.Paragraph -> Text(
            text = line.text,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
        )
    }
}
