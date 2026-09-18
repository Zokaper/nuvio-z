package com.nuvio.app.features.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.player.AvailableLanguageOptions
import com.nuvio.app.features.settings.LanguageSelectionDialog
import com.nuvio.app.features.settings.LanguageSelectionOption
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** Where TorBox shows the account API key the recommended setup asks for. */
private const val TorBoxApiKeySettingsUrl = "https://torbox.app/settings?section=account"

/** Every callback the Sources step can fire. A class so the body's signature stays readable. */
internal class SetupSourcesActions(
    val onUseRecommended: () -> Unit = {},
    val onSetUpManually: () -> Unit = {},
    val onKeepExisting: () -> Unit = {},
    val onDoItLater: () -> Unit = {},
    val onBackToChoice: () -> Unit = {},
    val onTorBoxApiKeyChange: (String) -> Unit = {},
    val onSourceLanguageChange: (String) -> Unit = {},
    val onSubtitleLanguageChange: (String) -> Unit = {},
    val onSetUpRecommended: () -> Unit = {},
    val onManualUrlChange: (String) -> Unit = {},
    val onInstallManual: () -> Unit = {},
)

/**
 * The Sources step: one question, then one of two short paths.
 *
 * ⚠ **No WebView and no browser.** The previous revision opened AIOStreams' configure page with the
 * template preloaded and asked the user to come back with a manifest URL - templates screen, Stremio
 * install popup, copy, paste. The recommended path now does all of it natively; see
 * [AioStreamsRecommendedSetup] for how, and [SetupSourcesController] for the rules.
 */
@Composable
internal fun SetupSourcesBody(
    state: SetupSourcesState,
    existingSourceName: String?,
    actions: SetupSourcesActions,
) {
    val configured = state.configuredName
    when {
        configured != null -> SourcesConfigured(name = configured, recovery = state.recovery)
        state.mode == SetupSourcesMode.Recommended -> SourcesRecommended(state, actions)
        state.mode == SetupSourcesMode.Manual -> SourcesManual(state, existingSourceName, actions)
        existingSourceName != null -> SourcesExisting(existingSourceName, actions)
        else -> SourcesQuestion(actions)
    }
}

@Composable
private fun SourcesQuestion(actions: SetupSourcesActions) {
    SourcesHeading(stringResource(Res.string.setup_sources_question))
    SetupParagraph(stringResource(Res.string.setup_sources_question_body))
    SetupParagraph(stringResource(Res.string.setup_sources_question_own))
    SourcesButtonRow {
        Button(onClick = actions.onUseRecommended) {
            Text(stringResource(Res.string.setup_sources_use_recommended))
        }
        TextButton(onClick = actions.onSetUpManually) {
            Text(stringResource(Res.string.setup_sources_set_up_manually))
        }
        TextButton(onClick = actions.onDoItLater) {
            Text(stringResource(Res.string.setup_sources_do_it_later))
        }
    }
    SetupParagraph(stringResource(Res.string.setup_sources_skip_hint))
}

@Composable
private fun SourcesExisting(name: String, actions: SetupSourcesActions) {
    SourcesSuccessBanner(stringResource(Res.string.setup_sources_existing_title))
    SetupParagraph(stringResource(Res.string.setup_sources_existing_body, name))
    SourcesButtonRow {
        Button(onClick = actions.onKeepExisting) {
            Text(stringResource(Res.string.setup_sources_keep))
        }
        TextButton(onClick = actions.onUseRecommended) {
            Text(stringResource(Res.string.setup_sources_use_recommended_instead))
        }
        TextButton(onClick = actions.onSetUpManually) {
            Text(stringResource(Res.string.setup_sources_manage_manually))
        }
    }
}

