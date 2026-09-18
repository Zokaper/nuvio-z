package com.nuvio.app.features.social

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.social_handle
import nuvio.composeapp.generated.resources.social_handle_help
import nuvio.composeapp.generated.resources.social_save_handle
import org.jetbrains.compose.resources.stringResource

/**
 * Choosing a handle.
 *
 * **One implementation, three callers**: the setup wizard's social-identity step, the Social
 * tab's `needsHandleSetup` branch, and the Settings social page. The wizard needing its own copy
 * of this was the obvious shortcut and it is exactly how the playback-mode copy drifted for a
 * whole release - two files describing the same thing, one of them fixed. The validation rule
 * lives in [isValidSocialHandle] and the normalisation in [normalizeSocialHandle]; neither is
 * restated here.
 *
 * ⚠ **Uniqueness is server-side.** `social_upsert_profile` is the only thing that knows whether a
 * handle is taken, so [message] carries its rejection verbatim. There is no client-side check to
 * keep in step with it, and there must not be one.
 *
 * Deliberately takes its state rather than holding it: the wizard keeps the draft in
 * `rememberSaveable` so a process-death restore does not lose a half-typed handle, and Settings
 * keeps it for the lifetime of a dialog. Neither is this composable's business.
 *
 * @param message a failure from the save, shown verbatim. Null while nothing has gone wrong.
 * @param busy true while a save is in flight, so the button cannot be pressed twice.
 */
@Composable
fun SocialIdentityBody(
    handle: String,
    onHandleChange: (String) -> Unit,
    message: String?,
    busy: Boolean,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    showHeading: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showHeading) {
            Text(
                stringResource(Res.string.social_handle),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            stringResource(Res.string.social_handle_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = handle,
            onValueChange = { onHandleChange(normalizeSocialHandle(it).take(SocialHandleMaxLength)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            prefix = { Text("@") },
            // Only once there is something to be wrong about. An empty field on a step the user
            // has just arrived at is not an error.
            isError = handle.isNotEmpty() && !isValidSocialHandle(handle),
            supportingText = {
                Text(
                    SocialHandleRuleHint,
                    style = MaterialTheme.typography.labelSmall,
                )
            },
        )
        Button(onClick = onSave, enabled = !busy && isValidSocialHandle(handle)) {
            Text(stringResource(Res.string.social_save_handle))
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Kept beside [isValidSocialHandle] so the field cannot let through more than it accepts. */
const val SocialHandleMaxLength: Int = 24

/**
 * The rule, in words.
 *
 * Not a string resource for the same reason the storyboard's release names are not: it spells out
 * a character class that does not translate, and it has to keep matching [isValidSocialHandle]
 * exactly. A translator cannot check that; a reader of this file can.
 */
const val SocialHandleRuleHint: String = "3-24 characters: letters, numbers and underscores."
