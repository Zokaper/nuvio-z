package com.nuvio.app.features.whatsnew

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.nuvio.app.isDesktop
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.zdevice_offer_body_desktop
import nuvio.composeapp.generated.resources.zdevice_offer_body_mobile
import nuvio.composeapp.generated.resources.zdevice_offer_later
import nuvio.composeapp.generated.resources.zdevice_offer_no
import nuvio.composeapp.generated.resources.zdevice_offer_title_desktop
import nuvio.composeapp.generated.resources.zdevice_offer_title_mobile
import nuvio.composeapp.generated.resources.zdevice_offer_yes
import nuvio.composeapp.generated.resources.zoffer_body
import nuvio.composeapp.generated.resources.zoffer_close
import nuvio.composeapp.generated.resources.zoffer_no
import nuvio.composeapp.generated.resources.zoffer_review_later
import nuvio.composeapp.generated.resources.zoffer_title
import nuvio.composeapp.generated.resources.zoffer_yes
import org.jetbrains.compose.resources.stringResource

/**
 * "New settings available" (2026-10-20, option A): shown after What's New when the profile owes an
 * upgrade of the setup wizard - which no longer gates the app at launch.
 *
 * - **Yes** walks only the steps that changed (the wizard's Upgrade run).
 * - **No** counts as answered: never asked again. The copy says where to review them later - the
 *   event's "Review new settings" card in Settings → What's New.
 * - **X** goes back to What's New; it is not an answer, so the offer comes back when What's New closes.
 */
@Composable
internal fun NewSettingsOffer(
    onYes: () -> Unit,
    onNo: () -> Unit,
    onBackToWhatsNew: () -> Unit,
) {
    Dialog(
        onDismissRequest = onBackToWhatsNew,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        NewSettingsOfferContent(onYes, onNo, onBackToWhatsNew)
    }
}

/** The production surface, shared with the render harness without a separate Dialog window. */
@Composable
internal fun NewSettingsOfferContent(
    onYes: () -> Unit,
    onNo: () -> Unit,
    onBackToWhatsNew: () -> Unit,
) {
    SetupOfferContent(
        title = stringResource(Res.string.zoffer_title),
        body = stringResource(Res.string.zoffer_body),
        note = stringResource(Res.string.zoffer_review_later),
        noLabel = stringResource(Res.string.zoffer_no),
        yesLabel = stringResource(Res.string.zoffer_yes),
        onYes = onYes,
        onNo = onNo,
        onClose = onBackToWhatsNew,
    )
}

/**
 * "First time on this device" (Device Setup redesign, 2026-10-24): shown once per profile per device,
 * after What's New, when the profile has not set this device up. **Set up** opens Device Setup;
 * **Not now** keeps the current values and counts as done - Settings → Setup & About can run it later.
 * Closing it is "Not now" too: there is no screen behind it to go back to.
 */
@Composable
internal fun DeviceSetupOffer(onYes: () -> Unit, onNo: () -> Unit) {
    Dialog(
        onDismissRequest = onNo,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        DeviceSetupOfferContent(onYes, onNo)
    }
}

@Composable
internal fun DeviceSetupOfferContent(onYes: () -> Unit, onNo: () -> Unit) {
    SetupOfferContent(
        title = stringResource(if (isDesktop) Res.string.zdevice_offer_title_desktop else Res.string.zdevice_offer_title_mobile),
        body = stringResource(if (isDesktop) Res.string.zdevice_offer_body_desktop else Res.string.zdevice_offer_body_mobile),
        note = stringResource(Res.string.zdevice_offer_later),
        noLabel = stringResource(Res.string.zdevice_offer_no),
        yesLabel = stringResource(Res.string.zdevice_offer_yes),
        onYes = onYes,
        onNo = onNo,
        onClose = onNo,
    )
}

/** The two setup offers' one dialog: a question, what it means, where to find it later, two answers. */
@Composable
private fun SetupOfferContent(
    title: String,
    body: String,
    note: String,
    noLabel: String,
    yesLabel: String,
    onYes: () -> Unit,
    onNo: () -> Unit,
    onClose: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = Modifier.padding(16.dp).widthIn(max = 460.dp).fillMaxWidth(),
        color = tokens.colors.surfaceDialog,
        shape = tokens.shapes.dialog,
    ) {
        Box {
            Column(
                modifier = Modifier.padding(tokens.spacing.dialogPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 40.dp),
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary,
                )
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                ) {
                    FilledTonalButton(onClick = onNo, shape = tokens.shapes.button) {
                        Text(noLabel)
                    }
                    Button(onClick = onYes, shape = tokens.shapes.button) {
                        Text(yesLabel)
                    }
                }
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .size(36.dp)
                    .background(tokens.colors.surfaceCard, CircleShape),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(Res.string.zoffer_close),
                    tint = tokens.colors.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
