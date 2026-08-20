/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baba.callvault.R

/**
 * A duration a person would say out loud: "under a minute", "16 minutes", "1 h 30 min".
 *
 * Deliberately coarse. The estimate is an estimate, and "about 16 minutes" is both more honest and
 * more useful than "15 minutes 47 seconds".
 */
@Composable
fun formatEstimate(ms: Long): String = when {
    ms < 60_000L -> stringResource(R.string.transcribe_estimate_under_minute)
    ms < 3_600_000L -> stringResource(R.string.transcribe_estimate_minutes, (ms / 60_000L).toInt())
    else -> stringResource(
        R.string.transcribe_estimate_hours,
        (ms / 3_600_000L).toInt(),
        ((ms % 3_600_000L) / 60_000L).toInt()
    )
}

/**
 * Asks before starting a transcription, saying how long it is expected to take.
 *
 * The estimate is arithmetic and instant — there is nothing to compute, so there is no progress bar
 * here pretending otherwise. Its accuracy comes from
 * [com.baba.callvault.transcription.TranscriptionEstimate], which learns this phone's real speed from
 * every finished run.
 *
 * @param estimate human-readable duration, already formatted; null when the recording's length could
 *   not be read, in which case the dialog asks without promising a number it does not have.
 * @param onConfirm receives whether the user asked not to be shown this again.
 */
@Composable
fun TranscribeConfirmDialog(
    title: String,
    estimate: String?,
    onDismiss: () -> Unit,
    onConfirm: (dontAskAgain: Boolean) -> Unit
) {
    var dontAskAgain by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(imageVector = Icons.Filled.Schedule, contentDescription = null) },
        title = {
            Text(
                text = estimate?.let { stringResource(R.string.transcribe_confirm_title, it) }
                    ?: stringResource(R.string.transcribe_confirm_title_unknown)
            )
        },
        text = {
            Column {
                Text(stringResource(R.string.transcribe_confirm_message, title))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .toggleable(
                            value = dontAskAgain,
                            onValueChange = { dontAskAgain = it },
                            role = androidx.compose.ui.semantics.Role.Checkbox
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = dontAskAgain, onCheckedChange = null)
                    Text(
                        text = stringResource(R.string.transcribe_confirm_dont_ask),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(dontAskAgain) }) {
                Text(stringResource(R.string.transcript_action_transcribe_short))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.general_cancel)) }
        }
    )
}
