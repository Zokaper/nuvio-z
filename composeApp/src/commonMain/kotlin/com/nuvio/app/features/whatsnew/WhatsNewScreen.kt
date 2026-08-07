package com.nuvio.app.features.whatsnew

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.updater.AppReleaseNotes
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_done
import nuvio.composeapp.generated.resources.whats_new_bug_fixes
import nuvio.composeapp.generated.resources.whats_new_improvements
import nuvio.composeapp.generated.resources.whats_new_new_features
import nuvio.composeapp.generated.resources.whats_new_no_notes
import nuvio.composeapp.generated.resources.whats_new_previous_versions
import nuvio.composeapp.generated.resources.whats_new_previous_versions_loading
import nuvio.composeapp.generated.resources.whats_new_previous_versions_unavailable
import nuvio.composeapp.generated.resources.whats_new_title
import nuvio.composeapp.generated.resources.whats_new_version
import org.jetbrains.compose.resources.stringResource

/**
 * @param history earlier releases, newest first, fetched from the releases feed. Null while it
 *   is still loading and empty when it could not be fetched - the two render differently, and
 *   neither one blocks the curated [sections], which are always available offline.
 * @param dismissible true when the screen was opened from Settings rather than shown after an
 *   update. The post-update showing is modal on purpose: it is the one moment the user is
 *   guaranteed to see it, and it is dismissed by the button that records the version as seen.
 */
@Composable
fun WhatsNewScreen(
    versionName: String,
    sections: List<WhatsNewSection>,
    onContinue: () -> Unit,
    history: List<AppReleaseNotes>? = null,
    dismissible: Boolean = false,
) {
    val tokens = MaterialTheme.nuvio

    Dialog(
        onDismissRequest = { if (dismissible) onContinue() },
        properties = DialogProperties(
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 680.dp)
                .padding(16.dp),
            color = tokens.colors.surfaceDialog,
            shape = tokens.shapes.dialog,
        ) {
            Column(
                modifier = Modifier.padding(tokens.spacing.dialogPadding),
            ) {
                Text(
                    text = stringResource(Res.string.whats_new_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(Res.string.whats_new_version, versionName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                )
                Spacer(modifier = Modifier.height(20.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    items(sections) { section ->
                        WhatsNewSectionContent(section)
                    }
                    item {
                        PreviousVersions(history = history)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = onContinue,
                    modifier = Modifier.align(Alignment.End),
                    shape = tokens.shapes.button,
                ) {
                    Text(stringResource(Res.string.action_done))
                }
            }
        }
    }
}

@Composable
private fun WhatsNewSectionContent(section: WhatsNewSection) {
    val tokens = MaterialTheme.nuvio
    val accent = when (section.category) {
        WhatsNewCategory.NewFeatures -> tokens.colors.accent
        WhatsNewCategory.Improvements -> Color(0xFF49B6FF)
        WhatsNewCategory.BugFixes -> Color(0xFF66C98D)
    }
    val label = when (section.category) {
        WhatsNewCategory.NewFeatures -> stringResource(Res.string.whats_new_new_features)
        WhatsNewCategory.Improvements -> stringResource(Res.string.whats_new_improvements)
        WhatsNewCategory.BugFixes -> stringResource(Res.string.whats_new_bug_fixes)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .background(accent, CircleShape),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = accent,
                fontWeight = FontWeight.SemiBold,
            )
        }
        section.items.forEach { item ->
            Column(modifier = Modifier.padding(start = 17.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                )
            }
        }
    }
}

/**
 * Earlier releases, straight from the GitHub releases feed.
 *
 * Rendered through [parseReleaseNotes] rather than a plain `Text`, because those bodies are
 * markdown - without it every heading shows as `## Fixes` and every bullet keeps its `- `.
 */
@Composable
private fun PreviousVersions(history: List<AppReleaseNotes>?) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