@Composable
private fun SourcesRecommended(state: SetupSourcesState, actions: SetupSourcesActions) {
    val tokens = MaterialTheme.nuvio
    var pickingSource by remember { mutableStateOf(false) }
    var pickingSubtitle by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(Res.string.setup_sources_recommended),
            style = MaterialTheme.typography.labelLarge,
            color = tokens.colors.accent,
            fontWeight = FontWeight.Bold,
        )
        SourcesHeading(stringResource(Res.string.setup_sources_recommended_title))
    }
    SetupParagraph(stringResource(Res.string.setup_sources_recommended_body))

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Res.string.setup_sources_torbox_key),
            style = MaterialTheme.typography.labelLarge,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        NuvioInputField(
            value = state.torBoxApiKey,
            onValueChange = actions.onTorBoxApiKeyChange,
            placeholder = stringResource(Res.string.setup_sources_torbox_key),
            visualTransformation = PasswordVisualTransformation(),
        )
        val keyHint = stringResource(Res.string.setup_sources_torbox_key_hint)
        val keyLink = stringResource(Res.string.setup_sources_torbox_key_link)
        Text(
            text = buildAnnotatedString {
                append(keyHint)
                append(" ")
                withLink(
                    LinkAnnotation.Url(
                        url = TorBoxApiKeySettingsUrl,
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = tokens.colors.accent,
                                fontWeight = FontWeight.SemiBold,
                                textDecoration = TextDecoration.Underline,
                            ),
                        ),
                    ),
                ) {
                    append(keyLink)
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
        )
    }

    SetupLanguageRow(
        title = stringResource(Res.string.setup_sources_source_language),
        value = sourcesLanguageLabel(state.sourceLanguageCode),
        onClick = { if (!state.busy) pickingSource = true },
    )
    SetupLanguageRow(
        title = stringResource(Res.string.setup_sources_subtitle_language),
        value = sourcesLanguageLabel(state.subtitleLanguageCode),
        onClick = { if (!state.busy) pickingSubtitle = true },
    )

    state.recommendedFailure?.let { failure ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(failure.messageRes),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.danger,
            )
            state.recommendedFailureDetail?.let { detail ->
                Text(text = detail, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
            }
        }
    }

    SourcesButtonRow {
        Button(
            onClick = actions.onSetUpRecommended,
            enabled = !state.busy && state.torBoxApiKey.isNotBlank(),
        ) {
            if (state.busy) {
                NuvioLoadingIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
            } else {
                Text(stringResource(Res.string.setup_sources_set_up))
            }
        }
        TextButton(onClick = actions.onBackToChoice, enabled = !state.busy) {
            Text(stringResource(Res.string.setup_sources_other_options))
        }
    }
    if (state.busy) SetupParagraph(stringResource(Res.string.setup_sources_setting_up))

    if (pickingSource) {
        SourcesLanguageDialog(
            title = stringResource(Res.string.setup_sources_source_language),
            selected = state.sourceLanguageCode,
            onSelect = { code ->
                actions.onSourceLanguageChange(code)
                pickingSource = false
            },
            onDismiss = { pickingSource = false },
        )
    }
    if (pickingSubtitle) {
        SourcesLanguageDialog(
            title = stringResource(Res.string.setup_sources_subtitle_language),
            selected = state.subtitleLanguageCode,
            onSelect = { code ->
                actions.onSubtitleLanguageChange(code)
                pickingSubtitle = false
            },
            onDismiss = { pickingSubtitle = false },
        )
    }
}

@Composable
private fun SourcesManual(state: SetupSourcesState, existingSourceName: String?, actions: SetupSourcesActions) {
    val tokens = MaterialTheme.nuvio
    if (existingSourceName != null) {
        SourcesSuccessBanner(stringResource(Res.string.setup_sources_configured, existingSourceName))
    }
    SourcesHeading(stringResource(Res.string.setup_sources_manual_title))
    SetupParagraph(stringResource(Res.string.setup_sources_manual_body))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NuvioInputField(
            value = state.manualUrl,
            onValueChange = actions.onManualUrlChange,
            placeholder = stringResource(Res.string.setup_sources_url_placeholder),
            modifier = Modifier.weight(1f),
        )
        Button(onClick = actions.onInstallManual, enabled = !state.busy) {
            if (state.busy) {
                NuvioLoadingIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
            } else {
                Text(stringResource(Res.string.setup_sources_install))
            }
        }
    }
    state.manualError?.let { error ->
        Text(text = error, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.danger)
    }
    SetupParagraph(stringResource(Res.string.setup_sources_manual_manage))
    TextButton(onClick = actions.onUseRecommended, enabled = !state.busy) {
        Text(stringResource(Res.string.setup_sources_use_recommended_instead))
    }
}

