/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.system.storage.RecordingTrashRepository
import com.baba.callvault.system.storage.RetentionScheduler
import com.baba.callvault.ui.common.M3DropdownField
import com.baba.callvault.ui.common.OptionItem
import kotlinx.coroutines.launch

/**
 * How long a deleted recording is kept, and a way to empty the bin now.
 *
 * **Settings only — this is not where deleted recordings are browsed.** Restoring one is something a
 * person does while looking at their recordings, not while configuring the app, so that belongs on
 * the main screen. What belongs here is the policy: how long, and clear it out.
 *
 * **Zero days is offered as a real choice, not a way to switch the feature off.** A trashed file
 * stays in the storage folder under a renamed form, so a file manager and any sync tool can still see
 * it — somebody who deletes a call because they want it *gone* is entitled to have that mean gone,
 * and burying that option inside the feature would be deciding it for them.
 */
@Composable
fun TrashSettings(preferences: AppPreferences, updateTrigger: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var days by remember(updateTrigger) { mutableIntStateOf(preferences.getTrashRetentionDays()) }
    var inTrash by remember { mutableIntStateOf(0) }
    var reloadNonce by remember { mutableIntStateOf(0) }
    var confirmEmpty by remember { mutableStateOf(false) }

    // Counted rather than assumed, because Empty has to be able to say what it will destroy — and
    // because a count of zero is what lets the row be absent instead of asking about nothing.
    LaunchedEffect(reloadNonce) {
        inTrash = runCatching { RecordingTrashRepository.list(context).size }.getOrDefault(0)
    }

    val options = listOf(
        OptionItem(key = "0", label = stringResource(R.string.settings_trash_keep_off)),
        OptionItem(key = "7", label = stringResource(R.string.settings_trash_keep_days, 7)),
        OptionItem(key = "30", label = stringResource(R.string.settings_trash_keep_days, 30)),
        OptionItem(key = "90", label = stringResource(R.string.settings_trash_keep_days, 90))
    )

    DropdownRow {
        M3DropdownField(
            label = stringResource(R.string.settings_trash_keep_title),
            selected = options.firstOrNull { it.key == days.toString() } ?: options[2],
            options = options,
            onOptionSelected = { option ->
                val chosen = option.key.toIntOrNull() ?: 0
                days = chosen
                preferences.setTrashRetentionDays(chosen)
                // The daily sweep is what empties the trash, and it is cancelled when nothing needs
                // it. Turning the trash on with retention off has to bring the sweep back, or the
                // thirty days would elapse with nothing there to act on them.
                RetentionScheduler.apply(context)
            }
        )
    }

    Text(
        text = stringResource(
            if (days <= 0) R.string.settings_trash_keep_off_explain
            else R.string.settings_trash_keep_explain
        ) + "\n\n" + stringResource(R.string.settings_trash_vs_retention),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    )

    if (inTrash > 0) {
        TextButton(
            onClick = { confirmEmpty = true },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            Text(stringResource(R.string.settings_trash_empty_now, inTrash))
        }
    }
    Spacer(Modifier.height(4.dp))

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text(stringResource(R.string.settings_trash_empty_confirm_title)) },
            // Says the number and says it cannot be undone. This is the one irreversible action left
            // in the whole delete story, and it destroys several recordings at once rather than one.
            text = { Text(stringResource(R.string.settings_trash_empty_confirm_message, inTrash)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmEmpty = false
                        scope.launch {
                            RecordingTrashRepository.emptyNow(context)
                            reloadNonce++
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(stringResource(R.string.settings_trash_delete_forever)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmEmpty = false }) {
                    Text(stringResource(R.string.general_cancel))
                }
            }
        )
    }
}