@Composable
private fun SourcesConfigured(name: String, recovery: AioStreamsRecovery?) {
    val tokens = MaterialTheme.nuvio
    var showRecovery by remember { mutableStateOf(false) }

    SourcesSuccessBanner(stringResource(Res.string.setup_sources_done_title))
    SetupParagraph(stringResource(Res.string.setup_sources_done_body, name))

    // ⚠ Collapsed by default and gone when the step is left. Nuvio never needs these again; they are
    // offered once for someone who wants to edit the config on the AIOStreams website, and that is
    // the only reason the generated password is shown anywhere at all.
    if (recovery != null) {
        TextButton(onClick = { showRecovery = !showRecovery }) {
            Text(
                stringResource(
                    if (showRecovery) Res.string.setup_sources_recovery_hide else Res.string.setup_sources_recovery_show,
                ),
            )
        }
        if (showRecovery) {
            Surface(
                color = tokens.colors.surfaceCard,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SelectionContainer {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SetupParagraph(stringResource(Res.string.setup_sources_recovery_body))
                        Text(recovery.configureUrl, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textPrimary)
                        RecoveryLine(stringResource(Res.string.setup_sources_recovery_uuid), recovery.uuid)
                        RecoveryLine(stringResource(Res.string.setup_sources_recovery_password), recovery.password)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecoveryLine(label: String, value: String) {
    val tokens = MaterialTheme.nuvio
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary)
    }
}

@Composable
private fun SourcesHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.nuvio.colors.textPrimary,
        fontWeight = FontWeight.SemiBold,
    )
}

/** Wraps rather than squeezing, for the same reason `SetupChoiceGroup` does. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourcesButtonRow(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

@Composable
private fun SourcesSuccessBanner(text: String) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(tokens.colors.success.copy(alpha = 0.12f))
            .border(1.dp, tokens.colors.success.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = null,
            tint = tokens.colors.success,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Only languages the template can express, labelled the way Settings labels them. */
@Composable
private fun SourcesLanguageDialog(
    title: String,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    LanguageSelectionDialog(
        title = title,
        options = AvailableLanguageOptions
            .filter { it.code in AioStreamsLanguageByCode }
            .map { option -> LanguageSelectionOption(option.code, stringResource(option.labelRes)) },
        selectedValue = selected,
        onSelect = { value -> value?.let(onSelect) ?: onDismiss() },
        onDismiss = onDismiss,
    )
}

@Composable
private fun sourcesLanguageLabel(code: String): String =
    AvailableLanguageOptions.firstOrNull { it.code == code }?.let { stringResource(it.labelRes) }
        ?: AioStreamsLanguageByCode[code]
        ?: code

private val RecommendedSourceFailure.messageRes
    get() = when (this) {
        RecommendedSourceFailure.MissingKey -> Res.string.setup_sources_error_missing_key
        RecommendedSourceFailure.TemplateUnavailable -> Res.string.setup_sources_error_template
        RecommendedSourceFailure.TemplateUnsupported -> Res.string.setup_sources_error_template_unsupported
        RecommendedSourceFailure.InstanceUnavailable -> Res.string.setup_sources_error_instance
        RecommendedSourceFailure.Rejected -> Res.string.setup_sources_error_rejected
        RecommendedSourceFailure.RateLimited -> Res.string.setup_sources_error_rate_limited
        RecommendedSourceFailure.InstallFailed -> Res.string.setup_sources_error_install
    }
